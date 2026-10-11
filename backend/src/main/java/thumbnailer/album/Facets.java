package thumbnailer.album;

import java.util.List;

/** Value counts over the albums matching a query, top 50 per field. */
public record Facets(List<Count> tags, List<Count> models, List<Count> studios, List<Count> photographers) {

    public record Count(String value, int count) {}
}
