package thumbnailer.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumQuery.Sort;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumRepository.Folder;
import thumbnailer.album.AlbumRepository.Save;
import thumbnailer.album.AlbumRepository.Stats;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.album.Facets;
import thumbnailer.album.Link;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.Entity;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntitySave;
import thumbnailer.catalog.Resolution;
import thumbnailer.source.Containers;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Entry;
import thumbnailer.spi.Kind;
import tools.jackson.databind.json.JsonMapper;

class JdbcAlbumRepositoryTest {

    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true).addScript("schema.sql").build();
    private final JdbcAlbumRepository repo = new JdbcAlbumRepository(new JdbcTemplate(db), JSON, Path.of("build"));
    private final Catalog catalog = new Catalog(new JdbcCatalogRepository(new JdbcTemplate(db)), e -> {});

    /** The file-system root, e.g. {@code C:/}: it already ends in '/'. */
    private static final String DRIVE = Containers.normalize(Path.of("/"));
    private static final String ROOT = DRIVE + "albums";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void close() { db.shutdown(); }

    @Test
    void entriesRoundTripPackedAndSurviveMetadataSaves() {
        var entries = List.of(new Entry(0, "IMG_0001.jpg", 4_000_000, 1_700_000_000_123L, 6000, 4000, LocalDateTime.of(2019, 5, 1, 10, 0, 7), 3),
                new Entry(1, "IMG_0002.jpg", 1, -5, 1, 1, null, 1),
                new Entry(2, "nested/Ünïcode фото.png", 0, 0, 16383, 2, LocalDateTime.of(1969, 12, 31, 23, 59, 59), 0),
                new Entry(3, "nested/Ünïcode фото😀.png", 0, 0, 1, 1, null, 0),
                new Entry(4, "nested/Ünïcode фото😁.png", 0, 0, 1, 1, null, 0)); // shares the high surrogate only
        repo.save(album("1", "A", null, List.of(), 1, null, 0), entries);
        repo.save(album("1", "A edited", null, List.of(), 1, null, 0), null);
        assertThat(repo.entries("1")).isEqualTo(entries);
        assertThat(repo.entry("1", 2)).hasValue(entries.get(2));
        assertThat(repo.entry("1", 5)).isEmpty();
        assertThat(repo.stamps(ROOT)).containsOnlyKeys(ROOT + "/A edited");
        repo.delete("1");
        assertThat(repo.entries("1")).isEmpty();
    }

    @Test
    void perImageRowsOfOlderVersionsArePackedOnStart() {
        var t = legacy();
        var upgraded = new JdbcAlbumRepository(t, JSON, Path.of("build"));
        assertThat(upgraded.entries("a")).extracting(Entry::name).containsExactly("a.jpg", "b.jpg");
        assertThat(upgraded.entry("b", 0)).hasValue(new Entry(0, "c.jpg", 1, 1, 1, 1, null, 0));
        assertThat(t.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ENTRY'", Integer.class)).isZero();
    }

    @Test
    void anInterruptedUpgradeGoesOnWhereItStopped() {
        var t = legacy();
        t.update("INSERT INTO entry VALUES ('c', 0, 'd.jpg', 1, 1, 1, 1, NULL)");
        t.execute("ALTER TABLE entries ADD CONSTRAINT stop CHECK (album_id <> 'b')"); // fails the second album
        assertThatThrownBy(() -> new JdbcAlbumRepository(t, JSON, Path.of("build"))).isInstanceOf(DataAccessException.class);
        assertThat(t.queryForList("SELECT DISTINCT album_id FROM entry ORDER BY album_id", String.class))
                .as("the first album's rows went with its packed value").containsExactly("b", "c");
        t.execute("ALTER TABLE entries DROP CONSTRAINT stop");
        var upgraded = new JdbcAlbumRepository(t, JSON, Path.of("build"));
        assertThat(t.queryForList("SELECT album_id FROM entries ORDER BY album_id", String.class)).containsExactly("a", "b", "c");
        assertThat(upgraded.entries("a")).extracting(Entry::name).containsExactly("a.jpg", "b.jpg");
        assertThat(upgraded.entries("b")).extracting(Entry::name).containsExactly("c.jpg");
        assertThat(upgraded.entries("c")).extracting(Entry::name).containsExactly("d.jpg");
        assertThat(t.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ENTRY'", Integer.class)).isZero();
    }

    /** The per-image table of older versions, rows out of order. */
    private JdbcTemplate legacy() {
        var t = new JdbcTemplate(db);
        t.execute("CREATE TABLE entry (album_id VARCHAR(36), idx INT, name VARCHAR(4096), bytes BIGINT, mtime BIGINT, width INT, height INT, shot_at TIMESTAMP)");
        t.update("INSERT INTO entry VALUES ('a', 1, 'b.jpg', 2, 3, 4, 5, NULL), ('a', 0, 'a.jpg', 1, 2, 3, 4, NULL), ('b', 0, 'c.jpg', 1, 1, 1, 1, NULL)");
        return t;
    }

    @Test
    void decodedEntriesAreSharedUntilTheirAlbumChanges() {
        var entries = entries(2);
        repo.save(album("1", "A", null, List.of(), 1, null, 0), entries);
        var decoded = repo.entries("1");
        assertThat(repo.entries("1")).as("a lookup after the save decodes nothing").isSameAs(decoded).isEqualTo(entries).isUnmodifiable();
        assertThat(repo.entry("1", 1)).containsSame(decoded.get(1));
        repo.save(album("1", "A edited", null, List.of(), 1, null, 0), null);
        assertThat(repo.entries("1")).as("a save without entries keeps them").isSameAs(decoded);

        var flagged = List.of(entries.get(0), new Entry(1, "1.jpg", 1, 1, 1, 1, null, Entry.META_KNOWN));
        repo.saveEntries("1", flagged);
        assertThat(repo.entries("1")).as("flags learned later").isEqualTo(flagged);
        repo.save(album("1", "A", null, List.of(), 1, null, 0), entries);
        assertThat(repo.entries("1")).as("a re-index").isEqualTo(entries);
        repo.delete("1");
        assertThat(repo.entries("1")).isEmpty();
    }

    @Test
    void aLoadRacingAnUncommittedWriteIsNotKeptPastTheCommit() {
        var flagged = List.of(new Entry(0, "0.jpg", 1, 1, 1, 1, null, Entry.META_KNOWN));
        repo.saveEntries("1", entries(1));
        new TransactionTemplate(new DataSourceTransactionManager(db)).executeWithoutResult(s -> {
            repo.saveEntries("1", flagged);
            assertThat(repo.entries("1")).as("the transaction reads its own write").isEqualTo(flagged);
            assertThat(CompletableFuture.supplyAsync(() -> repo.entries("1")).join()).as("another thread the committed value, kept")
                    .isEqualTo(entries(1));
        });
        var committed = repo.entries("1");
        assertThat(committed).isEqualTo(flagged);
        assertThat(repo.entries("1")).isSameAs(committed);
    }

    @Test
    void decodedEntriesStayWithinTheirBudget() {
        repo.budget = 4 * JdbcAlbumRepository.ENTRY_BYTES;
        for (var id : List.of("a", "b", "c")) repo.saveEntries(id, entries(2));
        repo.saveEntries("d", entries(5));
        var a = repo.entries("a");
        var b = repo.entries("b");
        assertThat(repo.entries("a")).isSameAs(a);
        repo.entries("c"); // 6 entries: b, used least recently, goes
        assertThat(repo.entries("a")).isSameAs(a);
        assertThat(repo.entries("b")).isNotSameAs(b).isEqualTo(b);
        assertThat(repo.entries("d")).as("over the whole budget: never kept").isNotSameAs(repo.entries("d"));
    }

    private static List<Entry> entries(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Entry(i, i + ".jpg", 1, 1, 1, 1, null, 0)).toList();
    }

    @Test
    void searchFiltersSortsAndPages() {
        resolved(album("1", "Ellena by the sea", "Ellena", List.of("wedding", "beach"), 500, LocalDate.of(2019, 1, 1), 8));
        resolved(album("2", "Ellena 2", "Ellena", List.of("wedding"), 900, LocalDate.of(2020, 1, 1), 5));
        resolved(album("3", "Mira", "Mira ELLENA-like", List.of("wedding", "outdoor"), 700, LocalDate.of(2021, 1, 1), 9));
        resolved(album("4", "Ellena old", "Ellena", List.of("wedding"), 950, LocalDate.of(2010, 1, 1), 9));
        resolved(album("5", "Ellena no tag", "Ellena", List.of(), 990, LocalDate.of(2022, 1, 1), 9));
        resolved(album("6", "Ellena undated", "Ellena", List.of("wedding"), 100, null, 1));

        // text + tag + ranges, largest first, two per page
        var q = query("ellena", List.of("Wedding"), 400L, LocalDate.of(2015, 1, 1), Sort.LARGEST, 0, 2);
        assertThat(repo.count(q)).isEqualTo(3);
        assertThat(repo.search(q)).extracting(Album::id).containsExactly("2", "3");
        assertThat(repo.search(query("ellena", List.of("Wedding"), 400L, LocalDate.of(2015, 1, 1), Sort.LARGEST, 2, 2)))
                .extracting(Album::id).containsExactly("1");

        // undated albums sort last for NEWEST and OLDEST
        assertThat(repo.search(query(null, null, null, null, Sort.NEWEST, 0, 10))).extracting(Album::id)
                .containsExactly("5", "3", "2", "1", "4", "6");
        assertThat(repo.search(query(null, null, null, null, Sort.OLDEST, 0, 10)).getLast().id()).isEqualTo("6");

        // reverse orders: NAME_DESC mirrors NAME exactly; LOWEST_RATED breaks rating ties by name ascending
        assertThat(repo.search(query(null, null, null, null, Sort.NAME_DESC, 0, 10))).extracting(Album::id)
                .containsExactlyElementsOf(repo.search(query(null, null, null, null, Sort.NAME, 0, 10)).stream().map(Album::id).toList().reversed());
        assertThat(repo.search(query(null, null, null, null, Sort.LOWEST_RATED, 0, 10))).extracting(Album::id)
                .containsExactly("6", "2", "1", "5", "4", "3");

        // a LIKE wildcard in the text is literal; a tag must match whole
        assertThat(repo.count(query("%", null, null, null, Sort.NAME, 0, 10))).isZero();
        assertThat(repo.count(query(null, List.of("wed"), null, null, Sort.NAME, 0, 10))).isZero();

        // a drive root as the root filter matches everything below it
        assertThat(repo.count(inRoot(DRIVE))).isEqualTo(6);
        assertThat(repo.count(inRoot(ROOT + "/Mira"))).isEqualTo(1);
        assertThat(repo.count(inRoot(DRIVE + "elsewhere"))).isZero();

        var facets = repo.facets(AlbumQuery.ALL);
        assertThat(facets.tags().getFirst()).isEqualTo(new thumbnailer.album.Facets.Count("wedding", 5));
        assertThat(facets.models().getFirst()).isEqualTo(new thumbnailer.album.Facets.Count("Ellena", 5));
    }

    @Test
    void roundTripsTheInternalLayers() {
        var a = album("x", "Name", "Model", List.of("tag"), 1, null, 0);
        var edited = new AlbumMeta(null, "", null, null, null, null, List.of(), List.of("Glamour"));
        repo.save(a.withEdits(edited), List.of());
        var stored = repo.find("x").orElseThrow();
        assertThat(stored.internal()).isEqualTo(new Album.Internal("cx", "sx", a.internal().extracted(), edited, stored.internal().seq()));
        assertThat(stored.internal().seq()).isPositive();
        assertThat(stored.genres()).containsExactly("Glamour");
        assertThat(stored.copies()).isEqualTo(1);
        assertThat(stored.model()).isNull();
        assertThat(stored.tags()).isEmpty();
    }

    @Test
    void foldersCountAlbumsAtOrBelowEachChildFolder() {
        repo.save(at("1", ROOT + "/b.zip", Kind.ZIP), List.of());
        repo.save(at("2", ROOT + "/a", Kind.DIRECTORY), List.of());
        repo.save(at("3", ROOT + "/C/x", Kind.DIRECTORY), List.of());
        repo.save(at("4", ROOT + "/C/y.zip", Kind.ZIP), List.of());
        repo.save(at("5", ROOT + "/B/z", Kind.DIRECTORY), List.of());
        repo.save(at("6", ROOT + "/C", Kind.DIRECTORY), List.of());
        repo.save(at("7", ROOT + "/d.7z", Kind.ARCHIVE), List.of());
        assertThat(repo.folders(ROOT)).as("an archive directly below is no folder; names ignore case").containsExactly(
                new Folder("a", ROOT + "/a", 1, false), new Folder("B", ROOT + "/B", 1, true), new Folder("C", ROOT + "/C", 3, true));
        assertThat(repo.folders(ROOT + "/C")).containsExactly(new Folder("x", ROOT + "/C/x", 1, false));
        assertThat(repo.folders(DRIVE)).containsExactly(new Folder("albums", ROOT, 7, true));
        var all = query(null, null, null, null, Sort.NAME, 0, 10);
        assertThat(repo.search(withKind(all, Kind.ARCHIVE))).as("ARCHIVE = every archive kind").extracting(Album::id).containsExactlyInAnyOrder("1", "4", "7");
        assertThat(repo.search(withKind(all, Kind.ZIP))).extracting(Album::id).containsExactlyInAnyOrder("1", "4");
        assertThat(repo.search(withKind(all, Kind.DIRECTORY))).extracting(Album::id).containsExactlyInAnyOrder("2", "3", "5", "6");
    }

    @Test
    void prefixesMatchWholeSegmentsAndRerootMovesOnlyWhatLiesBelow() {
        for (var name : List.of("A", "A/x", "AB", "AB/c")) repo.save(at(name, ROOT + "/" + name, Kind.DIRECTORY), List.of());
        assertThat(repo.stamps(ROOT + "/A")).containsOnlyKeys(ROOT + "/A", ROOT + "/A/x");
        assertThat(repo.stamps(ROOT + "/A_")).as("LIKE wildcards are literal").isEmpty();
        assertThat(repo.byRoot(ROOT)).hasSize(4);

        repo.reroot(ROOT, ROOT + "/A");
        assertThat(repo.rootStats()).containsOnlyKeys(ROOT, ROOT + "/A");
        assertThat(repo.rootStats().get(ROOT + "/A").albums()).isEqualTo(2);
        assertThat(repo.find("AB").orElseThrow().root()).isEqualTo(ROOT);
    }

    @Test
    void deleteLeavesATombstoneThatTheNextSaveOfItsIdConsumes() {
        var edited = new AlbumMeta(null, "Bob", null, null, null, null, List.of("x"), null);
        var t = at("t", ROOT + "/T", Kind.DIRECTORY).withEdits(edited).with("t", 4, 7).withCover("b.jpg", true, false).withHidden(true);
        repo.save(t, List.of());
        int seq = repo.find("t").orElseThrow().internal().seq();
        repo.delete("t");
        assertThat(repo.find("t")).isEmpty();
        assertThat(repo.recall("ct")).hasValue(new Tombstone("t", 4, 7, "b.jpg", true, edited, seq, true));
        assertThat(repo.recall("ct")).as("a peek").isPresent();
        repo.save(at("u", ROOT + "/U", Kind.DIRECTORY), List.of());
        repo.save(t.adopt(repo.recall("ct").orElseThrow()), List.of());
        assertThat(repo.recall("ct")).isEmpty();
        assertThat(repo.find("t").orElseThrow().internal().seq()).as("the revived album has its seq again").isEqualTo(seq);
    }

    @Test
    void tombstonesAreKeptPerAlbumAndPurgedWithTheirRows() {
        var catalog = new JdbcCatalogRepository(new JdbcTemplate(db));
        int list = catalog.insert(new Entity(0, EntityKind.LIST, "Best", 0, false, 0, null, null), "best");
        for (var id : List.of("d1", "d2")) repo.save(at(id, ROOT + "/" + id, Kind.DIRECTORY, "cd"), List.of());
        int seq1 = repo.find("d1").orElseThrow().internal().seq();
        catalog.setListLink(seq1, list, true);
        catalog.mark(seq1, "001.jpg", 4, List.of(list));
        assertThat(catalog.find(list).orElseThrow().albums()).isEqualTo(1);
        repo.delete("d1");
        repo.delete("d2");
        assertThat(catalog.find(list).orElseThrow().albums()).as("a visible album's removal drops its counts").isZero();
        new JdbcTemplate(db).update("UPDATE removed SET removed_at = 1000 WHERE id = 'd1'");
        assertThat(repo.recall("cd").orElseThrow().id()).as("one per album, the newest wins").isEqualTo("d2");
        assertThat(catalog.albumLinks(seq1)).as("kept for a revival").hasSize(1);

        assertThat(repo.purge(Instant.ofEpochMilli(2000))).isEqualTo(1);
        assertThat(repo.recall("cd").orElseThrow().id()).isEqualTo("d2");
        assertThat(catalog.albumLinks(seq1)).isEmpty();
        assertThat(catalog.marks(seq1)).isEmpty();
        assertThat(new JdbcTemplate(db).queryForObject("SELECT COUNT(*) FROM photo_link", Integer.class)).isZero();
    }

    @Test
    void anUpgradedLibraryNumbersItsAlbumsAndKeysTombstonesPerAlbum() {
        for (var id : List.of("a", "b", "c")) repo.save(at(id, ROOT + "/" + id, Kind.DIRECTORY), List.of());
        var t = new JdbcTemplate(db); // back to the shapes before the catalog: no seq/hidden/bio, tombstones by content
        t.execute("DROP INDEX album_seq");
        for (var column : List.of("seq", "hidden", "bio")) t.execute("ALTER TABLE album DROP COLUMN " + column);
        t.execute("DROP TABLE removed");
        t.execute("CREATE TABLE removed (content_id VARCHAR(64) PRIMARY KEY, id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL, "
                + "rating INT NOT NULL, cover VARCHAR(4096), cover_pinned BOOLEAN NOT NULL, edited_json CLOB NOT NULL, removed_at BIGINT NOT NULL)");
        t.update("INSERT INTO removed VALUES ('cx', 'x', 3, 5, NULL, FALSE, '{}', 1)");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(db);

        var upgraded = new JdbcAlbumRepository(t, JSON, Path.of("build"));
        assertThat(t.queryForList("SELECT seq FROM album", Integer.class)).as("H2 numbers existing rows").doesNotContainNull()
                .doesNotHaveDuplicates().hasSize(3);
        assertThat(upgraded.recall("cx")).hasValue(new Tombstone("x", 3, 5, null, false, AlbumMeta.EMPTY, 0, false));
        upgraded.save(at("y", ROOT + "/y", Kind.DIRECTORY, "cx"), List.of());
        upgraded.delete("y");
        assertThat(t.queryForObject("SELECT COUNT(*) FROM removed WHERE content_id = 'cx'", Integer.class)).isEqualTo(2);
        assertThat(upgraded.find("a").orElseThrow().hidden()).isFalse();
    }

    @Test
    void aTombstoneKeyDroppedByAnInterruptedUpgradeIsAddedOnStart() {
        var t = new JdbcTemplate(db);
        t.execute("ALTER TABLE removed DROP PRIMARY KEY"); // stopped between the upgrade's two statements
        new JdbcAlbumRepository(t, JSON, Path.of("build"));
        assertThat(t.queryForList("SELECT k.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k "
                + "ON k.CONSTRAINT_NAME = c.CONSTRAINT_NAME AND k.TABLE_NAME = c.TABLE_NAME WHERE c.TABLE_SCHEMA = 'PUBLIC' "
                + "AND c.TABLE_NAME = 'REMOVED' AND c.CONSTRAINT_TYPE = 'PRIMARY KEY' ORDER BY k.ORDINAL_POSITION", String.class))
                .containsExactly("CONTENT_ID", "ID");
    }

    @Test
    void hiddenAlbumsLeaveEveryViewAndCountButNotReconciliation() {
        repo.save(at("v", ROOT + "/x/v", Kind.DIRECTORY), List.of());
        repo.save(at("h", ROOT + "/x/h", Kind.DIRECTORY).withHidden(true), List.of());
        repo.save(at("o", ROOT + "/only", Kind.DIRECTORY).withHidden(true), List.of());
        assertThat(repo.search(AlbumQuery.ALL)).extracting(Album::id).containsExactly("v");
        assertThat(repo.search(hidden())).extracting(Album::id).containsExactlyInAnyOrder("h", "o");
        assertThat(repo.count(AlbumQuery.ALL)).isEqualTo(1);
        assertThat(repo.folders(ROOT)).containsExactly(new Folder("x", ROOT + "/x", 1, true));
        assertThat(repo.stats().albums()).isEqualTo(1);
        assertThat(repo.rootStats().get(ROOT).albums()).isEqualTo(1);
        assertThat(repo.stamps(ROOT)).hasSize(3);
        assertThat(repo.byRoot(ROOT + "/only")).extracting(Album::id).containsExactly("o");
    }

    @Test
    void linksFilterAndCopiesComeWithEveryAlbumRead() {
        var catalog = new JdbcCatalogRepository(new JdbcTemplate(db));
        int nikka = catalog.insert(new Entity(0, EntityKind.MODEL, "Ariell van Hoover", 0, true, 0, null, null), "ariell van hoover");
        catalog.addAlias(nikka, EntityKind.MODEL, "nikka");
        int studio = catalog.insert(new Entity(0, EntityKind.STUDIO, "EL-Studio", 0, true, 0, null, null), "el-studio");
        var links = new Resolution(List.of(new Link(nikka, EntityKind.MODEL, null, "Nikka"), new Link(studio, EntityKind.STUDIO, null, null)),
                "nikka ariell van hoover");
        repo.save(at("a", ROOT + "/a", Kind.DIRECTORY, "same"), List.of(), links, new Save(false, true, false, Map.of()));
        repo.save(at("b", ROOT + "/b", Kind.DIRECTORY, "same"), List.of());
        repo.save(at("c", ROOT + "/c", Kind.DIRECTORY), List.of());

        var a = repo.find("a").orElseThrow();
        assertThat(a.links()).containsExactly(new Link(nikka, EntityKind.MODEL, "Ariell van Hoover", "Nikka"),
                new Link(studio, EntityKind.STUDIO, "EL-Studio", null));
        assertThat(a.copies()).isEqualTo(2);
        assertThat(a.stamp()).isEqualTo("same");
        assertThat(catalog.find(nikka).orElseThrow().albums()).isEqualTo(1);
        assertThat(repo.search(query("ariell", null, null, null, Sort.NAME, 0, 10))).as("aliases are searchable")
                .extracting(Album::id).containsExactly("a");
        assertThat(repo.search(filter(List.of(nikka, studio), null, null))).extracting(Album::id).containsExactly("a");
        assertThat(repo.search(filter(null, EntityKind.MODEL, null))).extracting(Album::id).containsExactly("b", "c");
        assertThat(repo.search(filter(null, null, true))).extracting(Album::id, Album::copies)
                .containsExactly(tuple("a", 2), tuple("b", 2));
        assertThat(repo.byRoot(ROOT).stream().filter(x -> x.id().equals("c")).findFirst().orElseThrow().copies()).isEqualTo(1);
        assertThat(repo.search(filter(null, null, false))).as("one per copy group: the first indexed").extracting(Album::id).containsExactly("a", "c");
        assertThat(repo.count(filter(null, null, false))).isEqualTo(2);

        repo.save(a, null); // text links are replaced by every save (none here); favorites are not
        assertThat(repo.find("a").orElseThrow().links()).isEmpty();
    }

    @Test
    void entityFiltersResolveThroughAnyAliasAndFacetsCountTheLinks() {
        catalog.save(new EntitySave(null, EntityKind.MODEL, "Ariell van Hoover", 0, null, List.of("Nikka"), null));
        resolved(credited("1", "Nikka", "EL-Studio", "Pat", List.of("beach"), List.of("Glamour")));
        resolved(credited("2", "Ariell van Hoover", null, null, List.of("beach", "wedding"), List.of("Glamour", "Art")));
        resolved(credited("3", "Mira", "el-studio", null, List.of("wedding"), null));
        resolved(credited("4", "Nikka", null, null, List.of("beach", "hidden"), List.of("Art")).withHidden(true));

        assertThat(ids(by("NIKKA", null, null, null, null))).as("an alias finds every album of the entity").containsExactly("1", "2");
        assertThat(ids(by("ariell  van hoover", null, null, null, null))).containsExactly("1", "2");
        assertThat(ids(by("Nobody", null, null, null, null))).as("a name no entity has matches nothing").isEmpty();
        assertThat(ids(by("Ariell", null, null, null, null))).as("whole names, not substrings").isEmpty();
        assertThat(ids(by(null, "EL-STUDIO", null, null, null))).containsExactly("1", "3");
        assertThat(ids(by(null, null, "pat", null, null))).containsExactly("1");
        assertThat(ids(by(null, null, null, List.of("#Beach"), null))).containsExactly("1", "2");
        assertThat(ids(by(null, null, null, List.of("beach", "wedding"), null))).as("every tag").containsExactly("2");
        assertThat(ids(by(null, null, null, null, List.of("glamour")))).containsExactly("1", "2");
        assertThat(ids(by(null, null, null, null, List.of("Glamour", "Art")))).containsExactly("2");
        assertThat(ids(by(null, null, null, null, List.of("Nope")))).isEmpty();

        var facets = repo.facets(AlbumQuery.ALL);
        assertThat(facets.models()).as("primary names; hidden albums do not count")
                .containsExactly(new Facets.Count("Ariell van Hoover", 2), new Facets.Count("Mira", 1));
        assertThat(facets.studios()).containsExactly(new Facets.Count("EL-Studio", 2));
        assertThat(facets.photographers()).containsExactly(new Facets.Count("Pat", 1));
        assertThat(facets.tags()).containsExactly(new Facets.Count("beach", 2), new Facets.Count("wedding", 2));
        assertThat(facets.genres()).containsExactly(new Facets.Count("Glamour", 2), new Facets.Count("Art", 1));
        assertThat(repo.facets(by("nikka", null, null, null, null)).tags()).containsExactly(new Facets.Count("beach", 2), new Facets.Count("wedding", 1));
    }

    @Test
    void relocateBumpsTheRevisionAndByRootMatchesALiteralPrefix() {
        for (var path : List.of("A_%", "A_%/x", "AB_%", "Ab")) repo.save(at(path, ROOT + "/" + path, Kind.DIRECTORY), List.of());
        repo.save(at("r", ROOT + "/A_%/x/r", Kind.DIRECTORY), List.of());
        repo.relocate("Ab", DRIVE + "elsewhere/Ab", DRIVE + "elsewhere");
        assertThat(repo.find("Ab").orElseThrow()).extracting(Album::path, Album::root, Album::revision)
                .containsExactly(DRIVE + "elsewhere/Ab", DRIVE + "elsewhere", 2L);
        assertThat(repo.byRoot(ROOT + "/A_%")).as("_ and % are literal").extracting(Album::id).containsExactlyInAnyOrder("A_%", "A_%/x", "r");
    }

    @Test
    void statsCountAlbumsAndFilesAndTheFileCompactsAtClose(@TempDir Path dir) {
        var url = "jdbc:h2:split:27:" + Containers.normalize(dir) + "/albums;DB_CLOSE_DELAY=-1";
        var t = new JdbcTemplate(file(url));
        var files = new JdbcAlbumRepository(t, JSON, dir);
        assertThat(files.stats()).extracting(Stats::albums, Stats::lastIndexedAt).containsExactly(0, null);
        files.save(at("1", ROOT + "/a", Kind.DIRECTORY), List.of());
        files.save(at("2", ROOT + "/b", Kind.DIRECTORY), List.of());
        t.execute("CHECKPOINT"); // live bytes count what is written
        var stats = files.stats();
        assertThat(stats).extracting(Stats::albums, Stats::images, Stats::imageBytes, Stats::lastIndexedAt)
                .containsExactly(2, 20L, 200L, Instant.ofEpochMilli("2".hashCode()));
        assertThat(stats.liveBytes()).isPositive();
        assertThat(stats.fileBytes()).isPositive();

        files.compact();
        files.close();
        var reopened = new JdbcAlbumRepository(new JdbcTemplate(file(url)), JSON, dir);
        assertThat(reopened.find("2")).as("reopens after SHUTDOWN COMPACT").isPresent();
        reopened.close();
    }

    private static DataSource file(String url) {
        var ds = new DriverManagerDataSource(url, "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        return ds;
    }

    /** A plain album at {@code path}: 10 images, 100 bytes, indexed at epoch + hash of the id. */
    private static Album at(String id, String path, Kind kind) { return at(id, path, kind, "c" + id); }

    private static Album at(String id, String path, Kind kind, String contentId) {
        var extracted = new AlbumMeta(path.substring(path.lastIndexOf('/') + 1), null, null, null, null, null, List.of(), null);
        return new Album(id, ROOT, path, kind, null, "001.jpg", false, 1, false, 10, 100, 2_000_000, 1800, 1200, null,
                Instant.EPOCH, Instant.ofEpochMilli(id.hashCode()), null, null, null, null, List.of(), 0, false, null,
                List.of(), List.of(), 0, new Album.Internal(contentId, "s" + id, extracted, AlbumMeta.EMPTY, 0)).withEdits(AlbumMeta.EMPTY);
    }

    private static AlbumQuery withKind(AlbumQuery q, Kind kind) {
        return new AlbumQuery(q.text(), q.tags(), null, null, null, q.roots(), kind, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, q.sort(), q.offset(), q.limit());
    }

    private static AlbumQuery query(String text, List<String> tags, Long minBytes, LocalDate from, Sort sort, int offset, int limit) {
        return new AlbumQuery(text, tags, null, null, null, List.of(ROOT), null, minBytes, null, null, null, null,
                null, from, null, null, null, null, null, null, null, null, sort, offset, limit);
    }

    private static AlbumQuery inRoot(String root) {
        return new AlbumQuery(null, null, null, null, null, List.of(root), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 0, 10);
    }

    private static AlbumQuery filter(List<Integer> entities, EntityKind missing, Boolean duplicates) {
        return new AlbumQuery(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, entities, missing, null, duplicates, Sort.NAME, 0, 10);
    }

    /** Saved as {@code AlbumService} saves: its text resolved into links under the catalog lock. */
    private void resolved(Album a) {
        catalog.resolving(new AlbumMeta(a.name(), a.model(), a.studio(), a.photographer(), a.description(), null, a.tags(), a.genres()),
                0, r -> repo.save(a, List.of(), r, new Save(false, !a.hidden(), false, Map.of())));
    }

    private List<String> ids(AlbumQuery q) { return repo.search(q).stream().map(Album::id).toList(); }

    private static AlbumQuery by(String model, String studio, String photographer, List<String> tags, List<String> genres) {
        return new AlbumQuery(null, tags, model, studio, photographer, null, null, null, null, null, null, null, null, null,
                null, null, null, genres, null, null, null, null, Sort.NAME, 0, 10);
    }

    private static Album credited(String id, String model, String studio, String photographer, List<String> tags, List<String> genres) {
        return at(id, ROOT + "/" + id, Kind.DIRECTORY).withEdits(new AlbumMeta(null, model, studio, photographer, null, null, tags, genres));
    }

    private static AlbumQuery hidden() {
        return new AlbumQuery(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, true, null, Sort.NAME, 0, 10);
    }

    private static Album album(String id, String name, String model, List<String> tags, long bytes, LocalDate date, int rating) {
        var extracted = new AlbumMeta(name, model, null, null, null, date, tags, null);
        return new Album(id, ROOT, ROOT + "/" + name, Kind.DIRECTORY, null, "001.jpg", false, 1, false, 10,
                bytes, 2_000_000, 1800, 1200, date, Instant.EPOCH, Instant.EPOCH, null, null, null, null, List.of(),
                rating, false, null, List.of(), List.of(), 0, new Album.Internal("c" + id, "s" + id, extracted, AlbumMeta.EMPTY, 0))
                .withEdits(AlbumMeta.EMPTY);
    }
}
