package thumbnailer.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;
import org.junit.jupiter.api.Test;

class LimiterTest {

    @Test
    void resizeGrowsAndShrinksAroundHeldPermits() throws InterruptedIOException {
        var limiter = new Limiter();
        limiter.enter(3);
        assertThat(limiter.availablePermits()).isEqualTo(2);
        limiter.resize(3);
        assertThat(limiter.availablePermits()).as("same size: unchanged").isEqualTo(2);
        limiter.resize(1);
        assertThat(limiter.availablePermits()).as("the holder still counts").isZero();
        limiter.release();
        assertThat(limiter.availablePermits()).isEqualTo(1);
        limiter.resize(4);
        assertThat(limiter.availablePermits()).isEqualTo(4);
    }
}
