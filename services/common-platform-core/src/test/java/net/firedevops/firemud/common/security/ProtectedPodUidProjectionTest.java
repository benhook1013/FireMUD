package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtectedPodUidProjectionTest {
  private static final String POD_UID = "33333333-3333-4333-8333-333333333333";
  private static final String DATA_DIRECTORY = "..2026_10_09_12_34_56.123456789";

  @TempDir Path temporaryDirectory;

  @Test
  void readsCanonicalUidFromAtomicWriterProjectionFixture() throws IOException {
    Projection projection = createProjection(POD_UID);

    assertThat(ProtectedPodUidProjection.readForTesting(projection.root(), projection.ownerUid()))
        .isEqualTo(POD_UID);
    assertThat(Files.getPosixFilePermissions(projection.root()))
        .contains(PosixFilePermission.OWNER_WRITE)
        .doesNotContain(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);
    assertThat(Files.getPosixFilePermissions(projection.uidFile()))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);
  }

  @Test
  void acceptsCanonicalUidWithLeadingZeroesInFirstGroup() throws IOException {
    String uid = "00000000-0000-4000-8000-000000000001";
    Projection projection = createProjection(uid);

    assertThat(ProtectedPodUidProjection.readForTesting(projection.root(), projection.ownerUid()))
        .isEqualTo(uid);
  }

  @Test
  void rejectsMalformedUid() throws IOException {
    Projection projection = createProjection(POD_UID + "\n");

    assertUnavailable(projection);
  }

  @Test
  void rejectsNilUid() throws IOException {
    Projection projection = createProjection("00000000-0000-0000-0000-000000000000");

    assertUnavailable(projection);
  }

  @Test
  void rejectsWritableUidFile() throws IOException {
    Projection projection = createProjection(POD_UID);
    Files.setPosixFilePermissions(
        projection.uidFile(),
        permissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));

    assertUnavailable(projection);
  }

  @Test
  void rejectsGroupWritableProjectionDirectory() throws IOException {
    Projection projection = createProjection(POD_UID);
    Files.setPosixFilePermissions(
        projection.root(),
        permissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));

    assertUnavailable(projection);
  }

  @Test
  void rejectsWritableAtomicWriterTargetDirectory() throws IOException {
    Projection projection = createProjection(POD_UID);
    Files.setPosixFilePermissions(
        projection.targetDirectory(),
        permissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE));

    assertUnavailable(projection);
  }

  @Test
  void rejectsUidLinkThatBypassesAtomicWriterDataLink() throws IOException {
    Projection projection = createProjection(POD_UID);
    Files.delete(projection.root().resolve("uid"));
    Files.createSymbolicLink(projection.root().resolve("uid"), Path.of(DATA_DIRECTORY, "uid"));

    assertUnavailable(projection);
  }

  @Test
  void rejectsNonCanonicalAtomicWriterDataLink() throws IOException {
    Projection projection = createProjection(POD_UID);
    Files.delete(projection.root().resolve("..data"));
    Files.createSymbolicLink(projection.root().resolve("..data"), Path.of(".."));

    assertUnavailable(projection);
  }

  @Test
  void rejectsWrongOwner() throws IOException {
    Projection projection = createProjection(POD_UID);

    assertThatThrownBy(
            () ->
                ProtectedPodUidProjection.readForTesting(
                    projection.root(), projection.ownerUid() + 1))
        .isInstanceOf(ProtectedPodUidProjection.ProtectedPodUidUnavailableException.class);
  }

  private Projection createProjection(String uidContents) throws IOException {
    Path root = Files.createDirectory(temporaryDirectory.resolve("pod-identity"));
    Path targetDirectory = Files.createDirectory(root.resolve(DATA_DIRECTORY));
    Path uidFile = Files.writeString(targetDirectory.resolve("uid"), uidContents);
    Files.setPosixFilePermissions(
        root,
        permissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));
    Files.setPosixFilePermissions(
        targetDirectory,
        permissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));
    Files.setPosixFilePermissions(
        uidFile,
        permissions(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ));
    Files.createSymbolicLink(root.resolve("..data"), Path.of(DATA_DIRECTORY));
    Files.createSymbolicLink(root.resolve("uid"), Path.of("..data", "uid"));
    Object owner = Files.getAttribute(root, "unix:uid");
    if (!(owner instanceof Number uid)) {
      throw new IOException("Temporary projection has no Unix owner");
    }
    return new Projection(root, targetDirectory, uidFile, uid.longValue());
  }

  private void assertUnavailable(Projection projection) {
    assertThatThrownBy(
            () ->
                ProtectedPodUidProjection.readForTesting(projection.root(), projection.ownerUid()))
        .isInstanceOf(ProtectedPodUidProjection.ProtectedPodUidUnavailableException.class);
  }

  private static Set<PosixFilePermission> permissions(PosixFilePermission... values) {
    return EnumSet.copyOf(java.util.List.of(values));
  }

  private record Projection(Path root, Path targetDirectory, Path uidFile, long ownerUid) {}
}
