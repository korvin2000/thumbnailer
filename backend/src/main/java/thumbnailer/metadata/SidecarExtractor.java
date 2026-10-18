package thumbnailer.metadata;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumMeta;
import thumbnailer.source.AlbumContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code album.json}, {@code album.txt}, or the only {@code *.txt} of the album. Text sidecars hold {@code key: value}
 * lines (name, model, studio, photographer, date, description) and {@code #tag} tokens anywhere; they come decoded in
 * {@link Extraction#texts}, only {@code album.json} is read here (through the container, so not outside indexing).
 */
@Component
public class SidecarExtractor implements MetadataExtractor {

    private static final Pattern LINE = Pattern.compile(
            "^\\s*(name|model|studio|photographer|date|description)\\s*[:=]\\s*(.*?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TAG = Pattern.compile("#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]*)");

    private final JsonMapper json;

    public SidecarExtractor() { this(JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()); }

    @Autowired
    SidecarExtractor(JsonMapper json) { this.json = json; }

    @Override public int order() { return 1; }

    @Override
    public AlbumMeta extract(Extraction ctx) throws IOException {
        var names = ctx.items().stream().map(AlbumContainer.Item::name).toList();
        var json = find(names, "album.json");
        if (json.isPresent() && ctx.container() != null)
            try (var in = ctx.container().open(json.get())) {
                return this.json.readValue(in, AlbumMeta.class);
            }
        var texts = names.stream().filter(Extraction::isText).toList();
        var sidecar = find(names, "album.txt").or(() -> texts.size() == 1 ? Optional.of(texts.getFirst()) : Optional.empty());
        var text = sidecar.map(ctx.texts()::get);
        return text.isPresent() ? parse(text.get()) : AlbumMeta.EMPTY;
    }

    static AlbumMeta parse(String text) {
        var fields = new HashMap<String, String>();
        LINE.matcher(text).results().forEach(m -> fields.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), m.group(2)));
        return new AlbumMeta(fields.get("name"), fields.get("model"), fields.get("studio"), fields.get("photographer"),
                fields.get("description"), MetadataExtractor.date(fields.get("date")),
                TAG.matcher(text).results().map(m -> m.group(1)).toList(), null);
    }

    private static Optional<String> find(List<String> names, String file) {
        return names.stream().filter(n -> Extraction.fileName(n).equals(file)).findFirst();
    }
}
