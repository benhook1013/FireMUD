package net.firedevops.firemud.common.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a local workload Pod UID from its fixed kubelet-projected downwardAPI volume. Filesystem
 * checks constrain the expected projection shape; platform admission must establish its kubelet and
 * read-only-mount provenance.
 */
public final class ProtectedPodUidProjection {
  private static final Pattern UID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern ATOMIC_WRITER_DIRECTORY =
      Pattern.compile("\\.\\.[0-9]{4}_[0-9]{2}_[0-9]{2}_[0-9]{2}_[0-9]{2}_[0-9]{2}(?:\\.[0-9]+)?");
  private static final int UID_LENGTH = 36;
  private static final Set<PosixFilePermission> WRITE_PERMISSIONS =
      Set.of(
          PosixFilePermission.OWNER_WRITE,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.OTHERS_WRITE);

  private ProtectedPodUidProjection() {}

  /** Reads the UID only from {@code /var/run/secrets/firemud/pod-identity/uid}. */
  public static String read() {
    return readProjection(protectedProjectionRoot(), 0L, false);
  }

  /** Package-private fixture seam; production callers cannot choose a path or owner. */
  static String readForTesting(Path projectionRoot, long expectedOwnerUid) {
    return readProjection(projectionRoot, expectedOwnerUid, true);
  }

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification = "The protected local Pod UID projection has one fixed production path.")
  private static Path protectedProjectionRoot() {
    return Path.of("/var/run/secrets/firemud/pod-identity");
  }

  @SuppressFBWarnings(
      value = "DMI_HARDCODED_ABSOLUTE_FILENAME",
      justification =
          "This is the canonical path of the fixed /var/run projection when /var/run aliases /run.")
  private static Path canonicalProjectionRoot() {
    return Path.of("/run/secrets/firemud/pod-identity");
  }

  private static String readProjection(
      Path projectionRoot, long expectedOwnerUid, boolean fixtureRoot) {
    try {
      if (projectionRoot == null
          || !projectionRoot.isAbsolute()
          || expectedOwnerUid < 0
          || !projectionRoot.equals(projectionRoot.normalize())) {
        throw unavailable();
      }
      Path root = projectionRoot;
      DirectorySnapshot rootSnapshotBefore =
          verifyRootDirectory(root, expectedOwnerUid, fixtureRoot);

      Path uidLink = root.resolve("uid");
      Path dataLink = root.resolve("..data");
      BasicFileAttributes uidLinkBefore = readAttributes(uidLink, false);
      BasicFileAttributes dataLinkBefore = readAttributes(dataLink, false);
      Path uidLinkTarget = Files.readSymbolicLink(uidLink);
      Path dataLinkTarget = Files.readSymbolicLink(dataLink);
      if (!uidLinkBefore.isSymbolicLink()
          || !dataLinkBefore.isSymbolicLink()
          || !Path.of("..data", "uid").equals(uidLinkTarget)
          || dataLinkTarget.getNameCount() != 1
          || !ATOMIC_WRITER_DIRECTORY.matcher(dataLinkTarget.toString()).matches()) {
        throw unavailable();
      }
      verifyOwner(uidLink, expectedOwnerUid);
      verifyOwner(dataLink, expectedOwnerUid);

      Path targetDirectory = root.resolve(dataLinkTarget).normalize();
      if (!root.equals(targetDirectory.getParent())) {
        throw unavailable();
      }
      DirectorySnapshot targetDirectoryBefore = verifyDirectory(targetDirectory, expectedOwnerUid);
      Path uidFile = targetDirectory.resolve("uid");
      FileSnapshot uidBefore = readUidFileSnapshot(uidFile, expectedOwnerUid);
      String uid = readUid(uidFile, uidBefore.attributes());
      if (!isCanonicalUid(uid)) {
        throw unavailable();
      }

      FileSnapshot uidAfter = readUidFileSnapshot(uidFile, expectedOwnerUid);
      BasicFileAttributes uidLinkAfter = readAttributes(uidLink, false);
      BasicFileAttributes dataLinkAfter = readAttributes(dataLink, false);
      Path uidLinkTargetAfter = Files.readSymbolicLink(uidLink);
      Path dataLinkTargetAfter = Files.readSymbolicLink(dataLink);
      if (!sameAttributes(uidLinkBefore, uidLinkAfter)
          || !sameAttributes(dataLinkBefore, dataLinkAfter)
          || !uidLinkTarget.equals(uidLinkTargetAfter)
          || !dataLinkTarget.equals(dataLinkTargetAfter)
          || !uidBefore.sameAs(uidAfter)) {
        throw unavailable();
      }
      DirectorySnapshot rootSnapshotAfter =
          verifyRootDirectory(root, expectedOwnerUid, fixtureRoot);
      DirectorySnapshot targetDirectoryAfter = verifyDirectory(targetDirectory, expectedOwnerUid);
      if (!rootSnapshotBefore.sameAs(rootSnapshotAfter)
          || !targetDirectoryBefore.sameAs(targetDirectoryAfter)) {
        throw unavailable();
      }
      verifyOwner(uidLink, expectedOwnerUid);
      verifyOwner(dataLink, expectedOwnerUid);
      return uid;
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof ProtectedPodUidUnavailableException unavailable) {
        throw unavailable;
      }
      throw unavailable();
    }
  }

  private static DirectorySnapshot verifyRootDirectory(
      Path root, long expectedOwnerUid, boolean fixtureRoot) throws IOException {
    Path realRoot = root.toRealPath();
    boolean canonicalProductionAlias = !fixtureRoot && canonicalProjectionRoot().equals(realRoot);
    if (Files.isSymbolicLink(root) || (!root.equals(realRoot) && !canonicalProductionAlias)) {
      throw unavailable();
    }
    return verifyDirectory(root, expectedOwnerUid);
  }

  private static DirectorySnapshot verifyDirectory(Path directory, long expectedOwnerUid)
      throws IOException {
    BasicFileAttributes attributes = readAttributes(directory, true);
    Set<PosixFilePermission> mode =
        Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
    long ownerUid = owner(directory);
    if (!attributes.isDirectory() || writableByUntrusted(mode) || ownerUid != expectedOwnerUid) {
      throw unavailable();
    }
    return new DirectorySnapshot(attributes, mode, ownerUid);
  }

  private static FileSnapshot readUidFileSnapshot(Path file, long expectedOwnerUid)
      throws IOException {
    BasicFileAttributes attributes = readAttributes(file, true);
    Set<PosixFilePermission> mode = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
    long ownerUid = owner(file);
    if (!attributes.isRegularFile()
        || attributes.size() != UID_LENGTH
        || writable(mode)
        || !mode.contains(PosixFilePermission.OWNER_READ)
        || ownerUid != expectedOwnerUid) {
      throw unavailable();
    }
    return new FileSnapshot(attributes, mode, ownerUid);
  }

  private static String readUid(Path file, BasicFileAttributes before) throws IOException {
    byte[] bytes = new byte[UID_LENGTH];
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
    BasicFileAttributes after = readAttributes(file, true);
    if (!sameAttributes(before, after)) {
      throw unavailable();
    }
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  private static BasicFileAttributes readAttributes(Path path, boolean requireRegularOrDirectory)
      throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (requireRegularOrDirectory && !(attributes.isRegularFile() || attributes.isDirectory())) {
      throw unavailable();
    }
    return attributes;
  }

  private static long owner(Path path) throws IOException {
    Object value = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    if (!(value instanceof Number uid)) {
      throw unavailable();
    }
    return uid.longValue();
  }

  private static void verifyOwner(Path path, long expectedOwnerUid) throws IOException {
    if (owner(path) != expectedOwnerUid) {
      throw unavailable();
    }
  }

  private static boolean writable(Set<PosixFilePermission> mode) {
    return mode.stream().anyMatch(WRITE_PERMISSIONS::contains);
  }

  private static boolean writableByUntrusted(Set<PosixFilePermission> mode) {
    return mode.contains(PosixFilePermission.GROUP_WRITE)
        || mode.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private static boolean sameAttributes(BasicFileAttributes left, BasicFileAttributes right) {
    return left.isRegularFile() == right.isRegularFile()
        && left.isDirectory() == right.isDirectory()
        && left.isSymbolicLink() == right.isSymbolicLink()
        && left.size() == right.size()
        && Objects.equals(left.fileKey(), right.fileKey())
        && left.creationTime().equals(right.creationTime())
        && left.lastModifiedTime().equals(right.lastModifiedTime());
  }

  private static boolean isCanonicalUid(String uid) {
    return UID.matcher(uid).matches();
  }

  private static ProtectedPodUidUnavailableException unavailable() {
    return new ProtectedPodUidUnavailableException();
  }

  /** Redacted failure for an unavailable or unsafe projected UID. */
  public static final class ProtectedPodUidUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private ProtectedPodUidUnavailableException() {
      super("Protected Pod UID projection is unavailable");
    }
  }

  private record DirectorySnapshot(
      BasicFileAttributes attributes, Set<PosixFilePermission> mode, long ownerUid) {
    private DirectorySnapshot {
      Objects.requireNonNull(attributes);
      mode = Set.copyOf(mode);
    }

    private boolean sameAs(DirectorySnapshot other) {
      return sameAttributes(attributes, other.attributes)
          && mode.equals(other.mode)
          && ownerUid == other.ownerUid;
    }
  }

  private record FileSnapshot(
      BasicFileAttributes attributes, Set<PosixFilePermission> mode, long ownerUid) {
    private FileSnapshot {
      Objects.requireNonNull(attributes);
      mode = Set.copyOf(mode);
    }

    private boolean sameAs(FileSnapshot other) {
      return sameAttributes(attributes, other.attributes)
          && mode.equals(other.mode)
          && ownerUid == other.ownerUid;
    }
  }
}
