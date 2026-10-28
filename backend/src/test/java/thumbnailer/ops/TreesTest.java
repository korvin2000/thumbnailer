package thumbnailer.ops;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link Trees#move} never lands on an existing target, whatever case spells it, while a case-only rename still goes through. */
class TreesTest {

    @TempDir Path dir;

    @Test
    void aMoveNeverReplacesATargetSpelledInAnotherCase() throws IOException {
        var x = Files.write(dir.resolve("x.txt"), new byte[] {1});
        var existing = Files.write(dir.resolve("Existing.txt"), new byte[] {2});
        assumeTrue(Files.exists(dir.resolve("existing.txt")), "needs a case-insensitive volume");
        assertThat(Trees.present(dir.resolve("existing.txt"))).as("not under this spelling, yet taken").isFalse();
        assertThatThrownBy(() -> Trees.move(x, dir.resolve("existing.txt"))).isInstanceOf(FileAlreadyExistsException.class);
        var d = Files.createDirectories(dir.resolve("d"));
        Files.write(d.resolve("a.jpg"), new byte[] {3});
        var album = Files.createDirectories(dir.resolve("Album"));
        Files.write(album.resolve("keep.jpg"), new byte[] {4});
        assertThatThrownBy(() -> Trees.move(d, dir.resolve("album"))).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(existing).hasBinaryContent(new byte[] {2});
        assertThat(x).hasBinaryContent(new byte[] {1});
        assertThat(album.resolve("keep.jpg")).hasBinaryContent(new byte[] {4});
        assertThat(d.resolve("a.jpg")).hasBinaryContent(new byte[] {3});
    }

    @Test
    void aHardLinkAtTheTargetIsNeverTakenForTheMove() throws IOException {
        assumeTrue(File.separatorChar == '\\', "Windows: Files.move of two names of one file is a no-op there");
        var x = Files.write(dir.resolve("x.txt"), new byte[] {1});
        try {
            Files.createLink(dir.resolve("y.txt"), x);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "needs hard links");
        }
        assertThatThrownBy(() -> Trees.move(x, dir.resolve("y.txt"))).isInstanceOf(IOException.class).hasMessageContaining("hard link");
        assertThat(x).hasBinaryContent(new byte[] {1});
        assertThat(dir.resolve("y.txt")).hasBinaryContent(new byte[] {1});
    }

    @Test
    void aSymbolicLinkAtTheTargetIsAnOccupantWhateverItPointsAt() throws IOException {
        var x = Files.write(dir.resolve("x.txt"), new byte[] {1});
        try {
            Files.createSymbolicLink(dir.resolve("y.txt"), x);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "needs the privilege to create symbolic links");
        }
        assertThatThrownBy(() -> Trees.move(x, dir.resolve("y.txt"))).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(x).hasBinaryContent(new byte[] {1});
        assertThat(Files.isSymbolicLink(dir.resolve("y.txt"))).isTrue();
    }

    @Test
    void aCaseOnlyRenameOfAFileOrAFolderGoesThrough() throws IOException {
        Files.write(dir.resolve("Rn.zip"), new byte[] {5});
        Files.write(Files.createDirectories(dir.resolve("Dir")).resolve("a.jpg"), new byte[] {6});
        Trees.move(dir.resolve("Rn.zip"), dir.resolve("rn.zip"));
        Trees.move(dir.resolve("Dir"), dir.resolve("dir"));
        assertThat(Trees.present(dir.resolve("rn.zip"))).isTrue();
        assertThat(Trees.present(dir.resolve("Rn.zip"))).isFalse();
        assertThat(Trees.present(dir.resolve("dir"))).isTrue();
        assertThat(Trees.present(dir.resolve("Dir"))).isFalse();
        assertThat(dir.resolve("rn.zip")).hasBinaryContent(new byte[] {5});
        assertThat(dir.resolve("dir/a.jpg")).hasBinaryContent(new byte[] {6});
        assertThat(dir.resolve("rn.zip.part")).doesNotExist();
        assertThat(dir.resolve("dir.part")).doesNotExist();
    }

    /** The primitive {@link Trees#move} stands on, checked where it runs: MoveFileEx with and without MOVEFILE_REPLACE_EXISTING. */
    @Test
    void onWindowsAPlainMoveRefusesAnExistingTargetWhileAnAtomicOneReplacesIt() throws IOException {
        assumeTrue(File.separatorChar == '\\', "Windows");
        var a = Files.write(dir.resolve("a.txt"), new byte[] {1});
        var b = Files.write(dir.resolve("B.txt"), new byte[] {2});
        assertThatThrownBy(() -> Files.move(a, dir.resolve("b.txt"))).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(b).hasBinaryContent(new byte[] {2});
        Files.move(a, dir.resolve("b.txt"), ATOMIC_MOVE);
        assertThat(dir.resolve("b.txt")).as("replaced: why Trees.move never uses it here").hasBinaryContent(new byte[] {1});
    }
}
