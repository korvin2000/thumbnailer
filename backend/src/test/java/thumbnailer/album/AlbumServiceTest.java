package thumbnailer.album;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.album.AlbumRepository.RootStats;
import thumbnailer.album.AlbumRepository.Stamp;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.cover.CoverSelector;
import thumbnailer.image.Render;
import thumbnailer.image.Thumbnailer.Thumb;
import thumbnailer.index.Gate;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Containers;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Entry;
import thumbnailer.spi.Kind;
import tools.jackson.databind.json.JsonMapper;

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

    /**
     * A revival holds the stripe of the id it adopts: a patch pinning another cover waits for the save, and the stored
     * bytes keep the tag of the entry they were rendered from, so the patched album's cover is a miss, never a.jpg's bytes.
     */
    @Test
    void aPinChangeDuringARevivalNeverMakesTheRenderedBytesAHitOfTheNewCover() throws Exception {
        var m = new Memory("after");
        var albums = service(m);
        var patch = new AtomicReference<FutureTask<?>>();
        m.hook(() -> patch.set(race(() -> albums.patch("t", edit(null, "b.jpg")))));

        var revived = albums.commit(null, album("new", ROOT, ROOT + "/a"), ENTRIES, A);
        patch.get().get(10, TimeUnit.SECONDS);

        var now = m.rows.get("t");
        assertThat(now).extracting(Album::revision, Album::cover, Album::coverPinned).containsExactly(5L, "b.jpg", true);
        assertThat(albums.storedCover(now)).as("a.jpg's bytes under b.jpg's state").isEmpty();
        assertThat(revived).extracting(Album::id, Album::revision, Album::cover).containsExactly("t", 4L, "a.jpg");
        assertThat(albums.storedCover(revived)).as("a hit for the state they were rendered from").isPresent();
    }

    /** A removal of the revived id waits for the revival's save: the commit never reads its own album back gone. */
    @Test
    void aRemovalDuringARevivalWaitsForItsSave() throws Exception {
        var m = new Memory("after");
        var albums = service(m);
        var remove = new AtomicReference<FutureTask<?>>();
        m.hook(() -> remove.set(race(() -> {
            albums.remove("t");
            return null;
        })));

        assertThat(albums.commit(null, album("new", ROOT, ROOT + "/a"), ENTRIES, A).id()).isEqualTo("t");
        remove.get().get(10, TimeUnit.SECONDS);

        assertThat(m.rows).isEmpty();
        assertThat(m.removed.get("c").revision()).as("the revived album's tombstone").isEqualTo(4);
        assertThat(m.blobs).as("its cover went with it").isEmpty();
    }

    /**
     * Two albums of one content adopt a tombstone at once, one through a scan base gone meanwhile (its stripe is not
     * the tombstone's): the id goes to one of them, the other is new, and neither save overwrites the other.
     */
    @Test
    void twoAdoptersOfOneTombstoneNeverShareItsId() throws Exception {
        var m = new Memory("before");
        var albums = service(m);
        var other = new AtomicReference<FutureTask<Album>>();
        m.hook(() -> other.set(race(() -> albums.commit(null, album("b", ROOT, ROOT + "/b"), ENTRIES, A))));

        var a = albums.commit(album("x", ROOT, ROOT + "/x"), album("a", ROOT, ROOT + "/a"), ENTRIES, A);
        var b = other.get().get(10, TimeUnit.SECONDS);

        assertThat(a.id()).isEqualTo("t");
        assertThat(b.id()).isNotEqualTo("t");
        assertThat(m.rows.values()).extracting(Album::id, Album::path)
                .containsExactlyInAnyOrder(tuple("t", ROOT + "/a"), tuple(b.id(), ROOT + "/b"));
    }

    /** Re-rooting holds every stripe: a patch saving the row it read under its own cannot bring the old root back. */
    @Test
    void aPatchRacingARerootNeverRestoresTheOldRoot() throws Exception {
        var m = new Memory("find");
        m.rows.put("r", album("r", ROOT + "/old", ROOT + "/old/a"));
        var albums = service(m);
        var retain = new AtomicReference<FutureTask<?>>();
        m.hook(() -> retain.set(race(() -> {
            albums.retainRoots(List.of(ROOT));
            return null;
        })));

        albums.patch("r", edit(7, null));
        retain.get().get(10, TimeUnit.SECONDS);

        assertThat(m.rows.get("r")).extracting(Album::root, Album::rating, Album::revision).containsExactly(ROOT, 7, 2L);
    }

    @TempDir Path dir;
    private static final String ROOT = "/lib";
    private static final List<Entry> ENTRIES = List.of(new Entry(0, "a.jpg", 10, 1, 4, 3, null, 0), new Entry(1, "b.jpg", 10, 1, 4, 3, null, 0));
    /** Rendered from a.jpg, in the legacy layout of the default covers spec (the RIFF size is the length). */
    private static final Thumb A = new Thumb(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".getBytes(US_ASCII))
            .putInt(4).put("WEBP".getBytes(US_ASCII)).array(), Render.Format.WEBP, Render.LEGACY_EPOCH, false);

    private AlbumService service(Memory m) throws Exception {
        var store = SettingsStore.class.getDeclaredConstructor(Path.class, JsonMapper.class);
        store.setAccessible(true); // package-private: the store is a bean, built by Spring
        return new AlbumService(m.repo(), m.blobs(), null, null, new CoverSelector(List.of()),
                store.newInstance(dir, JsonMapper.builder().build()), e -> {}, null,
                new Catalog(port(CatalogRepository.class, (name, a) -> null), e -> {}), new Gate());
    }

    /** An album of content {@code c} and files {@code s}, showing a.jpg; a tombstone {@code t} of that content waits. */
    private static Album album(String id, String root, String path) {
        return new Album(id, root, path, Kind.DIRECTORY, null, "a.jpg", false, 1, false, 2, 20, 12, 4, 3, null, Instant.EPOCH,
                Instant.EPOCH, null, null, null, null, List.of(), 0, false, null, List.of(), List.of(), 0,
                new Album.Internal("c", "s", AlbumMeta.EMPTY, AlbumMeta.EMPTY, 0));
    }

    private static Album rooted(Album x, String root) {
        return new Album(x.id(), root, x.path(), x.kind(), x.name(), x.cover(), x.coverPinned(), x.revision(), x.coverPortrait(),
                x.imageCount(), x.bytes(), x.avgPixels(), x.maxWidth(), x.maxHeight(), x.shotDate(), x.modifiedAt(), x.indexedAt(),
                x.model(), x.studio(), x.photographer(), x.description(), x.tags(), x.rating(), x.hidden(), x.bio(), x.genres(),
                x.links(), x.copies(), x.internal());
    }

    private static AlbumPatch edit(Integer rating, String cover) {
        return new AlbumPatch(null, null, null, null, null, null, rating, cover, null, null, null, null, null);
    }

    /**
     * Runs {@code call} on another thread and returns once it has ended or waits for a lock the calling thread holds:
     * without that lock the race runs to its end in between, as it would have.
     */
    private static <T> FutureTask<T> race(Callable<T> call) {
        var task = new FutureTask<>(call);
        var thread = Thread.ofPlatform().start(task);
        var mx = ManagementFactory.getThreadMXBean();
        for (long end = System.nanoTime() + 10_000_000_000L; thread.isAlive() && System.nanoTime() < end; Thread.onSpinWait()) {
            var info = mx.getThreadInfo(thread.threadId());
            if (info != null && info.getLockOwnerId() == Thread.currentThread().threadId()) break;
        }
        return task;
    }

    /**
     * Rows, the newest tombstone per content (one of content {@code c}, id {@code t}, revision 3, rating 5) and covers;
     * the hook runs once at its point: {@code find}, or {@code before}/{@code after} the write of a save.
     */
    private static final class Memory {
        final Map<String, Album> rows = new ConcurrentHashMap<>();
        final Map<String, Tombstone> removed = new ConcurrentHashMap<>(Map.of("c", new Tombstone("t", 3, 5, "a.jpg", false, AlbumMeta.EMPTY, 0, false)));
        final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
        private final String at;
        private final AtomicReference<Runnable> hook = new AtomicReference<>();

        Memory(String at) { this.at = at; }

        void hook(Runnable h) { hook.set(h); }

        private void at(String point) {
            var h = point.equals(at) ? hook.getAndSet(null) : null;
            if (h != null) h.run();
        }

        AlbumRepository repo() {
            return port(AlbumRepository.class, (m, a) -> switch (m) {
                case "find" -> {
                    var row = Optional.ofNullable(rows.get((String) a[0]));
                    at("find"); // after the read: what the caller holds is now that row
                    yield row;
                }
                case "recall" -> Optional.ofNullable(removed.get((String) a[0]));
                case "entries" -> ENTRIES;
                case "save" -> {
                    var album = (Album) a[0];
                    at("before");
                    rows.put(album.id(), album);
                    removed.values().removeIf(t -> t.id().equals(album.id()));
                    at("after");
                    yield null;
                }
                case "delete" -> {
                    var gone = rows.remove((String) a[0]);
                    removed.put(gone.internal().contentId(), Tombstone.of(gone));
                    yield null;
                }
                case "rootStats" -> rows.values().stream().collect(Collectors.toMap(Album::root, x -> new RootStats(1, null), (x, y) -> x));
                case "stamps" -> rows.values().stream().filter(x -> Containers.within(x.path(), (String) a[0]))
                        .collect(Collectors.toMap(Album::path, x -> new Stamp(x.id(), "s")));
                case "reroot" -> {
                    rows.replaceAll((id, x) -> x.root().equals(a[0]) && Containers.within(x.path(), (String) a[1]) ? rooted(x, (String) a[1]) : x);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(m);
            });
        }

        BlobStore blobs() {
            return port(BlobStore.class, (m, a) -> switch (m) {
                case "put" -> blobs.put((String) a[0], (byte[]) a[1]);
                case "get" -> Optional.ofNullable(blobs.get((String) a[0]));
                case "remove" -> blobs.remove((String) a[0]);
                default -> throw new UnsupportedOperationException(m);
            });
        }
    }

    /** A port answering each call by method name and arguments. */
    private static <T> T port(Class<T> type, BiFunction<String, Object[], Object> answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> answer.apply(m.getName(), a)));
    }

    /** A stub of a port: every call is reported by name and answers null (only void methods are used). */
    private static <T> T stub(Class<T> port, Consumer<String> calls) {
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[]{port}, (p, m, a) -> {
            calls.accept(m.getName());
            return null;
        }));
    }
}
