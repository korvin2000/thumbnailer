package thumbnailer.catalog;

import java.util.List;

/** Published after a catalog change: views showing entities of these kinds reload. */
public record CatalogEvent(List<EntityKind> kinds) {}
