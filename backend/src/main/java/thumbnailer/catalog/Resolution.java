package thumbnailer.catalog;

import java.util.List;
import thumbnailer.album.Link;

/** An album's text resolved into catalog links (roles 1–5) and the aliases of the linked entities for its search text. */
public record Resolution(List<Link> links, String aliasText) {

    public static final Resolution NONE = new Resolution(List.of(), "");
}
