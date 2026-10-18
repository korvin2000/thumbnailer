package thumbnailer.catalog;

import java.util.List;

/** A photo's rating (0 = unrated) and the favorites lists holding it. */
public record PhotoMark(int rating, List<Integer> lists) {}
