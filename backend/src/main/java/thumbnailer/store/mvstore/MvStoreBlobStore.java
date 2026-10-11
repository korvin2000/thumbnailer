package thumbnailer.store.mvstore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import thumbnailer.album.BlobStore;

/** Thumbnails in an H2 MVStore file ({@code thumbs.mv}); auto-commits in the background, closed on shutdown. */
@Component
@ConditionalOnProperty(name = "thumbnailer.store.blobs", havingValue = "mvstore", matchIfMissing = true)
class MvStoreBlobStore implements BlobStore, AutoCloseable {

    private final MVStore store;
    private final MVMap<String, byte[]> blobs;

    MvStoreBlobStore(@Value("${thumbnailer.data-dir}") Path dataDir) throws IOException {
        store = new MVStore.Builder().fileName(Files.createDirectories(dataDir).resolve("thumbs.mv").toString()).open();
        blobs = store.openMap("blobs");
    }

    @Override public void put(String key, byte[] bytes) { blobs.put(key, bytes); }

    @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(blobs.get(key)); }

    @Override
    public void remove(String prefix) {
        // Iterators run over a snapshot, so removing while iterating is safe.
        for (var keys = blobs.keyIterator(prefix); keys.hasNext(); ) {
            var key = keys.next();
            if (!key.startsWith(prefix)) break;
            blobs.remove(key);
        }
    }

    @Override public void close() { store.close(); }
}
