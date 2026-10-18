package thumbnailer.catalog;

import java.util.List;

/** An entity as the card saves it: {@code id} null = new; {@code aliases}/{@code links} null = unchanged, else the whole set. */
public record EntitySave(Integer id, EntityKind kind, String name, int rating, String profile, List<String> aliases,
                         List<EntityLink> links) {}
