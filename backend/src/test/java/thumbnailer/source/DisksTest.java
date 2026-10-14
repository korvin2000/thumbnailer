package thumbnailer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thumbnailer.source.Disks.Disk;
import thumbnailer.source.Disks.Storage;
import thumbnailer.source.Disks.Use;

class DisksTest {

    @TempDir Path dir;

    @Test
    void anHddLaneLetsOneReaderInAtATimeAndAnSsdLaneNeverWaits() throws Exception {
        var disks = new Disks(1);
        var hdd = disks.lane(new Disk("d", false), Use.SCAN);
        assertThat(disks.lane(new Disk("d", false), Use.SCAN)).isSameAs(hdd);
        assertThat(disks.lane(new Disk("d", false), Use.VIEW)).as("browsing does not queue behind the scan").isNotSameAs(hdd);
        var first = hdd.enter();
        var entered = new CountDownLatch(1);
        var second = Thread.ofVirtual().start(() -> {
            try (var turn = hdd.enter()) {
                entered.countDown();
            } catch (InterruptedIOException e) {
                throw new AssertionError(e);
            }
        });
        assertThat(entered.await(200, TimeUnit.MILLISECONDS)).as("waits while the disk is busy").isFalse();
        first.close();
        first.close(); // handed on and closed again: releases once
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        second.join();

        var ssd = disks.lane(new Disk("s", true), Use.SCAN);
        try (var a = ssd.enter(); var b = ssd.enter()) {
            assertThat(a).isNotNull();
        }
    }

    @Test
    void aScanGivesWayWhileTheUiReadsTheSameHardDisk() throws Exception {
        var disks = new Disks(1);
        var hdd = new Disk("h", false);
        var view = disks.lane(hdd, Use.VIEW);
        var ui = view.enter();
        var scanned = new CountDownLatch(1);
        var scan = Thread.ofVirtual().start(() -> {
            try (var turn = disks.lane(hdd, Use.SCAN).enter()) {
                scanned.countDown();
            } catch (InterruptedIOException e) {
                throw new AssertionError(e);
            }
        });
        assertThat(scanned.await(200, TimeUnit.MILLISECONDS)).as("the UI is reading").isFalse();
        long freed = System.nanoTime();
        ui.close();
        assertThat(scanned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(System.nanoTime() - freed).as("then quiet for a moment").isGreaterThanOrEqualTo(Disks.QUIET - 20_000_000L);
        scan.join();

        var ssd = new Disk("s", true);
        try (var reading = disks.lane(ssd, Use.VIEW).enter(); var scanning = disks.lane(ssd, Use.SCAN).enter()) {
            assertThat(scanning).as("an SSD has no seeks to save").isNotNull();
        }
    }

    @Test
    void aWaiterLeavesOnInterrupt() throws Exception {
        var lane = new Disks(1).lane(new Disk("d", false), Use.VIEW);
        try (var busy = lane.enter()) {
            var failure = new Throwable[1];
            var waiter = Thread.ofVirtual().start(() -> {
                try {
                    lane.enter();
                } catch (InterruptedIOException e) {
                    failure[0] = e;
                }
            });
            Thread.sleep(50);
            waiter.interrupt();
            waiter.join();
            assertThat(failure[0]).isInstanceOf(InterruptedIOException.class);
        }
    }

    @Test
    void detectsAReachableRootAndTheSettingOverridesIt() {
        var disks = new Disks(1);
        var auto = disks.disk(dir, Storage.AUTO);
        assertThat(auto.key()).isNotBlank();
        assertThat(disks.disk(dir, Storage.HDD)).isEqualTo(new Disk(auto.key(), false));
        assertThat(disks.disk(dir, Storage.SSD)).isEqualTo(new Disk(auto.key(), true));
        assertThat(disks.disk(dir.resolve("gone"), null).key()).as("unreachable: unknown, read as HDD").isNotBlank();
    }
}
