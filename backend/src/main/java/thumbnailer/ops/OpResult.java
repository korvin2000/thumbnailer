package thumbnailer.ops;

import java.util.List;

/** Published once when an operation ends: what it did, skipped and failed, item by item. */
public record OpResult(String id, int done, List<Issue> skipped, List<Issue> failed) {

    public record Issue(String item, String reason) {}
}
