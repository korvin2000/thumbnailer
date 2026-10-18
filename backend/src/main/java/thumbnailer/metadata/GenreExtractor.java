package thumbnailer.metadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Extraction;
import thumbnailer.spi.MetadataExtractor;

/**
 * Genres from the album name: the configured words ({@code thumbnailer.genres}, comma-separated, none by default) that
 * stand as whole words in it. The configured half of the file-name parser, whose plugin ({@code :plugins:names}) has
 * no Spring configuration; same order, so the chain merges as before.
 */
@Component
class GenreExtractor implements MetadataExtractor {

    private final Map<String, Pattern> genres = new LinkedHashMap<>(); // configured word -> whole-word matcher

    GenreExtractor(@Value("${thumbnailer.genres:}") String[] genres) {
        for (var g : genres)
            if (!g.isBlank()) this.genres.put(g.strip(), Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(g.strip())
                    + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    @Override public int order() { return 2; }

    @Override
    public AlbumMeta extract(Extraction ctx) {
        if (ctx.name() == null) return AlbumMeta.EMPTY;
        var found = genres.entrySet().stream().filter(g -> g.getValue().matcher(ctx.name()).find()).map(Map.Entry::getKey).toList();
        return found.isEmpty() ? AlbumMeta.EMPTY : new AlbumMeta(null, null, null, null, null, null, null, found);
    }
}
