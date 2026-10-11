package thumbnailer.album;

/** Published by {@link AlbumService} after each store write. */
public sealed interface AlbumEvent {

    record Changed(Album album) implements AlbumEvent {}

    record Removed(String albumId) implements AlbumEvent {}
}
