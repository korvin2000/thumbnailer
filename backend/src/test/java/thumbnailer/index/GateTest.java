package thumbnailer.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import thumbnailer.index.Gate.Activity;

class GateTest {

    private final Gate gate = new Gate();

    @Test
    void oneActivityAtATimeAndASettingsHoldHandsOverToARestart() {
        assertThat(gate.current()).isNull();
        var scan = gate.tryEnter(Activity.SCAN).orElseThrow();
        assertThat(gate.tryEnter(Activity.OP)).isEmpty();
        assertThat(gate.blocker()).isEqualTo("A scan is running");
        scan.close();
        scan.close(); // idempotent: never releases what another activity holds
        var op = gate.tryEnter(Activity.OP).orElseThrow();
        scan.close();
        assertThat(gate.current()).isEqualTo(Activity.OP);
        assertThat(gate.blocker()).isEqualTo("An operation is running");
        op.close();

        var settings = gate.tryEnter(Activity.SETTINGS).orElseThrow();
        assertThat(settings.restart()).isTrue();
        settings.close();
        assertThat(gate.current()).as("handed over to the restart").isEqualTo(Activity.RESTART);
        assertThat(settings.restart()).isFalse();
    }

    @Test
    void aRestartIsNeverReleased() {
        gate.tryEnter(Activity.RESTART).orElseThrow().close();
        assertThat(gate.current()).as("never released").isEqualTo(Activity.RESTART);
        assertThat(gate.tryEnter(Activity.SETTINGS)).isEmpty();
        assertThat(gate.blocker()).isEqualTo("A restart is pending");
    }
}
