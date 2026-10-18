package thumbnailer.ops;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static thumbnailer.ops.Op.Kind.COPY;
import static thumbnailer.ops.Op.Kind.COPY_PHOTOS;
import static thumbnailer.ops.Op.Kind.DELETE;
import static thumbnailer.ops.Op.Kind.FOLDER_COPY;
import static thumbnailer.ops.Op.Kind.FOLDER_DELETE;
import static thumbnailer.ops.Op.Kind.FOLDER_MOVE;
import static thumbnailer.ops.Op.Kind.FOLDER_RENAME;
import static thumbnailer.ops.Op.Kind.MOVE;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.Entry;
import thumbnailer.settings.Settings;
import thumbnailer.source.AlbumContainer;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Kind;

/**
 * What an operation would do, item by item (catalog_plan.md §4.4, §4.5, §4.8): the sources (albums, folders or one
 * album's photos; a source inside another selected one is left out), and for each, planned again right before it runs,
 * its destination under the conflict policy, the {@link Manifest} it starts from, or why it is skipped, with the notes,
 * bytes and space the confirm dialog shows.
 */
@Component
class Planner {

    static final Set<Op.Kind> TARGETED = EnumSet.of(MOVE, COPY, FOLDER_MOVE, FOLDER_COPY, COPY_PHOTOS);
    private static final Set<Op.Kind> TREES = EnumSet.of(MOVE, COPY, FOLDER_MOVE, FOLDER_COPY);

    private final AlbumRepository repo;
    private final Containers containers;
    private final int minImages;
    /** Whether two paths share a volume (an atomic rename); tests force the copy path. */
    BiPredicate<Path, Path> sameVolume = (a, b) -> {
        try {
            return Files.getFileStore(a).equals(Files.getFileStore(b));
        } catch (IOException e) {
            return false;
        }
    };

    Planner(AlbumRepository repo, Containers containers, @Value("${thumbnailer.min-images:2}") int minImages) {
        this.repo = repo;
        this.containers = containers;
        this.minImages = minImages;
    }

    /** A source: canonical path (an entry name for COPY_PHOTOS), its album, and why it is left out. */
    record Source(String path, Album album, String reason) {}

    /** {@code manifest} null = skipped for {@code reason}; {@code need}: extra bytes the item takes on {@code volume}. */
    record Item(String path, Manifest manifest, String reason, String note, long bytes, long need, FileStore volume) {}

    List<Source> sources(Op op) {
        if (op.kind() == COPY_PHOTOS) {
            var album = albums(op).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No album"));
            if (op.names() == null) return List.of();
            var listed = repo.entries(album.id()).stream().map(Entry::name).collect(Collectors.toSet());
            return op.names().stream().distinct().map(n -> new Source(n, album, listed.contains(n) ? null : "not in the album")).toList();
        }
        var all = (op.kind().name().startsWith("FOLDER_")
                ? Stream.ofNullable(op.paths()).flatMap(List::stream).map(p -> new Source(Containers.normalize(Path.of(p)), null, null))
                : albums(op).stream().map(a -> new Source(a.path(), a, null))).collect(Collectors.toMap(Source::path, s -> s, (a, b) -> a, LinkedHashMap::new)).values();
        return all.stream().map(s -> all.stream().anyMatch(o -> o != s && Containers.within(s.path(), o.path()))
                ? new Source(s.path(), s.album(), "inside another selected item") : s).toList();
    }

    /** The item for {@code src} now; {@code taken}: destinations earlier items of a preflight will take (lowercase). */
    Item item(Op op, Settings s, byte[] key, Source src, Set<String> taken) {
        if (src.reason() != null) return skip(src.path(), src.reason());
        try {
            return plan(op, s, key, src, taken);
        } catch (IOException | RuntimeException e) {
            return skip(src.path(), Trees.reason(e));
        }
    }

