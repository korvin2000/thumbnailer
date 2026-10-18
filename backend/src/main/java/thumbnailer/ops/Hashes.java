package thumbnailer.ops;

import com.dynatrace.hash4j.hashing.HashStream64;
import com.dynatrace.hash4j.hashing.Hashing;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** The content hash operations verify copies with: 64-bit wyhash (final 4) of every byte, streamed. */
public final class Hashes {

    private Hashes() {}

    public static long of(InputStream in) throws IOException { return copy(in, OutputStream.nullOutputStream()); }

    public static long of(byte[] bytes) { return Hashing.wyhashFinal4().hashStream().putBytes(bytes).getAsLong(); }

    /** Copies {@code in} to {@code out} and returns the hash of what passed. */
    public static long copy(InputStream in, OutputStream out) throws IOException {
        var h = Hashing.wyhashFinal4().hashStream();
        var buffer = new byte[64 << 10];
        for (int n; (n = in.read(buffer)) > 0; ) {
            h.putBytes(buffer, 0, n);
            out.write(buffer, 0, n);
        }
        return h.getAsLong();
    }

    /** Hashes every byte read through it ({@code skip} reads too). */
    static final class In extends FilterInputStream {
        private final HashStream64 h = Hashing.wyhashFinal4().hashStream();

        In(InputStream in) { super(in); }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) h.putByte((byte) b);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) h.putBytes(b, off, n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            var buf = new byte[8192];
            long done = 0;
            for (int k; done < n && (k = read(buf, 0, (int) Math.min(buf.length, n - done))) > 0; ) done += k;
            return done;
        }

        long hash() { return h.getAsLong(); }
    }

    /** Hashes every byte written through it. */
    static final class Out extends FilterOutputStream {
        private final HashStream64 h = Hashing.wyhashFinal4().hashStream();

        Out(OutputStream out) { super(out); }

        @Override
        public void write(int b) throws IOException {
            h.putByte((byte) b);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            h.putBytes(b, off, len);
            out.write(b, off, len);
        }

        long hash() { return h.getAsLong(); }
    }
}
