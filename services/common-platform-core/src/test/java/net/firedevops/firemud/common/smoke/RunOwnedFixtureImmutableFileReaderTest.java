package net.firedevops.firemud.common.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunOwnedFixtureImmutableFileReaderTest {
  private static final int MAX_BYTES = 32;
  private static final String INVALID_MESSAGE = "fixture file is invalid";

  @TempDir Path temporaryDirectory;

  @Test
  void readsExactBytesFromReadOnlyFileAtLimit() throws Exception {
    byte[] expected = "bounded fixture bytes".getBytes(StandardCharsets.UTF_8);
    Path file = temporaryDirectory.resolve("fixture.json");
    Files.write(file, expected);
    makeReadOnly(file);

    assertThat(read(file, expected.length)).containsExactly(expected);
  }

  @Test
  void refusesRelativeAndEmptyFiles() throws Exception {
    Path empty = temporaryDirectory.resolve("empty.json");
    Files.createFile(empty);
    makeReadOnly(empty);

    assertInvalid(Path.of("relative-fixture.json"), MAX_BYTES);
    assertInvalid(empty, MAX_BYTES);
  }

  @Test
  void refusesFilesOverLimit() throws Exception {
    Path file = temporaryDirectory.resolve("oversized.json");
    Files.write(file, new byte[MAX_BYTES + 1]);
    makeReadOnly(file);

    assertInvalid(file, MAX_BYTES);
  }

  @Test
  void refusesSymbolicLinks() throws Exception {
    Path target = temporaryDirectory.resolve("target.json");
    Path link = temporaryDirectory.resolve("fixture.json");
    Files.writeString(target, "{}", StandardCharsets.UTF_8);
    makeReadOnly(target);
    Files.createSymbolicLink(link, target.getFileName());

    assertInvalid(link, MAX_BYTES);
  }

  @Test
  void refusesWritableFiles() throws Exception {
    Path file = temporaryDirectory.resolve("writable.json");
    Files.writeString(file, "{}", StandardCharsets.UTF_8);

    assertInvalid(file, MAX_BYTES);
  }

  private static byte[] read(Path path, int maxBytes) {
    return RunOwnedFixtureImmutableFileReader.readBoundedImmutableFile(
        path, maxBytes, () -> new IllegalArgumentException(INVALID_MESSAGE));
  }

  private static void assertInvalid(Path path, int maxBytes) {
    assertThatThrownBy(() -> read(path, maxBytes))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(INVALID_MESSAGE);
  }

  private static void makeReadOnly(Path path) throws Exception {
    Files.setPosixFilePermissions(
        path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));
  }
}
