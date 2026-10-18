package thumbnailer.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.PhotoMark;
import thumbnailer.index.Reindexer;
import thumbnailer.ops.Journal.Phase;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Cry;
import thumbnailer.source.Kind;

/**
 * File operations end to end on real trees under two roots, with the real index (in-memory H2) and the real packers
 * (zip and 7z): identity through every kind, conflicts, containment, locked files, cancel, and a crash at every journal
 * transition followed by recovery (files and index both right).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FileOpsTest {

    private static final AtomicInteger TESTS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var id = UUID.randomUUID();
        registry.add("thumbnailer.data-dir", () -> Path.of("build/test-data", id.toString()).toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
    }

    @TestConfiguration
    static class Config {
        @Bean Results results() { return new Results(); }
    }

    @Autowired FileOps ops;
    @Autowired Steps steps;
    @Autowired Planner planner;
    @Autowired Results results;
    @Autowired Reindexer reindexer;
    @Autowired AlbumRepository repo;
    @Autowired AlbumService albums;
    @Autowired SettingsStore settings;
    @Autowired Catalog catalog;
    @TempDir Path root;
    private final int salt = TESTS.incrementAndGet() * 8;

    private java.util.function.BiPredicate<Path, Path> volume;

    @org.junit.jupiter.api.BeforeEach
    void keep() { volume = planner.sameVolume; }

    @AfterEach
    void reset() {
        steps.crash = p -> {};
        planner.sameVolume = volume;
    }

    @Test
    void movesKeepIdentityByRenameAndByVerifiedCopy() throws Exception {
        dir("lib/A", "a.jpg", "b.jpg");
        dir("lib/A/N", "c.jpg", "d.jpg");
        zip("lib/B.zip", "a.jpg", "b.jpg");
        Files.createDirectories(root.resolve("other"));
        Files.createDirectories(root.resolve("outside"));
        scan(null);
        Album a = at("lib/A"), n = at("lib/A/N"), b = at("lib/B.zip");

        var move = op(Op.Kind.MOVE, List.of(a.id()), null, "other", Op.Conflict.SKIP);
        assertThat(ops.preflight(move).items()).singleElement().extracting(Preflight.Item::skip, Preflight.Item::note)
                .containsExactly(false, "contains 1 nested albums");
        assertThat(run(move).done()).isEqualTo(1);
        assertThat(root.resolve("other/A/N/c.jpg")).exists();
        assertThat(root.resolve("lib/A")).doesNotExist();
        assertThat(repo.find(a.id()).orElseThrow()).extracting(Album::path, Album::root).containsExactly(path("other/A"), path("other"));
        assertThat(repo.find(n.id()).orElseThrow()).extracting(Album::path, Album::root).containsExactly(path("other/A/N"), path("other"));

        planner.sameVolume = (x, y) -> false; // "another volume": copy to .part, verify, delete the source, rename
        assertThat(run(op(Op.Kind.MOVE, List.of(b.id()), null, "other", Op.Conflict.SKIP)).done()).isEqualTo(1);
        assertThat(root.resolve("lib/B.zip")).doesNotExist();
        assertThat(root.resolve("other/B.zip.part")).doesNotExist();
        assertThat(repo.find(b.id()).orElseThrow()).extracting(Album::path, Album::revision).containsExactly(path("other/B.zip"), b.revision() + 1);
        assertThat(reindexer.scan(null, () -> false).changed()).as("a later scan finds both unchanged").isZero();

        var out = op(Op.Kind.MOVE, List.of(b.id()), null, "outside", Op.Conflict.SKIP);
        assertThat(ops.preflight(out).items().getFirst().note()).isEqualTo("leaves the library");
        assertThat(ops.preflight(out).consequences()).anyMatch(c -> c.contains("leave the library"));
        assertThat(run(out).done()).isEqualTo(1);
        assertThat(root.resolve("outside/B.zip")).exists();
        assertThat(repo.find(b.id())).isEmpty();
        assertThat(repo.recall(b.internal().contentId())).as("its state waits for a return").isPresent();
    }

    @Test
    void conflictsSkipOrRenameCopiesBecomeNewAlbumsAndDeleteLeavesTombstones() throws Exception {
        dir("lib/C", "a.jpg", "b.jpg");
        dir("other/C", "x.jpg", "y.jpg", "z.jpg");
        scan(null);
        var c = at("lib/C");
        var skipped = run(op(Op.Kind.MOVE, List.of(c.id()), null, "other", Op.Conflict.SKIP));
        assertThat(skipped.skipped()).containsExactly(new OpResult.Issue(c.path(), "exists at the target"));
        assertThat(run(op(Op.Kind.COPY, List.of(c.id()), null, "other", Op.Conflict.RENAME)).done()).isEqualTo(1);
        assertThat(root.resolve("lib/C/a.jpg")).exists();
        var copy = at("other/C (2)");
        assertThat(copy.id()).as("a copy is a new album").isNotEqualTo(c.id());
        assertThat(copy.internal().contentId()).isEqualTo(c.internal().contentId());

        var delete = op(Op.Kind.DELETE, List.of(copy.id()), null, null, Op.Conflict.SKIP);
        assertThat(ops.preflight(delete).consequences()).anyMatch(s -> s.contains("no Recycle Bin"));
        assertThat(run(delete).done()).isEqualTo(1);
        assertThat(root.resolve("other/C (2)")).doesNotExist();
        assertThat(repo.find(copy.id())).isEmpty();
        assertThat(repo.recall(copy.internal().contentId()).map(t -> t.id())).hasValue(copy.id());

        var folders = new Op(Op.Kind.FOLDER_DELETE, null, null, List.of(path("lib/C"), path("lib/C/x"), path("lib")), null,
                null, null, null, null, null);
        assertThat(ops.preflight(folders).items()).extracting(Preflight.Item::reason)
                .containsExactly("inside another selected item", "inside another selected item", "a root folder");
        var inside = new Op(Op.Kind.FOLDER_MOVE, null, null, List.of(path("lib/C")), path("lib/C"), null, null, null, null, null);
        assertThat(ops.preflight(inside).items().getFirst().reason()).isEqualTo("the target is inside it");
    }

    @Test
    void packKeepsTheSubFoldersAndUnpackFlattensWhileEncryptionKeepsThePinAndTheMarks() throws Exception {
        dir("lib/P", "a.jpg", "b.jpg", "notes.txt");
        dir("lib/P/S", "c.jpg", "d.jpg");
        zip("lib/F.zip", "F/a.jpg", "F/b.jpg", "F/c.jpg");
        zip("lib/W.zip", "one.jpg", "readme.txt");
        scan(null);
        var p = albums.patch(at("lib/P").id(), patch(5, "b.jpg"));
        var f = albums.patch(at("lib/F.zip").id(), patch(null, "F/b.jpg"));
        catalog.mark(f.internal().seq(), "F/a.jpg", 8, null);

        var pack = op(Op.Kind.PACK, List.of(p.id()), null, null, Op.Conflict.SKIP);
        assertThat(ops.preflight(pack).items().getFirst().note()).isEqualTo("folder keeps 1 sub-folders");
        assertThat(run(pack).done()).isEqualTo(1);
        assertThat(entries(root.resolve("lib/P.zip"))).containsExactly("a.jpg", "b.jpg", "notes.txt");
        assertThat(root.resolve("lib/P/S/c.jpg")).exists();
        assertThat(root.resolve("lib/P/a.jpg")).doesNotExist();
        assertThat(repo.find(p.id()).orElseThrow()).extracting(Album::path, Album::kind, Album::rating, Album::cover, Album::coverPinned)
                .containsExactly(path("lib/P.zip"), Kind.ZIP, 5, "b.jpg", true);
        assertThat(at("lib/P/S")).as("the nested album stays").isNotNull();
        assertThat(ops.preflight(pack).items().getFirst().reason()).isEqualTo("already packed");
        assertThat(ops.preflight(op(Op.Kind.UNPACK, List.of(at("lib/P/S").id()), null, null, Op.Conflict.SKIP)).items()
                .getFirst().reason()).isEqualTo("already unpacked");
        assertThat(ops.preflight(op(Op.Kind.UNPACK, List.of(at("lib/W.zip").id()), null, null, Op.Conflict.SKIP)).items()
                .getFirst().reason()).isEqualTo("would not be an album");

        assertThat(run(op(Op.Kind.UNPACK, List.of(f.id()), null, null, Op.Conflict.SKIP)).done()).isEqualTo(1);
        assertThat(root.resolve("lib/F/a.jpg")).as("flattened").exists();
        assertThat(root.resolve("lib/F.zip")).doesNotExist();
        var unpacked = repo.find(f.id()).orElseThrow();
        assertThat(unpacked).extracting(Album::path, Album::kind, Album::cover, Album::coverPinned)
                .containsExactly(path("lib/F"), Kind.DIRECTORY, "b.jpg", true);
        assertThat(catalog.marks(unpacked.internal().seq())).containsOnlyKeys("a.jpg");
        var plain = Files.readAllBytes(root.resolve("lib/F/a.jpg"));

        scan("pw"); // the session's key reads what the operation writes
        assertThat(run(new Op(Op.Kind.CRYPT, List.of(f.id()), null, null, null, null, true, null, null, null)).done()).isEqualTo(1);
        assertThat(root.resolve("lib/F/a.jpg.cry")).exists();
        assertThat(root.resolve("lib/F/a.jpg")).doesNotExist();
        var encrypted = repo.find(f.id()).orElseThrow();
        assertThat(encrypted).extracting(Album::cover, Album::coverPinned, Album::imageCount).containsExactly("b.jpg.cry", true, 3);
        assertThat(catalog.marks(encrypted.internal().seq())).containsOnlyKeys("a.jpg.cry");

        assertThat(run(new Op(Op.Kind.CRYPT, List.of(f.id()), null, null, null, null, false, null, null, null)).done()).isEqualTo(1);
        assertThat(Files.readAllBytes(root.resolve("lib/F/a.jpg"))).isEqualTo(plain);
        assertThat(repo.find(f.id()).orElseThrow().cover()).isEqualTo("b.jpg");
        assertThat(catalog.marks(f.internal().seq())).containsOnlyKeys("a.jpg");

        assertThat(run(new Op(Op.Kind.CRYPT, List.of(p.id()), null, null, null, null, true, null, null, null)).done())
                .as("an archive is rewritten with .cry entries").isEqualTo(1);
        assertThat(entries(root.resolve("lib/P.zip"))).containsExactly("a.jpg.cry", "b.jpg.cry", "notes.txt");
        assertThat(repo.find(p.id()).orElseThrow()).extracting(Album::cover, Album::imageCount).containsExactly("b.jpg.cry", 2);
    }

    @Test
    void aCrashAtEveryJournalTransitionLeavesTheSourceOrTheVerifiedResult() throws Exception {
        Files.createDirectories(root.resolve("other"));
        var phases = List.of(Phase.COPYING, Phase.VERIFIED, Phase.DISPOSING, Phase.INSTALLING, Phase.REBASING, Phase.INDEXING);
        for (var phase : phases) dir("lib/M" + phase.ordinal(), "a.jpg", "b.jpg");
        for (var phase : phases) if (phase != Phase.REBASING) dir("lib/K" + phase.ordinal(), "a.jpg", "b.jpg");
        dir("lib/E", "a.jpg", "b.jpg", "c.jpg");
        scan("pw");
        planner.sameVolume = (x, y) -> false;
        for (var phase : phases) {
            var m = at("lib/M" + phase.ordinal());
            crash(phase, 1, op(Op.Kind.MOVE, List.of(m.id()), null, "other", Op.Conflict.SKIP));
            var moved = phase != Phase.COPYING;
            assertThat(root.resolve((moved ? "other/M" : "lib/M") + phase.ordinal() + "/b.jpg")).as(phase.name()).exists();
            assertThat(root.resolve((moved ? "lib/M" : "other/M") + phase.ordinal())).as(phase.name()).doesNotExist();
            assertThat(root.resolve("other/M" + phase.ordinal() + ".part")).doesNotExist();
            assertThat(repo.find(m.id()).orElseThrow().path()).as(phase.name()).isEqualTo(path((moved ? "other/M" : "lib/M") + phase.ordinal()));
        }
        for (var phase : phases) {
            if (phase == Phase.REBASING) continue;
            var k = at("lib/K" + phase.ordinal());
            crash(phase, 1, op(Op.Kind.PACK, List.of(k.id()), null, null, Op.Conflict.SKIP));
            var packed = phase != Phase.COPYING;
            assertThat(root.resolve("lib/K" + phase.ordinal() + ".zip")).as(phase.name()).matches(z -> Files.exists(z) == packed);
            assertThat(root.resolve("lib/K" + phase.ordinal())).as(phase.name()).matches(d -> Files.exists(d) != packed);
            assertThat(repo.find(k.id()).orElseThrow().kind()).as(phase.name()).isEqualTo(packed ? Kind.ZIP : Kind.DIRECTORY);
        }
        var e = at("lib/E"); // in-place encryption, journaled per file: a crash while the second file's source goes
        crash(Phase.DISPOSING, 2, new Op(Op.Kind.CRYPT, List.of(e.id()), null, null, null, null, true, null, null, null));
        try (var files = Files.list(root.resolve("lib/E"))) {
            assertThat(files.map(f -> f.getFileName().toString()).sorted()).containsExactly("a.jpg.cry", "b.jpg.cry", "c.jpg");
        }
        assertThat(repo.entries(e.id())).extracting(thumbnailer.album.Entry::name).containsExactly("a.jpg.cry", "b.jpg.cry", "c.jpg");
        var manifests = settings.dataDir().resolve("ops");
        if (Files.exists(manifests)) try (var left = Files.list(manifests)) {
            assertThat(left).as("no manifest left").isEmpty();
        }
        assertThat(settings.dataDir().resolve("ops.journal")).content().isBlank();
    }

    @Test
    void aLockedSourceIsSkippedInUseAndCancelStopsBetweenItems() throws Exception {
        dir("lib/L", "a.jpg", "b.jpg");
        for (int i = 0; i < 3; i++) dir("lib/D" + i, "a.jpg", "b.jpg");
        Files.createDirectories(root.resolve("other"));
        scan(null);
        var l = at("lib/L");
        if (File.separatorChar == '\\') // Windows refuses to rename a folder with a file open inside
            try (var open = new RandomAccessFile(root.resolve("lib/L/a.jpg").toFile(), "r")) {
                assertThat(run(op(Op.Kind.MOVE, List.of(l.id()), null, "other", Op.Conflict.SKIP)).skipped())
                        .containsExactly(new OpResult.Issue(l.path(), "in use"));
                assertThat(root.resolve("lib/L/b.jpg")).exists();
                assertThat(repo.find(l.id()).orElseThrow().path()).isEqualTo(l.path());
            }

        var ids = List.of(at("lib/D0").id(), at("lib/D1").id(), at("lib/D2").id());
        steps.crash = p -> { if (p == Phase.DISPOSING) ops.cancel(); };
        var result = run(op(Op.Kind.DELETE, ids, null, null, Op.Conflict.SKIP));
        assertThat(result.done()).as("the item in hand completes").isEqualTo(1);
        assertThat(results.progress).filteredOn(p -> !p.running()).last().extracting(OpProgress::message).isEqualTo("Cancelled");
        assertThat(root.resolve("lib/D1")).exists();
        assertThat(repo.find(ids.get(2))).isPresent();
    }

    @Test
    void foldersRenameCaseOnlyAndRootsRenameInTheSettingsToo() throws Exception {
        dir("lib/Foo", "a.jpg", "b.jpg");
        scan(null);
        var foo = at("lib/Foo");
        assertThat(run(new Op(Op.Kind.FOLDER_RENAME, null, null, List.of(foo.path()), "foo", null, null, null, null, null)).done()).isEqualTo(1);
        try (var names = Files.list(root.resolve("lib"))) {
            assertThat(names.map(f -> f.getFileName().toString())).containsExactly("foo");
        }
        assertThat(repo.find(foo.id()).orElseThrow().path()).isEqualTo(path("lib/foo"));

        assertThat(run(new Op(Op.Kind.FOLDER_RENAME, null, null, List.of(path("lib")), "lib2", null, null, null, null, null)).done()).isEqualTo(1);
        assertThat(settings.get().roots()).extracting(Settings.Root::path).contains(path("lib2")).doesNotContain(path("lib"));
        assertThat(repo.find(foo.id()).orElseThrow()).extracting(Album::path, Album::root).containsExactly(path("lib2/foo"), path("lib2"));
        assertThat(reindexer.scan(null, () -> false).changed()).isZero();
    }

    @Test
    void copyPhotosExportsDecryptedSkipsIdenticalAndNumbersDifferent() throws Exception {
        dir("lib/Ph", "a.jpg", "b.jpg");
        var secret = jpeg(7);
        Files.write(root.resolve("lib/Ph/c.jpg.cry"), Cry.encrypt(secret, "c.jpg", Cry.key("pw")));
        Files.createDirectories(root.resolve("out"));
        scan("pw");
        var ph = at("lib/Ph");
        var copy = new Op(Op.Kind.COPY_PHOTOS, List.of(ph.id()), null, null, path("out"), null, null, List.of("a.jpg", "c.jpg.cry"), null, null);
        assertThat(run(copy).done()).isEqualTo(2);
        assertThat(Files.readAllBytes(root.resolve("out/c.jpg"))).isEqualTo(secret);
        assertThat(run(copy).skipped()).extracting(OpResult.Issue::reason).containsOnly("identical file exists");
        Files.write(root.resolve("out/a.jpg"), new byte[] {1, 2, 3});
        assertThat(run(copy).done()).isEqualTo(1);
        assertThat(root.resolve("out/a (2).jpg")).hasSameBinaryContentAs(root.resolve("lib/Ph/a.jpg"));
        assertThat(run(copy).skipped()).as("copied before under a numbered name").extracting(OpResult.Issue::reason)
                .containsOnly("identical file exists");
        assertThat(root.resolve("out/a (3).jpg")).doesNotExist();
    }

    @Test
    void theRealPackersRunInsideOperationsSevenZipThenZipThenAFolderAgain() throws Exception {
        dir("lib/Q", "a.jpg", "b.jpg", "notes.txt");
        dir("lib/S", "a.jpg", "b.jpg");
        scan(null);
        var q = albums.patch(at("lib/Q").id(), patch(6, "b.jpg"));
        catalog.mark(q.internal().seq(), "a.jpg", 7, null);
        assertThat(run(op(Op.Kind.PACK, List.of(q.id()), "SEVEN_ZIP", null, Op.Conflict.SKIP)).done()).isEqualTo(1);
        assertThat(repo.find(q.id()).orElseThrow()).extracting(Album::path, Album::kind, Album::rating, Album::cover, Album::coverPinned)
                .containsExactly(path("lib/Q.7z"), Kind.ARCHIVE, 6, "b.jpg", true);
        assertThat(run(op(Op.Kind.REPACK, List.of(q.id()), "ZIP", null, Op.Conflict.SKIP)).done()).isEqualTo(1);
        assertThat(entries(root.resolve("lib/Q.zip"))).containsExactly("a.jpg", "b.jpg", "notes.txt");
        assertThat(root.resolve("lib/Q.7z")).doesNotExist();
        assertThat(run(op(Op.Kind.UNPACK, List.of(q.id()), null, null, Op.Conflict.SKIP)).done()).isEqualTo(1);
        assertThat(root.resolve("lib/Q/b.jpg")).hasBinaryContent(jpeg(1));
        assertThat(root.resolve("lib/Q.zip")).doesNotExist();
        var back = repo.find(q.id()).orElseThrow();
        assertThat(back).extracting(Album::path, Album::kind, Album::rating, Album::cover, Album::coverPinned)
                .containsExactly(path("lib/Q"), Kind.DIRECTORY, 6, "b.jpg", true);
        assertThat(catalog.marks(back.internal().seq())).containsOnlyKeys("a.jpg");
        assertThat(Planner.flatten("X", java.util.Set.of("x/a.jpg", "X/b.jpg"))).as("the top folder's case is ignored")
                .containsExactlyInAnyOrderEntriesOf(Map.of("x/a.jpg", "a.jpg", "X/b.jpg", "b.jpg"));

        var s = at("lib/S"); // a 7z pack stopped hard leaves its spill files beside the .part: recovery deletes them too
        var spill = root.resolve("lib/S.7z.part.42.spill");
        steps.crash = p -> {
            if (p != Phase.COPYING) return;
            try {
                Files.write(spill, new byte[] {1});
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            throw new Crash();
        };
        ops.submit(op(Op.Kind.PACK, List.of(s.id()), "SEVEN_ZIP", null, Op.Conflict.SKIP));
        ops.await();
        steps.crash = p -> {};
        steps.recover();
        assertThat(spill).doesNotExist();
        assertThat(root.resolve("lib/S/a.jpg")).exists();
        assertThat(root.resolve("lib/S.7z")).doesNotExist();
    }

    @Test
    void anEncryptionWithAnotherPasswordIsIndexedWithItsOwnKey() throws Exception {
        dir("lib/G", "a.jpg", "b.jpg");
        Files.createDirectories(root.resolve("lib/G/empty"));
        scan("pw");
        var g = albums.patch(at("lib/G").id(), patch(4, "b.jpg"));
        catalog.mark(g.internal().seq(), "a.jpg", 8, null);
        var other = new Op(Op.Kind.CRYPT, List.of(g.id()), null, null, null, null, true, null, null, "other");
        assertThat(ops.preflight(other).consequences().getFirst()).endsWith("(key " + Cry.fingerprint(Cry.key("other")) + ")");
        assertThat(run(other).done()).isEqualTo(1);
        assertThat(root.resolve("lib/G/a.jpg.cry")).exists();
        var encrypted = repo.find(g.id()).orElseThrow();
        assertThat(repo.entries(g.id())).as("read with the operation's key, not the session's").extracting(thumbnailer.album.Entry::name)
                .containsExactly("a.jpg.cry", "b.jpg.cry");
        assertThat(encrypted).extracting(Album::path, Album::rating, Album::cover, Album::coverPinned).containsExactly(g.path(), 4, "b.jpg.cry", true);
        assertThat(catalog.marks(g.internal().seq())).containsOnlyKeys("a.jpg.cry");
        scan("other");
        assertThat(repo.find(g.id()).orElseThrow().revision()).as("stored with that key's state: unchanged").isEqualTo(encrypted.revision());

        planner.sameVolume = (x, y) -> false;
        assertThat(run(op(Op.Kind.MOVE, List.of(g.id()), null, "other", Op.Conflict.SKIP)).done()).isEqualTo(1);
        assertThat(root.resolve("other/G/empty")).as("empty folders travel too").isDirectory();
    }

    @Test
    void recoveryKeepsTheSourceAndTheLineWhileAResultIsMissing() throws Exception {
        dir("lib/V", "a.jpg", "b.jpg");
        dir("lib/I", "a.jpg", "b.jpg");
        scan(null);
        planner.sameVolume = (x, y) -> false;
        var v = at("lib/V");
        Path part = root.resolve("other/V.part"), aside = root.resolve("aside");
        var id = crash(Phase.DISPOSING, 1, op(Op.Kind.MOVE, List.of(v.id()), null, "other", Op.Conflict.SKIP), () -> Files.move(part, aside));
        assertThat(root.resolve("lib/V/b.jpg")).as("no verified result, no disposal").exists();
        assertThat(results.future(id)).as("an Error still ends the operation for the client").isDone();
        assertThat(journal()).contains(id);
        Files.move(aside, part);
        steps.recover();
        assertThat(root.resolve("other/V/b.jpg")).exists();
        assertThat(root.resolve("lib/V")).doesNotExist();
        assertThat(repo.find(v.id()).orElseThrow().path()).isEqualTo(path("other/V"));

        var i = at("lib/I"); // a result gone at indexing is not a missing manifest: the line waits for it
        var zip = root.resolve("lib/I.zip");
        id = crash(Phase.INDEXING, 1, op(Op.Kind.PACK, List.of(i.id()), null, null, Op.Conflict.SKIP), () -> Files.move(zip, aside));
        assertThat(journal()).contains(id);
        Files.move(aside, zip);
        steps.recover();
        assertThat(repo.find(i.id()).orElseThrow()).extracting(Album::path, Album::kind).containsExactly(path("lib/I.zip"), Kind.ZIP);
        assertThat(journal()).isBlank();
    }

    @Test
    void aSourceThatChangesWhileItIsCopiedIsKeptAndTheResultGoes() throws Exception {
        dir("lib/Ch", "a.jpg", "b.jpg");
        scan(null);
        planner.sameVolume = (x, y) -> false;
        var ch = at("lib/Ch");
        steps.crash = p -> { // another program writes into the folder after it was copied and verified
            if (p == Phase.VERIFIED) try {
                Files.write(root.resolve("lib/Ch/new.jpg"), new byte[] {1, 2, 3});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        var result = run(op(Op.Kind.MOVE, List.of(ch.id()), null, "other", Op.Conflict.SKIP));
        assertThat(result.failed()).containsExactly(new OpResult.Issue(ch.path(), "changed during the operation"));
        assertThat(root.resolve("lib/Ch/new.jpg")).exists();
        assertThat(root.resolve("lib/Ch/a.jpg")).exists();
        assertThat(root.resolve("other/Ch.part")).doesNotExist();
        assertThat(root.resolve("other/Ch")).doesNotExist();
        assertThat(repo.find(ch.id()).orElseThrow().path()).isEqualTo(ch.path());
        assertThat(journal()).isBlank();
    }

    @Test
    void stagingFilesThatExistAreNeitherWrittenOverNorDeleted() throws Exception {
        dir("lib/Y", "a.jpg", "b.jpg");
        Files.write(root.resolve("lib/Y/a.jpg.cry.part"), new byte[] {9, 9});
        zip("lib/Z.zip", "a.jpg", "b.jpg");
        Files.write(root.resolve("lib/Z.zip.part"), new byte[] {7});
        dir("lib/Rn", "a.jpg", "b.jpg");
        Files.write(root.resolve("lib/rn.part"), new byte[] {5});
        scan("pw");
        assertThat(run(new Op(Op.Kind.CRYPT, List.of(at("lib/Y").id()), null, null, null, null, true, null, null, null)).done()).isEqualTo(1);
        assertThat(root.resolve("lib/Y/a.jpg.cry.part")).hasBinaryContent(new byte[] {9, 9});
        assertThat(root.resolve("lib/Y/a (2).jpg.cry")).exists();
        var z = new Op(Op.Kind.CRYPT, List.of(at("lib/Z.zip").id()), null, null, null, null, true, null, null, null);
        assertThat(ops.preflight(z).items()).extracting(Preflight.Item::reason).containsExactly("staging file exists: Z.zip.part");
        assertThat(run(z).skipped()).hasSize(1);
        assertThat(root.resolve("lib/Z.zip.part")).hasBinaryContent(new byte[] {7});
        var rename = new Op(Op.Kind.FOLDER_RENAME, null, null, List.of(path("lib/Rn")), "rn", null, null, null, null, null);
        assertThat(ops.preflight(rename).items()).extracting(Preflight.Item::reason).containsExactly("staging file exists: rn.part");
    }

    @Test
    void aPartThatAppearsAfterThePlanIsNeitherWrittenOverNorDeleted() throws Exception {
        dir("lib/Q", "a.jpg", "b.jpg");
        scan(null);
        var q = at("lib/Q");
        var part = root.resolve("lib/Q.zip.part");
        steps.crash = p -> { // another program creates the staging file between the planner's look and the packer's
            if (p == Phase.COPYING) try {
                Files.write(part, new byte[] {4});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        var result = run(op(Op.Kind.PACK, List.of(q.id()), null, null, Op.Conflict.SKIP));
        assertThat(result.failed()).containsExactly(new OpResult.Issue(q.path(), "exists"));
        assertThat(part).hasBinaryContent(new byte[] {4});
        assertThat(root.resolve("lib/Q/a.jpg")).exists();
        assertThat(journal()).as("the line goes, nothing to recover").doesNotContain(result.id());
    }

    @Test
    void anEncryptionsPreflightSaysWhetherItsKeyOpensACryTheSelectionHolds() throws Exception {
        dir("lib/K", "a.jpg", "b.jpg");
        Files.write(root.resolve("lib/K/c.jpg.cry"), Cry.encrypt(jpeg(2), "c.jpg", Cry.key("pw")));
        dir("lib/N", "a.jpg", "b.jpg");
        scan("pw");
        String k = at("lib/K").id(), n = at("lib/N").id();
        assertThat(ops.preflight(crypt(List.of(n, k), null)).keyVerified()).as("the session's key").isTrue();
        assertThat(ops.preflight(crypt(List.of(n), null)).keyVerified()).as("no .cry to try").isNull();
        assertThat(ops.preflight(crypt(List.of(k), "other")).keyVerified()).as("another key").isNull();
    }

    @Test
    void aSourceOrTargetReachedThroughALinkIsRefused() throws Exception {
        dir("outside/victim", "a.jpg", "b.jpg");
        dir("lib/Src", "a.jpg", "b.jpg");
        scan(null);
        var link = root.resolve("lib/j");
        link(link, root.resolve("outside"));
        try {
            var delete = new Op(Op.Kind.FOLDER_DELETE, null, null, List.of(path("lib/j/victim")), null, null, null, null, null, null);
            assertThat(run(delete).skipped()).containsExactly(new OpResult.Issue(path("lib/j/victim"), "reached through a link"));
            assertThat(root.resolve("outside/victim/a.jpg")).exists();
            var copy = new Op(Op.Kind.FOLDER_COPY, null, null, List.of(path("lib/Src")), path("lib/j/victim"), null, null, null, null, null);
            assertThat(ops.preflight(copy).items()).extracting(Preflight.Item::reason).containsExactly("the target is reached through a link");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aTargetInsideTheSourceIsRefusedWhicheverPathNamesIt() throws Exception {
        dir("lib/A4/sub", "a.jpg");
        var stored = path("virtual/lib"); // the root as stored; on disk it is lib (a mapped drive, a moved folder)
        var s = new Settings(List.of(new Settings.Root(stored, List.of())), List.of(), false,
                List.of(new Settings.Mapping(stored, root.resolve("lib").toString())), null, null);
        var copy = new Op(Op.Kind.FOLDER_COPY, null, null, List.of(stored + "/A4"), path("lib/A4/sub"), null, null, null, null, null);
        assertThat(planner.preflight(copy, s, null).items()).extracting(Preflight.Item::reason).containsExactly("the target is inside it");
    }

    @Test
    void anArchiveListingANameTwiceIsRefused() throws Exception {
        zip("lib/Dup.zip", "a.jpg", "b.jpg", "c.jpX");
        scan("pw");
        var ids = List.of(at("lib/Dup.zip").id());
        var file = root.resolve("lib/Dup.zip");
        var bytes = Files.readAllBytes(file); // c.jpX becomes a second b.jpg, in the local and the central header
        byte[] from = "c.jpX".getBytes(StandardCharsets.US_ASCII), to = "b.jpg".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i + from.length <= bytes.length; i++)
            if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) System.arraycopy(to, 0, bytes, i, to.length);
        Files.write(file, bytes);
        for (var op : List.of(op(Op.Kind.UNPACK, ids, null, null, Op.Conflict.SKIP), op(Op.Kind.REPACK, ids, "SEVEN_ZIP", null, Op.Conflict.SKIP),
                new Op(Op.Kind.CRYPT, ids, null, null, null, null, true, null, null, null)))
            assertThat(ops.preflight(op).items()).as(op.kind().name()).extracting(Preflight.Item::reason).containsExactly("duplicate names in archive");
    }

    @Test
    void anUnreadableJournalStopsEveryItemAndStaysAsItIs() throws Exception {
        dir("lib/J", "a.jpg", "b.jpg");
        scan(null);
        var file = settings.dataDir().resolve("ops.journal");
        Files.writeString(file, "not json\n");
        try {
            var j = at("lib/J");
            assertThat(run(op(Op.Kind.DELETE, List.of(j.id()), null, null, Op.Conflict.SKIP)).failed())
                    .containsExactly(new OpResult.Issue(j.path(), "The operations journal cannot be read"));
            assertThat(root.resolve("lib/J/a.jpg")).exists();
            assertThat(Files.readString(file)).isEqualTo("not json\n");
        } finally {
            Files.delete(file);
            steps.recover();
        }
    }

    @Test
    void aReplayedReindexKeepsPhotoMarksAndAKeylessOneWaitsForThePassword() throws Exception {
        dir("lib/R", "a.jpg", "b.jpg");
        scan("pw");
        var r = albums.patch(at("lib/R").id(), patch(null, "b.jpg"));
        catalog.mark(r.internal().seq(), "a.jpg", 8, null);
        assertThat(run(new Op(Op.Kind.CRYPT, List.of(r.id()), null, null, null, null, true, null, null, null)).done()).isEqualTo(1);
        reindexer.reindex(r.id(), r.path(), Kind.DIRECTORY, Map.of("a.jpg", "a.jpg.cry", "b.jpg", "b.jpg.cry"), null); // a recovery replays it
        assertThat(catalog.marks(r.internal().seq())).containsOnlyKeys("a.jpg.cry").extractingByKey("a.jpg.cry")
                .extracting(PhotoMark::rating).isEqualTo(8);
        assertThat(repo.find(r.id()).orElseThrow()).extracting(Album::cover, Album::coverPinned).containsExactly("b.jpg.cry", true);

        Files.write(root.resolve("lib/R/c.jpg"), jpeg(2)); // without the key only this one would be indexed, the pin lost
        settings.forgetKey();
        assertThatThrownBy(() -> reindexer.reindex(r.id(), r.path(), Kind.DIRECTORY, Map.of(), null)).hasMessage("waits for the password");
        assertThat(repo.find(r.id()).orElseThrow()).extracting(Album::cover, Album::coverPinned, Album::imageCount)
                .containsExactly("b.jpg.cry", true, 2);
    }

    @Test
    void repackNeedsTheSpaceOfItsOutputNotOfTheCompressedSource() throws Exception {
        var file = root.resolve("lib/Big.zip");
        Files.createDirectories(file.getParent());
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var n : List.of("a.jpg", "b.jpg", "notes.txt")) {
                out.putNextEntry(new ZipEntry(n));
                out.write(n.endsWith(".jpg") ? jpeg(n.charAt(0) - 'a') : new byte[200_000]);
            }
        }
        scan(null);
        long plain;
        try (var z = new ZipFile(file.toFile())) {
            plain = Collections.list(z.entries()).stream().mapToLong(ZipEntry::getSize).sum();
        }
        var preflight = ops.preflight(op(Op.Kind.REPACK, List.of(at("lib/Big.zip").id()), "SEVEN_ZIP", null, Op.Conflict.SKIP));
        assertThat(preflight.bytes()).isEqualTo(plain).isGreaterThan(Files.size(file));
    }

    // --- helpers

    /** Runs the operation with a crash at the {@code nth} line of {@code phase}, then recovers like a restart would. */
    private void crash(Phase phase, int nth, Op op) throws Exception { crash(phase, nth, op, () -> {}); }

    /** {@link #crash(Phase, int, Op)}, {@code meanwhile} done right before the crash; returns the operation's id. */
    private String crash(Phase phase, int nth, Op op, Trees.Io meanwhile) throws Exception {
        var seen = new AtomicInteger();
        steps.crash = p -> {
            if (p != phase || seen.incrementAndGet() != nth) return;
            try {
                meanwhile.run();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new Crash();
        };
        var id = ops.submit(op);
        ops.await();
        steps.crash = p -> {};
        assertThat(seen.get()).as("crashed").isEqualTo(nth);
        steps.recover();
        return id;
    }

    private String journal() throws IOException { return Files.readString(settings.dataDir().resolve("ops.journal")); }

    /** A directory link: an NTFS junction on Windows (no privilege needed), else a symbolic link. */
    private static void link(Path link, Path target) throws Exception {
        if (File.separatorChar == '\\') new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).start().waitFor();
        else Files.createSymbolicLink(link, target);
        assumeTrue(Files.isDirectory(link), "no directory link here");
    }

    private OpResult run(Op op) throws Exception { return results.future(ops.submit(op)).get(30, TimeUnit.SECONDS); }

    private Op op(Op.Kind kind, List<String> ids, String format, String target, Op.Conflict conflict) {
        return new Op(kind, ids, null, null, target == null ? null : path(target), format == null ? null : Op.Format.valueOf(format),
                null, null, conflict, null);
    }

    private static Op crypt(List<String> ids, String password) {
        return new Op(Op.Kind.CRYPT, ids, null, null, null, null, true, null, null, password);
    }

    private static AlbumPatch patch(Integer rating, String cover) {
        return new AlbumPatch(null, null, null, null, null, null, rating, cover, null, null, null, null, null);
    }

    private void scan(String password) throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("other"));
        settings.set(new Settings(List.of(new Settings.Root(root.resolve("lib").toString(), List.of()),
                new Settings.Root(root.resolve("other").toString(), List.of())), List.of(), false, null, null, null, null, null,
                null, password));
        reindexer.scan(null, () -> false);
    }

    private String path(String relative) { return Containers.normalize(root.resolve(relative)); }

    private Album at(String relative) {
        var p = path(relative);
        return repo.byRoot(p).stream().filter(a -> a.path().equals(p)).findFirst().orElseThrow(() -> new AssertionError("No album at " + p));
    }

    private static List<String> entries(Path zip) throws IOException {
        try (var z = new ZipFile(zip.toFile())) {
            return Collections.list(z.entries()).stream().map(ZipEntry::getName).sorted().toList();
        }
    }

    private void dir(String relative, String... names) throws IOException {
        var d = Files.createDirectories(root.resolve(relative));
        for (int i = 0; i < names.length; i++) Files.write(d.resolve(names[i]), names[i].endsWith(".jpg") ? jpeg(i) : new byte[] {'t'});
    }

    private void zip(String relative, String... names) throws IOException {
        Files.createDirectories(root.resolve(relative).getParent());
        try (var out = new ZipOutputStream(Files.newOutputStream(root.resolve(relative)))) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(names[i].endsWith(".jpg") ? jpeg(i) : new byte[] {'t'});
            }
        }
    }

    private byte[] jpeg(int i) throws IOException {
        var img = new BufferedImage(64 + salt + 32 * i, 48 + 24 * i, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    /** Stops an item the way a crash would: no rollback, no result. */
    static final class Crash extends Error {
        Crash() { super("simulated crash", null, false, false); }
    }

    /** The operations' results and progress, as published. */
    static final class Results {
        final Map<String, CompletableFuture<OpResult>> byId = new ConcurrentHashMap<>();
        final List<OpProgress> progress = new CopyOnWriteArrayList<>();

        @EventListener void on(OpResult r) { future(r.id()).complete(r); }

        @EventListener void on(OpProgress p) { progress.add(p); }

        CompletableFuture<OpResult> future(String id) { return byId.computeIfAbsent(id, k -> new CompletableFuture<>()); }
    }
}
