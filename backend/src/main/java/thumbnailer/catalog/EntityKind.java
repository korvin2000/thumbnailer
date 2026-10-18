package thumbnailer.catalog;

/** The kinds of catalog entities; {@link #code} is what the database stores (never the ordinal), the wire carries the name. */
public enum EntityKind {
    MODEL(1), STUDIO(2), PHOTOGRAPHER(3), GENRE(4), TAG(5), LIST(6);

    private final int code;

    EntityKind(int code) { this.code = code; }

    public int code() { return code; }

    public static EntityKind of(int code) {
        for (var k : values()) if (k.code == code) return k;
        throw new IllegalArgumentException("No entity kind " + code);
    }
}
