package thumbnailer.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.album.AlbumService;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.settings.SettingsStore;
import tools.jackson.databind.json.JsonMapper;

/** {@link ScanService#start} and {@link ScanService#cancel} over fakes: no roots, so a scan ends at once. */
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

    private ScanService scans(Gate gate) throws Exception {
        var store = SettingsStore.class.getDeclaredConstructor(Path.class, JsonMapper.class);
        store.setAccessible(true); // package-private: the store is a bean, built by Spring
        var albums = new AlbumService(null, null, null, null, null, null, null, null, null, null) {
            @Override
            public void retainRoots(Collection<String> roots) {} // no repository behind it
        };
        var catalog = new Catalog(CatalogRepository.class.cast(Proxy.newProxyInstance(CatalogRepository.class.getClassLoader(),
                new Class<?>[]{CatalogRepository.class}, (p, m, a) -> null)), e -> {});
        return new ScanService(store.newInstance(dir, JsonMapper.builder().build()), null, new Reconciler(null, albums, null, null),
                events::add, null, gate, catalog, 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
