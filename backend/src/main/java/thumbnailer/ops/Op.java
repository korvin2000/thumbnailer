package thumbnailer.ops;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import thumbnailer.album.AlbumQuery;

/**
 * A file operation as the client asks for it: the albums ({@code albumIds}, else {@code query}; at most 500) or the
 * folders ({@code paths}) it acts on, the {@code target} directory, the archive {@code format} (null = the settings'),
 * {@code encrypt} (CRYPT: true encrypts, false decrypts), the entry {@code names} (COPY_PHOTOS) and what to do with an
 * existing destination. {@code password} is read once and never written back or logged.
 */
public record Op(Kind kind, List<String> albumIds, AlbumQuery query, List<String> paths, String target, Format format,
                 Boolean encrypt, List<String> names, Conflict conflict,
                 @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {

    public enum Kind { MOVE, COPY, DELETE, PACK, UNPACK, REPACK, CRYPT, COPY_PHOTOS, FOLDER_RENAME, FOLDER_MOVE, FOLDER_COPY, FOLDER_DELETE }

    /** {@code AUTO} = zip with stored images. */
    public enum Format { AUTO, ZIP, SEVEN_ZIP }

    public enum Conflict { SKIP, RENAME }

    public Op {
        Objects.requireNonNull(kind, "kind is required");
        conflict = conflict == null ? Conflict.SKIP : conflict;
    }

    @Override
    public String toString() {
        return "Op[kind=%s, albumIds=%s, query=%s, paths=%s, target=%s, format=%s, encrypt=%s, names=%s, conflict=%s]"
                .formatted(kind, albumIds, query, paths, target, format, encrypt, names, conflict);
    }
}
