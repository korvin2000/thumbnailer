package thumbnailer.ops;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static thumbnailer.ops.Journal.Phase.COPYING;
import static thumbnailer.ops.Journal.Phase.DISPOSING;
import static thumbnailer.ops.Journal.Phase.INDEXING;
import static thumbnailer.ops.Journal.Phase.INSTALLING;
import static thumbnailer.ops.Journal.Phase.REBASING;
import static thumbnailer.ops.Journal.Phase.VERIFIED;
import static thumbnailer.ops.Op.Kind.COPY;
import static thumbnailer.ops.Op.Kind.COPY_PHOTOS;
import static thumbnailer.ops.Op.Kind.CRYPT;
import static thumbnailer.ops.Op.Kind.FOLDER_COPY;
import static thumbnailer.ops.Op.Kind.FOLDER_MOVE;
import static thumbnailer.ops.Op.Kind.FOLDER_RENAME;
import static thumbnailer.ops.Op.Kind.MOVE;
import static thumbnailer.ops.Op.Kind.PACK;
import static thumbnailer.ops.Op.Kind.REPACK;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.index.Reindexer;
import thumbnailer.ops.Journal.Line;
import thumbnailer.ops.Journal.Phase;
import thumbnailer.ops.pack.Packer;
import thumbnailer.ops.pack.Unpacker;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Disks;
import thumbnailer.source.Kind;

/**
 * One item through its journaled phases (catalog_plan.md §4.7): {@code copying} (the result written to {@code tmp},
 * hashing what was read, forced) → {@code verified} (read back equal) → {@code disposing} (the source deleted) →
 * {@code installing} (renamed to {@code dst}; an atomic move is only this) → {@code rebasing} (paths in the index, a
 * renamed root in the settings) → {@code indexing} (re-index with identity, a scoped scan or a removal) → checkpoint,
 * line removed. Recovery resumes a line through the same steps, so a crash leaves the source, the verified result or
 * both, never neither. A directory's in-place encryption journals each file. The index is written only through
 * {@link AlbumService} and {@link Reindexer}.
 */
@Component
class Steps {

    private static final Logger log = LoggerFactory.getLogger(Steps.class);
    private static final Set<Op.Kind> MOVES = EnumSet.of(MOVE, FOLDER_MOVE, FOLDER_RENAME);
    private static final Set<Op.Kind> KEEPS = EnumSet.of(COPY, FOLDER_COPY, COPY_PHOTOS);
    private static final Set<Op.Kind> ARCHIVES = EnumSet.of(PACK, REPACK, CRYPT); // per-file CRYPT never verifies here

    private final AlbumService albums;
    private final AlbumRepository repo;
    private final Reindexer reindexer;
    private final Containers containers;
    private final SettingsStore settings;
    private final JdbcTemplate jdbc;
    private final Journal journal;
    private final Packer packer;
    private final Unpacker unpacker;
    private final int minImages;
    /** Called after each journal line is written: tests throw here to stop an item the way a crash would. */
    volatile Consumer<Phase> crash = p -> {};

    Steps(AlbumService albums, AlbumRepository repo, Reindexer reindexer, Containers containers, SettingsStore settings,
          JdbcTemplate jdbc, Journal journal, Packer packer, Unpacker unpacker,
          @Value("${thumbnailer.min-images:2}") int minImages) {
        this.albums = albums;
        this.repo = repo;
        this.reindexer = reindexer;
        this.containers = containers;
        this.settings = settings;
        this.jdbc = jdbc;
        this.journal = journal;
        this.packer = packer;
        this.unpacker = unpacker;
        this.minImages = minImages;
    }

