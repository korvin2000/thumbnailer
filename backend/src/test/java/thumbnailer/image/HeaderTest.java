package thumbnailer.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class HeaderTest {

    @Test
    void readsSizeAndTheThreeTagsLikeMetadataExtractorInBothByteOrders() throws IOException {
        for (var order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            byte[] jpeg = withSegments(jpeg(300, 200), exif(order, 6, "Ivan Ivanov", "2024:05:17 14:03:59"), xmp(90_000));
            var h = Header.parse(MemorySegment.ofArray(jpeg));
            assertThat(h).as(order.toString()).isNotNull();
            assertThat(h.width() + "x" + h.height()).isEqualTo("300x200");
            assertThat(h.exif()).as(order.toString()).isEqualTo(new ImageProbe.Exif(6, LocalDateTime.of(2024, 5, 17, 14, 3, 59), "Ivan Ivanov"))
                    .isEqualTo(ImageProbe.exif(new ByteArrayInputStream(jpeg)));
            assertThat(ImageProbe.exif(jpeg)).isEqualTo(h.exif());
        }
    }

    @Test
    void plainJpegHasNoExifAndAPrefixWithoutTheFrameHeaderIsNull() throws IOException {
        byte[] jpeg = jpeg(64, 48);
        var h = Header.parse(MemorySegment.ofArray(jpeg));
        assertThat(h.width() + "x" + h.height()).isEqualTo("64x48");
        assertThat(h.exif()).isEqualTo(ImageProbe.Exif.NONE);
        byte[] big = withSegments(jpeg, exif(ByteOrder.BIG_ENDIAN, 3, null, null), xmp(200_000));
        assertThat(Header.parse(MemorySegment.ofArray(Arrays.copyOf(big, ImageProbe.HEAD)))).as("frame header past the head").isNull();
        assertThat(Header.parse(MemorySegment.ofArray(new byte[] {'R', 'I', 'F', 'F', 0, 0}))).isNull();
    }

    @Test
    void otherFormatsGiveTheSizeImageIoReadsFromTheirHead() throws IOException {
        for (var format : List.of("png", "gif", "bmp", "tiff")) {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB), format, out);
            var probe = ImageProbe.probe(out.toByteArray());
            assertThat(probe).as(format).isNotNull();
            assertThat(probe.width() + "x" + probe.height()).as(format).isEqualTo("300x200");
            assertThat(Header.parse(MemorySegment.ofArray(Arrays.copyOf(out.toByteArray(), 3)))).as(format + " cut short").isNull();
            if (format.equals("png")) assertThat(Header.parse(MemorySegment.ofArray(Arrays.copyOf(out.toByteArray(), 40))))
                    .as("png ending before IDAT: an eXIf may follow, so the full read decides").isNull();
        }
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', (byte) 0xE8, 3, (byte) 0xE8, 3, (byte) 0x80, 0, 0, // 1000x1000 screen, 2-colour table
                0, 0, 0, 1, 1, 1, 0x21, (byte) 0xF9, 4, 0, 0, 0, 0, 0, 0x2C, 0, 0, 0, 0, 100, 0, 50, 0, 0};
        var frame = Header.parse(MemorySegment.ofArray(gif));
        assertThat(frame.width() + "x" + frame.height()).as("the first frame, as ImageIO reads it").isEqualTo("100x50");
    }

    @Test
    void metaTellsWhetherTheHeadShowsAMetadataBlock() throws IOException {
        byte[] plain = jpeg(64, 48), withExif = withSegments(plain, exif(ByteOrder.LITTLE_ENDIAN, 1, "A", null));
        assertThat(Header.meta(withExif)).as("jpeg + APP1 Exif").isTrue();
        assertThat(Header.meta(withSegments(plain, xmp(100)))).as("jpeg + APP1 XMP").isTrue();
        assertThat(Header.meta(plain)).as("jpeg, JFIF only").isFalse();
        var png = write("png");
        assertThat(Header.meta(png)).as("png").isFalse();
        var tiffPayload = Arrays.copyOfRange(exif(ByteOrder.BIG_ENDIAN, 6, "B", null), 10, exif(ByteOrder.BIG_ENDIAN, 6, "B", null).length);
        var chunk = ByteBuffer.allocate(12 + tiffPayload.length).putInt(tiffPayload.length).put("eXIf".getBytes(StandardCharsets.US_ASCII))
                .put(tiffPayload).putInt(0).array(); // the CRC is not checked
        var out = new ByteArrayOutputStream();
        out.write(png, 0, 33); // signature + IHDR
        out.writeBytes(chunk);
        out.write(png, 33, png.length - 33);
        var pngExif = out.toByteArray();
        assertThat(Header.meta(pngExif)).as("png + eXIf").isTrue();
        assertThat(Header.parse(MemorySegment.ofArray(pngExif)).exif().orientation()).isEqualTo(6);
        assertThat(Header.meta(write("tiff"))).as("tiff: its IFDs").isTrue();
        assertThat(Header.meta(write("gif"))).isFalse();
        assertThat(Header.meta(Arrays.copyOf(plain, 3))).as("not parsed: unknown").isNull();
        int sos = sos(plain);
        var out2 = new ByteArrayOutputStream();
        out2.write(plain, 0, sos);
        out2.writeBytes(exif(ByteOrder.LITTLE_ENDIAN, 1, "A", null));
        out2.write(plain, sos, plain.length - sos);
        var late = out2.toByteArray();
        assertThat(Header.meta(late)).as("APP1 between SOF and SOS").isTrue();
        assertThat(Header.meta(Arrays.copyOf(late, sos))).as("cut between SOF and that APP1: unknown, not none").isNull();
        assertThat(Header.parse(MemorySegment.ofArray(Arrays.copyOf(late, sos)))).as("the size is known all the same")
                .extracting(Header::width, Header::height).containsExactly(64, 48);
    }

    /** The offset of a JPEG's SOS marker. */
    private static int sos(byte[] jpeg) {
        int i = 2;
        while ((jpeg[i + 1] & 255) != 0xDA) i += 2 + ((jpeg[i + 2] & 255) << 8 | jpeg[i + 3] & 255);
        return i;
    }

    private static byte[] write(String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }

    private static byte[] jpeg(int w, int h) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    /** Inserts APPn segments right after SOI, in order. */
    private static byte[] withSegments(byte[] jpeg, byte[]... segments) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        for (var s : segments) out.writeBytes(s);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** APP1 "Exif": IFD0 with Orientation, Artist and the Exif-IFD pointer; the Exif IFD with DateTimeOriginal. */
    private static byte[] exif(ByteOrder order, int orientation, String artist, String date) {
        var b = ByteBuffer.allocate(512).order(order);
        b.put(order == ByteOrder.BIG_ENDIAN ? new byte[] {'M', 'M'} : new byte[] {'I', 'I'}).putShort((short) 42).putInt(8);
        byte[] a = artist == null ? new byte[0] : (artist + "\0").getBytes(StandardCharsets.US_ASCII);
        byte[] d = date == null ? new byte[0] : (date + "\0").getBytes(StandardCharsets.US_ASCII);
        int ifd0 = 8, entries = 3, sub = ifd0 + 2 + 12 * entries + 4, artistAt = sub + 2 + 12 + 4, dateAt = artistAt + a.length;
        b.putShort((short) entries);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        b.putShort((short) 0x013B).putShort((short) 2).putInt(a.length).putInt(artistAt);
        b.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(sub);
        b.putInt(0);
        b.putShort((short) 1).putShort((short) 0x9003).putShort((short) 2).putInt(d.length).putInt(dateAt).putInt(0);
        b.put(a).put(d);
        return app(0xE1, "Exif\0\0".getBytes(StandardCharsets.US_ASCII), Arrays.copyOf(b.array(), b.position()));
    }

    private static byte[] xmp(int size) {
        var body = new byte[Math.min(size, 65_000)];
        Arrays.fill(body, (byte) ' ');
        var out = new ByteArrayOutputStream();
        for (int left = size; left > 0; left -= body.length) out.writeBytes(app(0xE1, "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII), body));
        return out.toByteArray();
    }

    private static byte[] app(int marker, byte[] id, byte[] body) {
        int len = 2 + id.length + body.length;
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) marker, (byte) (len >> 8), (byte) len});
        out.writeBytes(id);
        out.writeBytes(body);
        return out.toByteArray();
    }
}
