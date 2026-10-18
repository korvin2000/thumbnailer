package thumbnailer.source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.SevenZipException;

/**
 * A push parser for the tar stream 7-Zip decompresses out of a .tar.gz/.bz2/.xz/.Z (7-Zip-JBinding cannot open the
 * nested tar itself): regular files reach the {@link Visitor} with their data, in archive order, without buffering
 * the archive; hard and symbolic links only by name. Understands ustar prefixes, GNU long names ('L') and pax paths
 * ('x'); directories are skipped.
 */
final class TarStream implements ISequentialOutStream {

    interface Visitor {
        /** A regular file starts; return whether its bytes should follow through {@link #data}. */
        boolean entry(String name, long size, long mtime) throws SevenZipException;

        void data(byte[] b, int off, int len) throws SevenZipException;

        /** The current wanted file ended. */
        void end() throws SevenZipException;

        /** A hard ('1') or symbolic ('2') link: no bytes of its own to read (a listing still has to show it). */
        default void link(String name, long size, long mtime) throws SevenZipException {}
    }

    private final Visitor visitor;
    private final byte[] header = new byte[512];
    private int filled;
    private long remaining, pad;
    private boolean wanted, done;
    private char type;
    private ByteArrayOutputStream meta; // body of an 'L' or 'x' header
    private String nextName;

    TarStream(Visitor visitor) { this.visitor = visitor; }

    @Override
    public int write(byte[] data) throws SevenZipException {
        for (int i = 0, n = data.length; i < n && !done; ) {
            if (remaining > 0) {
                int k = (int) Math.min(remaining, n - i);
                if (meta != null) meta.write(data, i, k);
                else if (wanted) visitor.data(data, i, k);
                i += k;
                if ((remaining -= k) == 0) finish();
            } else if (pad > 0) {
                int k = (int) Math.min(pad, n - i);
                i += k;
                pad -= k;
            } else {
                int k = Math.min(512 - filled, n - i);
                System.arraycopy(data, i, header, filled, k);
                i += k;
                if ((filled += k) == 512) {
                    filled = 0;
                    start();
                }
            }
        }
        return data.length;
    }

    private void start() throws SevenZipException {
        boolean zero = true;
        for (byte b : header) zero &= b == 0;
        if (zero) {
            done = true; // end-of-archive blocks
            return;
        }
        type = (char) header[156];
        long size = (header[124] & 0x80) != 0 ? base256() : octal(124, 12);
        var name = nextName != null ? nextName : (header[345] != 0 ? text(345, 155) + "/" : "") + text(0, 100);
        remaining = size;
        pad = (512 - size % 512) % 512;
        wanted = false;
        meta = null;
        if (type == 'L' || type == 'x') meta = new ByteArrayOutputStream((int) Math.min(size, 1 << 16));
        else {
            nextName = null;
            if (type == '0' || type == 0 || type == '7') wanted = visitor.entry(name, size, octal(136, 12) * 1000);
            else if (type == '1' || type == '2') visitor.link(name, size, octal(136, 12) * 1000);
        }
        if (size == 0) finish();
    }

    private void finish() throws SevenZipException {
        if (meta != null) {
            var s = meta.toString(StandardCharsets.UTF_8);
            if (type == 'L') nextName = s.replace("\0", "");
            else for (var record : s.split("\n")) { // "<len> path=<value>"
                int eq = record.indexOf(" path=");
                if (eq >= 0) nextName = record.substring(eq + 6);
            }
            meta = null;
        } else if (wanted) visitor.end();
        wanted = false;
    }

    private String text(int off, int len) {
        int end = off;
        while (end < off + len && header[end] != 0) end++;
        return new String(header, off, end - off, StandardCharsets.UTF_8);
    }

    private long octal(int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = header[i];
            if (c >= '0' && c <= '7') v = v * 8 + c - '0';
            else if (v > 0 || c != ' ') break;
        }
        return v;
    }

    private long base256() {
        long v = header[124] & 0x7F;
        for (int i = 125; i < 136; i++) v = v << 8 | header[i] & 255;
        return v;
    }
}
