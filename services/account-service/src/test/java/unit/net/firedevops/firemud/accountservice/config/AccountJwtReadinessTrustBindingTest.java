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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
