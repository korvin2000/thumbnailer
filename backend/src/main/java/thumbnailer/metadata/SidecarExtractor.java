package thumbnailer.metadata;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import thumbnailer.spi.AlbumContainer.Item;
import thumbnailer.spi.AlbumMeta;
import thumbnailer.spi.Extraction;
import thumbnailer.spi.MetadataExtractor;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code album.json}, {@code album.txt}, or the only {@code *.txt} of the album. Text sidecars hold {@code key: value}
 * lines (name, model, studio, photographer, date, description) and {@code #tag} tokens anywhere; they come decoded in
 * {@link Extraction#texts}, only {@code album.json} is read here (its head, through the container, so not outside
 * indexing; a sequential archive's indexing pass keeps it for this read).
 */
@Component
public class SidecarExtractor implements MetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(SidecarExtractor.class);
    private static final Pattern LINE = Pattern.compile( // horizontal space only: a match never runs into the next line
            "^\\h*(name|model|studio|photographer|date|description)\\h*[:=]\\h*(.*?)\\h*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TAG = Pattern.compile("#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]*)");

    private final JsonMapper json;

    public SidecarExtractor() { this(JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()); }

    @Autowired
    SidecarExtractor(JsonMapper json) { this.json = json; }

    @Override public int order() { return 1; }

    /** {@code album.json} within {@link Extraction#TEXT_BYTES} (a larger one is skipped), else the text sidecar. */
    @Override
    public AlbumMeta extract(Extraction ctx) throws IOException {
        var json = find(ctx.items(), "album.json");
        if (json.isPresent() && ctx.container() != null) {
            if (json.get().bytes() <= Extraction.TEXT_BYTES)
                return this.json.readValue(ctx.container().head(json.get().name(), Extraction.TEXT_BYTES), AlbumMeta.class);
            log.warn("Skipping {} of {}: over {} bytes", json.get().name(), ctx.path(), Extraction.TEXT_BYTES);
        }
        var texts = ctx.items().stream().map(Item::name).filter(Extraction::isText).toList();
        var sidecar = find(ctx.items(), "album.txt").map(Item::name).or(() -> texts.size() == 1 ? Optional.of(texts.getFirst()) : Optional.empty());
        var text = sidecar.map(ctx.texts()::get);
        return text.isPresent() ? parse(text.get()) : AlbumMeta.EMPTY;
    }

    /** One field per line; an empty value leaves the field unset (a later line may give it). */
    static AlbumMeta parse(String text) {
        var fields = new HashMap<String, String>();
        LINE.matcher(text).results().filter(m -> !m.group(2).isEmpty())
                .forEach(m -> fields.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), m.group(2)));
        return new AlbumMeta(fields.get("name"), fields.get("model"), fields.get("studio"), fields.get("photographer"),
                fields.get("description"), MetadataExtractor.date(fields.get("date")),
                TAG.matcher(text).results().map(m -> m.group(1)).toList(), null);
    }

    private static Optional<Item> find(List<Item> items, String file) {
        return items.stream().filter(i -> Extraction.fileName(i.name()).equals(file)).findFirst();
    }
}
