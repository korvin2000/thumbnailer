package thumbnailer.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumRepository.Save;
import thumbnailer.album.Link;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogEvent;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntityLink;
import thumbnailer.catalog.EntitySave;
import thumbnailer.catalog.PhotoMark;
import thumbnailer.catalog.Resolution;
import thumbnailer.source.Kind;
import tools.jackson.databind.json.JsonMapper;

/** The catalog adapter and, over it, the {@link Catalog}'s rules: one H2 database with the album tables. */
class JdbcCatalogRepositoryTest {

    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true).addScript("schema.sql").build();
    private final JdbcCatalogRepository catalog = new JdbcCatalogRepository(new JdbcTemplate(db));
    private final JdbcAlbumRepository albums = new JdbcAlbumRepository(new JdbcTemplate(db), JsonMapper.builder().build(), Path.of("build"));
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final Catalog service = new Catalog(transactional(catalog, CatalogRepository.class), events::add);

    @AfterEach
    void close() { db.shutdown(); }

    @Test
    void entitiesAreKnownByEveryAliasOncePerKind() {
        int ariell = model("Ariell van Hoover", "ariell van hoover");
        catalog.addAlias(ariell, EntityKind.MODEL, "nikka");
        int studio = catalog.insert(new Entity(0, EntityKind.STUDIO, "Nikka", 3, false, 0, "{\"web\":\"x\"}", null), "nikka");
        assertThat(catalog.resolve(EntityKind.MODEL, "nikka")).hasValue(ariell);
        assertThat(catalog.resolve(EntityKind.STUDIO, "nikka")).as("unique per kind only").hasValue(studio);
        assertThatThrownBy(() -> model("Nikka", "nikka")).isInstanceOf(DuplicateKeyException.class);
        assertThat(catalog.aliases(ariell)).containsExactly("ariell van hoover", "nikka");
        assertThat(catalog.find(studio)).hasValue(new Entity(studio, EntityKind.STUDIO, "Nikka", 3, false, 0, "{\"web\":\"x\"}", null));

        catalog.store(new Entity(ariell, EntityKind.MODEL, "Ariell", 8, false, 0, null, null), List.of("ariell", "ariell van hoover"),
                List.of(new EntityLink(studio, "Nikka", "Glamour, Art")));
        assertThat(catalog.find(ariell).orElseThrow()).extracting(Entity::name, Entity::rating, Entity::auto).containsExactly("Ariell", 8, false);
        assertThat(catalog.resolve(EntityKind.MODEL, "nikka")).as("the names are replaced").isEmpty();
        assertThat(catalog.aliases(ariell)).containsExactly("ariell", "ariell van hoover");
        catalog.store(catalog.find(ariell).orElseThrow(), List.of("ariell"), null);
        assertThat(catalog.entityLinks(ariell)).as("null keeps the links").containsExactly(new EntityLink(studio, "Nikka", "Glamour, Art"));
        catalog.delete(studio);
        assertThat(catalog.find(studio)).isEmpty();
        assertThat(catalog.resolve(EntityKind.STUDIO, "nikka")).isEmpty();
        assertThat(catalog.entityLinks(ariell)).as("both sides go").isEmpty();
        assertThat(catalog.version()).isZero();
        catalog.version(1);
        assertThat(catalog.version()).isEqualTo(1);
    }

    @Test
    void pagesByNameOrCountAndByAliasPrefix() {
        int a = model("Aurora", "aurora"), b = model("bella", "bella"), c = model("Cleo", "cleo");
        catalog.addAlias(c, EntityKind.MODEL, "a_lias");
        catalog.adjust(Map.of(b, 5, c, 2));
        assertThat(catalog.list(EntityKind.MODEL, null, null, false, false, 0, 10)).extracting(Entity::id).containsExactly(a, b, c);
        assertThat(catalog.list(EntityKind.MODEL, null, null, false, true, 0, 2)).extracting(Entity::id).containsExactly(b, c);
        assertThat(catalog.list(EntityKind.MODEL, "a", null, false, false, 0, 10)).extracting(Entity::id).containsExactly(a, c);
        assertThat(catalog.list(EntityKind.MODEL, "a_", null, false, false, 0, 10)).as("LIKE wildcards are literal").extracting(Entity::id).containsExactly(c);
        assertThat(catalog.count(EntityKind.MODEL, "a", null, false)).isEqualTo(2);
        assertThat(catalog.list(EntityKind.STUDIO, null, null, false, false, 0, 10)).isEmpty();
    }

    @Test
    void countsFollowVisibleAlbumsAndRecountRebuildsThem() {
        int m = model("Mira", "mira");
        int list = catalog.insert(new Entity(0, EntityKind.LIST, "Best", 0, false, 0, null, null), "best");
        var links = new Resolution(List.of(new Link(m, EntityKind.MODEL, null, null)), "");
        albums.save(album("v", false), List.of(), links, new Save(false, true, false, Map.of()));
        albums.save(album("h", true), List.of(), links, new Save(false, false, false, Map.of()));
        int v = seq("v"), h = seq("h");
        assertThat(catalog.setListLink(v, list, true)).isTrue();
        assertThat(catalog.setListLink(v, list, true)).as("already linked").isFalse();
        assertThat(catalog.setListLink(h, list, true)).as("a hidden album is linked, not counted").isTrue();
        assertThat(catalog.find(m).orElseThrow().albums()).isEqualTo(1);
        assertThat(catalog.find(list).orElseThrow().albums()).as("hidden albums do not count; a link counts once").isEqualTo(1);
        assertThat(catalog.albumLinks(v)).containsExactly(new Link(m, EntityKind.MODEL, "Mira", null), new Link(list, EntityKind.LIST, "Best", null));

        catalog.adjust(Map.of(m, 40));
        catalog.recount();
        assertThat(catalog.find(m).orElseThrow().albums()).isEqualTo(1);
        assertThat(catalog.setListLink(v, list, false)).isTrue();
        assertThat(catalog.setListLink(v, list, false)).isFalse();
        assertThat(catalog.find(list).orElseThrow().albums()).isZero();
        albums.delete("v");
        catalog.recount();
        assertThat(catalog.find(m).orElseThrow().albums()).as("removed albums do not count").isZero();
    }

    @Test
    void aSaveCountsItsLinksOfEveryRoleByWhatBecameVisibleOrInvisible() {
        int m = model("Mira", "mira");
        int list = catalog.insert(new Entity(0, EntityKind.LIST, "Best", 0, false, 0, null, null), "best");
        var mira = new Resolution(List.of(new Link(m, EntityKind.MODEL, null, null)), "");
        albums.save(album("a", false), List.of(), mira, new Save(false, true, false, Map.of()));
        catalog.setListLink(seq("a"), list, true);
        assertThat(counts(m, list)).containsExactly(1, 1);
        albums.save(album("a", false), null, mira, new Save(true, true, false, Map.of()));
        assertThat(counts(m, list)).as("unchanged").containsExactly(1, 1);
        albums.save(album("a", true), null, mira, new Save(true, false, false, Map.of()));
        assertThat(counts(m, list)).as("hidden: the list link counts down too").containsExactly(0, 0);
        albums.save(album("a", false), null, mira, new Save(false, true, false, Map.of()));
        assertThat(counts(m, list)).as("unhidden").containsExactly(1, 1);
        albums.save(album("a", false), null, Resolution.NONE, new Save(true, true, false, Map.of()));
        assertThat(counts(m, list)).as("the text dropped the model; list links are not text").containsExactly(0, 1);
        albums.delete("a");
        assertThat(counts(m, list)).as("removed").containsExactly(0, 0);
        albums.save(album("b", false).adopt(albums.recall("ca").orElseThrow()), List.of(), mira, new Save(false, true, false, Map.of()));
        assertThat(counts(m, list)).as("revived from its tombstone, list link included").containsExactly(1, 1);
        albums.save(album("a", true), null, mira, new Save(true, false, true, Map.of()));
        assertThat(counts(m, list)).as("suspended while a scan runs").containsExactly(1, 1);
        catalog.recount();
        assertThat(counts(m, list)).as("the scan's recount").containsExactly(0, 0);
    }

    @Test
    void listMembershipCountsWhatChangedAndConcurrentUnlinksNeverGoBelowZero() throws Exception {
        int list = service.save(new EntitySave(null, EntityKind.LIST, "Best", 0, null, null, null)).entity().id();
        albums.save(album("v", false), List.of());
        albums.save(album("h", true), List.of());
        int v = seq("v"), h = seq("h");
        events.clear();
        assertThat(service.setList(List.of(v, h, v), list, true)).as("links actually added").isEqualTo(2);
        assertThat(service.setList(List.of(v), list, true)).isZero();
        assertThat(events).as("announced only when something changed").containsExactly(new CatalogEvent(List.of(EntityKind.LIST)));
        assertThat(counts(list)).as("the hidden album does not count").containsExactly(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 30; round++) {
                service.setList(List.of(v), list, true);
                var go = new CountDownLatch(1);
                var unlinks = Stream.generate(() -> pool.submit(() -> {
                    go.await();
                    return service.setList(List.of(v), list, false);
                })).limit(2).toList();
                go.countDown();
                assertThat(unlinks.get(0).get() + unlinks.get(1).get()).isEqualTo(1);
                assertThat(counts(list)).containsExactly(0);
            }
        }
    }

    @Test
    void anEntitySaveChecksFirstWritesInOneTransactionAndIsAlwaysAnnounced() {
        int studio = service.save(new EntitySave(null, EntityKind.STUDIO, "Nikka", 0, null, null, null)).entity().id();
        var mira = service.save(new EntitySave(null, EntityKind.MODEL, "Mira", 3, null, List.of("Mi"), null)).entity();
        assertThatThrownBy(() -> service.save(new EntitySave(null, EntityKind.MODEL, "Other", 0, null, List.of(" MI "), null)))
                .hasMessage("\"MI\" is Mira; merge instead?");
        assertThatThrownBy(() -> service.save(new EntitySave(mira.id(), EntityKind.MODEL, "Mira B", 9, null, List.of("x"),
                List.of(new EntityLink(studio, null, null), new EntityLink(studio, null, null))))).isInstanceOf(DuplicateKeyException.class);
        assertThat(catalog.find(mira.id()).orElseThrow()).as("a failed save changes nothing").extracting(Entity::name, Entity::rating)
                .containsExactly("Mira", 3);
        assertThat(catalog.aliases(mira.id())).containsExactly("mi", "mira");
        assertThat(catalog.entityLinks(mira.id())).isEmpty();
        service.save(new EntitySave(mira.id(), EntityKind.MODEL, "Mira", 5, null, null, null));
        assertThat(events).as("every successful save, a rating-only one too").containsExactly(new CatalogEvent(List.of(EntityKind.STUDIO)),
                new CatalogEvent(List.of(EntityKind.MODEL)), new CatalogEvent(List.of(EntityKind.MODEL)));
    }

    @Test
    void photoMarksAreSparseAndFollowRenames() {
        int list = catalog.insert(new Entity(0, EntityKind.LIST, "Best", 0, false, 0, null, null), "best");
        albums.save(album("a", false), List.of());
        int s = seq("a");
        catalog.mark(s, "b.jpg", 7, null);
        catalog.mark(s, "a.jpg", null, List.of(list, list));
        assertThat(catalog.marks(s)).containsExactly(Map.entry("a.jpg", new PhotoMark(0, List.of(list))),
                Map.entry("b.jpg", new PhotoMark(7, List.of())));
        assertThat(catalog.find(list).orElseThrow().photos()).isEqualTo(1);
        catalog.mark(s, "b.jpg", null, List.of(list));
        assertThat(catalog.marks(s).get("b.jpg")).as("null keeps the rating").isEqualTo(new PhotoMark(7, List.of(list)));

        albums.save(album("a", false), null, Resolution.NONE, new Save(true, true, false, Map.of("a.jpg", "a.jpg.cry")));
        assertThat(catalog.marks(s)).containsOnlyKeys("a.jpg.cry", "b.jpg");
        catalog.mark(s, "a.jpg.cry", 0, List.of());
        assertThat(catalog.marks(s)).as("nothing left: the row goes").containsOnlyKeys("b.jpg");
        albums.save(album("a", true), null);
        assertThat(catalog.find(list).orElseThrow().photos()).as("photos of hidden albums do not count").isZero();
    }

    @Test
    void savesSwappingTheirEntitiesAtOnceNeverDeadlock() throws Exception {
        int a = model("A", "a"), b = model("B", "b");
        var tx = transactional(albums, AlbumRepository.class);
        var toA = new Resolution(List.of(new Link(a, EntityKind.MODEL, null, null)), "");
        var toB = new Resolution(List.of(new Link(b, EntityKind.MODEL, null, null)), "");
        tx.save(album("x", false), List.of(), toA, new Save(false, true, false, Map.of()));
        tx.save(album("y", false), List.of(), toB, new Save(false, true, false, Map.of()));
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 100; round++) {
                var go = new CyclicBarrier(2);
                boolean even = round % 2 == 0; // x: A → B while y: B → A, then back
                var saves = Stream.of("x", "y").map(id -> pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    tx.save(album(id, false), null, id.equals("x") == even ? toB : toA, new Save(true, true, false, Map.of()));
                    return id;
                })).toList();
                for (var s : saves) s.get(30, TimeUnit.SECONDS);
            }
        }
        assertThat(counts(a, b)).containsExactly(1, 1);
    }

    @Test
    void photoMarksFollowSwapsAndChainsInTheAlbumSaveAndAFailedRenameUndoesIt() {
        int m = model("Mira", "mira");
        var mira = new Resolution(List.of(new Link(m, EntityKind.MODEL, null, null)), "");
        var tx = transactional(albums, AlbumRepository.class);
        tx.save(album("a", false), List.of(), mira, new Save(false, true, false, Map.of()));
        int s = seq("a");
        for (int i : new int[]{1, 2, 3, 4, 6, 7}) catalog.mark(s, i + ".jpg", i, null);
        tx.save(album("a", false), null, mira, new Save(true, true, false,
                Map.of("1.jpg", "2.jpg", "2.jpg", "1.jpg", "3.jpg", "4.jpg", "4.jpg", "5.jpg", "7.jpg", "6.jpg")));
        assertThat(catalog.marks(s)).as("swapped, chained; 6.jpg is now the photo that was 7.jpg").containsExactlyInAnyOrderEntriesOf(Map.of(
                "1.jpg", new PhotoMark(2, List.of()), "2.jpg", new PhotoMark(1, List.of()), "4.jpg", new PhotoMark(3, List.of()),
                "5.jpg", new PhotoMark(4, List.of()), "6.jpg", new PhotoMark(7, List.of())));

        assertThatThrownBy(() -> tx.save(album("a", true), null, Resolution.NONE, new Save(true, false, false,
                Map.of("1.jpg", "x.jpg", "2.jpg", "x.jpg")))).isInstanceOf(DuplicateKeyException.class);
        assertThat(albums.find("a").orElseThrow().hidden()).as("the album row rolled back with the marks").isFalse();
        assertThat(catalog.marks(s)).containsOnlyKeys("1.jpg", "2.jpg", "4.jpg", "5.jpg", "6.jpg");
        assertThat(catalog.albumLinks(s)).as("and with its links and counts").extracting(Link::entity).containsExactly(m);
        assertThat(counts(m)).containsExactly(1);
    }

    private int model(String name, String norm) {
        return catalog.insert(new Entity(0, EntityKind.MODEL, name, 0, true, 0, null, null), norm);
    }

    private int seq(String id) { return albums.find(id).orElseThrow().internal().seq(); }

    private List<Integer> counts(int... ids) { return IntStream.of(ids).mapToObj(id -> catalog.find(id).orElseThrow().albums()).toList(); }

    /** The adapter as Spring wires it: its {@code @Transactional} methods run in transactions. */
    private <T> T transactional(Object target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db), new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }

    private static Album album(String id, boolean hidden) {
        var extracted = new AlbumMeta(id, null, null, null, null, null, List.of(), null);
        return new Album(id, "C:/r", "C:/r/" + id, Kind.DIRECTORY, null, "a.jpg", false, 1, false, 2, 100, 1, 1, 1, null,
                Instant.EPOCH, Instant.EPOCH, null, null, null, null, List.of(), 0, hidden, null, List.of(), List.of(), 0,
                new Album.Internal("c" + id, "s" + id, extracted, AlbumMeta.EMPTY, 0)).withEdits(AlbumMeta.EMPTY);
    }
}
