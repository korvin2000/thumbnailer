package thumbnailer.store.tupl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.album.BlobStore;

class TuplBlobStoreTest {

    @TempDir Path dataDir;

    @Test
    void storesOverwritesRemovesAndPersists() throws IOException {
        try (var store = new TuplBlobStore(dataDir, 8)) {
            store.put("a", bytes(3, 1));
            store.put("ab", bytes(3, 2));
            store.put("a", bytes(5, 3));
            store.remove("ab");
            assertThat(store.get("a")).hasValue(bytes(5, 3));
            assertThat(store.get("ab")).isEmpty();
            assertThat(store.get("missing")).isEmpty();
        }
        try (var reopened = new TuplBlobStore(dataDir, 8)) {
            assertThat(reopened.get("a")).hasValue(bytes(5, 3));
        }
        try (var files = Files.list(dataDir.resolve("covers"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("tupl.redo"));
        }
    }

    @Test
    void concurrentWritersAndReadersSeeWholeValues() throws Exception {
        try (var store = new TuplBlobStore(dataDir, 8); var pool = Executors.newFixedThreadPool(8)) {
            for (int k = 0; k < 50; k++) store.put("k" + k, bytes(1_000, 7));
            var tasks = IntStream.range(0, 8).mapToObj(t -> pool.submit(() -> {
                for (int i = 0; i < 300; i++) {
                    var key = "k" + i % 50;
                    if (t < 4) store.put(key, bytes(20_000 + i, i));
                    else assertThat(store.get(key)).hasValueSatisfying(v -> assertThat(v).containsOnly(v[0]));
                }
                return null;
            })).toList();
            for (Future<?> task : tasks) task.get();
            for (int k = 0; k < 50; k++) assertThat(store.get("k" + k).orElseThrow()).as("a writer's value").hasSizeGreaterThanOrEqualTo(20_000);
        }
    }

    @Test
    void compactionReturnsFreedSpaceAndStatsSeeIt() throws IOException {
        try (var store = new TuplBlobStore(dataDir, 8)) {
            BlobStore port = store;
            for (int i = 0; i < 2000; i++) port.put("c" + i, bytes(20_000, i));
            for (int i = 0; i < 2000; i += 2) port.remove("c" + i);
            port.compact();
            var stats = port.stats();
            assertThat(stats.count()).isEqualTo(1000);
            assertThat(stats.liveBytes()).isBetween(1000 * 20_000L, (long) (1000 * 20_000 * 1.1));
            assertThat(stats.fileBytes()).isEqualTo(Files.size(dataDir.resolve("covers/tupl.db")))
                    .isLessThanOrEqualTo((long) (1000 * 20_000 * 1.1));
            assertThat(port.get("c1")).hasValue(bytes(20_000, 1));
        }
    }

    @Test
    void corruptStoreIsMovedAsideAndLockedStoreIsAnError() throws IOException {
        Files.createDirectories(dataDir.resolve("covers"));
        Files.write(dataDir.resolve("covers/tupl.db"), bytes(64 << 10, 7));
        try (var store = new TuplBlobStore(dataDir, 8)) {
            store.put("a", bytes(3, 1));
            assertThat(store.get("a")).isPresent();
            try (var dirs = Files.list(dataDir)) {
                assertThat(dirs.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("covers.broken-"));
            }
            assertThatThrownBy(() -> new TuplBlobStore(dataDir, 8)).isInstanceOf(IllegalStateException.class);
        }
    }

    private static byte[] bytes(int n, int fill) {
        var b = new byte[n];
        Arrays.fill(b, (byte) fill);
        return b;
    }
}
