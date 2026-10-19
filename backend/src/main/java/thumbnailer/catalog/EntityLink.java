package thumbnailer.catalog;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A curated fact from one entity to another; one to the entity's own kind is its parent (a studio's parent studio);
 * model → studio only: {@code alias} credited there, {@code roles} (a name list).
 */
public record EntityLink(int to, @JsonInclude(NON_NULL) String alias, @JsonInclude(NON_NULL) String roles) {}
