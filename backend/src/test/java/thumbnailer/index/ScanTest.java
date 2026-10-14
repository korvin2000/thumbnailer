package thumbnailer.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dynatrace.hash4j.hashing.Hashing;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.album.BlobStore;
import thumbnailer.image.Render;
import thumbnailer.settings.Settings;
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
        albums.patch(a.id(), new AlbumPatch(null, null, null, null, null, null, 7, null));

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
        albums.patch(before.id(), new AlbumPatch(null, null, null, null, null, null, 6, null));

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

        albums.patch(e.id(), new AlbumPatch(null, "Bob", null, null, "", List.of("wedding"), null, "b.jpg"));
        Files.writeString(album.resolve("album.txt"), "model: Carol\nstudio: S2\ndescription: Second\n#wedding #outdoor #beach");
        assertThat(scan(() -> false).changed()).isEqualTo(1);

        var refreshed = repo.find(e.id()).orElseThrow();
        assertThat(refreshed.model()).as("override kept").isEqualTo("Bob");
        assertThat(refreshed.studio()).as("extracted refreshed").isEqualTo("S2");
        assertThat(refreshed.description()).as("\"\" clears").isNull();
        assertThat(refreshed.tags()).as("a removed tag does not return").containsExactly("wedding");
        assertThat(refreshed.cover()).isEqualTo("b.jpg");
        assertThat(refreshed.coverPinned()).isTrue();

        var unpinned = albums.patch(e.id(), new AlbumPatch(null, null, null, null, null, null, null, ""));
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
        var pinned = albums.patch(k.id(), new AlbumPatch(null, null, null, null, null, null, null, other));
        assertThat(albums.storedCover(pinned)).as("the previous cover's value is a miss").isEmpty();
        var rendered = albums.cover(pinned).orElseThrow().bytes();
        var rated = albums.patch(k.id(), new AlbumPatch(null, null, null, null, null, null, 3, null));
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
                m.cover().equals("a.jpg") ? "b.jpg" : "a.jpg"));
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
        var t = albums.patch(byName("T").id(), new AlbumPatch(null, "Bob", null, null, null, null, 8, null));
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