    /**
     * Runs a planned item ({@link Manifest#at} numbered) with the operation's {@code .cry} key, reading and writing in
     * {@code turn}, which ends before the index is touched (a scoped scan takes turns of its own). Returns null, or what
     * a per-file item left as it was. Throws when the item rolled back (the source untouched) or stays journaled
     * ({@link #pending}).
     */
    String run(Manifest m, byte[] key, Disks.Hold turn) throws IOException {
        var held = quiesce(m);
        try {
            journal.save(m);
            if (m.perFile()) return perFile(m, key, turn);
            if (m.tmp() == null) return finish(m, DISPOSING, key, turn);
            if (m.atomic()) {
                line(m, INSTALLING, null, null);
                try {
                    m = install(m);
                } catch (AtomicMoveNotSupportedException e) { // another volume after all: the copy path of the same item
                    line(m, COPYING, null, null); // before the manifest names a tmp: a COPYING line never deletes tmp = src
                    return copy(journal.save(m.copying()), key, turn);
                } catch (IOException | RuntimeException e) {
                    journal.remove(m.op(), m.item()); // nothing moved
                    throw e;
                }
                return finish(m, REBASING, key, turn);
            }
            line(m, COPYING, null, null);
            return copy(m, key, turn);
        } finally {
            albums.release(held);
        }
    }

    /** Whether item {@code item} of operation {@code op} is still journaled (a recovery finishes it). */
    boolean pending(String op, int item) { return journal.has(op, item); }

    /** Finishes or rolls back every journaled item (§4.7 recovery table); one that fails again stays journaled. */
    void recover() {
        List<Line> lines;
        try {
            lines = journal.read();
        } catch (IOException | RuntimeException e) {
            log.error("The operations journal cannot be read; nothing is recovered", e);
            return;
        }
        for (var l : lines) {
            try {
                var m = journal.manifest(l);
                var held = quiesce(m);
                try {
                    resume(m, l);
                } finally {
                    albums.release(held);
                }
                log.info("Recovered {} {} (item {} of operation {}) from {}", m.kind(), m.src(), m.item(), m.op(), l.phase());
            } catch (NoSuchFileException e) { // no manifest: the item never wrote anything
                remove(l);
            } catch (IOException | RuntimeException e) {
                log.warn("Item {} of operation {} stays journaled at {}: {}", l.item(), l.op(), l.phase(), Trees.reason(e));
            }
        }
        try {
            journal.prune();
        } catch (IOException e) {
            log.warn("Old manifests stay: {}", e.toString());
        }
    }

    private void resume(Manifest m, Line l) throws IOException {
        if (m.perFile()) {
            resumeFile(m, l);
            finish(m, INDEXING, null, Disks.Hold.NONE);
            return;
        }
        switch (l.phase()) {
            case COPYING -> rollback(m);
            case VERIFIED, DISPOSING -> {
                if (m.tmp() != null && Files.exists(path(m.tmp()), NOFOLLOW_LINKS)) verify(m); // before the source goes
                finish(m, DISPOSING, null, Disks.Hold.NONE);
            }
            default -> finish(m, l.phase(), null, Disks.Hold.NONE);
        }
    }

    private String copy(Manifest m, byte[] key, Disks.Hold turn) throws IOException {
        try {
            m = journal.save(write(m, key));
            verify(m);
        } catch (IOException | RuntimeException e) {
            rollback(m);
            throw e;
        }
        return finish(m, VERIFIED, key, turn);
    }

    /** The phases from {@code from} on that apply to the item, each journaled before it runs; then checkpoint and done. */
    private String finish(Manifest m, Phase from, byte[] key, Disks.Hold turn) throws IOException {
        for (var p : Phase.values()) {
            if (p.compareTo(from) < 0 || !applies(p, m)) continue;
            line(m, p, null, null);
            switch (p) {
                case DISPOSING -> dispose(m);
                case INSTALLING -> m = install(m);
                case REBASING -> {
                    turn.close();
                    rebase(m);
                }
                case INDEXING -> {
                    turn.close();
                    index(m, key);
                }
                default -> {}
            }
        }
        jdbc.execute("CHECKPOINT SYNC"); // WRITE_DELAY would let a committed re-index vanish behind a journal that says done
        journal.remove(m.op(), m.item());
        return null;
    }

