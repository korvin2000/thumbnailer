package thumbnailer.catalog;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A model, studio, photographer, genre, tag or favorites list. {@code auto}: created from album text, not yet
 * confirmed; {@code albums}: visible linked albums (stored); {@code profile}: card-only fields as JSON text;
 * {@code photos}: a list's photos (computed), null for other kinds.
 */
public record Entity(int id, EntityKind kind, String name, int rating, boolean auto, int albums,
                     @JsonInclude(NON_NULL) String profile, @JsonInclude(NON_NULL) Integer photos) {}
