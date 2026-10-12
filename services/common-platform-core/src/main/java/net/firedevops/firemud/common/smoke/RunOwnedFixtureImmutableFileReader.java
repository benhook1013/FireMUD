package net.firedevops.firemud.common.smoke;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Bounded reader for immutable, run-owned fixture files. */
public final class RunOwnedFixtureImmutableFileReader {
  private RunOwnedFixtureImmutableFileReader() {}

  /** Reads exact bytes from an absolute, read-only regular file that remains unchanged. */
  public static byte[] readBoundedImmutableFile(
      Path path, int maxBytes, Supplier<? extends RuntimeException> invalidFile) {
    if (path == null || maxBytes <= 0 || invalidFile == null || !path.isAbsolute()) {
      throw invalidFile == null
          ? new IllegalArgumentException("Invalid file reader arguments")
          : invalidFile.get();
    }

    try {
      BasicFileAttributes before =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!before.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() <= 0L
          || before.size() > maxBytes
          || !isReadOnly(path)) {
        throw invalidFile.get();
      }

      ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) before.size());
      try (SeekableByteChannel channel =
              Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
          InputStream input = Channels.newInputStream(channel)) {
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
          if (read == 0) {
            continue;
          }
          if ((long) bytes.size() + read > maxBytes) {
            throw invalidFile.get();
          }
          bytes.write(buffer, 0, read);
        }
      }

      BasicFileAttributes after =
          Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!after.isRegularFile()
          || Files.isSymbolicLink(path)
          || before.size() != after.size()
          || bytes.size() != before.size()
          || !before.lastModifiedTime().equals(after.lastModifiedTime())
          || !sameFileKey(before.fileKey(), after.fileKey())
          || !isReadOnly(path)) {
        throw invalidFile.get();
      }
      return bytes.toByteArray();
    } catch (IOException | SecurityException exception) {
      throw invalidFile.get();
    }
  }

  private static boolean isReadOnly(Path path) throws IOException {
    try {
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      return permissions.contains(PosixFilePermission.OWNER_READ)
          && !permissions.contains(PosixFilePermission.OWNER_WRITE)
          && !permissions.contains(PosixFilePermission.GROUP_WRITE)
          && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
    } catch (UnsupportedOperationException exception) {
      Object readOnly = Files.getAttribute(path, "dos:readonly", LinkOption.NOFOLLOW_LINKS);
      return Boolean.TRUE.equals(readOnly) && !Files.isWritable(path);
    }
  }

  private static boolean sameFileKey(Object before, Object after) {
    return Objects.equals(before, after);
  }
}
