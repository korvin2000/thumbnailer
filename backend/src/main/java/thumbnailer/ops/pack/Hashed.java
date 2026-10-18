package thumbnailer.ops.pack;

import com.dynatrace.hash4j.hashing.HashStream64;
import com.dynatrace.hash4j.hashing.Hashing;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Hashes ({@link thumbnailer.ops.Hashes}' wyhash) and counts every byte read through it: the sink that copies and verifies at once. */
final class Hashed extends FilterInputStream {

    private final HashStream64 hash = Hashing.wyhashFinal4().hashStream();
    private long count;

    Hashed(InputStream in) { super(in); }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            hash.putByte((byte) b);
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) {
            hash.putBytes(b, off, n);
            count += n;
        }
        return n;
    }

    /** Read, never skipped: every byte counts. */
    @Override
    public long skip(long n) throws IOException {
        var buf = new byte[(int) Math.min(n, 8192)];
        long done = 0;
        for (int k; done < n && (k = read(buf, 0, (int) Math.min(buf.length, n - done))) > 0; ) done += k;
        return done;
    }

    /** The rest of the stream into {@code out}. */
    long copyTo(OutputStream out) throws IOException {
        var buf = new byte[64 << 10];
        long done = 0;
        for (int n; (n = read(buf, 0, buf.length)) > 0; done += n) out.write(buf, 0, n);
        return done;
    }

    long hash() { return hash.getAsLong(); }

    /** Fails unless exactly the {@code size} bytes the listing promised came through (negative: unknown, e.g. a decrypted size). */
    void expect(String name, long size) throws IOException {
        if (size >= 0 && count != size) throw new IOException(name + ": " + count + " bytes, listed " + size);
    }
}