    private static boolean applies(Phase p, Manifest m) {
        return switch (p) {
            case COPYING, VERIFIED -> m.tmp() != null && !m.atomic();
            case DISPOSING -> !m.atomic() && !KEEPS.contains(m.kind());
            case INSTALLING -> m.tmp() != null;
            case REBASING -> MOVES.contains(m.kind());
            case INDEXING -> m.kind() != COPY_PHOTOS;
        };
    }

    private Manifest write(Manifest m, byte[] key) throws IOException {
        Path src = path(m.src()), tmp = path(m.tmp());
        Map<String, Long> hashes;
        switch (m.kind()) {
            case MOVE, COPY, FOLDER_MOVE, FOLDER_COPY -> hashes = Trees.copy(src, tmp);
            case UNPACK -> {
                try (var raw = containers.openRaw(m.container(), src)) {
                    hashes = unpacker.unpack(raw, tmp, m.names());
                }
            }
            case COPY_PHOTOS -> {
                var name = m.names().keySet().iterator().next();
                byte[] bytes;
                try (var raw = containers.openRaw(m.container(), src); var in = raw.open(name)) {
                    bytes = in.readAllBytes();
                }
                if (Cry.is(name)) bytes = Cry.decrypt(bytes, key);
                Files.write(tmp, bytes, CREATE_NEW);
                hashes = Map.of("", Hashes.of(bytes));
            }
            default -> { // PACK, REPACK, CRYPT: an archive, written in one pass
                var raw = containers.openRaw(m.container(), src);
                AlbumContainer in;
                try {
                    in = m.kind() == CRYPT ? Crypt.view(raw, renamed(m), key, m.encrypt()) : raw;
                } catch (IOException | RuntimeException e) {
                    raw.close();
                    throw e;
                }
                try (in) {
                    hashes = packer.pack(in, m.names().values(), tmp, m.format());
                }
            }
        }
        if (!m.names().isEmpty() && !hashes.keySet().equals(Set.copyOf(m.names().values())))
            throw new IOException("The result does not hold every file of " + m.src());
        Trees.sync(tmp);
        return m.withEntries(hashes);
    }

    private void verify(Manifest m) throws IOException {
        var tmp = path(m.tmp());
        if (!(ARCHIVES.contains(m.kind()) ? packer.hashes(tmp) : Trees.hashes(tmp)).equals(m.entries()))
            throw new IOException("The result does not read back as written: " + m.tmp());
    }

    /** Deletes the partial result, and what a 7z pack spilled beside it ({@code <tmp>.<n>.spill}) when it never got to its cleanup. */
    private void rollback(Manifest m) throws IOException {
        if (m.tmp() != null && !m.atomic()) {
            var tmp = path(m.tmp());
            Trees.delete(tmp);
            var spill = tmp.getFileName() + ".";
            if (ARCHIVES.contains(m.kind())) try (var spills = Files.newDirectoryStream(tmp.getParent(), f -> // no glob: names hold [ ]
                    f.getFileName().toString().startsWith(spill) && f.getFileName().toString().endsWith(".spill"))) {
                for (var f : spills) Trees.delete(f);
            }
        }
        journal.remove(m.op(), m.item());
    }

    private void dispose(Manifest m) throws IOException {
        var src = path(m.src());
        if (m.kind() != PACK) {
            Trees.delete(src);
            return;
        }
        for (var name : m.names().keySet()) Trees.delete(src.resolve(name)); // a directory album: its own files only
        try {
            Trees.retry(() -> Files.deleteIfExists(src));
        } catch (DirectoryNotEmptyException e) {
            log.info("{} keeps its sub-folders beside the archive", m.src());
        }
    }

