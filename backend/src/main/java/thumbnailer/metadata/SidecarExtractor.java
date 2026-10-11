package thumbnailer.metadata;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import thumbnailer.album.AlbumMeta;
import thumbnailer.album.Entry;
import thumbnailer.source.AlbumContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code album.json}, {@code album.txt}, or the only {@code *.txt} of the album. Text sidecars hold {@code key: value}
 * lines (name, model, studio, photographer, date, description) and {@code #tag} tokens anywhere.
 */
@Component
@Order(1)
class SidecarExtractor implements MetadataExtractor {

    private static final Pattern LINE = Pattern.compile(
            "^\\s*(name|model|studio|photographer|date|description)\\s*[:=]\\s*(.*?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TAG = Pattern.compile("#([\\p{L}\\p{N}][\\p{L}\\p{N}_-]*)");

    private final JsonMapper json;

    SidecarExtractor(JsonMapper json) { this.json = json; }

    @Override
    public AlbumMeta extract(AlbumContainer container, List<Entry> entries) throws IOException {
        var names = container.items().stream().map(AlbumContainer.Item::name).toList();
        var texts = names.stream().filter(n -> fileName(n).endsWith(".txt")).toList();
        var sidecar = find(names, "album.json").or(() -> find(names, "album.txt"))
                .or(() -> texts.size() == 1 ? Optional.of(texts.getFirst()) : Optional.empty());
        if (sidecar.isEmpty()) return AlbumMeta.EMPTY;
        try (var in = container.open(sidecar.get())) {
            return fileName(sidecar.get()).endsWith(".json") ? json.readValue(in, AlbumMeta.class)
                    : parse(new String(in.readAllBytes(), UTF_8));
        }
    }

    static AlbumMeta parse(String text) {
        var fields = new HashMap<String, String>();
        LINE.matcher(text).results().forEach(m -> fields.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), m.group(2)));
        return new AlbumMeta(fields.get("name"), fields.get("model"), fields.get("studio"), fields.get("photographer"),
                fields.get("description"), MetadataExtractor.date(fields.get("date")),
                TAG.matcher(text).results().map(m -> m.group(1)).toList());
    }

    private static Optional<String> find(List<String> names, String file) {
        return names.stream().filter(n -> fileName(n).equals(file)).findFirst();
    }

    private static String fileName(String entry) {
        return entry.substring(entry.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }
}
