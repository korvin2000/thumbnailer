package thumbnailer.album;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import thumbnailer.catalog.EntityKind;

/** An album credited to an entity; {@code alias}: the token in the album's text when it is not the primary name. */
public record Link(int entity, EntityKind role, String name, @JsonInclude(NON_NULL) String alias) {}