    private Item plan(Op op, Settings s, byte[] key, Source in, Set<String> taken) throws IOException {
        var kind = op.kind();
        var a = in.album();
        if (kind == COPY_PHOTOS) return photo(op, s, key, in, taken);
        var src = in.path();
        var p = s.locate(src);
        var attrs = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) return skip(src, "is a link");
        if (a == null && s.root(src).isEmpty()) return skip(src, "not in the library");
        if (!direct(s, src)) return skip(src, "reached through a link");
        boolean root = s.roots().stream().anyMatch(r -> r.path().equalsIgnoreCase(src));
        if (root && kind != COPY && kind != FOLDER_COPY && kind != FOLDER_RENAME) return skip(src, "a root folder");
        int below = repo.byRoot(src).size() - (a != null ? 1 : 0);
        var nested = below <= 0 ? null : a != null ? "contains " + below + " nested albums" : below + " albums";
        var name = p.getFileName().toString();
        var parent = src.substring(0, src.lastIndexOf('/'));
        if (TREES.contains(kind)) {
            var target = target(op, s);
            if (!direct(s, target)) return skip(src, "the target is reached through a link");
            if (Containers.within(target, src) || Containers.within(real(s, target), real(s, src))) return skip(src, "the target is inside it");
            if ((kind == MOVE || kind == FOLDER_MOVE) && target.equalsIgnoreCase(parent)) return skip(src, "already there");
            var dst = destination(op.conflict(), s, target, name, attrs.isRegularFile(), taken);
            if (dst == null) return skip(src, "exists at the target");
            boolean move = kind == MOVE || kind == FOLDER_MOVE;
            boolean atomic = move && sameVolume.test(p, s.locate(target));
            long bytes = Trees.size(p);
            var leaves = move && s.root(target).isEmpty() ? "leaves the library" : null;
            return new Item(src, new Manifest(kind, id(a), a != null ? a.kind() : null, src, dst, atomic ? src : Trees.part(dst),
                    null, false, null), null, join(nested, leaves), bytes, atomic ? 0 : bytes, store(s.locate(target)));
        }
        return switch (kind) {
            case DELETE, FOLDER_DELETE -> new Item(src, new Manifest(kind, id(a), a != null ? a.kind() : null, src, null, null,
                    null, false, null), null, nested, Trees.size(p), 0, null);
            case FOLDER_RENAME -> {
                var to = op.target() == null ? "" : op.target().strip();
                if (to.isEmpty() || to.equals(".") || to.equals("..") || to.chars().anyMatch(c -> "/\\:*?\"<>|".indexOf(c) >= 0))
                    yield skip(src, "not a folder name: " + to);
                if (root && !s.locate(src).equals(Path.of(src))) yield skip(src, "a mapped root");
                var dst = parent + "/" + to;
                if (dst.equals(src)) yield skip(src, "same name");
                if (!dst.equalsIgnoreCase(src) && Files.exists(s.locate(dst), NOFOLLOW_LINKS)) yield skip(src, "exists");
                var staging = staged(s, dst, false); // a case-only rename goes through it
                if (staging != null) yield skip(src, staging);
                yield new Item(src, new Manifest(kind, null, null, src, dst, src, null, false, null), null,
                        join(nested, root ? "renames the root" : null), 0, 0, null);
            }
            case PACK -> {
                if (a.kind() != Kind.DIRECTORY) yield skip(src, "already packed");
                var own = own(p);
                var format = op.format() != null ? op.format() : s.ops().packFormat();
                var dst = destination(op.conflict(), s, parent, name + (format == Op.Format.SEVEN_ZIP ? ".7z" : ".zip"), true, taken);
                if (dst == null) yield skip(src, "the archive exists");
                var staging = staged(s, dst, format == Op.Format.SEVEN_ZIP);
                if (staging != null) yield skip(src, staging);
                long dirs;
                try (var list = Files.list(p)) {
                    dirs = list.filter(f -> Files.isDirectory(f, NOFOLLOW_LINKS)).count();
                }
                long bytes = own.values().stream().mapToLong(AlbumContainer.Item::bytes).sum();
                yield new Item(src, new Manifest(kind, a.id(), Kind.DIRECTORY, src, dst, Trees.part(dst), format, false,
                        identity(own.keySet())), null, dirs > 0 ? "folder keeps " + dirs + " sub-folders" : null, bytes, bytes, store(p));
            }
            case REPACK -> {
                if (a.kind() == Kind.DIRECTORY) yield skip(src, "not an archive");
                var format = op.format() != null ? op.format() : s.ops().packFormat();
                var ext = format == Op.Format.SEVEN_ZIP ? ".7z" : ".zip";
                if (name.toLowerCase(Locale.ROOT).endsWith(ext)) yield skip(src, "already a " + ext.substring(1));
                var dst = destination(op.conflict(), s, parent, Trees.stem(name) + ext, true, taken);
                if (dst == null) yield skip(src, "the archive exists");
                var staging = staged(s, dst, format == Op.Format.SEVEN_ZIP);
                if (staging != null) yield skip(src, staging);
                var entries = entries(a.kind(), p);
                long bytes = entries.values().stream().mapToLong(AlbumContainer.Item::bytes).sum(); // the output: images stored, never the compressed source
                yield new Item(src, new Manifest(kind, a.id(), a.kind(), src, dst, Trees.part(dst), format, false, identity(entries.keySet())),
                        null, "the result is a " + ext.substring(1), bytes, bytes, store(p));
            }
            case UNPACK -> {
                if (a.kind() == Kind.DIRECTORY) yield skip(src, "already unpacked");
                var base = Trees.stem(name);
                var entries = entries(a.kind(), p);
                var names = flatten(base, entries.keySet());
                var images = names.values().stream().filter(AlbumContainer::isImage).collect(Collectors.groupingBy(
                        n -> n.contains("/") ? n.substring(0, n.lastIndexOf('/')) : "", Collectors.counting()));
                boolean album = images.getOrDefault("", 0L) >= minImages;
                long albums = images.entrySet().stream().filter(e -> !e.getKey().isEmpty() && e.getValue() >= minImages).count();
                if (!album && albums == 0) yield skip(src, "would not be an album");
                var dst = destination(op.conflict(), s, parent, base, false, taken);
                if (dst == null) yield skip(src, "the folder exists");
                long bytes = entries.values().stream().mapToLong(AlbumContainer.Item::bytes).sum();
                yield new Item(src, new Manifest(kind, a.id(), a.kind(), src, dst, Trees.part(dst), null, false, names), null,
                        albums > 0 ? "becomes " + (albums + (album ? 1 : 0)) + " albums" : null, bytes, bytes, store(p));
            }
            case CRYPT -> {
                boolean encrypt = Boolean.TRUE.equals(op.encrypt());
                if (key == null) yield skip(src, "needs the password");
                var listed = a.kind() == Kind.DIRECTORY ? own(p) : entries(a.kind(), p);
                Map<String, String> names;
                try (var raw = containers.openRaw(a.kind(), p)) {
                    names = Crypt.names(raw, key, encrypt);
                }
                if (names.isEmpty()) yield skip(src, encrypt ? "already encrypted" : "not encrypted");
                if (a.kind() == Kind.DIRECTORY) {
                    long largest = names.keySet().stream().mapToLong(n -> p.resolve(n).toFile().length()).max().orElse(0);
                    yield new Item(src, new Manifest(kind, a.id(), a.kind(), src, src, null, null, encrypt, names), null, null,
                            largest, largest, store(p));
                }
                var lower = name.toLowerCase(Locale.ROOT);
                var format = lower.endsWith(".7z") ? Op.Format.SEVEN_ZIP : Op.Format.ZIP;
                var dst = lower.endsWith(".7z") || lower.endsWith(".zip") ? src : destination(op.conflict(), s, parent, Trees.stem(name) + ".zip", true, taken);
                if (dst == null) yield skip(src, "the archive exists");
                var staging = staged(s, dst, format == Op.Format.SEVEN_ZIP); // in place: X.zip.part beside X.zip
                if (staging != null) yield skip(src, staging);
                var all = new LinkedHashMap<>(identity(listed.keySet()));
                all.putAll(names);
                long bytes = listed.values().stream() // the output: the members as they are, an encrypted one with its .cry header
                        .mapToLong(i -> encrypt && names.containsKey(i.name()) ? Crypt.encryptedSize(i.bytes(), i.name()) : i.bytes()).sum();
                yield new Item(src, new Manifest(kind, a.id(), a.kind(), src, dst, Trees.part(dst), format, encrypt, all), null,
                        dst.equals(src) ? null : "the result is a zip", bytes, bytes, store(p));
            }
            default -> throw new IllegalStateException("Unexpected " + kind);
        };
    }

    /** One photo: its plain name and size from the listing (a {@code .cry} one from its head); read whole only to compare with a same-size file. */
    private Item photo(Op op, Settings s, byte[] key, Source in, Set<String> taken) throws IOException {
        var a = in.album();
        var name = in.path();
        var target = target(op, s);
        try (var raw = containers.openRaw(a.kind(), s.locate(a.path()))) {
            var listed = raw.items().stream().filter(i -> i.name().equals(name)).toList();
            if (listed.isEmpty()) return skip(name, "not in the album");
            if (listed.size() > 1) return skip(name, "duplicate names in archive");
            long file = listed.getFirst().bytes(), bytes = file;
            String plain;
            if (Cry.is(name)) {
                if (key == null) return skip(name, "needs the password");
                var head = raw.head(name, Crypt.HEAD);
                plain = Crypt.name(head, file, key);
                bytes = ByteBuffer.wrap(head).getInt(); // the header holds the image's size
            } else plain = name.substring(name.lastIndexOf('/') + 1);
            Long hash = null;
            for (int n = 1; ; n++) { // the photo itself under any of its names (copied before) is not copied again
                var dst = Containers.prefix(target) + (n == 1 ? plain : Trees.numbered(plain, n, true));
                var at = s.locate(dst);
                if (Files.isRegularFile(at, NOFOLLOW_LINKS) && Files.size(at) == bytes) {
                    if (hash == null) try (var member = raw.open(name)) {
                        hash = Crypt.plain(member, file, key, Cry.is(name), OutputStream.nullOutputStream());
                    }
                    if (Trees.hashes(at).get("").equals(hash)) return skip(name, "identical file exists");
                }
                if (Files.exists(at, NOFOLLOW_LINKS) || Files.exists(s.locate(Trees.part(dst)), NOFOLLOW_LINKS)
                        || taken.contains(dst.toLowerCase(Locale.ROOT))) continue;
                return new Item(name, new Manifest(COPY_PHOTOS, a.id(), a.kind(), a.path(), dst, Trees.part(dst), null, false,
                        Map.of(name, "")), null, null, bytes, bytes, store(s.locate(target)));
            }
        }
    }

    /** What would happen, for the confirm dialog; items that do not fit the free space of their volume are skipped. */
    Preflight preflight(Op op, Settings s, byte[] key) {
        var taken = new HashSet<String>();
        var needs = new HashMap<FileStore, Long>();
        var items = new ArrayList<Preflight.Item>();
        long bytes = 0;
        int done = 0;
        for (var src : sources(op)) {
            var it = item(op, s, key, src, taken);
            var reason = it.reason();
            if (reason == null && it.need() > 0 && usable(it.volume()) < needs.merge(it.volume(), it.need(), Long::sum) + Trees.MARGIN) {
                needs.merge(it.volume(), -it.need(), Long::sum);
                reason = "No space";
            }
            if (reason == null) {
                if (it.manifest().dst() != null) taken.add(it.manifest().dst().toLowerCase(Locale.ROOT));
                bytes += it.bytes();
                done++;
            }
            items.add(new Preflight.Item(it.path(), reason != null, reason, it.note()));
        }
        var warnings = items.stream().anyMatch(i -> "No space".equals(i.reason())) ? List.of("Not enough free space for every item") : List.<String>of();
        return new Preflight(items, bytes, free(s, op, items), warnings, consequences(op, s, key, done));
    }

    static boolean fits(Item it) { return it.need() == 0 || usable(it.volume()) >= it.need() + Trees.MARGIN; }

    private List<String> consequences(Op op, Settings s, byte[] key, int n) {
        if (n == 0) return List.of();
        var target = op.target() == null ? null : Containers.normalize(Path.of(op.target()));
        var out = new ArrayList<String>();
        switch (op.kind()) {
            case MOVE, FOLDER_MOVE -> {
                out.add(n + " item(s) move to " + target);
                if (s.root(target).isEmpty()) out.add("They leave the library: removed from the index, their state kept for a return");
            }
            case COPY, FOLDER_COPY -> out.add(n + " item(s) are copied to " + target + (s.root(target).isPresent() ? "; the copies become new albums" : ""));
            case DELETE, FOLDER_DELETE -> out.add(n + " item(s) are deleted from disk with everything inside; there is no Recycle Bin");
            case PACK -> out.add(n + " folder(s) are packed; their files are deleted after the archives are verified");
            case UNPACK -> out.add(n + " archive(s) are unpacked; the archives are deleted after the folders are verified");
            case REPACK -> out.add(n + " archive(s) are rewritten; each original is deleted after its result is verified");
            case CRYPT -> out.add(n + " album(s) are " + (Boolean.TRUE.equals(op.encrypt()) ? "encrypted" : "decrypted")
                    + " in place; each original is deleted after its result is verified (key " + Cry.fingerprint(key) + ")");
            case COPY_PHOTOS -> out.add(n + " photo(s) are copied to " + target);
            case FOLDER_RENAME -> out.add("The folder is renamed to " + op.target());
        }
        return out;
    }

    /** Free bytes where the operation writes: the target's volume, else the first item's; -1 for renames and deletes. */
    private static long free(Settings s, Op op, List<Preflight.Item> items) {
        if (EnumSet.of(FOLDER_RENAME, DELETE, FOLDER_DELETE).contains(op.kind())) return -1;
        var at = TARGETED.contains(op.kind()) ? op.target() : items.isEmpty() ? null : items.getFirst().path();
        if (at == null) return -1;
        for (var p = s.locate(Containers.normalize(Path.of(at))); p != null; p = p.getParent())
            if (Files.exists(p)) return usable(store(p));
        return -1;
    }

    /** The canonical target folder; it must exist now (checked again right before each item). */
    private static String target(Op op, Settings s) {
        if (op.target() == null) throw new IllegalArgumentException("No target folder");
        var target = Containers.normalize(Path.of(op.target()));
        if (!Files.isDirectory(s.locate(target), NOFOLLOW_LINKS)) throw new IllegalArgumentException("The target folder is gone: " + target);
        return target;
    }

    /** {@code dir/name}, or under RENAME the first free {@code name (n)}; null = exists and SKIP. */
    private static String destination(Op.Conflict conflict, Settings s, String dir, String name, boolean file, Set<String> taken) {
        var dst = Containers.prefix(dir) + name;
        for (int n = 2; Files.exists(s.locate(dst), NOFOLLOW_LINKS) || Files.exists(s.locate(Trees.part(dst)), NOFOLLOW_LINKS)
                || taken.contains(dst.toLowerCase(Locale.ROOT)); n++) {
            if (conflict == Op.Conflict.SKIP) return null;
            dst = Containers.prefix(dir) + Trees.numbered(name, n, file);
        }
        return dst;
    }

    /** A directory album's own files, refused when one is a link. */
    private Map<String, AlbumContainer.Item> own(Path dir) throws IOException {
        var own = entries(Kind.DIRECTORY, dir);
        if (own.keySet().stream().anyMatch(n -> !Files.isRegularFile(dir.resolve(n), NOFOLLOW_LINKS))) throw new IOException("contains a link");
        return own;
    }

    /**
     * Every file of a container, raw; a name that would leave the folder it is extracted to refuses it, and so does a
     * name listed twice (members are read by name: one of them would be lost).
     */
    private Map<String, AlbumContainer.Item> entries(Kind kind, Path p) throws IOException {
        try (var raw = containers.openRaw(kind, p)) {
            var bad = raw.items().stream().filter(i -> !Trees.safe(i.name())).findFirst();
            if (bad.isPresent()) throw new IOException("unsafe entry name: " + bad.get().name());
            var entries = new LinkedHashMap<String, AlbumContainer.Item>();
            for (var i : raw.items()) if (entries.putIfAbsent(i.name(), i) != null) throw new IOException("duplicate names in archive");
            return entries;
        }
    }

    /** Why the item cannot write where it would: its {@code .part} (or, for a 7z, a spill of it) exists; else null. Never written over or deleted. */
    private static String staged(Settings s, String dst, boolean sevenZip) throws IOException {
        var tmp = s.locate(Trees.part(dst));
        var there = Files.exists(tmp, NOFOLLOW_LINKS) ? List.of(tmp) : sevenZip ? Trees.spills(tmp) : List.<Path>of();
        return there.isEmpty() ? null : "staging file exists: " + there.getFirst().getFileName();
    }

    /** Whether canonical {@code path} is reached through no link (symlink, junction) below its root; outside every root: true. */
    private static boolean direct(Settings s, String path) throws IOException {
        var root = s.root(path);
        return root.isEmpty() || real(s, path).equalsIgnoreCase(Containers.rebase(path, root.get().path(), real(s, root.get().path())));
    }

    /** Where {@code path} physically is, links resolved: containment holds for the files, not just for the names. */
    private static String real(Settings s, String path) throws IOException { return Containers.normalize(s.locate(path).toRealPath()); }

    /** An archive whose only top-level entry is a folder of its own name (case ignored) is extracted without it. */
    static Map<String, String> flatten(String base, Set<String> names) {
        var top = base + "/";
        boolean flat = !names.isEmpty() && names.stream().allMatch(n -> n.length() > top.length() && n.regionMatches(true, 0, top, 0, top.length()));
        return names.stream().collect(Collectors.toMap(n -> n, n -> flat ? n.substring(top.length()) : n, (x, y) -> x, LinkedHashMap::new));
    }

    private static Map<String, String> identity(Set<String> names) {
        return names.stream().collect(Collectors.toMap(n -> n, n -> n, (x, y) -> x, LinkedHashMap::new));
    }

    private List<Album> albums(Op op) {
        return op.albumIds() == null && op.query() == null ? List.of() : repo.select(op.albumIds(), op.query());
    }

    private static Item skip(String path, String reason) { return new Item(path, null, reason, null, 0, 0, null); }

    private static String id(Album a) { return a == null ? null : a.id(); }

    private static String join(String a, String b) { return a == null ? b : b == null ? a : a + "; " + b; }

    private static FileStore store(Path p) {
        try {
            return Files.getFileStore(p);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long usable(FileStore store) {
        try {
            return store.getUsableSpace();
        } catch (IOException e) {
            return 0;
        }
    }
}
