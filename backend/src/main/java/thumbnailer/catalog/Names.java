package thumbnailer.catalog;

import static java.util.stream.Collectors.joining;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The name-list syntax of album text fields: names separated by {@code ,} or {@code ;}; a name holding a separator or a
 * quote is quoted ({@code "Smith, John"}, a quote inside doubled), so {@code parse(join(x)).equals(x)} for trimmed,
 * non-empty names.
 */
public final class Names {

    private static final Pattern QUOTE = Pattern.compile("[,;\"]");

    private Names() {}

    public static List<String> parse(String text) {
        var names = new ArrayList<String>();
        if (text == null) return names;
        var name = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') name.append(text.charAt(++i));
            else if (c == '"') quoted = !quoted;
            else if (!quoted && (c == ',' || c == ';')) add(names, name);
            else name.append(c);
        }
        add(names, name);
        return names;
    }

    public static String join(List<String> names) {
        return names.stream().map(n -> QUOTE.matcher(n).find() ? '"' + n.replace("\"", "\"\"") + '"' : n).collect(joining(", "));
    }

    private static void add(List<String> names, StringBuilder name) {
        var n = name.toString().strip();
        if (!n.isEmpty()) names.add(n);
        name.setLength(0);
    }
}
