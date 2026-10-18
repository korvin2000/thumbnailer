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
import thumbnailer.spi.Entry;

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

    /** A metadata read stores what it learned only on the entry it read: a re-index in between may have stored another file. */
    @Test
    void setFlagsRewritesOnlyTheStoredEntryThatIsStillTheFileRead() {
        var stored = new Entry(0, "a.jpg", 10, 100, 4, 3, null, 0);
        var saved = new ArrayList<List<Entry>>();
        var repo = (AlbumRepository) Proxy.newProxyInstance(AlbumRepository.class.getClassLoader(), new Class<?>[]{AlbumRepository.class},
                (p, m, a) -> {
                    if (m.getName().equals("saveEntries")) saved.add(List.copyOf((List<Entry>) a[1]));
                    return m.getName().equals("entries") ? List.of(stored) : null;
                });
        var albums = new AlbumService(repo, null, null, null, null, null, e -> {}, null, null, null);

        albums.setFlags("x", new Entry(0, "a.jpg", 10, 99, 4, 3, null, 0), Entry.META_KNOWN);
        assertThat(saved).as("another mtime: another file").isEmpty();
        albums.setFlags("x", stored, Entry.META_KNOWN | Entry.META);
        assertThat(saved).containsExactly(List.of(new Entry(0, "a.jpg", 10, 100, 4, 3, null, Entry.META_KNOWN | Entry.META)));
    }

    /** A stub of a port: every call is reported by name and answers null (only void methods are used). */
    private static <T> T stub(Class<T> port, Consumer<String> calls) {
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (p, m, a) -> {
            calls.accept(m.getName());
            return null;
        }));
    }
}
