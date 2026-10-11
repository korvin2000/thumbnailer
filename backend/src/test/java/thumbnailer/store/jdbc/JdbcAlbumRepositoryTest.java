package thumbnailer.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.AlbumQuery;
import thumbnailer.album.AlbumQuery.Sort;
import thumbnailer.source.Containers;
import thumbnailer.source.Kind;
import tools.jackson.databind.json.JsonMapper;

class JdbcAlbumRepositoryTest {

    private final EmbeddedDatabase db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true).addScript("schema.sql").build();
    private final JdbcAlbumRepository repo = new JdbcAlbumRepository(new JdbcTemplate(db), JsonMapper.builder().build());

    /** The file-system root, e.g. {@code C:/}: it already ends in '/'. */
    private static final String DRIVE = Containers.normalize(Path.of("/"));
    private static final String ROOT = DRIVE + "albums";

    @AfterEach
    void close() { db.shutdown(); }

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
