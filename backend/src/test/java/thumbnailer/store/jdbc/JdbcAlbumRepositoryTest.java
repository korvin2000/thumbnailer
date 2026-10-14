package thumbnailer.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumQuery.Sort;
import thumbnailer.album.AlbumRepository.Folder;
import thumbnailer.album.AlbumRepository.Stats;
import thumbnailer.album.AlbumRepository.Tombstone;
import thumbnailer.album.Entry;
import thumbnailer.source.Containers;
import thumbnailer.source.Kind;
import tools.jackson.databind.json.JsonMapper;

class JdbcAlbumRepositoryTest {

    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true).addScript("schema.sql").build();
    private final JdbcAlbumRepository repo = new JdbcAlbumRepository(new JdbcTemplate(db), JSON, Path.of("build"));

    /** The file-system root, e.g. {@code C:/}: it already ends in '/'. */
    private static final String DRIVE = Containers.normalize(Path.of("/"));
    private static final String ROOT = DRIVE + "albums";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AfterEach
    void close() { db.shutdown(); }

    @Test
    void entriesRoundTripPackedAndSurviveMetadataSaves() {
        var entries = List.of(new Entry(0, "IMG_0001.jpg", 4_000_000, 1_700_000_000_123L, 6000, 4000, LocalDateTime.of(2019, 5, 1, 10, 0, 7)),
                new Entry(1, "IMG_0002.jpg", 1, -5, 1, 1, null),
                new Entry(2, "nested/Ünïcode фото.png", 0, 0, 16383, 2, LocalDateTime.of(1969, 12, 31, 23, 59, 59)),
                new Entry(3, "nested/Ünïcode фото😀.png", 0, 0, 1, 1, null),
                new Entry(4, "nested/Ünïcode фото😁.png", 0, 0, 1, 1, null)); // shares the high surrogate only
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
        var t = new JdbcTemplate(db);
        t.execute("CREATE TABLE entry (album_id VARCHAR(36), idx INT, name VARCHAR(4096), bytes BIGINT, mtime BIGINT, width INT, height INT, shot_at TIMESTAMP)");
        t.update("INSERT INTO entry VALUES ('a', 1, 'b.jpg', 2, 3, 4, 5, NULL), ('a', 0, 'a.jpg', 1, 2, 3, 4, NULL), ('b', 0, 'c.jpg', 1, 1, 1, 1, NULL)");
        var upgraded = new JdbcAlbumRepository(t, JSON, Path.of("build"));
        assertThat(upgraded.entries("a")).extracting(Entry::name).containsExactly("a.jpg", "b.jpg");
        assertThat(upgraded.entry("b", 0)).hasValue(new Entry(0, "c.jpg", 1, 1, 1, 1, null));
        assertThat(t.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ENTRY'", Integer.class)).isZero();
    }

    @Test
    void searchFiltersSortsAndPages() {
        repo.save(album("1", "Ellena by the sea", "Ellena", List.of("wedding", "beach"), 500, LocalDate.of(2019, 1, 1), 8), List.of());
        repo.save(album("2", "Ellena 2", "Ellena", List.of("wedding"), 900, LocalDate.of(2020, 1, 1), 5), List.of());
        repo.save(album("3", "Mira", "Mira ELLENA-like", List.of("wedding", "outdoor"), 700, LocalDate.of(2021, 1, 1), 9), List.of());
        repo.save(album("4", "Ellena old", "Ellena", List.of("wedding"), 950, LocalDate.of(2010, 1, 1), 9), List.of());
        repo.save(album("5", "Ellena no tag", "Ellena", List.of(), 990, LocalDate.of(2022, 1, 1), 9), List.of());
        repo.save(album("6", "Ellena undated", "Ellena", List.of("wedding"), 100, null, 1), List.of());

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
        var edited = new AlbumMeta(null, "", null, null, null, null, List.of());
        repo.save(a.withEdits(edited), List.of());
        var stored = repo.find("x").orElseThrow();
        assertThat(stored.internal()).isEqualTo(a.withEdits(edited).internal());
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
        assertThat(repo.folders(ROOT)).as("an archive directly below is no folder; names ignore case").containsExactly(
                new Folder("a", ROOT + "/a", 1, false), new Folder("B", ROOT + "/B", 1, true), new Folder("C", ROOT + "/C", 3, true));
        assertThat(repo.folders(ROOT + "/C")).containsExactly(new Folder("x", ROOT + "/C/x", 1, false));
        assertThat(repo.folders(DRIVE)).containsExactly(new Folder("albums", ROOT, 6, true));
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
        var edited = new AlbumMeta(null, "Bob", null, null, null, null, List.of("x"));
        var t = at("t", ROOT + "/T", Kind.DIRECTORY).withEdits(edited).with("t", 4, 7).withCover("b.jpg", true, false);
        repo.save(t, List.of());
        repo.delete("t");
        assertThat(repo.find("t")).isEmpty();
        assertThat(repo.recall("ct")).hasValue(new Tombstone("t", 4, 7, "b.jpg", true, edited));
        assertThat(repo.recall("ct")).as("a peek").isPresent();
        repo.save(t.with("t", 5, 7), List.of());
        assertThat(repo.recall("ct")).isEmpty();
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
    private static Album at(String id, String path, Kind kind) {
        var extracted = new AlbumMeta(path.substring(path.lastIndexOf('/') + 1), null, null, null, null, null, List.of());
        return new Album(id, ROOT, path, kind, null, "001.jpg", false, 1, false, 10, 100, 2_000_000, 1800, 1200, null,
                Instant.EPOCH, Instant.ofEpochMilli(id.hashCode()), null, null, null, null, List.of(), 0,
                new Album.Internal("c" + id, "s" + id, extracted, AlbumMeta.EMPTY)).withEdits(AlbumMeta.EMPTY);
    }

    private static AlbumQuery query(String text, List<String> tags, Long minBytes, LocalDate from, Sort sort, int offset, int limit) {
        return new AlbumQuery(text, tags, null, null, null, List.of(ROOT), null, minBytes, null, null, null, null,
                null, from, null, null, null, sort, offset, limit);
    }

    private static AlbumQuery inRoot(String root) {
        return new AlbumQuery(null, null, null, null, null, List.of(root), null, null, null, null, null, null, null,
                null, null, null, null, null, 0, 10);
    }

    private static Album album(String id, String name, String model, List<String> tags, long bytes, LocalDate date, int rating) {
        var extracted = new AlbumMeta(name, model, null, null, null, date, tags);
        return new Album(id, ROOT, ROOT + "/" + name, Kind.DIRECTORY, null, "001.jpg", false, 1, false, 10,
                bytes, 2_000_000, 1800, 1200, date, Instant.EPOCH, Instant.EPOCH, null, null, null, null, List.of(),
                rating, new Album.Internal("c" + id, "s" + id, extracted, AlbumMeta.EMPTY)).withEdits(AlbumMeta.EMPTY);
    }
}
