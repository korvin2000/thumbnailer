package thumbnailer.metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumMeta;

/**
 * Studio-release names such as {@code CLS-2019-01-14 - EL-Studio - Ellena - New Model - (vol-1) 1800px (x133) 52.3Mb}:
 * a leading code becomes a tag, the first ISO date the date, {@code vol-N} part of the description; size noise is
 * dropped; of the {@code " - "} parts a studio/photo/lab one is the studio, the first other one the model, the rest
 * description. Names without a date or separators yield only a name. Genres only from the configured words
 * ({@code thumbnailer.genres}, comma-separated, none by default) that stand as whole words in the name.
 */
@Component
public class FileNameExtractor implements MetadataExtractor {

    private static final Pattern NOISE = Pattern.compile("(?i)\\b\\d+px\\b|\\(x\\d+\\)|\\b[\\d.]+ ?[mg]b\\b");
    private static final Pattern CODE_DATE = Pattern.compile("(?:^([A-Za-z]{2,6})-)?\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern VOL = Pattern.compile("(?i)\\(?\\bvol-?(\\d+)\\)?");
    private static final Pattern STUDIO = Pattern.compile("(?i)studio|photo|lab");

    private final Map<String, Pattern> genres = new LinkedHashMap<>(); // configured word -> whole-word matcher

    public FileNameExtractor() { this(new String[0]); }

    @Autowired
    FileNameExtractor(@Value("${thumbnailer.genres:}") String[] genres) {
        for (var g : genres)
            if (!g.isBlank()) this.genres.put(g.strip(), Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(g.strip())
                    + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
    }

    @Override public int order() { return 2; }

    @Override
    public AlbumMeta extract(Extraction ctx) {
        if (ctx.name() == null) return AlbumMeta.EMPTY;
        var m = parse(ctx.name());
        var found = genres.entrySet().stream().filter(g -> g.getValue().matcher(ctx.name()).find()).map(Map.Entry::getKey).toList();
        return found.isEmpty() ? m : new AlbumMeta(m.name(), m.model(), m.studio(), null, m.description(), m.date(), m.tags(), found);
    }

    static AlbumMeta parse(String baseName) {
        String name = NOISE.matcher(baseName).replaceAll(" ").replaceAll("\\s+", " ").replaceAll("[\\s-]+$", "").strip();
        var date = MetadataExtractor.date(name);
        var m = CODE_DATE.matcher(name);
        boolean dated = date != null && m.find();
        var tags = dated && m.group(1) != null ? List.of(m.group(1)) : List.<String>of();
        String rest = dated ? name.substring(0, m.start()) + name.substring(m.end()) : name;
        var description = new ArrayList<String>();
        var vols = VOL.matcher(rest).results().map(v -> "vol-" + v.group(1)).toList();
        var parts = Arrays.stream(VOL.matcher(rest).replaceAll(" ").split("\\s+-\\s+"))
                .map(p -> p.replaceAll("^[\\s-]+|[\\s-]+$", "")).filter(p -> !p.isEmpty()).toList();
        String studio = null, model = null;
        if (dated || parts.size() > 1) {
            for (var part : parts) {
                if (studio == null && STUDIO.matcher(part).find()) studio = part;
                else if (model == null) model = part;
                else description.add(part);
            }
        }
        description.addAll(vols);
        return new AlbumMeta(name.isEmpty() ? baseName : name, model, studio, null,
                description.isEmpty() ? null : String.join(", ", description), date,
                tags.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList(), null);
    }
}
