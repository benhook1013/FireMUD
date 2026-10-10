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
import java.security.MessageDigest;
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

  private static final int MAX_FILE_BYTES = AccountPublicJwksCache.MAX_JWKS_BYTES;

  private final GameSessionJwtReadinessLocalIdentityProvider identityProvider;
  private final Path jwksPath;
  private final Path jwksDirectory;
  private final Path dataLink;
  private final long expectedOwnerUid;

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "The Account public JWKS is intentionally fixed to this read-only projected path; alternate paths would permit trust-source substitution.")
  private static Path fixedJwksPath() {
    return Path.of("/var/run/secrets/firemud/jwks/jwks.json");
  }

  public GameSessionJwtReadinessProtectedJwksSource(
      GameSessionJwtReadinessLocalIdentityProvider identityProvider) {
    this(identityProvider, JWKS_PATH, 0L);
  }

  GameSessionJwtReadinessProtectedJwksSource(
      GameSessionJwtReadinessLocalIdentityProvider identityProvider,
      Path jwksPath,
      long expectedOwnerUid) {
    this.identityProvider = Objects.requireNonNull(identityProvider);
    this.jwksPath = Objects.requireNonNull(jwksPath);
    this.jwksDirectory =
        Objects.requireNonNull(jwksPath.getParent(), "The Account JWKS path must have a parent");
    this.dataLink = jwksDirectory.resolve("..data");
    Path fileName = jwksPath.getFileName();
    if (!jwksPath.isAbsolute()
        || !jwksPath.equals(jwksPath.normalize())
        || fileName == null
        || !"jwks.json".equals(fileName.toString())
        || expectedOwnerUid < 0L) {
      throw new IllegalArgumentException("Protected Account JWKS path or owner is invalid");
    }
    this.expectedOwnerUid = expectedOwnerUid;
  }

  @Override
  public PublicJwksSnapshot load() {
    try {
      GameSessionJwtReadinessLocalIdentityProvider.LocalObservation before =
          Objects.requireNonNull(identityProvider.observe());
      byte[] bytes = readProjectedFile();
      try {
        if (!before.wireIdentity().getAccountPublicJwksSha256().equals(sha256(bytes))) {
          throw unavailable();
        }
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

  private byte[] readProjectedFile() throws IOException {
    Path normalizedDirectory = jwksDirectory.toAbsolutePath().normalize();
    if (!normalizedDirectory.equals(jwksDirectory)
        || Files.isSymbolicLink(jwksDirectory)
        || !Files.isSymbolicLink(dataLink)
        || !Files.isSymbolicLink(jwksPath)
        || !Path.of("..data/jwks.json").equals(Files.readSymbolicLink(jwksPath))) {
      throw unavailable();
    }
    Path dataVersion = Files.readSymbolicLink(dataLink);
    if (dataVersion.isAbsolute()
        || dataVersion.getNameCount() != 1
        || !dataVersion.toString().matches("\\.\\.[A-Za-z0-9._-]{1,128}")) {
      throw unavailable();
    }
    Path trustedDirectory = jwksDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
    verifyOwnedProjectionPath(trustedDirectory, true);
    Path dataTarget = dataLink.toRealPath();
    Path dataTargetParent = dataTarget.getParent();
    if (dataTargetParent == null
        || !dataTargetParent.equals(trustedDirectory)
        || !Files.isDirectory(dataTarget, LinkOption.NOFOLLOW_LINKS)) {
      throw unavailable();
    }
    verifyOwnedProjectionPath(dataTarget, true);
    Path file = dataTarget.resolve("jwks.json");
    BasicFileAttributes before =
        Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!before.isRegularFile() || before.size() <= 0L || before.size() > MAX_FILE_BYTES) {
      throw unavailable();
    }
    verifyOwnedProjectionPath(file, false);
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
        || !dataVersion.equals(Files.readSymbolicLink(dataLink))
        || !dataTarget.equals(dataLink.toRealPath())
        || !Path.of("..data/jwks.json").equals(Files.readSymbolicLink(jwksPath))) {
      java.util.Arrays.fill(bytes, (byte) 0);
      throw unavailable();
    }
    verifyOwnedProjectionPath(file, false);
    return bytes;
  }

  private void verifyOwnedProjectionPath(Path path, boolean directory) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if ((directory && !attributes.isDirectory()) || (!directory && !attributes.isRegularFile())) {
      throw unavailable();
    }
    Object owner = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    boolean unsafeWrite =
        mode.contains(PosixFilePermission.GROUP_WRITE)
            || mode.contains(PosixFilePermission.OTHERS_WRITE)
            || (!directory && mode.contains(PosixFilePermission.OWNER_WRITE));
    if (!(owner instanceof Number uid) || uid.longValue() != expectedOwnerUid || unsafeWrite) {
      throw unavailable();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception unavailable) {
      throw new SourceUnavailableException();
    }
  }

  private static SourceUnavailableException unavailable() {
    return new SourceUnavailableException();
  }
}
