package thumbnailer.album;

import java.time.LocalDate;
import java.util.List;
import thumbnailer.catalog.EntityKind;
import thumbnailer.spi.Kind;

/**
 * Search criteria; a null field means "no filter". {@code kind}: {@code ARCHIVE} matches every archive kind (zip
 * included), {@code ZIP} zip alone; {@code entities}: linked to every one; {@code missing}: no link of that role;
 * {@code hidden}: null/false = visible albums only, true = hidden ones only; {@code duplicates}: true = albums whose
 * content stamp another album shares, false = one album per stamp (the first indexed among those of its visibility).
 */
public record AlbumQuery(String text, List<String> tags, String model, String studio, String photographer,
                         List<String> roots, Kind kind, Long minBytes, Long maxBytes, Integer minCount,
                         Integer maxCount, Double minMegapixels, Double maxMegapixels, LocalDate dateFrom,
                         LocalDate dateTo, Integer minRating, Integer maxRating, List<String> genres,
                         List<Integer> entities, EntityKind missing, Boolean hidden, Boolean duplicates, Sort sort,
                         int offset, int limit) {

    public enum Sort { NEWEST, OLDEST, LARGEST, SMALLEST, MOST_PHOTOS, FEWEST_PHOTOS, BEST_QUALITY, TOP_RATED, NAME, RECENTLY_INDEXED,
        WORST_QUALITY, LOWEST_RATED, NAME_DESC, FIRST_INDEXED }

    public static final AlbumQuery ALL = new AlbumQuery(null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 0);

    public AlbumQuery {
        sort = sort == null ? Sort.NEWEST : sort;
        offset = Math.max(0, offset);
        limit = limit <= 0 ? 100 : Math.min(limit, 1000);
    }

    public AlbumQuery page(int offset, int limit) {
        return new AlbumQuery(text, tags, model, studio, photographer, roots, kind, minBytes, maxBytes, minCount, maxCount,
                minMegapixels, maxMegapixels, dateFrom, dateTo, minRating, maxRating, genres, entities, missing, hidden,
                duplicates, sort, offset, limit);
    }
}
