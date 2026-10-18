package thumbnailer.web.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.sf.sevenzipjbinding.ArchiveFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import thumbnailer.album.Album;
import thumbnailer.album.AlbumPatch;
import thumbnailer.album.AlbumRepository;
import thumbnailer.album.AlbumService;
import thumbnailer.catalog.Catalog;
import thumbnailer.catalog.EntityKind;
import thumbnailer.catalog.EntitySave;
import thumbnailer.index.Reindexer;
import thumbnailer.settings.Settings;
import thumbnailer.settings.SettingsStore;
import thumbnailer.source.Archives;
import thumbnailer.source.Containers;
import thumbnailer.spi.Entry;

/** Indexing feeds the extraction context (texts, bio, flags); the photo messages answer from the files and the catalog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PhotosApiTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        var id = UUID.randomUUID();
        registry.add("thumbnailer.data-dir", () -> Path.of("build/test-data", id.toString()).toAbsolutePath().toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + id + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired Reindexer scans; // its synchronous scan is public
    @Autowired AlbumRepository repo;
    @Autowired AlbumService albums;
    @Autowired SettingsStore settings;
    @Autowired Catalog catalog;
    @Autowired PhotosApi photos;
    @TempDir Path root;

    @Test
    void indexingReadsTextsBioAndFlagsAndThePhotoMessagesAnswer() throws IOException {
        var l = root.resolve("L");
        Files.createDirectories(l);
        Files.write(l.resolve("a.jpg"), withArtist(jpeg(70, 50), "Ivan"));
        Files.write(l.resolve("b.jpg"), jpeg(90, 60));
        Files.write(l.resolve("album.txt"), "model: Anna\n#Studio-Set".getBytes(StandardCharsets.UTF_8));
        Files.write(l.resolve("Model Bio.txt"), "Анна, 25 лет".getBytes(Charset.forName("windows-1251")));
        var archive = new LinkedHashMap<String, byte[]>();
        archive.put("CLS-2020-02-02 - Long Folder Name/a.jpg", jpeg(80, 40));
        archive.put("CLS-2020-02-02 - Long Folder Name/album.txt", "﻿model: Zoe".getBytes(StandardCharsets.UTF_8));
        archive.put("CLS-2020-02-02 - Long Folder Name/b.jpg", jpeg(81, 41));
        Archives.sevenZip(root.resolve("S.7z"), ArchiveFormat.SEVEN_ZIP, archive, true);
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(), false, null, null, null));
        scans.scan(null, () -> false);

        var album = byName("L");
        assertThat(album).extracting(Album::model, Album::tags, Album::bio).containsExactly("Anna", List.of("studio-set"), "Model Bio.txt");
        assertThat(repo.entries(album.id())).extracting(Entry::name, Entry::flags)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a.jpg", Entry.META_KNOWN | Entry.META),
                        org.assertj.core.groups.Tuple.tuple("b.jpg", Entry.META_KNOWN));
        assertThat(byName("S")).as("a solid archive's sidecar, read in the heads pass").extracting(Album::model, Album::bio).containsExactly("Zoe", null);

        var text = (Event.Text) photos.handle(new Request.AlbumText(album.id(), "Model Bio.txt"));
        assertThat(text).isEqualTo(new Event.Text("Model Bio.txt", "Анна, 25 лет"));
        assertThatThrownBy(() -> photos.handle(new Request.AlbumText(album.id(), "a.jpg"))).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(root.resolve("secret.txt"), "outside the album");
        for (var outside : List.of("../secret.txt", Containers.normalize(root.resolve("secret.txt"))))
            assertThatThrownBy(() -> photos.handle(new Request.AlbumText(album.id(), outside))).as(outside).isInstanceOf(IllegalArgumentException.class);

        var meta = (Event.PhotoMetaRows) photos.handle(new Request.PhotoMeta(album.id(), 0));
        assertThat(meta.rows()).contains(new Event.PhotoMetaRows.Row("Exif IFD0", "Artist", "Ivan"))
                .anyMatch(r -> r.dir().equals("JPEG") && r.tag().equals("Image Width"));

        int list = catalog.save(new EntitySave(null, EntityKind.LIST, "Fav", 0, null, List.of(), null)).entity().id();
        albums.quiesce(List.of(album.id())); // an operation renaming the album's files: a mark waits for it or is refused
        assertThatThrownBy(() -> photos.handle(new Request.MarkPhoto(album.id(), "b.jpg", 5, List.of(list)))).hasMessage(AlbumService.BUSY);
        albums.release(List.of(album.id()));
        photos.handle(new Request.MarkPhoto(album.id(), "b.jpg", 5, List.of(list)));
        var page = (Event.PhotoList) photos.handle(new Request.Photos(list, 0, 10));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).containsExactly(new Event.PhotoList.Photo(album.id(), "b.jpg", 1, 5,
                new Event.PhotoList.AlbumRef(album.id(), "L", album.revision())));
        assertThat(((Event.PhotoList) photos.handle(new Request.Photos(list, 1, 10))).items()).isEmpty();
        albums.patch(album.id(), new AlbumPatch(null, null, null, null, null, null, null, null, null, null, true, null, null));
        assertThat(((Event.PhotoList) photos.handle(new Request.Photos(list, 0, 10))).total()).as("hidden albums do not count").isZero();
    }

    @Test
    void photoMetaReadsTheWholeFileWhenTheHeadEndsBeforeTheMetadata() throws IOException {
        var t = root.resolve("T");
        Files.createDirectories(t);
        Files.write(t.resolve("a.jpg"), skipped(withArtist(jpeg(72, 52), "Ivan"), 3)); // APP11 x 3, then APP1: past the head
        Files.write(t.resolve("b.jpg"), jpeg(92, 62));
        settings.set(new Settings(List.of(new Settings.Root(root.toString(), List.of())), List.of(), false, null, null, null));
        scans.scan(null, () -> false);

        var album = byName("T");
        assertThat(repo.entries(album.id()).getFirst().flags()).as("the head ends before the frame header: unknown").isZero();
        var meta = (Event.PhotoMetaRows) photos.handle(new Request.PhotoMeta(album.id(), 0));
        assertThat(meta.rows()).as("a head parse that stopped early is not the answer").contains(new Event.PhotoMetaRows.Row("Exif IFD0", "Artist", "Ivan"));
        assertThat(repo.entries(album.id()).getFirst().flags()).isEqualTo(Entry.META_KNOWN | Entry.META);
    }

    private Album byName(String name) {
        return repo.byRoot(Containers.normalize(root)).stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    /** Inserts {@code n} APP11 segments of 60000 bytes (a type metadata-extractor skips) right after SOI. */
    private static byte[] skipped(byte[] jpeg, int n) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        for (int i = 0; i < n; i++) {
            out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xEB, (byte) (60_002 >> 8), (byte) 60_002});
            out.writeBytes(new byte[60_000]);
        }
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** Inserts an APP1 Exif segment whose IFD0 holds only Artist, right after SOI. */
    private static byte[] withArtist(byte[] jpeg, String artist) {
        var a = (artist + "\0").getBytes(StandardCharsets.US_ASCII);
        var tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + a.length).put(new byte[] {'M', 'M'}).putShort((short) 42).putInt(8)
                .putShort((short) 1).putShort((short) 0x013B).putShort((short) 2).putInt(a.length).putInt(8 + 2 + 12 + 4).putInt(0).put(a).array();
        int len = 2 + 6 + tiff.length;
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len, 'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiff);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
