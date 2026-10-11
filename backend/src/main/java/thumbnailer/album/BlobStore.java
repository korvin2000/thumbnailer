package thumbnailer.album;

import java.util.Optional;

/** Port: thumbnail bytes by key ({@code cover/{id}/{revision}}, {@code thumb/{id}/{revision}/{index}}). Thread-safe. */
public interface BlobStore {

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    /** Removes every key starting with {@code prefix}. */
    void remove(String prefix);
}
