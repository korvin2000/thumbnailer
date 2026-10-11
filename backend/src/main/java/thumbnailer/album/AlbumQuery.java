package thumbnailer.album;

import java.time.LocalDate;
import java.util.List;
import thumbnailer.source.Kind;

/** Search criteria; a null field means "no filter". */
public record AlbumQuery(String text, List<String> tags, String model, String studio, String photographer,
                         List<String> roots, Kind kind, Long minBytes, Long maxBytes, Integer minCount,
                         Integer maxCount, Double minMegapixels, Double maxMegapixels, LocalDate dateFrom,
                         LocalDate dateTo, Integer minRating, Integer maxRating, Sort sort, int offset, int limit) {

    public enum Sort { NEWEST, OLDEST, LARGEST, SMALLEST, MOST_PHOTOS, FEWEST_PHOTOS, BEST_QUALITY, TOP_RATED, NAME, RECENTLY_INDEXED }

    public static final AlbumQuery ALL = new AlbumQuery(null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, 0, 0);

    public AlbumQuery {
        sort = sort == null ? Sort.NEWEST : sort;
        offset = Math.max(0, offset);
        limit = limit <= 0 ? 100 : Math.min(limit, 1000);
    }
}