    /** Brings the result from {@code tmp} (or the case-only rename's halfway name) to {@code dst}; done if it is there. */
    private Manifest install(Manifest m) throws IOException {
        Path tmp = path(m.tmp()), half = Trees.part(path(m.dst()));
        var from = Trees.present(tmp) ? tmp : Trees.present(half) ? half : null;
        if (from == null) {
            if (Trees.present(path(m.dst()))) return m;
            throw new IOException("The result of " + m.src() + " is missing");
        }
        if (Trees.present(path(m.dst()))) { // taken since the item started: the result never waits for a decision
            boolean file = Files.isRegularFile(from, NOFOLLOW_LINKS);
            var dir = m.dst().substring(0, m.dst().lastIndexOf('/') + 1);
            var name = m.dst().substring(dir.length());
            String dst = m.dst();
            for (int n = 2; Files.exists(path(dst), NOFOLLOW_LINKS) || Files.exists(path(Trees.part(dst)), NOFOLLOW_LINKS); n++)
                dst = dir + Trees.numbered(name, n, file);
            log.warn("{} appeared meanwhile; the result goes to {}", m.dst(), dst);
            m = journal.save(m.withDst(dst));
        }
        Trees.move(from, path(m.dst()));
        return m;
    }

    /** Moves the index along: the albums at or below {@code src} follow to {@code dst} (and its root), or leave the library. */
    private void rebase(Manifest m) throws IOException {
        var s = settings.get();
        boolean root = s.roots().stream().anyMatch(r -> r.path().equalsIgnoreCase(m.src()));
        var target = root ? Optional.of(m.dst()) : s.root(m.dst()).map(Settings.Root::path);
        if (target.isEmpty()) {
            albums.removeBelow(m.src()); // tombstones keep their state for a return
            return;
        }
        for (var a : repo.byRoot(m.src())) albums.relocate(a.id(), Containers.rebase(a.path(), m.src(), m.dst()), target.get());
        if (root) // after the database: a scan in between would see albums of a root that is not configured
            settings.set(new Settings(s.roots().stream().map(r -> r.path().equalsIgnoreCase(m.src())
                    ? new Settings.Root(m.dst(), r.exclusions(), r.storage()) : r).toList(), s.exclusions(), s.scanOnStart(),
                    s.pathMap(), s.covers(), s.thumbs(), s.copyTargets(), s.ops(), s.cryKey(), null));
    }

    private void index(Manifest m, byte[] key) throws IOException {
        var s = settings.get();
        try {
            switch (m.kind()) {
                case PACK, REPACK, CRYPT -> {
                    if (m.kind() == CRYPT && m.encrypt() && !keyed(s, key)) { // the session cannot read the result yet
                        if (m.dst().equals(m.src())) log.info("{} is re-indexed by the next scan with its password", m.src());
                        else throw new IllegalStateException("waits for the password");
                    } else reindexer.reindex(m.albumId(), m.dst(), kind(m.dst()), renamed(m));
                }
                case UNPACK -> {
                    if (m.names().values().stream().filter(n -> !n.contains("/") && AlbumContainer.isImage(n)).count() >= minImages)
                        reindexer.reindex(m.albumId(), m.dst(), Kind.DIRECTORY, renamed(m));
                    else albums.remove(m.albumId());
                    if (m.names().values().stream().anyMatch(n -> n.contains("/"))) reindexer.scan(List.of(m.dst()), () -> false);
                }
                case COPY, FOLDER_COPY -> {
                    if (s.root(m.dst()).isPresent())
                        reindexer.scan(List.of(Files.isDirectory(path(m.dst())) ? m.dst() : m.dst().substring(0, m.dst().lastIndexOf('/'))), () -> false);
                }
                case DELETE, FOLDER_DELETE -> albums.removeBelow(m.src());
                case MOVE, FOLDER_MOVE, FOLDER_RENAME -> { // a new last segment: the file-name extractor sees it
                    if (!last(m.src()).equals(last(m.dst())))
                        for (var a : repo.byRoot(m.dst())) if (a.path().equals(m.dst())) reindexer.reindex(a.id(), a.path(), a.kind(), Map.of());
                }
                default -> {}
            }
        } catch (NoSuchElementException e) {
            log.warn("{}: the album is gone, nothing to index", m.src());
        }
    }

