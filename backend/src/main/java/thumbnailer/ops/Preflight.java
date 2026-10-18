package thumbnailer.ops;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What an operation would do, for its confirm dialog: each item (done or skipped, and why), the bytes it moves, the free
 * space at the target ({@code -1}: no target), warnings and the consequences in words.
 */
public record Preflight(List<Item> items, long bytes, long free, List<String> warnings, List<String> consequences) {

    public record Item(String path, boolean skip, @JsonInclude(NON_NULL) String reason, @JsonInclude(NON_NULL) String note) {}
}
