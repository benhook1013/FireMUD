package net.firedevops.firemud.accountservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class AccountJwtReadinessTrustBindingTest {
  private static final long NOW = 1_800_000_000L;

  @TempDir Path tempDirectory;

  @Test
  void missingOrDisabledConfigurationNeverCreatesReadinessTrust() {
    assertThat(new AccountJwtReadinessTrustBinding(false, "/unused", Clock.systemUTC()).current())
        .isEmpty();
    assertThat(new AccountJwtReadinessTrustBinding(true, "", Clock.systemUTC()).current())
        .isEmpty();
  }

  @Test
  void strictBindingPinsAccountRuntimeAndDedicatedHarnessIdentity() {
    var binding =
        AccountJwtReadinessTrustBinding.parseProtectedBytes(
            protectedConfig(NOW + 60).getBytes(StandardCharsets.UTF_8), NOW);

    assertThat(binding.environmentId()).isEqualTo("prod");
    assertThat(binding.clusterId()).isEqualTo("prod-cluster-1");
    assertThat(binding.namespace()).isEqualTo("firemud-prod");
    assertThat(binding.expectedPeerUri())
        .isEqualTo("spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness");
    assertThat(binding.validatorId()).isEqualTo("account-service");
    assertThat(binding.validatorInstanceId()).isEqualTo("account-validator-instance-7");
    assertThat(binding.validUntilEpochSecond()).isEqualTo(NOW + 60);
    assertThat(binding.isCurrentAt(NOW)).isTrue();
    assertThat(binding.isCurrentAt(NOW + 60)).isFalse();
  }

  @Test
  void missingExpiredDuplicateUnknownAndChangedIdentityBindingsAreRejected() {
    String valid = protectedConfig(NOW + 60);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    valid.getBytes(StandardCharsets.UTF_8), NOW + 60))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    (valid + "validatorInstanceId=other\n").getBytes(StandardCharsets.UTF_8), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    (valid + "untrusted=true\n").getBytes(StandardCharsets.UTF_8), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    valid
                        .replace(
                            "validatorInstanceId=account-validator-instance-7",
                            "validatorInstanceId=other")
                        .getBytes(StandardCharsets.UTF_8),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountJwtReadinessTrustBinding.parseProtectedBytes(
                    valid.replace("enabled=true", "enabled=false").getBytes(StandardCharsets.UTF_8),
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void acceptsRootOwnedOwnerWritableDirectoryButRejectsWritableProtectedFile() throws Exception {
    Path directory = Files.createDirectory(tempDirectory.resolve("protected"));
    Path binding = directory.resolve("binding.conf");
    Files.writeString(binding, protectedConfig(NOW + 60), StandardCharsets.UTF_8);
    Assumptions.assumeTrue(((Number) Files.getAttribute(directory, "unix:uid")).longValue() == 0L);
    Assumptions.assumeTrue(((Number) Files.getAttribute(binding, "unix:uid")).longValue() == 0L);
    Assumptions.assumeTrue(hasProtectedDirectoryAncestors(directory));
    Files.setPosixFilePermissions(
        directory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));
    Files.setPosixFilePermissions(
        binding, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ));

    assertThat(
            new AccountJwtReadinessTrustBinding(
                    true,
                    binding.toString(),
                    Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC))
                .current())
        .isPresent();

    Files.setPosixFilePermissions(
        directory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));
    assertThat(
            new AccountJwtReadinessTrustBinding(
                    true,
                    binding.toString(),
                    Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC))
                .current())
        .isEmpty();
    Files.setPosixFilePermissions(
        directory,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE));

    Files.setPosixFilePermissions(
        binding, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    assertThat(
            new AccountJwtReadinessTrustBinding(
                    true,
                    binding.toString(),
                    Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC))
                .current())
        .isEmpty();
  }

  @Test
  void rejectsWritableAncestorEvenWhenTheImmediateDirectoryIsProtected() throws Exception {
    Path writableAncestor = Files.createDirectory(tempDirectory.resolve("ancestor"));
    Path intermediate = Files.createDirectory(writableAncestor.resolve("middle"));
    Path directory = Files.createDirectory(intermediate.resolve("protected"));
    Path binding = directory.resolve("binding.conf");
    Files.writeString(binding, protectedConfig(NOW + 60), StandardCharsets.UTF_8);
    Path absoluteBinding = binding.toAbsolutePath().normalize();
    Path absoluteWritableAncestor = writableAncestor.toRealPath();
    Set<PosixFilePermission> protectedDirectoryMode =
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE);
    Set<PosixFilePermission> writableDirectoryMode = new HashSet<>(protectedDirectoryMode);
    writableDirectoryMode.add(PosixFilePermission.GROUP_WRITE);
    Map<Path, Object> owners = new HashMap<>();
    Map<Path, Set<PosixFilePermission>> directoryModes = new HashMap<>();
    for (Path ancestor = directory.toRealPath();
        ancestor != null;
        ancestor = ancestor.getParent()) {
      owners.put(ancestor, 0L);
      directoryModes.put(ancestor, protectedDirectoryMode);
    }
    Set<PosixFilePermission> fileMode =
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ);

    try (MockedStatic<Files> mockedFiles =
        Mockito.mockStatic(Files.class, Mockito.CALLS_REAL_METHODS)) {
      mockedFiles
          .when(
              () ->
                  Files.getAttribute(
                      absoluteBinding, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .thenReturn(0L);
      mockedFiles
          .when(
              () ->
                  Files.getPosixFilePermissions(
                      absoluteBinding, java.nio.file.LinkOption.NOFOLLOW_LINKS))
          .thenReturn(fileMode);
      for (Path ancestor : owners.keySet()) {
        Path resolvedAncestor = ancestor;
        mockedFiles
            .when(
                () ->
                    Files.getAttribute(
                        resolvedAncestor, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS))
            .thenAnswer(invocation -> owners.get(resolvedAncestor));
        mockedFiles
            .when(
                () ->
                    Files.getPosixFilePermissions(
                        resolvedAncestor, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            .thenAnswer(invocation -> directoryModes.get(resolvedAncestor));
      }

      assertThat(
              new AccountJwtReadinessTrustBinding(
                      true,
                      binding.toString(),
                      Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC))
                  .current())
          .isPresent();

      directoryModes.put(absoluteWritableAncestor, writableDirectoryMode);
      assertThat(
              new AccountJwtReadinessTrustBinding(
                      true,
                      binding.toString(),
                      Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC))
                  .current())
          .isEmpty();

      directoryModes.put(absoluteWritableAncestor, protectedDirectoryMode);
      owners.put(absoluteWritableAncestor, 1000L);
      assertThat(
              new AccountJwtReadinessTrustBinding(
                      true,
                      binding.toString(),
                      Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC))
                  .current())
          .isEmpty();
    }
  }

  private static boolean hasProtectedDirectoryAncestors(Path directory) throws Exception {
    for (Path ancestor = directory.toRealPath();
        ancestor != null;
        ancestor = ancestor.getParent()) {
      Object owner =
          Files.getAttribute(ancestor, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS);
      if (!(owner instanceof Number uid) || uid.longValue() != 0L) {
        return false;
      }
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS);
      if (permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
        return false;
      }
    }
    return true;
  }

  private static String protectedConfig(long validUntil) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String uri = "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";
    List<String> pins = List.of("a".repeat(64));
    String digest =
        AccountJwtReadinessTrustBinding.computeBindingDigest(
            "revision-7",
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            clusterUid,
            namespaceUid,
            uri,
            pins,
            "account-validator-instance-7",
            validUntil);
    return "enabled=true\n"
        + "configRevision=revision-7\n"
        + "environmentId=prod\n"
        + "clusterId=prod-cluster-1\n"
        + "namespace=firemud-prod\n"
        + "expectedClusterIncarnationUid="
        + clusterUid
        + "\n"
        + "expectedNamespaceUid="
        + namespaceUid
        + "\n"
        + "expectedPeerUri="
        + uri
        + "\n"
        + "peerSpkiSha256Pins="
        + String.join(",", pins)
        + "\n"
        + "validatorId=account-service\n"
        + "validatorInstanceId=account-validator-instance-7\n"
        + "validUntilEpochSecond="
        + validUntil
        + "\n"
        + "bindingDigest="
        + digest
        + "\n";
  }
}
