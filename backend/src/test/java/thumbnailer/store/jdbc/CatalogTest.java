package thumbnailer.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumRepository.Save;
import thumbnailer.album.Link;
import thumbnailer.catalog.Card;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.CatalogEvent;
import thumbnailer.catalog.CatalogRepository;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntityLink;
import thumbnailer.catalog.EntitySave;
import thumbnailer.catalog.PhotoMark;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Kind;
import tools.jackson.databind.json.JsonMapper;

/** The catalog's rules (catalog_plan.md §2.5) over the real adapters: resolution, curation, derived facts, backfill. */
class CatalogTest {

    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true).addScript("schema.sql").build();
    private final JdbcCatalogRepository repo = new JdbcCatalogRepository(new JdbcTemplate(db));
    private final JdbcAlbumRepository albums = new JdbcAlbumRepository(new JdbcTemplate(db), JsonMapper.builder().build(), Path.of("build"));
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final Catalog catalog = new Catalog(transactional(), events::add);

    @AfterEach
    void close() { db.shutdown(); }

    @Test
    void textResolvesWholeFirstThenSplitOneLinkPerEntityWithTheNameAsWritten() {
        int ariell = entity(EntityKind.MODEL, "Ariell van Hoover", "Nikka");
        int smith = entity(EntityKind.MODEL, "Smith, John");
        events.clear();
        save("a", meta("Nikka; Ariell van Hoover", null, null, null), AlbumMeta.EMPTY);
        save("b", meta("Smith, John", null, null, null), AlbumMeta.EMPTY);
        save("c", meta("Ellena, Aurora", "EL-Studio", List.of("Wedding Day"), List.of("Glamour, Art")), AlbumMeta.EMPTY);
        save("d", meta("nikka", null, null, null), AlbumMeta.EMPTY);

        assertThat(links("a")).as("two names of one entity are one link; the alias is the name that is not the primary one")
                .containsExactly(new Link(ariell, EntityKind.MODEL, "Ariell van Hoover", "Nikka"));
        assertThat(links("b")).as("a whole text the kind knows is not split").containsExactly(new Link(smith, EntityKind.MODEL, "Smith, John", null));
        assertThat(links("c")).extracting(Link::role, Link::name, Link::alias).containsExactly(tuple(EntityKind.MODEL, "Aurora", null),
                tuple(EntityKind.MODEL, "Ellena", null), tuple(EntityKind.STUDIO, "EL-Studio", null), tuple(EntityKind.GENRE, "Art", null),
                tuple(EntityKind.GENRE, "Glamour", null), tuple(EntityKind.TAG, "wedding-day", null));
        assertThat(catalog.entities(EntityKind.MODEL, "ell", null, false, false, 0, 10)).extracting(Entity::name, Entity::auto, Entity::albums)
                .containsExactly(tuple("Ellena", true, 1));
        assertThat(catalog.entities(EntityKind.TAG, null, null, false, false, 0, 10)).extracting(Entity::name, Entity::auto)
                .as("tags are named by their norm and never auto").containsExactly(tuple("wedding-day", false));
        assertThat(events).as("new entities are announced after the save").containsExactly(
                new CatalogEvent(List.of(EntityKind.MODEL, EntityKind.STUDIO, EntityKind.GENRE, EntityKind.TAG)));
        assertThat(repo.find(ariell).orElseThrow().albums()).isEqualTo(2);
        assertThat(search("hoover")).as("every alias of a linked entity is searchable").containsExactly("a", "d");
    }

    @Test
    void anExplicitEmptyTextStopsTheExtractedNameFromLinkingAgain() {
        save("a", meta("Mira", null, null, null), AlbumMeta.EMPTY);
        assertThat(links("a")).extracting(Link::name).containsExactly("Mira");
        var cleared = new AlbumMeta(null, "", null, null, null, null, null, null);
        save("a", meta("Mira", null, null, null), cleared);
        assertThat(links("a")).isEmpty();
        save("a", meta("Mira, Zoe", null, null, null), cleared); // a re-index finds the names again
        assertThat(links("a")).as("edits win").isEmpty();
        assertThat(repo.find(repo.resolve(EntityKind.MODEL, "mira").orElseThrow()).orElseThrow().albums()).isZero();
    }

    @Test
    void twoAlbumsNamingOneNewEntityAtOnceCreateItOnce() throws Exception {
        var both = new CyclicBarrier(2);
        var first = ThreadLocal.withInitial(() -> true);
        var tx = transactional();
        var racing = (CatalogRepository) Proxy.newProxyInstance(CatalogRepository.class.getClassLoader(), new Class<?>[]{CatalogRepository.class},
                (p, m, args) -> {
                    if (m.getName().equals("named") && first.get()) { // both miss the name before either creates it
                        first.set(false);
                        both.await(10, TimeUnit.SECONDS);
                    }
                    try {
                        return m.invoke(tx, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        var raced = new Catalog(racing, events::add);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var saves = List.of("a", "b").stream().map(id -> pool.submit(() -> {
                var album = album(id, meta("Ellena", null, null, null), AlbumMeta.EMPTY, false);
                raced.resolving(effective(album), 0, r -> albums.save(album, List.of(), r, new Save(false, true, false, Map.of())));
                return id;
            })).toList();
            for (var s : saves) s.get(20, TimeUnit.SECONDS);
        }
        int ellena = repo.resolve(EntityKind.MODEL, "ellena").orElseThrow();
        assertThat(repo.count(EntityKind.MODEL, null, null, false)).isEqualTo(1);
        assertThat(links("a")).isEqualTo(links("b")).extracting(Link::entity).containsExactly(ellena);
        assertThat(repo.find(ellena).orElseThrow().albums()).isEqualTo(2);
        assertThat(events).as("created, and announced, once").containsExactly(new CatalogEvent(List.of(EntityKind.MODEL)));
    }

    @Test
    void aMergeMovesLinksNamesAndCuratedLinksOverAndKeepsTheMergedNameOnTheLinks() {
        int studio = entity(EntityKind.STUDIO, "EL-Studio");
        save("x", meta("Nikka", "EL-Studio", null, null), AlbumMeta.EMPTY);
        save("y", meta("Nikka, Ariell van Hoover", null, null, null), AlbumMeta.EMPTY);
        save("z", meta("Ariell van Hoover", null, null, null), AlbumMeta.EMPTY);
        int nikka = repo.resolve(EntityKind.MODEL, "nikka").orElseThrow(), ariell = repo.resolve(EntityKind.MODEL, "ariell van hoover").orElseThrow();
        catalog.save(new EntitySave(nikka, EntityKind.MODEL, "Nikka", 3, "{\"hair\":\"red\",\"born\":\"1991\"}", null,
                List.of(new EntityLink(studio, "Nikka", null), new EntityLink(ariell, null, null))));
        catalog.save(new EntitySave(ariell, EntityKind.MODEL, "Ariell van Hoover", 7, "{\"born\":\"1990\",\"hair\":\"\"}", null,
                List.of(new EntityLink(studio, null, "Glamour"))));
        catalog.confirm(List.of(ariell));
        events.clear();

        var merged = catalog.merge(List.of(nikka, ariell), ariell);
        assertThat(merged).extracting(Entity::rating, Entity::auto, Entity::albums, Entity::profile)
                .containsExactly(7, false, 3, "{\"born\":\"1990\",\"hair\":\"red\"}");
        assertThat(repo.find(nikka)).isEmpty();
        assertThat(repo.resolve(EntityKind.MODEL, "nikka")).hasValue(ariell);
        assertThat(catalog.get(ariell).aliases()).containsExactly("nikka");
        assertThat(links("x")).as("the merged name stays on the link").contains(new Link(ariell, EntityKind.MODEL, "Ariell van Hoover", "Nikka"));
        assertThat(links("y")).as("a colliding link goes").extracting(Link::entity).containsExactly(ariell);
        assertThat(repo.entityLinks(ariell)).as("the colliding and the self link go").containsExactly(new EntityLink(studio, null, "Glamour"));
        assertThat(search("nikka")).as("search text recomputed").containsExactly("x", "y", "z");
        assertThat(events).containsExactly(new CatalogEvent(List.of(EntityKind.MODEL)));
        assertThatThrownBy(() -> catalog.merge(List.of(studio), ariell)).hasMessage("Only entities of one kind merge: EL-Studio is no model");
        assertThatThrownBy(() -> catalog.merge(List.of(ariell), ariell)).hasMessage("Nothing to merge into Ariell van Hoover");
    }

    @Test
    void aRenameKeepsTheOldNameOnTheLinksAndEveryNameChangeRecomputesSearch() {
        save("x", meta("Nikka", null, null, null), AlbumMeta.EMPTY);
        int id = repo.resolve(EntityKind.MODEL, "nikka").orElseThrow();
        catalog.save(new EntitySave(id, EntityKind.MODEL, "Ariell", 0, null, null, null));
        assertThat(links("x")).containsExactly(new Link(id, EntityKind.MODEL, "Ariell", "Nikka"));
        assertThat(search("ariell")).containsExactly("x");
        catalog.save(new EntitySave(id, EntityKind.MODEL, "Ariell", 0, null, List.of("Nikka", "Mimi"), null));
        assertThat(search("mimi")).as("an alias added").containsExactly("x");
        catalog.save(new EntitySave(id, EntityKind.MODEL, "Ariell", 0, null, List.of("Nikka"), null));
        assertThat(search("mimi")).as("an alias dropped").isEmpty();
        catalog.save(new EntitySave(id, EntityKind.MODEL, "nikka", 0, null, null, null));
        assertThat(links("x")).as("the alias is the primary name again").containsExactly(new Link(id, EntityKind.MODEL, "nikka", null));
        assertThat(catalog.get(id).aliases()).containsExactly("ariell");
    }

    @Test
    void editsForLinkUnlinkAndCreditComeFromTheEffectiveText() {
        int ariell = entity(EntityKind.MODEL, "Ariell", "Nikka");
        int mira = entity(EntityKind.MODEL, "Mira");
        int smith = entity(EntityKind.MODEL, "Smith, John");
        int studio = entity(EntityKind.STUDIO, "EL-Studio");
        int beauty = entity(EntityKind.TAG, "beauty", "beautiful");
        int glamour = entity(EntityKind.GENRE, "Glamour");
        int list = entity(EntityKind.LIST, "Best");
        var a = album("a", meta("Nikka, Mira", "EL-Studio", List.of("beautiful", "x"), List.of("Glamour", "Art")), AlbumMeta.EMPTY, false);
        var b = album("b", meta("Smith, John", null, null, null), AlbumMeta.EMPTY, false);
        save(a);
        save(b);

        assertThat(catalog.linkPatch(a, mira, true)).as("already says so").isNull();
        assertThat(catalog.linkPatch(b, mira, true).model()).as("names with a separator are quoted").isEqualTo("\"Smith, John\", Mira");
        assertThat(catalog.linkPatch(a, ariell, false).model()).as("the alias resolving to it goes").isEqualTo("Mira");
        assertThat(catalog.linkPatch(b, smith, false).model()).as("the explicit empty").isEmpty();
        assertThat(catalog.linkPatch(a, beauty, false).removeTags()).containsExactly("beautiful");
        assertThat(catalog.linkPatch(b, beauty, true).addTags()).containsExactly("beauty");
        assertThat(catalog.linkPatch(a, glamour, false).genres()).containsExactly("Art");
        assertThat(catalog.linkPatch(b, studio, true).studio()).isEqualTo("EL-Studio");
        assertThatThrownBy(() -> catalog.linkPatch(a, list, true)).hasMessage("Best is a favorites list");

        assertThat(catalog.creditAs(ariell, studio, " Nikki ", "Glamour")).containsExactly("a");
        assertThat(catalog.get(ariell)).extracting(Card::aliases, Card::links).containsExactly(List.of("nikka", "nikki"),
                List.of(new Card.Relation(repo.find(studio).orElseThrow(), "Nikki", "Glamour")));
        assertThat(search("nikki")).containsExactly("a");
        assertThat(catalog.creditPatch(a, ariell, studio, "Nikki")).extracting(AlbumPatch::model).isEqualTo("Nikki, Mira");
        assertThat(catalog.creditPatch(b, ariell, studio, "Nikki")).isNull();
        var moved = album("a", meta("Nikka, Mira", "Other", null, null), AlbumMeta.EMPTY, false);
        assertThat(catalog.creditPatch(moved, ariell, studio, "Nikki")).as("its studio changed since creditAs listed it").isNull();
        assertThatThrownBy(() -> catalog.creditAs(ariell, studio, "Mira", null)).hasMessage("\"Mira\" is Mira; merge instead?");
        catalog.creditAs(ariell, studio, "Nikki", null);
        assertThat(repo.entityLinks(ariell)).as("roles null = unchanged").containsExactly(new EntityLink(studio, "Nikki", "Glamour"));
        assertThat(catalog.albumIds(mira)).containsExactly("a");
    }

    @Test
    void aListEditNeverResolvesWholeToAnotherEntity() {
        int alice = entity(EntityKind.MODEL, "Alice"), bob = entity(EntityKind.MODEL, "Bob"), carol = entity(EntityKind.MODEL, "Carol");
        entity(EntityKind.MODEL, "Alice, Bob");
        var a = album("a", meta("Alice", null, null, null), AlbumMeta.EMPTY, false);
        save(edited(a, catalog.linkPatch(a, bob, true)));
        assertThat(links("a")).extracting(Link::entity).as("not the entity named \"Alice, Bob\"").containsExactlyInAnyOrder(alice, bob);
        var b = album("b", meta("Alice, Bob, Carol", null, null, null), AlbumMeta.EMPTY, false);
        save(edited(b, catalog.linkPatch(b, carol, false)));
        assertThat(links("b")).extracting(Link::entity).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void aDeleteLeavesAnEntityThatATextNamedAfterItsRewriteUntilForced() {
        save("a", meta("Mira", null, null, null), AlbumMeta.EMPTY);
        int mira = repo.resolve(EntityKind.MODEL, "mira").orElseThrow(), list = entity(EntityKind.LIST, "Best");
        save(edited(albums.find("a").orElseThrow(), catalog.linkPatch(albums.find("a").orElseThrow(), mira, false))); // the rewrite
        save("b", meta("Mira", null, null, null), AlbumMeta.EMPTY); // a save naming her meanwhile
        events.clear();
        assertThat(catalog.delete(List.of(mira, list), false)).as("still linked: rewritten again first").containsExactly(mira);
        assertThat(links("b")).extracting(Link::entity).containsExactly(mira);
        assertThat(repo.find(list)).isEmpty();
        assertThat(events).containsExactly(new CatalogEvent(List.of(EntityKind.LIST)));
        save(edited(albums.find("b").orElseThrow(), catalog.linkPatch(albums.find("b").orElseThrow(), mira, false)));
        assertThat(catalog.delete(List.of(mira), false)).isEmpty();
        assertThat(repo.find(mira)).isEmpty();

        save("c", meta("Zoe", null, null, null), AlbumMeta.EMPTY);
        int zoe = repo.resolve(EntityKind.MODEL, "zoe").orElseThrow();
        assertThat(catalog.delete(List.of(zoe), true)).as("forced: the links go too").isEmpty();
        assertThat(links("c")).isEmpty();
    }

    @Test
    void aPhotoMarkWaitsForTheCatalogWriteLockAndKeepsTheHalfItDoesNotSet() throws Exception {
        save("a", meta(null, null, null, null), AlbumMeta.EMPTY);
        int seq = albums.find("a").orElseThrow().internal().seq(), list = entity(EntityKind.LIST, "Best");
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            pool.submit(() -> catalog.reading(() -> { // an album save in flight
                reading.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }));
            try {
                reading.await(10, TimeUnit.SECONDS);
                var rated = pool.submit(() -> catalog.mark(seq, "x.jpg", 7, null));
                assertThatThrownBy(() -> rated.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                rated.get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
        catalog.mark(seq, "x.jpg", null, List.of(list));
        catalog.mark(seq, "x.jpg", 8, null);
        assertThat(catalog.marks(seq)).containsExactly(Map.entry("x.jpg", new PhotoMark(8, List.of(list))));
    }

    @Test
    void entitiesUnderAnotherAndTheDerivedCardComeFromVisibleAlbums() {
        int m1 = entity(EntityKind.MODEL, "Mia", "Mimi");
        save("a", meta("Mia", "S1", List.of("t"), List.of("Glamour")), new AlbumMeta(null, null, null, "Pat", null, null, null, null));
        save("b", meta("Mimi", "S1", null, null), AlbumMeta.EMPTY);
        save("c", meta("Mia", "S2", null, null), AlbumMeta.EMPTY);
        save("d", meta("Zoe", "S1", null, null), AlbumMeta.EMPTY);
        save(album("h", meta("Mia", "S3", null, null), AlbumMeta.EMPTY, true));
        int s1 = repo.resolve(EntityKind.STUDIO, "s1").orElseThrow(), genre = repo.resolve(EntityKind.GENRE, "glamour").orElseThrow();

        assertThat(catalog.entities(EntityKind.STUDIO, null, m1, false, true, 0, 10)).as("hidden albums do not count")
                .extracting(Entity::name, Entity::albums).containsExactly(tuple("S1", 2), tuple("S2", 1));
        assertThat(catalog.count(EntityKind.STUDIO, null, m1, false)).isEqualTo(2);
        assertThat(catalog.entities(EntityKind.MODEL, "z", s1, false, false, 0, 10)).extracting(Entity::name).containsExactly("Zoe");
        assertThat(catalog.entities(EntityKind.MODEL, null, genre, false, true, 0, 10)).extracting(Entity::name, Entity::albums).containsExactly(tuple("Mia", 1));

        var mia = catalog.get(m1).derived();
        assertThat(mia.albums()).isEqualTo(3);
        assertThat(mia.studios()).extracting(c -> c.entity().name(), Card.Credit::aliases, Card.Credit::genres, Card.Credit::albums)
                .containsExactly(tuple("S1", List.of("Mimi"), List.of("Glamour"), 2), tuple("S2", List.of(), List.of(), 1));
        assertThat(mia.photographers()).extracting(Entity::name).containsExactly("Pat");
        assertThat(mia.genres()).extracting(Entity::name).containsExactly("Glamour");
        assertThat(mia.tags()).containsExactly("t");
        assertThat(mia.models()).isNull();
        var studio = catalog.get(s1).derived();
        assertThat(studio.models()).extracting(Entity::name, Entity::albums).containsExactly(tuple("Mia", 2), tuple("Zoe", 1));
        assertThat(studio.studios()).isNull();
    }

    @Test
    void unusedEntitiesAreAutoOnesNoAlbumLinksToAHiddenAlbumsLinkIncluded() {
        save("a", meta("Ann", null, null, null), AlbumMeta.EMPTY);
        save(album("h", meta("Hid", null, null, null), AlbumMeta.EMPTY, true));
        save("x", meta("Gone, Left", null, null, null), AlbumMeta.EMPTY);
        save("x", meta("Ann", null, null, null), AlbumMeta.EMPTY); // Gone and Left stay, linked nowhere
        entity(EntityKind.MODEL, "Curated");
        assertThat(catalog.entities(EntityKind.MODEL, null, null, true, false, 0, 10)).as("not Hid: its album is only hidden; not Curated")
                .extracting(Entity::name, Entity::auto, Entity::albums).containsExactly(tuple("Gone", true, 0), tuple("Left", true, 0));
        assertThat(catalog.entities(EntityKind.MODEL, "l", null, true, true, 0, 10)).extracting(Entity::name).containsExactly("Left");
        assertThat(catalog.entities(EntityKind.MODEL, null, null, true, false, 1, 10)).extracting(Entity::name).containsExactly("Left");
        assertThat(catalog.count(EntityKind.MODEL, null, null, true)).isEqualTo(2);
        assertThat(catalog.count(EntityKind.MODEL, null, null, false)).isEqualTo(5);
    }

    @Test
    void theBackfillResolvesEveryAlbumOnceWithoutSavingIt() {
        int ariell = entity(EntityKind.MODEL, "Ariell", "Nikka");
        albums.save(album("a", meta("Nikka", "EL-Studio", List.of("beach"), List.of("Glamour")),
                new AlbumMeta(null, null, null, null, null, null, null, List.of("Art")), false), List.of()); // no resolution: an old database
        albums.save(album("h", meta("Ariell", null, null, null), AlbumMeta.EMPTY, true), List.of());
        long revision = albums.find("a").orElseThrow().revision();
        assertThat(repo.version()).isZero();

        catalog.backfill();
        assertThat(links("a")).extracting(Link::name, Link::alias).containsExactly(tuple("Ariell", "Nikka"), tuple("EL-Studio", null),
                tuple("Art", null), tuple("beach", null));
        assertThat(links("h")).extracting(Link::entity).containsExactly(ariell);
        assertThat(repo.find(ariell).orElseThrow().albums()).as("recounted: hidden albums do not count").isEqualTo(1);
        assertThat(search("nikka")).containsExactly("a");
        assertThat(albums.find("a").orElseThrow().revision()).as("no save").isEqualTo(revision);
        assertThat(repo.version()).isEqualTo(1);
        assertThat(events).as("the recount announces").contains(new CatalogEvent(List.of(EntityKind.values())));

        albums.save(album("n", meta("New", null, null, null), AlbumMeta.EMPTY, false), List.of());
        catalog.backfill();
        assertThat(links("n")).as("once").isEmpty();
    }

    /** Saved as {@code AlbumService} saves: the effective text resolved under the catalog lock, the save counting. */
    private void save(String id, AlbumMeta extracted, AlbumMeta edited) { save(album(id, extracted, edited, false)); }

    private void save(Album a) {
        boolean before = albums.find(a.id()).filter(x -> !x.hidden()).isPresent();
        catalog.resolving(effective(a), a.internal().seq(), r -> albums.save(a, List.of(), r, new Save(before, !a.hidden(), false, Map.of())));
    }

    private int entity(EntityKind kind, String name, String... aliases) {
        return catalog.save(new EntitySave(null, kind, name, 0, null, List.of(aliases), null)).entity().id();
    }

    private List<Link> links(String id) { return albums.find(id).orElseThrow().links(); }

    private List<String> search(String text) {
        return albums.search(new AlbumQuery(text, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, AlbumQuery.Sort.NAME, 0, 100)).stream().map(Album::id).toList();
    }

    private static AlbumMeta meta(String model, String studio, List<String> tags, List<String> genres) {
        return new AlbumMeta(null, model, studio, null, null, null, tags, genres);
    }

    /** The album with a model text edit applied, as {@code AlbumService.patch} applies it. */
    private static Album edited(Album a, AlbumPatch p) {
        return a.withEdits(new AlbumMeta(null, p.model(), null, null, null, null, null, null).over(a.internal().edited()));
    }

    private static AlbumMeta effective(Album a) {
        return new AlbumMeta(a.name(), a.model(), a.studio(), a.photographer(), a.description(), null, a.tags(), a.genres());
    }

    private static Album album(String id, AlbumMeta extracted, AlbumMeta edited, boolean hidden) {
        var named = new AlbumMeta(id, extracted.model(), extracted.studio(), extracted.photographer(), null, null, extracted.tags(), extracted.genres());
        return new Album(id, "C:/r", "C:/r/" + id, Kind.DIRECTORY, null, "a.jpg", false, 1, false, 2, 100, 1, 1, 1, null,
                Instant.EPOCH, Instant.EPOCH, null, null, null, null, List.of(), 0, hidden, null, List.of(), List.of(), 0,
                new Album.Internal("c" + id, "s" + id, named, AlbumMeta.EMPTY, 0)).withEdits(edited);
    }

    /** The adapter as Spring wires it: its {@code @Transactional} methods run in transactions. */
    private CatalogRepository transactional() {
        var proxy = new ProxyFactory(repo);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db), new AnnotationTransactionAttributeSource()));
        return (CatalogRepository) proxy.getProxy();
    }
}