    /** In-place encryption or decryption of a directory album, file by file, each journaled; then one re-index. */
    private String perFile(Manifest m, byte[] key, Disks.Hold turn) throws IOException {
        var dir = path(m.src());
        String first = null;
        int left = 0;
        for (var e : m.names().entrySet()) {
            Path from = dir.resolve(e.getKey()), to = dir.resolve(e.getValue()), tmp = Trees.part(to);
            long hash;
            line(m, COPYING, e.getKey(), null);
            try {
                var out = Crypt.transform(e.getKey(), Files.readAllBytes(from), key, m.encrypt());
                Files.write(tmp, out, CREATE_NEW);
                Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(from));
                Trees.sync(tmp);
                hash = Hashes.of(out);
                if (Trees.hashes(tmp).get("") != hash) throw new IOException("does not read back as written");
                line(m, DISPOSING, e.getKey(), hash); // verified: recovery checks the hash again before the source goes
                Trees.retry(() -> Files.delete(from));
            } catch (IOException | RuntimeException x) { // nothing deleted: this file stays as it was
                Files.deleteIfExists(tmp);
                if (left++ == 0) first = e.getKey() + ": " + Trees.reason(x);
                continue;
            }
            line(m, INSTALLING, e.getKey(), hash);
            Trees.move(tmp, to); // a failure keeps the line: recovery renames it
        }
        finish(m, INDEXING, key, turn);
        return left == 0 ? null : left + " of " + m.names().size() + " files left as they were (" + first + ")";
    }

    private void resumeFile(Manifest m, Line l) throws IOException {
        if (l.file() == null) return;
        var dir = path(m.src());
        Path from = dir.resolve(l.file()), to = dir.resolve(m.names().get(l.file())), tmp = Trees.part(to);
        switch (l.phase()) {
            case COPYING -> Files.deleteIfExists(tmp);
            case VERIFIED, DISPOSING -> {
                if (!Files.exists(tmp)) return;
                if (Trees.hashes(tmp).get("") != l.hash().longValue()) {
                    if (!Files.exists(from)) throw new IOException("The verified result of " + from + " changed");
                    Files.delete(tmp);
                    return;
                }
                Trees.retry(() -> Files.deleteIfExists(from));
                Trees.move(tmp, to);
            }
            case INSTALLING -> {
                if (Trees.present(tmp)) Trees.move(tmp, to);
            }
            default -> {}
        }
    }

    /** Renamed files only (a per-file item: those done), for the re-index: pins and photo marks follow them. */
    private Map<String, String> renamed(Manifest m) {
        var dir = m.perFile() ? path(m.src()) : null;
        return m.names().entrySet().stream().filter(e -> !e.getKey().equals(e.getValue()))
                .filter(e -> dir == null || Files.exists(dir.resolve(e.getValue())) && !Files.exists(dir.resolve(e.getKey())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Whether the session's key reads what the operation wrote (after a restart: whether any key is set). */
    private static boolean keyed(Settings s, byte[] key) {
        var session = s.cryKey() == null ? null : HexFormat.of().parseHex(s.cryKey());
        return key == null ? session != null : Arrays.equals(key, session);
    }

    private Collection<String> quiesce(Manifest m) {
        return KEEPS.contains(m.kind()) ? List.of() : albums.quiesceBelow(m.src());
    }

    private void line(Manifest m, Phase phase, String file, Long hash) throws IOException {
        journal.line(m, phase, file, hash);
        crash.accept(phase);
    }

    private void remove(Line l) {
        try {
            journal.remove(l.op(), l.item());
        } catch (IOException e) {
            log.warn("Journal line {} stays: {}", l, e.toString());
        }
    }

    private Kind kind(String canonical) {
        var p = path(canonical);
        return Files.isDirectory(p) ? Kind.DIRECTORY : containers.archive(p).orElseThrow(() -> new IllegalStateException("Not an album: " + canonical));
    }

    private Path path(String canonical) { return settings.get().locate(canonical); }

    private static String last(String path) { return path.substring(path.lastIndexOf('/') + 1); }
}
