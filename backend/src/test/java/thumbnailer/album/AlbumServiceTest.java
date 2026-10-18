package thumbnailer.album;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogRepository;

class AlbumServiceTest {

    /**
     * A delete that races a catalog write (favorites list change, recount) is held off until the delete has ended. The
     * cover's removal comes after the read lock is released, so the write may run before or after it: covers are no
     * catalog data.
     */
    @Test
    void removeDeletesUnderTheCatalogReadLockSoNoCountChangeLandsInTheDeleteTransaction() throws Exception {
        var calls = Collections.synchronizedList(new ArrayList<String>()); // the writer thread reports too
        var catalog = new Catalog(stub(CatalogRepository.class, m -> calls.add("catalog." + m)), e -> {});
        var writer = new Thread[1];
        var seen = new Thread.State[1];
        var repo = stub(AlbumRepository.class, m -> {
            calls.add("delete");
            writer[0] = Thread.ofPlatform().start(catalog::recount); // takes the write lock
            for (long end = System.nanoTime() + 10_000_000_000L; writer[0].isAlive() && writer[0].getState() != Thread.State.WAITING
                    && System.nanoTime() < end; ) Thread.onSpinWait();
            seen[0] = writer[0].getState(); // while the delete is still running
        });
        var albums = new AlbumService(repo, stub(BlobStore.class, m -> calls.add("blob." + m)), null, null, null, null, e -> {}, null, catalog, null);

        albums.remove("a");

        assertThat(seen[0]).as("the write waited for the delete").isEqualTo(Thread.State.WAITING);
        writer[0].join(10_000);
        assertThat(calls).containsExactlyInAnyOrder("delete", "blob.remove", "catalog.recount").first().isEqualTo("delete");
    }

    /** A stub of a port: every call is reported by name and answers null (only void methods are used). */
    private static <T> T stub(Class<T> port, Consumer<String> calls) {
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (p, m, a) -> {
            calls.accept(m.getName());
            return null;
        }));
    }
}
