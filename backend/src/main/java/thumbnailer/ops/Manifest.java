package thumbnailer.ops;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import thumbnailer.source.Kind;

/**
 * What one item of an operation must preserve (catalog_plan.md §4.7), written before its first phase: canonical
 * {@code src}, the result's final {@code dst} and the {@code tmp} it is written to first ({@code tmp == src}: an atomic
 * rename; null: nothing is written, a delete), the source {@code container} kind, the archive {@code format}, the file
 * {@code names} the result takes (old → new, every file it holds; empty for a plain copy) and, once written, the
 * {@code entries} the result must read back as (name → {@link Hashes hash}).
 */
record Manifest(String op, int item, Op.Kind kind, String albumId, Kind container, String src, String dst, String tmp,
                Op.Format format, boolean encrypt, Map<String, String> names, Map<String, Long> entries) {

    Manifest {
        names = names == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(names)); // per-file items go in order
        entries = entries == null ? Map.of() : Map.copyOf(entries);
    }

    Manifest(Op.Kind kind, String albumId, Kind container, String src, String dst, String tmp, Op.Format format,
             boolean encrypt, Map<String, String> names) {
        this(null, 0, kind, albumId, container, src, dst, tmp, format, encrypt, names, null);
    }

    boolean atomic() { return src.equals(tmp); }

    /** In-place encryption of a directory: journaled file by file. */
    boolean perFile() { return kind == Op.Kind.CRYPT && container == Kind.DIRECTORY; }

    Manifest at(String op, int item) { return new Manifest(op, item, kind, albumId, container, src, dst, tmp, format, encrypt, names, entries); }

    Manifest withEntries(Map<String, Long> entries) { return new Manifest(op, item, kind, albumId, container, src, dst, tmp, format, encrypt, names, entries); }

    Manifest withDst(String dst) { return new Manifest(op, item, kind, albumId, container, src, dst, tmp, format, encrypt, names, entries); }

    /** The copy path of a move whose atomic rename crossed volumes. */
    Manifest copying() { return new Manifest(op, item, kind, albumId, container, src, dst, Trees.part(dst), format, encrypt, names, entries); }
}
