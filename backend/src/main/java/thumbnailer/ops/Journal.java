package thumbnailer.ops;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The journal of unfinished items, {@code <library-dir>/ops.journal}: one JSON line per item ({@code op, item, phase},
 * and for an item journaled file by file the current {@code file} and the {@code hash} its result must read back as),
 * rewritten before each phase; and each item's {@link Manifest}, {@code <library-dir>/ops/<op>/<item>.json}. Both are
 * written to a temporary file, forced and moved into place, so a crash leaves the old or the new state, never a torn one.
 * Used by one thread at a time (the {@code ops} worker, or recovery before it).
 */
@Component
final class Journal {

    enum Phase { COPYING, VERIFIED, DISPOSING, INSTALLING, REBASING, INDEXING }

    record Line(String op, int item, Phase phase, @JsonInclude(NON_NULL) String file, @JsonInclude(NON_NULL) Long hash) {}

    private final Path file, manifests;
    private final JsonMapper json;
    private final Map<String, Line> lines = new LinkedHashMap<>();
    private boolean unread; // the last read failed: writing now would replace records nobody has recovered

    Journal(JsonMapper json, @Value("${thumbnailer.library-dir:${thumbnailer.data-dir}}") Path dir) {
        this.file = dir.resolve("ops.journal");
        this.manifests = dir.resolve("ops");
        this.json = json;
    }

    /** The lines on disk (what a previous run left), read again; until a read succeeds, every write fails. */
    List<Line> read() throws IOException {
        lines.clear();
        unread = true;
        if (Files.exists(file))
            for (var l : Files.readAllLines(file, UTF_8)) if (!l.isBlank()) put(json.readValue(l, Line.class));
        unread = false;
        return List.copyOf(lines.values());
    }

    Manifest manifest(Line l) throws IOException { return json.readValue(path(l.op(), l.item()).toFile(), Manifest.class); }

    Manifest save(Manifest m) throws IOException {
        var p = path(m.op(), m.item());
        Files.createDirectories(p.getParent());
        write(p, json.writeValueAsBytes(m));
        return m;
    }

    void line(Manifest m, Phase phase, String file, Long hash) throws IOException {
        writable();
        put(new Line(m.op(), m.item(), phase, file, hash));
        flush();
    }

    boolean has(String op, int item) { return lines.containsKey(op + '/' + item); }

    /** The item is done (or rolled back): its line goes, then its manifest, then its operation's folder once empty. */
    void remove(String op, int item) throws IOException {
        writable();
        lines.remove(op + '/' + item);
        flush();
        var p = path(op, item);
        if (!Files.isDirectory(p.getParent())) return;
        Files.deleteIfExists(p);
        try (var rest = Files.list(p.getParent())) {
            if (rest.findAny().isEmpty()) Files.delete(p.getParent());
        }
    }

    /** Deletes the manifests no line refers to (an operation that ended, or a crash before its first line). */
    void prune() throws IOException {
        if (!Files.isDirectory(manifests)) return;
        var live = lines.values().stream().map(Line::op).collect(Collectors.toSet());
        try (var ops = Files.list(manifests)) {
            for (var op : ops.filter(p -> !live.contains(p.getFileName().toString())).toList()) Trees.delete(op);
        }
    }

    private void put(Line l) { lines.put(l.op() + '/' + l.item(), l); }

    private Path path(String op, int item) { return manifests.resolve(op).resolve(item + ".json"); }

    private void writable() throws IOException {
        if (unread) throw new IOException("The operations journal cannot be read");
    }

    private void flush() throws IOException {
        var out = new ArrayList<String>();
        lines.values().forEach(l -> out.add(json.writeValueAsString(l)));
        write(file, (String.join("\n", out) + "\n").getBytes(UTF_8));
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        var tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var ch = FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            for (var b = ByteBuffer.wrap(bytes); b.hasRemaining(); ) ch.write(b);
            ch.force(true);
        }
        Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
    }
}
