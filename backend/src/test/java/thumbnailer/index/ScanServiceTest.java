package thumbnailer.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumRepository.Stamp;
import thumbnailer.album.AlbumService;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.source.Disks;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ScanService#start} and {@link ScanService#cancel} over fakes: no roots, so a scan ends at once; a scan of one
 * root over a stored index, its walk failing on purpose.
 */
class ScanServiceTest {

    @TempDir Path dir;
    private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();

    /** The gate is taken and visible, the flag is not yet published: a cancel must wait for it, not hit the previous scan's flag. */
    @Test
    void aCancelThatSeesTheScanRunningReachesItWhileStartIsStillTakingTheGate() throws Exception {
        var inside = new CountDownLatch(1);
        var go = new CountDownLatch(1);
        var gate = new Gate() {
            @Override
            public Optional<Hold> tryEnter(Activity activity) {
                var hold = super.tryEnter(activity);
                inside.countDown();
                await(go);
                return hold;
            }
        };
        var scans = scans(gate);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var started = pool.submit(() -> scans.start(List.of()));
            await(inside);
            assertThat(scans.running()).isTrue();
            var canceller = Thread.ofPlatform().start(scans::cancel);
            for (long end = System.nanoTime() + 10_000_000_000L; canceller.isAlive() && canceller.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < end; ) Thread.onSpinWait();
            go.countDown();
            assertThat(started.get(10, TimeUnit.SECONDS)).isTrue();
            canceller.join(10_000);
            assertThat(((ScanProgress) events.poll(10, TimeUnit.SECONDS)).message()).isEqualTo("Cancelled");
        }
    }

    /** A refused start lets the gate go, and a cancel with no scan running is not carried over to the next scan. */
    @Test
    void aRefusedStartReleasesTheGateAndALateCancelDoesNotPoisonTheNextScan() throws Exception {
        var gate = new Gate();
        var scans = scans(gate);
        assertThatThrownBy(() -> scans.start(List.of("/nowhere"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(gate.current()).isNull();
        scans.cancel();
        assertThat(scans.start(List.of())).isTrue();
        assertThat(((ScanProgress) events.poll(10, TimeUnit.SECONDS)).message()).isEqualTo("Done");
    }

    /**
     * A folder the walk may not list hides what is stored below it, it does not remove it: those albums stay, an album
     * gone elsewhere is pruned, and an unreadable system folder counts as empty. The denial is injected into the walk.
     */
    @Test
    void anUnreadableFolderKeepsTheAlbumsStoredBelowItWhileTheRestIsPruned() throws Exception {
        var root = Files.createDirectories(dir.resolve("root"));
        var denied = Set.of(Files.createDirectories(root.resolve("locked/inner")).getParent(),
                Files.createDirectories(root.resolve("$RECYCLE.BIN/S-1-5-21")));
        var r = Containers.normalize(root);
        var stored = Map.of(r + "/locked/inner/a", new Stamp("kept", "s"), r + "/gone", new Stamp("gone", "s"),
                r + "/$RECYCLE.BIN/S-1-5-21/b", new Stamp("binned", "s"));
        var repo = AlbumRepository.class.cast(Proxy.newProxyInstance(AlbumRepository.class.getClassLoader(), new Class<?>[]{AlbumRepository.class},
                (p, m, a) -> stored.entrySet().stream().filter(e -> Containers.within(e.getKey(), (String) a[0]))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))); // stamps(dir) only
        var removed = new ArrayList<String>();
        var albums = new AlbumService(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public void retainRoots(Collection<String> roots) {}

            @Override
            public void remove(String id) { removed.add(id); }
        };
        var discovery = new AlbumDiscovery(null, 2) {
            @Override
            void walkFileTree(Path start, FileVisitor<Path> visitor) throws IOException {
                super.walkFileTree(start, new SimpleFileVisitor<>() { // a denied folder: no visit, no post-visit, a failure
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                        if (!denied.contains(d)) return visitor.preVisitDirectory(d, attrs);
                        visitor.visitFileFailed(d, new AccessDeniedException(d.toString()));
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException { return visitor.postVisitDirectory(d, e); }
                });
            }
        };
        var disks = new Disks(1) {
            @Override
            public Disk disk(Path physicalRoot, Storage storage) { return new Disk("test", true); }
        };
        var store = settings();
        store.set(new Settings(List.of(new Settings.Root(r, null)), null, false, null, null, null));
        var scans = new ScanService(store, discovery, new Reconciler(repo, albums, null, null), events::add, disks, new Gate(),
                catalog(), 1);

        var done = scans.scan(null, () -> false);

        assertThat(done.message()).isEqualTo("Done");
        assertThat(removed).containsExactlyInAnyOrder("gone", "binned");
        assertThat(done.removed()).isEqualTo(2);
    }

    private SettingsStore settings() throws Exception {
        var store = SettingsStore.class.getDeclaredConstructor(Path.class, JsonMapper.class);
        store.setAccessible(true); // package-private: the store is a bean, built by Spring
        return store.newInstance(dir.resolve("library"), JsonMapper.builder().build());
    }

    private static Catalog catalog() {
        return new Catalog(CatalogRepository.class.cast(Proxy.newProxyInstance(CatalogRepository.class.getClassLoader(),
                new Class<?>[]{CatalogRepository.class}, (p, m, a) -> null)), e -> {});
    }

    private ScanService scans(Gate gate) throws Exception {
        var albums = new AlbumService(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public void retainRoots(Collection<String> roots) {} // no repository behind it
        };
        return new ScanService(settings(), null, new Reconciler(null, albums, null, null), events::add, null, gate, catalog(), 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
