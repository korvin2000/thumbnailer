package thumbnailer.ops;

import com.dynatrace.hash4j.hashing.Hashing;
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
}
