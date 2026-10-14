package thumbnailer.album;

import java.util.Optional;

/** Port: cover bytes by album id, a regenerable cache (a miss is re-rendered). Thread-safe. */
public interface BlobStore {

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    void remove(String key);

    /** Estimates: live = bytes in use, file = bytes on disk. */
    Stats stats();

    /** Returns free space to the file system now; synchronous, best effort. */
    void compact();

    record Stats(long count, long liveBytes, long fileBytes) {}
}
