package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceUnavailableException;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.TrustedPublicJwksSource;

/**
 * Reads only Account's fixed projected public JWKS; it never discovers keys or uses classpath data.
 */
public final class GameSessionJwtReadinessProtectedJwksSource implements TrustedPublicJwksSource {
  public static final Path JWKS_PATH = fixedJwksPath();

  private static final Path JWKS_DIRECTORY =
      Objects.requireNonNull(
          JWKS_PATH.getParent(), "The fixed Account JWKS path must have a parent");
  private static final Path DATA_LINK = JWKS_DIRECTORY.resolve("..data");
  private static final int MAX_FILE_BYTES = AccountPublicJwksCache.MAX_JWKS_BYTES;

  private final GameSessionJwtReadinessLocalIdentityProvider identityProvider;

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "The Account public JWKS is intentionally fixed to this read-only projected path; alternate paths would permit trust-source substitution.")
  private static Path fixedJwksPath() {
    return Path.of("/var/run/secrets/firemud/jwks/jwks.json");
  }

  public GameSessionJwtReadinessProtectedJwksSource(
      GameSessionJwtReadinessLocalIdentityProvider identityProvider) {
    this.identityProvider = Objects.requireNonNull(identityProvider);
  }

  @Override
  public PublicJwksSnapshot load() {
    try {
      GameSessionJwtReadinessLocalIdentityProvider.LocalObservation before =
          Objects.requireNonNull(identityProvider.observe());
      byte[] bytes = readProjectedFile();
      try {
        GameSessionJwtReadinessLocalIdentityProvider.LocalObservation after =
            Objects.requireNonNull(identityProvider.observe());
        if (!before.equals(after)) {
          throw unavailable();
        }
        return new PublicJwksSnapshot(before.jwksSourceIdentity(), bytes);
      } finally {
        java.util.Arrays.fill(bytes, (byte) 0);
      }
    } catch (SourceUnavailableException unavailable) {
      throw unavailable;
    } catch (RuntimeException | IOException unavailable) {
      throw unavailable();
    }
  }

  private static byte[] readProjectedFile() throws IOException {
    Path normalizedDirectory = JWKS_DIRECTORY.toAbsolutePath().normalize();
    if (!normalizedDirectory.equals(JWKS_DIRECTORY)
        || Files.isSymbolicLink(JWKS_DIRECTORY)
        || !Files.isSymbolicLink(DATA_LINK)
        || !Files.isSymbolicLink(JWKS_PATH)
        || !Path.of("..data/jwks.json").equals(Files.readSymbolicLink(JWKS_PATH))) {
      throw unavailable();
    }
    Path dataVersion = Files.readSymbolicLink(DATA_LINK);
    if (dataVersion.isAbsolute()
        || dataVersion.getNameCount() != 1
        || !dataVersion.toString().matches("\\.\\.[A-Za-z0-9._-]{1,128}")) {
      throw unavailable();
    }
    Path trustedDirectory = JWKS_DIRECTORY.toRealPath(LinkOption.NOFOLLOW_LINKS);
    verifyRootOwnedReadOnly(trustedDirectory, true);
    Path dataTarget = DATA_LINK.toRealPath();
    Path dataTargetParent = dataTarget.getParent();
    if (dataTargetParent == null
        || !dataTargetParent.equals(trustedDirectory)
        || !Files.isDirectory(dataTarget, LinkOption.NOFOLLOW_LINKS)) {
      throw unavailable();
    }
    verifyRootOwnedReadOnly(dataTarget, true);
    Path file = dataTarget.resolve("jwks.json");
    BasicFileAttributes before =
        Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() <= 0L || before.size() > MAX_FILE_BYTES) {
      throw unavailable();
    }
    verifyRootOwnedReadOnly(file, false);
    byte[] bytes = new byte[(int) before.size()];
    try (SeekableByteChannel channel =
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        if (channel.read(buffer) < 0) {
          throw unavailable();
        }
      }
      if (channel.read(ByteBuffer.allocate(1)) != -1) {
        throw unavailable();
      }
    }
    BasicFileAttributes after =
        Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!Objects.equals(before.fileKey(), after.fileKey())
        || before.size() != after.size()
        || !before.lastModifiedTime().equals(after.lastModifiedTime())
        || !dataVersion.equals(Files.readSymbolicLink(DATA_LINK))
        || !dataTarget.equals(DATA_LINK.toRealPath())
        || !Path.of("..data/jwks.json").equals(Files.readSymbolicLink(JWKS_PATH))) {
      java.util.Arrays.fill(bytes, (byte) 0);
      throw unavailable();
    }
    verifyRootOwnedReadOnly(file, false);
    return bytes;
  }

  private static void verifyRootOwnedReadOnly(Path path, boolean directory) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if ((directory && !attributes.isDirectory()) || (!directory && !attributes.isRegularFile())) {
      throw unavailable();
    }
    Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    if (!(owner instanceof Number uid)
        || uid.longValue() != 0L
        || mode.contains(PosixFilePermission.OWNER_WRITE)
        || mode.contains(PosixFilePermission.GROUP_WRITE)
        || mode.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw unavailable();
    }
  }

  private static SourceUnavailableException unavailable() {
    return new SourceUnavailableException();
  }
}
