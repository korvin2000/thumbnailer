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

/** Alternative cover store ({@code blobs: mvstore}): an H2 MVStore file {@code covers.mv}; auto-commits in the background. */
@Component
@ConditionalOnProperty(name = "thumbnailer.store.blobs", havingValue = "mvstore")
class MvStoreBlobStore implements BlobStore, AutoCloseable {

    private final MVStore store;
    private final MVMap<String, byte[]> blobs;

    MvStoreBlobStore(@Value("${thumbnailer.library-dir:${thumbnailer.data-dir}}") Path dataDir) throws IOException {
        store = new MVStore.Builder().fileName(Files.createDirectories(dataDir).resolve("covers.mv").toString()).open();
        blobs = store.openMap("blobs");
    }

    @Override public void put(String key, byte[] bytes) { blobs.put(key, bytes); }

    @Override public Optional<byte[]> get(String key) { return Optional.ofNullable(blobs.get(key)); }

    @Override public void remove(String key) { blobs.remove(key); }

    @Override
    public Stats stats() {
        long file = store.getFileStore().size();
        return new Stats(blobs.sizeAsLong(), file * store.getFillRate() / 100, file);
    }

    @Override public void compact() { store.compactFile(30_000); }

    @Override public void close() { store.close(); }
}
