package thumbnailer.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dynatrace.hash4j.hashing.Hashing;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.Entry;
import thumbnailer.album.Link;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.album.AlbumService;
import thumbnailer.album.BlobStore;
import thumbnailer.image.Render;
import thumbnailer.ops.FileOps;
import thumbnailer.ops.Op;
import thumbnailer.settings.Settings;
import thumbnailer.source.Cry;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Archives;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import thumbnailer.source.Kind;

/** Scanning end to end against real files, an in-memory H2 and the real MVStore. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScanTest {

    private static final AtomicInteger TESTS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var id = UUID.randomUUID();
        registry.add("thumbnailer.data-dir", () -> Path.of("build/test-data", id.toString()).toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired ScanService scans;
    @Autowired AlbumRepository repo;
    @Autowired AlbumService albums;
    @Autowired SettingsStore settings;
    @Autowired BlobStore blobs;
    @Autowired Catalog catalog;
    @Autowired Gate gate;
    @Autowired Reindexer reindexer;
    @Autowired FileOps ops;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @TempDir Path root;
    /** Distinct image sizes per test, so no test's album looks like another test's moved album. */
    private final int salt = TESTS.incrementAndGet() * 8;

    @Test
    void reconcilesAddedChangedMovedAndRemovedAlbums() throws IOException {
        dir(root.resolve("A"), "a.jpg", "b.jpg", "c.jpg");
        zip(root.resolve("B.zip"), "a.jpg", "b.jpg", "c.jpg");
        dir(root.resolve("C"), "a.jpg", "b.jpg");
        var first = scan(() -> false);
        assertThat(first.changed()).isEqualTo(3);
        var a = byName("A");
        var b = byName("B");
        albums.patch(a.id(), new AlbumPatch(null, null, null, null, null, null, 7, null, null, null, null, null, null));

        assertThat(scan(() -> false).changed()).as("unchanged rescan").isZero();

        dir(root.resolve("A"), "d.jpg");
        Files.createDirectories(root.resolve("sub"));
        Files.move(root.resolve("B.zip"), root.resolve("sub/B.zip"));
        assertThat(scan(() -> false).changed()).isEqualTo(2);
        var changed = repo.find(a.id()).orElseThrow();
        assertThat(changed.imageCount()).isEqualTo(4);
        assertThat(changed.rating()).as("user state survives re-index").isEqualTo(7);
        assertThat(changed.revision()).isGreaterThan(a.revision() + 1);
        var moved = repo.find(b.id()).orElseThrow();
        assertThat(moved.path()).isEqualTo(Containers.normalize(root.resolve("sub/B.zip")));
        assertThat(repo.byRoot(normalizedRoot())).hasSize(3);

        var c = byName("C");
        for (var f : List.of("a.jpg", "b.jpg")) Files.delete(root.resolve("C").resolve(f));
        Files.delete(root.resolve("C"));
        var checks = new AtomicInteger();
        var cancelled = scan(() -> checks.incrementAndGet() > 3);
        assertThat(cancelled.message()).isEqualTo("Cancelled");
        assertThat(repo.find(c.id())).as("a cancelled scan prunes nothing").isPresent();

        assertThat(scan(() -> false).removed()).isEqualTo(1);
        assertThat(repo.find(c.id())).isEmpty();
        assertThat(repo.find(a.id())).isPresent();
    }

    @Test
    void renamedAlbumKeepsItsIdentityAndTakesTheNewName() throws IOException {
        var old = root.resolve("CLS-2019-01-14 - Aurora - (vol-5) 1800px (x2) 1Mb.zip");
        zip(old, "a.jpg", "b.jpg");
        scan(() -> false);
        var before = byName("CLS-2019-01-14 - Aurora - (vol-5)");
        albums.patch(before.id(), new AlbumPatch(null, null, null, null, null, null, 6, null, null, null, null, null, null));

        Files.move(old, root.resolve("CLS-2020-02-02 - EL-Studio - Jeny - (vol-6).zip"));
        assertThat(scan(() -> false).changed()).isEqualTo(1);
        var after = repo.find(before.id()).orElseThrow();
        assertThat(after.name()).isEqualTo("CLS-2020-02-02 - EL-Studio - Jeny - (vol-6)");
        assertThat(after.model()).isEqualTo("Jeny");
        assertThat(after.studio()).isEqualTo("EL-Studio");
        assertThat(after.shotDate()).isEqualTo(LocalDate.of(2020, 2, 2));
        assertThat(after.rating()).as("edits survive the rename").isEqualTo(6);
        assertThat(repo.byRoot(normalizedRoot())).hasSize(1);
    }

    @Test
    void exclusionsApplyInsideContainers() throws IOException {
        dir(root.resolve("X"), "a.jpg", "b.jpg", "c_tmp.jpg");
        zip(root.resolve("Y.zip"), "a.jpg", "b.jpg", "raw/c.jpg");
        scan(() -> false, "*_tmp*", "*/raw/*");
        assertThat(byName("X").imageCount()).isEqualTo(2);
        assertThat(byName("Y").imageCount()).isEqualTo(2);
    }

    @Test
    void overlappingRootsAreRejected() {
        var outer = normalizedRoot();
        var inner = outer + "/sub";
        assertThatThrownBy(() -> settings.set(new Settings(List.of(new Settings.Root(outer, List.of()),
                new Settings.Root(inner, List.of())), List.of(), false, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(outer).hasMessageContaining(inner);
        assertThatThrownBy(() -> settings.set(new Settings(List.of(new Settings.Root(outer, List.of())), List.of(), false,
                List.of(new Settings.Mapping(inner, "Z:/elsewhere")), null, null)))
                .as("a mapping inside a root").isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(outer).hasMessageContaining(inner);
    }

    @Test
    void userEditsSurviveExtractedRefresh() throws IOException {
        var album = root.resolve("E");
        dir(album, "a.jpg", "b.jpg");
        Files.writeString(album.resolve("album.txt"), "model: Alice\nstudio: S1\ndescription: First\n#wedding #outdoor");
        scan(() -> false);
        var e = byName("E");
        assertThat(e.model()).isEqualTo("Alice");
        assertThat(e.tags()).containsExactly("wedding", "outdoor");

        albums.patch(e.id(), new AlbumPatch(null, "Bob", null, null, "", List.of("wedding"), null, "b.jpg", null, null, null, null, null));
        Files.writeString(album.resolve("album.txt"), "model: Carol\nstudio: S2\ndescription: Second\n#wedding #outdoor #beach");
        assertThat(scan(() -> false).changed()).isEqualTo(1);

        var refreshed = repo.find(e.id()).orElseThrow();
        assertThat(refreshed.model()).as("override kept").isEqualTo("Bob");
        assertThat(refreshed.studio()).as("extracted refreshed").isEqualTo("S2");
        assertThat(refreshed.description()).as("\"\" clears").isNull();
        assertThat(refreshed.tags()).as("a removed tag does not return").containsExactly("wedding");
        assertThat(refreshed.cover()).isEqualTo("b.jpg");
        assertThat(refreshed.coverPinned()).isTrue();

        var unpinned = albums.patch(e.id(), new AlbumPatch(null, null, null, null, null, null, null, "", null, null, null, null, null));
        assertThat(unpinned.coverPinned()).isFalse();
        assertThat(unpinned.revision()).isEqualTo(refreshed.revision() + 1);
    }

    @Test
    void coverIsServedOnlyForTheAlbumStateItShows() throws Exception {
        dir(root.resolve("K"), "a.jpg", "b.jpg");
        scan(() -> false);
        var k = byName("K");
        assertThat(albums.storedCover(k)).as("rendered while indexing").isPresent();

        var other = k.cover().equals("a.jpg") ? "b.jpg" : "a.jpg";
        var pinned = albums.patch(k.id(), new AlbumPatch(null, null, null, null, null, null, null, other, null, null, null, null, null));
        assertThat(albums.storedCover(pinned)).as("the previous cover's value is a miss").isEmpty();
        var rendered = albums.cover(pinned).orElseThrow().bytes();
        var rated = albums.patch(k.id(), new AlbumPatch(null, null, null, null, null, null, 3, null, null, null, null, null, null));
        assertThat(albums.storedCover(rated).orElseThrow().bytes()).as("an edit keeps files and tag").isEqualTo(rendered);

        dir(root.resolve("K"), "c.jpg");
        assertThat(scan(() -> false).changed()).isEqualTo(1);
        var reindexed = repo.find(k.id()).orElseThrow();
        assertThat(reindexed.internal().signature()).isNotEqualTo(rated.internal().signature());
        assertThat(albums.storedCover(reindexed).orElseThrow().bytes()).as("unchanged cover file: reused, re-tagged").isEqualTo(rendered);

        // The pre-settings value layout [tag][webp], written by hand: still a hit under the default (legacy) spec.
        long tag = Hashing.wyhashFinal4(Render.LEGACY_EPOCH).hashStream().putString(reindexed.cover())
                .putString(reindexed.internal().signature()).getAsLong();
        blobs.put(k.id(), ByteBuffer.allocate(8 + rendered.length).order(ByteOrder.LITTLE_ENDIAN).putLong(tag).put(rendered).array());
        assertThat(albums.storedCover(reindexed).orElseThrow().bytes()).as("legacy layout").isEqualTo(rendered);

        var jpeg = new Render(Render.Format.JPEG, 80, 256, 1);
        settings.set(new Settings(settings.get().roots(), List.of(), false, null, jpeg, null));
        assertThat(albums.storedCover(reindexed)).as("another render spec: a miss").isEmpty();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/img/"
                + k.id() + "/cover?v=" + reindexed.revision())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(response.headers().firstValue("ETag")).hasValue("\"" + reindexed.revision() + "-" + Long.toHexString(jpeg.epoch()) + "\"");
        assertThat(response.body()).startsWith(0xFF, 0xD8);
        var stored = albums.storedCover(reindexed).orElseThrow();
        assertThat(stored.format()).isEqualTo(Render.Format.JPEG);
        assertThat(stored.bytes()).as("stored in the length-prefixed layout").isEqualTo(response.body());
    }

    @Test
    void pathMapFindsAMovedLibraryWithoutReindexing() throws Exception {
        var lib = root.resolve("lib");
        var canonical = Containers.normalize(lib);
        dir(lib.resolve("M"), "a.jpg", "b.jpg");
        zip(lib.resolve("N.zip"), "a.jpg", "b.jpg", "raw/c.jpg");
        var roots = List.of(new Settings.Root(canonical, List.of(canonical + "/N.zip/raw/*")));
        settings.set(new Settings(roots, List.of(), false, null, null, null));
        assertThat(scans.scan(null, () -> false).changed()).isEqualTo(2);

        var moved = root.resolve("moved");
        Files.move(lib, moved);
        settings.set(new Settings(roots, List.of(), false,
                List.of(new Settings.Mapping(canonical + "/", Containers.normalize(moved) + "/")), null, null));
        var rescan = scans.scan(null, () -> false);
        assertThat(rescan.changed()).as("found through the map; exclusions still in canonical paths").isZero();
        assertThat(rescan.removed()).isZero();

        var m = byName(canonical, "M");
        assertThat(m.path()).isEqualTo(canonical + "/M");
        assertThat(byName(canonical, "N").imageCount()).isEqualTo(2);
        var pinned = albums.patch(m.id(), new AlbumPatch(null, null, null, null, null, null, null,
                m.cover().equals("a.jpg") ? "b.jpg" : "a.jpg", null, null, null, null, null));
        assertThat(albums.cover(pinned)).as("rendered from where the map points").isPresent();

        var received = new CopyOnWriteArrayList<String>();
        var socket = connect(received);
        socket.sendText("{\"type\":\"getSettings\",\"id\":3}", true).join();
        assertThat(await(received, msg -> msg.contains("\"id\":3"))).contains("\"path\":\"" + canonical + "\",\"location\":\""
                + Containers.normalize(moved) + "\",\"exists\":true", "\"renderEpoch\":\"");
        socket.abort();
    }

    @Test
    void scanningAFolderPrunesOnlyBelowIt() throws IOException {
        dir(root.resolve("x/A"), "a.jpg", "b.jpg");
        dir(root.resolve("x/B"), "c.jpg", "d.jpg");
        dir(root.resolve("y/C"), "e.jpg", "f.jpg");
        assertThat(scan(() -> false).changed()).isEqualTo(3);
        var a = byName("A");
        var c = byName("C");

        FileSystemUtils.deleteRecursively(root.resolve("x/A"));
        dir(root.resolve("y/D"), "g.jpg", "h.jpg");
        var x = normalizedRoot() + "/x";
        var progress = scans.scan(List.of(x, x + "/B", x), () -> false);
        assertThat(progress.root()).isEqualTo(x);
        assertThat(progress.removed()).isEqualTo(1);
        assertThat(repo.find(a.id())).isEmpty();
        assertThat(repo.find(c.id())).isPresent();
        assertThat(repo.byRoot(normalizedRoot() + "/y")).as("y was not walked").hasSize(1);
        assertThatThrownBy(() -> scans.scan(List.of(root.getParent().toString()), () -> false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Not inside an indexed folder");
    }

    @Test
    void aRemovedAlbumThatComesBackKeepsItsIdentity() throws IOException {
        dir(root.resolve("T"), "a.jpg", "b.jpg");
        scan(() -> false);
        var t = albums.patch(byName("T").id(), new AlbumPatch(null, "Bob", null, null, null, null, 8, null, null, null, null, null, null));
        FileSystemUtils.deleteRecursively(root.resolve("T"));
        assertThat(scan(() -> false).removed()).isEqualTo(1);

        dir(root.resolve("T"), "a.jpg", "b.jpg"); // restored from a backup, with a sidecar the column cannot hold
        Files.writeString(root.resolve("T/album.txt"), "description: " + "x".repeat(16_385));
        assertThat(scan(() -> false).changed()).as("the save fails").isZero();
        assertThat(repo.byRoot(normalizedRoot())).isEmpty();
        assertThat(repo.recall(t.internal().contentId())).as("a failed save keeps the tombstone").isPresent();

        Files.writeString(root.resolve("T/album.txt"), "description: fixed");
        assertThat(scan(() -> false).changed()).isEqualTo(1);
        var back = byName("T");
        assertThat(back.id()).isEqualTo(t.id());
        assertThat(back.rating()).isEqualTo(8);
        assertThat(back.model()).isEqualTo("Bob");
        assertThat(back.revision()).isGreaterThan(t.revision());
        assertThat(repo.recall(t.internal().contentId())).as("consumed by the save").isEmpty();
    }

    @Test
    void aScopeTypedInAnotherCaseUsesTheStoredSpelling() throws IOException {
        dir(root.resolve("Sub/S"), "a.jpg", "b.jpg");
        assertThat(scan(() -> false).changed()).isEqualTo(1);
        var s = byName("S");
        assumeTrue(Files.isDirectory(root.resolve("sub")), "a case-insensitive file system");
        var progress = scans.scan(List.of(normalizedRoot() + "/sub"), () -> false);
        assertThat(progress.root()).isEqualTo(normalizedRoot() + "/Sub");
        assertThat(progress.changed()).isZero();
        assertThat(progress.removed()).isZero();
        assertThat(repo.byRoot(normalizedRoot())).extracting(Album::id).containsExactly(s.id());
    }

    @Test
    void wideningOrNarrowingARootKeepsTheAlbumsStillInside() throws IOException {
        var sub = Containers.normalize(root.resolve("sub"));
        dir(root.resolve("sub/P"), "a.jpg", "b.jpg");
        useRoot(sub);
        assertThat(scans.scan(null, () -> false).changed()).isEqualTo(1);
        var p = byName(sub, "P");

        useRoot(normalizedRoot());
        var widened = scans.scan(null, () -> false);
        assertThat(widened.changed()).isZero();
        assertThat(widened.removed()).isZero();
        assertThat(repo.find(p.id()).orElseThrow().root()).isEqualTo(normalizedRoot());

        dir(root.resolve("Q"), "c.jpg", "d.jpg");
        assertThat(scans.scan(null, () -> false).changed()).isEqualTo(1);
        var q = byName("Q");
        useRoot(sub);
        assertThat(scans.scan(null, () -> false).changed()).isZero();
        assertThat(repo.find(q.id())).as("outside the narrowed root").isEmpty();
        assertThat(repo.find(p.id()).orElseThrow().root()).isEqualTo(sub);
    }

    @Test
    void encryptedImagesFollowThePassword() throws Exception {
        // a client stays connected: the key lives as long as one does (a pending forget of an earlier test's socket is cancelled)
        var received = new CopyOnWriteArrayList<String>();
        var socket = connect(received);
        socket.sendText("{\"type\":\"getSettings\",\"id\":1}", true).join();
        await(received, m -> m.contains("\"id\":1"));
        var key = Cry.key("test");
        Files.createDirectories(root.resolve("E"));
        Files.write(root.resolve("E/x1.cry"), Cry.encrypt(jpeg(0), "a.jpg", key));
        Files.write(root.resolve("E/x2.cry"), Cry.encrypt(jpeg(1), "b.jpg", key));
        dir(root.resolve("M"), "a.jpg");
        Files.write(root.resolve("M/x3.cry"), Cry.encrypt(jpeg(2), "c.jpg", key));
        scan(() -> false);
        assertThat(repo.byRoot(normalizedRoot())).as("without the password .cry files are no images").extracting(Album::name).containsExactly("M");
        assertThat(byName("M").imageCount()).isEqualTo(1);

        var s = settings.get();
        settings.set(new Settings(s.roots(), s.exclusions(), false, null, null, null, null, null, null, "test"));
        assertThat(settings.get().cryKey()).isEqualTo(HexFormat.of().formatHex(key));
        assertThat(settings.get().cryPassword()).as("never kept").isNull();
        assertThat(Files.readString(settings.file())).as("session only").doesNotContain("cryKey");
        var fingerprint = Cry.fingerprint(key);
        socket.sendText("{\"type\":\"getSettings\",\"id\":2}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":2"))).as("clients see only the fingerprint")
                .contains("\"cryKey\":\"" + fingerprint + "\"").doesNotContain(settings.get().cryKey());
        assertThat(fingerprint).matches("\\p{XDigit}{8}");
        assertThat(settings.set(settings.masked()).cryKey()).as("echoed back: kept").isEqualTo(HexFormat.of().formatHex(key));
        assertThat(scans.scan(null, () -> false).changed()).as("E is new, M holds a .cry file").isEqualTo(2);
        var e = byName("E");
        assertThat(e.imageCount()).isEqualTo(2);
        assertThat(albums.storedCover(e)).as("cover decrypted while indexing").isPresent();
        assertThat(albums.thumb(e, repo.entry(e.id(), 1).orElseThrow())).as("rendered on demand").isPresent();
        assertThat(byName("M").imageCount()).isEqualTo(2);
        assertThat(scans.scan(null, () -> false).changed()).as("same key: unchanged").isZero();
        albums.patch(e.id(), new AlbumPatch(null, null, null, null, null, null, 4, null, null, null, null, null, null));

        settings.set(new Settings(s.roots(), s.exclusions(), false, null, null, null, null, null, null, null));
        var locked = scans.scan(null, () -> false);
        assertThat(locked.changed() + " changed, " + locked.removed() + " removed").as("no key: kept as indexed").isEqualTo("0 changed, 0 removed");
        assertThat(byName("E").imageCount()).isEqualTo(2);
        assertThat(albums.thumb(e, repo.entry(e.id(), 1).orElseThrow())).as("cannot decrypt").isEmpty();

        settings.set(new Settings(s.roots(), s.exclusions(), false, null, null, null, null, null, null, "test"));
        assertThat(scans.scan(null, () -> false).changed()).as("the same key again: unchanged").isZero();
        var back = byName("E");
        assertThat(albums.thumb(back, repo.entry(e.id(), 1).orElseThrow())).isPresent();
        assertThat(back.rating()).isEqualTo(4);

        var echoed = settings.masked();
        settings.forgetKey();
        assertThat(settings.set(echoed).cryKey()).as("a stale fingerprint resurrects nothing").isNull();
        assertThat(new Settings(s.roots(), s.exclusions(), false, null, null, null, null, null, "0A1B2C3D", null).cryKey()).isEqualTo("0a1b2c3d");
        socket.abort();
    }

    @Test
    void socketEchoesIdsHidesServerFieldsAndDropsRemovedRoots() throws Exception {
        dir(root.resolve("W"), "a.jpg", "b.jpg");
        scan(() -> false);
        var w = byName("W");
        var received = new CopyOnWriteArrayList<String>();
        var socket = connect(received);
        socket.sendText("{\"type\":\"search\",\"id\":7,\"query\":{\"text\":\"w\",\"roots\":[\"" + normalizedRoot()
                + "\"],\"sort\":\"NAME\",\"offset\":0,\"limit\":10}}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":7"))).startsWith("{\"type\":\"results\"")
                .contains("\"total\":1", "\"name\":\"W\"", "\"kind\":\"DIRECTORY\"").doesNotContain("internal", "signature");
        socket.sendText("{\"type\":\"nope\",\"id\":\"x\"}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":\"x\""))).contains("\"type\":\"error\"");
        socket.sendText("{\"type\":\"folders\",\"id\":10,\"path\":\"" + normalizedRoot() + "\"}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":10"))).startsWith("{\"type\":\"folders\"")
                .contains("{\"name\":\"W\",\"path\":\"" + normalizedRoot() + "/W\",\"albums\":1,\"nested\":false}");
        socket.sendText("{\"type\":\"browse\",\"id\":11,\"path\":\"" + normalizedRoot() + "\"}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":11"))).startsWith("{\"type\":\"listing\"").contains("\"name\":\"W\"");
        socket.sendText("{\"type\":\"library\",\"id\":12}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":12"))).startsWith("{\"type\":\"libraryInfo\"")
                .contains("\"pinned\":false", "\"albums\":1,", "\"coversCount\":1,", "\"about\":{\"version\":\"dev\"");

        socket.sendText("{\"type\":\"setSettings\",\"id\":9,\"settings\":{\"roots\":[],\"exclusions\":[],\"scanOnStart\":false}}", true).join();
        assertThat(await(received, m -> m.contains("\"id\":9"))).startsWith("{\"type\":\"settings\"");
        await(received, m -> m.equals("{\"type\":\"albumRemoved\",\"albumId\":\"" + w.id() + "\"}"));
        assertThat(repo.byRoot(normalizedRoot())).isEmpty();
        socket.abort();
    }

    @Test
    void catalogOperationAndPhotoMessagesRoundTripThroughTheSocket() throws Exception {
        dir(root.resolve("L"), "a.jpg", "b.jpg");
        scan(() -> false);
        var l = byName("L");
        var received = new CopyOnWriteArrayList<String>();
        var socket = connect(received);
        socket.sendText("{\"type\":\"saveEntity\",\"id\":1,\"entity\":{\"kind\":\"LIST\",\"name\":\"Best\",\"rating\":0,\"aliases\":[\" Top  Ten \"]}}", true).join();
        var card = await(received, m -> m.endsWith("\"id\":1}"));
        assertThat(card).startsWith("{\"type\":\"entityCard\",\"entity\":{\"id\":").contains("\"name\":\"Best\"", "\"aliases\":[\"top ten\"]");
        var list = card.replaceFirst(".*?\"entity\":\\{\"id\":(\\d+).*", "$1");
        await(received, m -> m.equals("{\"type\":\"catalogChanged\",\"kinds\":[\"LIST\"]}"));
        var other = String.valueOf(Integer.parseInt(list) + 100); // the envelope id is not the entity's
        socket.sendText("{\"type\":\"entity\",\"id\":\"" + other + "\",\"entityId\":" + list + "}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":\"" + other + "\"}"))).startsWith("{\"type\":\"entityCard\"")
                .contains("\"entity\":{\"id\":" + list + ",", "\"name\":\"Best\"");
        socket.sendText("{\"type\":\"link\",\"id\":2,\"albumIds\":[\"" + l.id() + "\"],\"entityId\":" + list + "}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":2}"))).isEqualTo("{\"type\":\"ok\",\"count\":1,\"id\":2}");
        socket.sendText("{\"type\":\"entities\",\"id\":3,\"kind\":\"LIST\",\"text\":\"BE\",\"sort\":\"COUNT\",\"offset\":0,\"limit\":10}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":3}"))).startsWith("{\"type\":\"entityList\"")
                .contains("\"albums\":1,", "\"photos\":0", "\"total\":1");
        socket.sendText("{\"type\":\"markPhoto\",\"id\":4,\"albumId\":\"" + l.id() + "\",\"name\":\"a.jpg\",\"rating\":7,\"lists\":[" + list + "]}", true).join();
        var marks = "{\"a.jpg\":{\"rating\":7,\"lists\":[" + list + "]}}";
        assertThat(await(received, m -> m.endsWith("\"id\":4}"))).startsWith("{\"type\":\"photoMarks\"").contains(marks);
        socket.sendText("{\"type\":\"search\",\"id\":5,\"query\":{\"roots\":[\"" + normalizedRoot() + "\"],\"offset\":0,\"limit\":10}}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":5}"))).contains("\"hidden\":false",
                "\"links\":[{\"entity\":" + list + ",\"role\":\"LIST\",\"name\":\"Best\"}]", "\"copies\":1",
                "\"stamp\":\"" + l.internal().contentId() + "\"").doesNotContain("seq");
        socket.sendText("{\"type\":\"photos\",\"id\":11,\"listId\":" + list + ",\"offset\":0,\"limit\":10}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":11}"))).startsWith("{\"type\":\"photoList\"")
                .contains("\"name\":\"a.jpg\",\"index\":0,\"rating\":7", "\"total\":1");
        repo.saveEntries(l.id(), repo.entries(l.id()).stream().map(e -> new Entry(e.index(), e.name(), e.bytes(), e.mtime(),
                e.width(), e.height(), e.shotAt(), 0)).toList()); // indexed before flags were known
        long revision = repo.find(l.id()).orElseThrow().revision();
        socket.sendText("{\"type\":\"photoMeta\",\"id\":12,\"albumId\":\"" + l.id() + "\",\"index\":0}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":12}"))).startsWith("{\"type\":\"photoMeta\",\"rows\":[");
        assertThat(repo.entries(l.id())).as("what the read learned is stored").extracting(Entry::flags).containsExactly(Entry.META_KNOWN, 0);
        assertThat(repo.find(l.id()).orElseThrow().revision()).as("no album save").isEqualTo(revision);
        assertThat(albums.patch(l.id(), a -> null)).as("no edit, no save").isEmpty();
        assertThat(albums.patch("gone", a -> { throw new AssertionError("no album to edit"); })).isEmpty();

        socket.sendText("{\"type\":\"bulkUpdate\",\"id\":6,\"query\":{\"roots\":[\"" + normalizedRoot() + "\"],\"offset\":0,\"limit\":10},"
                + "\"patch\":{\"hidden\":true,\"addTags\":[\"#Best Of\"],\"date\":\"2001\"}}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":6}"))).isEqualTo("{\"type\":\"ok\",\"count\":1,\"id\":6}");
        var hidden = repo.find(l.id()).orElseThrow();
        assertThat(hidden).extracting(Album::hidden, Album::tags, Album::shotDate).containsExactly(true, List.of("best-of"), LocalDate.of(2001, 1, 1));
        socket.sendText("{\"type\":\"search\",\"id\":10,\"query\":{\"roots\":[\"" + normalizedRoot() + "\"],\"offset\":0,\"limit\":10}}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":10}"))).as("hidden").contains("\"total\":0");
        socket.sendText("{\"type\":\"album\",\"id\":7,\"albumId\":\"" + l.id() + "\"}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":7}"))).contains("\"marks\":" + marks, "\"flags\":1");
        socket.sendText("{\"type\":\"compareCopies\",\"id\":8,\"a\":\"x\",\"b\":\"y\"}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":8}"))).isEqualTo("{\"type\":\"error\",\"message\":\"No album x\",\"id\":8}");

        socket.sendText("{\"type\":\"op\",\"id\":9,\"op\":{\"kind\":\"REPACK\",\"albumIds\":[\"" + l.id() + "\"],\"password\":\"s3cret\"}}", true).join();
        assertThat(await(received, m -> m.endsWith("\"id\":9}"))).startsWith("{\"type\":\"opAccepted\",\"opId\":\"");
        assertThat(await(received, m -> m.startsWith("{\"type\":\"opResult\"")))
                .contains("\"skipped\":[{\"item\":\"" + l.path() + "\",\"reason\":\"not an archive\"}]");
        assertThat(received).noneMatch(m -> m.contains("s3cret"));
        socket.abort();
    }

    @Test
    void scannedTextBecomesAutoEntitiesCountedWhileVisibleAndAnOldDatabaseIsBackfilled() throws IOException {
        var model = "Zoe " + salt;
        dir(root.resolve("Z"), "a.jpg", "b.jpg");
        Files.writeString(root.resolve("Z/album.txt"), "model: " + model + "\nstudio: Studio " + salt + "\n#tag" + salt);
        assertThat(jdbc.queryForObject("SELECT val FROM meta WHERE name = 'catalog_version'", String.class)).as("backfilled at start").isEqualTo("1");
        scan(() -> false);
        var z = byName("Z");
        assertThat(z.links()).extracting(Link::role, Link::name).containsExactly(tuple(EntityKind.MODEL, model),
                tuple(EntityKind.STUDIO, "Studio " + salt), tuple(EntityKind.TAG, "tag" + salt));
        var zoe = catalog.entities(EntityKind.MODEL, model, null, false, 0, 10).getFirst();
        assertThat(zoe).extracting(Entity::name, Entity::auto, Entity::albums).containsExactly(model, true, 1);
        assertThat(catalog.entities(EntityKind.TAG, "tag" + salt, null, false, 0, 10)).extracting(Entity::auto).containsExactly(false);
        albums.patch(z.id(), new AlbumPatch(null, null, null, null, null, null, null, null, null, null, true, null, null));
        assertThat(catalog.entity(zoe.id()).albums()).as("hidden").isZero();
        albums.patch(z.id(), new AlbumPatch(null, null, null, null, null, null, null, null, null, null, false, null, null));
        assertThat(catalog.entity(zoe.id()).albums()).isEqualTo(1);

        jdbc.update("DELETE FROM album_link WHERE role <> 6"); // a database from before the catalog
        jdbc.update("UPDATE meta SET val = '0' WHERE name = 'catalog_version'");
        assertThat(repo.find(z.id()).orElseThrow().links()).isEmpty();
        catalog.backfill();
        assertThat(repo.find(z.id()).orElseThrow()).extracting(a -> a.links().size(), Album::revision).containsExactly(3, z.revision() + 2);
        assertThat(catalog.entity(zoe.id()).albums()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT val FROM meta WHERE name = 'catalog_version'", String.class)).isEqualTo("1");
    }

    @Test
    void curationRewritesAlbumTextThroughPatchesAndCopiesCompareByteForByte() throws Exception {
        String model = "Zed " + salt, studio = "Stu " + salt, other = "Ola " + salt, credited = "Zeta " + salt;
        dir(root.resolve("C1"), "a.jpg", "b.jpg");
        Files.writeString(root.resolve("C1/album.txt"), "model: " + model + "\nstudio: " + studio);
        dir(root.resolve("C2"), "a.jpg", "b.jpg");
        Files.writeString(root.resolve("C2/album.txt"), "model: " + other);
        scan(() -> false);
        var c1 = byName("C1");
        var c2 = byName("C2");
        int m = c1.links().get(0).entity(), s = c1.links().get(1).entity();
        var received = new CopyOnWriteArrayList<String>();
        var socket = connect(received);

        socket.sendText("{\"type\":\"link\",\"id\":1,\"albumIds\":[\"" + c2.id() + "\"],\"entityId\":" + m + "}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":1}"))).isEqualTo("{\"type\":\"ok\",\"count\":1,\"id\":1}");
        assertThat(repo.find(c2.id()).orElseThrow().model()).as("the effective list + the name").isEqualTo(other + ", " + model);
        socket.sendText("{\"type\":\"link\",\"id\":2,\"albumIds\":[\"" + c2.id() + "\"],\"entityId\":" + m + "}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":2}"))).as("already linked").isEqualTo("{\"type\":\"ok\",\"count\":0,\"id\":2}");

        socket.sendText("{\"type\":\"creditAs\",\"id\":3,\"modelId\":" + m + ",\"studioId\":" + s + ",\"alias\":\"" + credited + "\"}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":3}"))).startsWith("{\"type\":\"entityCard\"").contains("\"alias\":\"" + credited + "\"");
        var rewritten = repo.find(c1.id()).orElseThrow();
        assertThat(rewritten.model()).isEqualTo(credited);
        assertThat(rewritten.links().getFirst()).extracting(Link::entity, Link::alias).containsExactly(m, credited);
        assertThat(rewritten.revision()).isEqualTo(c1.revision() + 1);
        assertThat(repo.find(c2.id()).orElseThrow().model()).as("not at that studio").isEqualTo(other + ", " + model);

        socket.sendText("{\"type\":\"unlink\",\"id\":4,\"albumIds\":[\"" + c1.id() + "\"],\"entityId\":" + m + "}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":4}"))).isEqualTo("{\"type\":\"ok\",\"count\":1,\"id\":4}");
        assertThat(repo.find(c1.id()).orElseThrow()).extracting(Album::model, a -> a.internal().edited().model()).containsExactly(null, "");

        socket.sendText("{\"type\":\"deleteEntities\",\"id\":5,\"ids\":[" + m + "]}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":5}"))).isEqualTo("{\"type\":\"ok\",\"id\":5}");
        assertThat(repo.find(c2.id()).orElseThrow().model()).isEqualTo(other);
        assertThat(catalog.entities(EntityKind.MODEL, model, null, false, 0, 10)).isEmpty();

        Files.createDirectories(root.resolve("C3"));
        for (var f : List.of("a.jpg", "b.jpg", "album.txt")) Files.copy(root.resolve("C2").resolve(f), root.resolve("C3").resolve(f));
        scan(() -> false);
        var c3 = byName("C3");
        socket.sendText("{\"type\":\"compareCopies\",\"id\":6,\"a\":\"" + c2.id() + "\",\"b\":\"" + c3.id() + "\"}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":6}"))).isEqualTo("{\"type\":\"comparison\",\"identical\":true,\"differing\":[],\"id\":6}");
        var b = Files.readAllBytes(root.resolve("C3/b.jpg"));
        b[b.length / 2] ^= 1; // same size, other bytes
        Files.write(root.resolve("C3/b.jpg"), b);
        socket.sendText("{\"type\":\"compareCopies\",\"id\":7,\"a\":\"" + c2.id() + "\",\"b\":\"" + c3.id() + "\"}", true).join();
        assertThat(await(received, x -> x.endsWith("\"id\":7}"))).isEqualTo("{\"type\":\"comparison\",\"identical\":false,\"differing\":[\"b.jpg\"],\"id\":7}");
        socket.abort();
    }

    @Test
    void theGateAdmitsOneActivityAtATime() throws Exception {
        useRoot(root.toString());
        var received = new CopyOnWriteArrayList<String>();
        var socket = connect(received);
        try (var op = gate.tryEnter(Gate.Activity.OP).orElseThrow()) {
            assertThat(gate.tryEnter(Gate.Activity.SCAN)).isEmpty();
            assertThat(scans.start(null)).isFalse();
            assertThat(scans.start(List.of(normalizedRoot() + "-outside"))).as("the gate before the paths").isFalse();
            assertThat(scans.running()).isFalse();
            socket.sendText("{\"type\":\"scan\",\"id\":1}", true).join();
            assertThat(await(received, m -> m.contains("\"id\":1"))).contains("\"message\":\"An operation is running\"");
            socket.sendText("{\"type\":\"setSettings\",\"id\":2,\"settings\":{\"roots\":[{\"path\":\"" + normalizedRoot()
                    + "/elsewhere\"}],\"exclusions\":[],\"scanOnStart\":false}}", true).join();
            assertThat(await(received, m -> m.contains("\"id\":2"))).contains("\"message\":\"An operation is running\"");
            assertThat(settings.get().roots()).noneMatch(r -> r.path().endsWith("/elsewhere"));
            socket.sendText("{\"type\":\"setSettings\",\"id\":3,\"settings\":{\"roots\":[{\"path\":\"" + normalizedRoot()
                    + "\"}],\"exclusions\":[\"*.tmp\"],\"scanOnStart\":false}}", true).join();
            assertThat(await(received, m -> m.contains("\"id\":3"))).as("same folders: no gate needed").startsWith("{\"type\":\"settings\"");
            assertThat(settings.get().exclusions()).containsExactly("*.tmp");
        }
        assertThat(gate.current()).isNull();
        assertThatThrownBy(() -> scans.start(List.of(normalizedRoot() + "-outside"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Not inside an indexed folder");
        assertThat(gate.current()).as("a refused scan lets the gate go").isNull();
        socket.abort();
    }

    @Test
    void quiesceWaitsForReadsInFlightAndRefusesEveryNewOne() throws Exception {
        dir(root.resolve("Q"), "a.jpg", "b.jpg");
        scan(() -> false);
        var q = byName("Q");
        var reading = albums.read(q.id());
        var quiesced = CompletableFuture.runAsync(() -> albums.quiesce(List.of(q.id())));
        Thread.sleep(300);
        assertThat(quiesced).as("waits for the read that began before").isNotDone();
        assertThatThrownBy(() -> albums.read(q.id())).hasMessage(AlbumService.BUSY);
        assertThatThrownBy(() -> albums.thumb(q, repo.entry(q.id(), 0).orElseThrow())).hasMessage(AlbumService.BUSY);
        assertThatThrownBy(() -> albums.cover(q)).hasMessage(AlbumService.BUSY);
        reading.close();
        reading.close(); // harmless
        quiesced.get(2, TimeUnit.SECONDS);
        for (var path : List.of("/0", "/0/thumb", "/cover")) {
            var busy = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/img/"
                    + q.id() + path)).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(busy.statusCode()).as(path).isEqualTo(503);
            assertThat(busy.headers().firstValue("Retry-After")).hasValue("2");
        }
        albums.release(List.of(q.id()));
        var original = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/img/"
                + q.id() + "/0")).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(original.statusCode()).isEqualTo(200);
        var again = CompletableFuture.runAsync(() -> albums.quiesce(List.of(q.id())));
        again.get(2, TimeUnit.SECONDS); // the streamed original closed its read
        albums.release(List.of(q.id()));
    }

    @Test
    void preflightMeasuresFreeSpaceWhereThePathMapPutsTheTarget() throws IOException {
        var rename = new Op(Op.Kind.FOLDER_RENAME, null, null, List.of(normalizedRoot()), "Renamed", null, null, null, null, null);
        assertThat(ops.preflight(rename).free()).as("a rename's target is a name").isEqualTo(-1);
        var delete = new Op(Op.Kind.FOLDER_DELETE, null, null, List.of(normalizedRoot()), normalizedRoot(), null, null, null, null, null);
        assertThat(ops.preflight(delete).free()).isEqualTo(-1);
        var drive = IntStream.rangeClosed('M', 'Z').mapToObj(c -> (char) c + ":/").filter(d -> Files.notExists(Path.of(d))).findFirst();
        assumeTrue(File.separatorChar == '\\' && drive.isPresent(), "needs a drive letter that does not exist");
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(), false,
                List.of(new Settings.Mapping(drive.get() + "library", root.toString())), null, null));
        var move = new Op(Op.Kind.MOVE, List.of(), null, null, drive.get() + "library/sub", null, null, null, null, null);
        assertThat(ops.preflight(move).free()).as("measured on the disk the mapping leads to").isPositive();
    }

    @Test
    void reindexKeepsTheIdentityOfAnAlbumAnOperationRewrote() throws Exception {
        dir(root.resolve("R"), "a.jpg", "b.jpg");
        scan(() -> false);
        var r = albums.patch(byName("R").id(), new AlbumPatch(null, null, null, null, null, null, 6, "a.jpg", "2001-05", null, null, null, null));
        assertThat(r.shotDate()).isEqualTo(LocalDate.of(2001, 5, 1));
        catalog.mark(r.internal().seq(), "a.jpg", 9, null);
        Files.move(root.resolve("R"), root.resolve("S"));
        Files.move(root.resolve("S/a.jpg"), root.resolve("S/x.jpg"));

        var s = reindexer.reindex(r.id(), normalizedRoot() + "/S", Kind.DIRECTORY, Map.of("a.jpg", "x.jpg"));
        assertThat(s).extracting(Album::id, Album::path, Album::rating, Album::revision, Album::cover, Album::coverPinned, Album::shotDate)
                .containsExactly(r.id(), normalizedRoot() + "/S", 6, r.revision() + 1, "x.jpg", true, LocalDate.of(2001, 5, 1));
        assertThat(catalog.marks(s.internal().seq())).containsOnlyKeys("x.jpg");
        assertThat(scan(() -> false).changed()).as("a later scan finds it unchanged").isZero();
        var cleared = albums.patch(r.id(), new AlbumPatch(null, null, null, null, null, null, null, null, "", null, null, null, null));
        assertThat(cleared.shotDate()).as("back to the indexed date").isNotEqualTo(LocalDate.of(2001, 5, 1)).isNotNull();
        assertThatThrownBy(() -> albums.patch(r.id(), new AlbumPatch(null, null, null, null, null, null, null, null, "May", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);

        albums.quiesce(List.of(r.id()));
        var busy = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/img/"
                + r.id() + "/cover")).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(busy.statusCode()).isEqualTo(503);
        assertThat(busy.headers().firstValue("Retry-After")).hasValue("2");
        assertThatThrownBy(() -> albums.cover(cleared)).hasMessage("busy");
        albums.release(List.of(r.id()));
        assertThat(albums.cover(cleared)).isPresent();

        var stale = Tombstone.of(cleared); // an edit lands while an operation indexes: the commit keeps it
        var edited = albums.patch(r.id(), new AlbumPatch(null, null, null, null, null, null, 3, null, null, null, null, null, null));
        assertThat(albums.commit(stale, cleared, repo.entries(r.id()), null, Map.of()))
                .extracting(Album::rating, Album::revision).containsExactly(3, edited.revision() + 1);

        Files.createDirectories(root.resolve("T"));
        Files.move(root.resolve("S"), root.resolve("T/S"));
        assertThat(albums.rebase(normalizedRoot() + "/S", normalizedRoot() + "/T/S", normalizedRoot())).isEqualTo(1);
        assertThat(repo.find(r.id()).orElseThrow()).extracting(Album::path, Album::root, Album::rating, Album::revision)
                .containsExactly(normalizedRoot() + "/T/S", normalizedRoot(), 3, edited.revision() + 2);
    }

    private ScanProgress scan(BooleanSupplier cancelled, String... exclusions) throws IOException {
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(exclusions), false, null, null, null));
        return scans.scan(null, cancelled);
    }

    private String normalizedRoot() { return Containers.normalize(root); }

    private void useRoot(String path) throws IOException {
        settings.set(new Settings(List.of(new Settings.Root(path, List.of())), List.of(), false, null, null, null));
    }

    private Album byName(String name) { return byName(normalizedRoot(), name); }

    private Album byName(String root, String name) {
        return repo.byRoot(root).stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private WebSocket connect(List<String> received) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        received.add(data.toString());
                        ws.request(1);
                        return CompletableFuture.completedFuture(null);
                    }
                }).get(5, TimeUnit.SECONDS);
    }

    private static String await(List<String> received, Predicate<String> match) throws InterruptedException {
        for (long end = System.nanoTime() + 5_000_000_000L; System.nanoTime() < end; Thread.sleep(10)) {
            for (var m : received) if (match.test(m)) return m;
        }
        throw new AssertionError("No matching message in " + received);
    }

    @Test
    void rootsOnDifferentDisksAreScannedAtOnceAndEachPrunes() throws IOException {
        Path fast = root.resolve("fast"), slow = root.resolve("slow"); // forced apart: one SSD walker, one HDD walker
        dir(fast.resolve("A"), "a.jpg", "b.jpg");
        dir(slow.resolve("B"), "c.jpg", "d.jpg");
        dir(slow.resolve("C"), "e.jpg", "f.jpg");
        settings.set(new Settings(List.of(new Settings.Root(fast.toString(), List.of(), Disks.Storage.SSD),
                new Settings.Root(slow.toString(), List.of(), Disks.Storage.HDD)), List.of(), false, null, null, null));
        assertThat(scans.scan(null, () -> false).changed()).isEqualTo(3);
        assertThat(albums.storedCover(byName(Containers.normalize(slow), "B"))).as("read one by one, rendered").isPresent();
        FileSystemUtils.deleteRecursively(slow.resolve("C"));
        var progress = scans.scan(null, () -> false);
        assertThat(progress.message()).isEqualTo("Done");
        assertThat(progress.removed()).isEqualTo(1);
        assertThat(repo.byRoot(Containers.normalize(fast))).hasSize(1);
        assertThat(repo.byRoot(Containers.normalize(slow))).hasSize(1);
    }

    @Test
    void sevenZipArchivesBecomeAlbumsAndRescanByStat() throws IOException {
        var images = new java.util.LinkedHashMap<String, byte[]>();
        for (int i = 0; i < 3; i++) images.put("set/" + (char) ('a' + i) + ".jpg", jpeg(i));
        Archives.sevenZip(root.resolve("D.7z"), ArchiveFormat.SEVEN_ZIP, images, true);
        images.remove("set/c.jpg");
        Files.write(root.resolve("E.tar.gz"), Archives.gzip(Archives.tar(images)));
        assertThat(scan(() -> false).changed()).isEqualTo(2);
        var d = byName("D");
        var e = byName("E");
        assertThat(d.kind()).isEqualTo(Kind.ARCHIVE);
        assertThat(d.imageCount()).isEqualTo(3);
        assertThat(e.imageCount()).isEqualTo(2);
        assertThat(d.cover()).as("smallest image").isEqualTo("set/a.jpg");
        assertThat(albums.storedCover(d)).isPresent();
        assertThat(albums.storedCover(e)).isPresent();
        for (var album : List.of(d, e))
            for (var entry : repo.entries(album.id()))
                assertThat(albums.thumb(album, entry)).as("one gallery pass, then alone: " + entry.name()).isPresent();
        assertThat(albums.thumb(d, repo.entries(d.id()).getFirst())).as("taken before: rendered alone").isPresent();
        assertThat(scan(() -> false).changed()).as("unchanged archives are not opened").isZero();
    }

    private void dir(Path dir, String... names) throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < names.length; i++) Files.write(dir.resolve(names[i]), jpeg(i));
    }

    private void zip(Path file, String... names) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < names.length; i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(jpeg(i));
            }
        }
    }

    /** Distinct sizes per index, so "smallest" covers are deterministic. */
    private byte[] jpeg(int i) throws IOException {
        var img = new BufferedImage(64 + salt + 32 * i, 48 + 24 * i, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
