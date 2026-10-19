package thumbnailer.catalog;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What an entity card shows: the entity, every name it is known by (normalized), its curated links (one to its own
 * kind = its parent), the facts derived from its albums (read only; null until computed) and its children (the
 * entities of its kind whose parent it is; left out when none).
 */
public record Card(Entity entity, List<String> aliases, List<Relation> links, @JsonInclude(NON_NULL) Derived derived,
                   @JsonInclude(NON_EMPTY) List<Entity> children) {

    public record Relation(Entity to, @JsonInclude(NON_NULL) String alias, @JsonInclude(NON_NULL) String roles) {}

    /** Per kind, only the sections that kind shows are set. */
    @JsonInclude(NON_NULL)
    public record Derived(int albums, List<Entity> models, List<Credit> studios, List<Entity> photographers,
                          List<Entity> genres, List<String> tags) {}

    /** A studio a model's albums credit her at: the names used there, the genres of those albums, their count. */
    public record Credit(Entity entity, List<String> aliases, List<String> genres, int albums) {}
}
