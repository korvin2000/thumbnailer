package thumbnailer.album;

import java.util.List;

/** A user edit: null = unchanged, {@code ""}/{@code []} = explicit empty, {@code cover: ""} unpins the cover. */
public record AlbumPatch(String name, String model, String studio, String photographer, String description,
                         List<String> tags, Integer rating, String cover) {

    AlbumMeta meta() { return new AlbumMeta(name, model, studio, photographer, description, null, tags); }
}
