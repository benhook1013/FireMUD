package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountMountedGameplayCredentialDigestKeySourceTest {
  private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
  private static final Instant HORIZON = Instant.parse("2027-01-16T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @TempDir Path tempDirectory;

  @Test
  void resolvesDistinctActiveAndRetainedHmacKeysWithTheirExplicitHorizon() throws Exception {
    Path mount = tempDirectory.resolve("mount");
    byte[] activeMaterial = keyMaterial(0x11);
    byte[] retainedMaterial = keyMaterial(0x22);
    writeManifest(
        mount,
        active("active-2", HORIZON, activeMaterial),
        retained("prior-1", HORIZON.plusSeconds(60), retainedMaterial));
    AccountMountedGameplayCredentialDigestKeySource source = source(mount);

    var active = source.currentKey();
    var retained = source.requireKey("prior-1");

    assertThat(active.keyId()).isEqualTo("active-2");
    assertThat(active.key().getAlgorithm()).isEqualTo("HmacSHA256");
    assertThat(active.key().getEncoded()).containsExactly(activeMaterial);
    assertThat(active.retainedUntil()).isEqualTo(HORIZON);
    assertThat(retained.keyId()).isEqualTo("prior-1");
    assertThat(retained.key().getAlgorithm()).isEqualTo("HmacSHA256");
    assertThat(retained.key().getEncoded()).containsExactly(retainedMaterial);
    assertThat(retained.retainedUntil()).isEqualTo(HORIZON.plusSeconds(60));
    assertThat(active.toString()).isEqualTo("CredentialDigestKey[redacted]");
    assertThat(source.toString()).contains("redacted").doesNotContain(encoded(activeMaterial));
    assertThat(active.key().toString()).doesNotContain(encoded(activeMaterial));
  }

  @Test
  void rejectsAnExpiredActiveOrRetainedKeyAtItsExactHorizon() throws Exception {
    Path mount = tempDirectory.resolve("expired");
    writeManifest(
        mount,
        active("active-1", NOW, keyMaterial(0x31)),
        retained("prior-1", HORIZON, keyMaterial(0x32)));
    AccountMountedGameplayCredentialDigestKeySource source = source(mount);

    assertUnavailable(source::currentKey);
    assertThat(source.requireKey("prior-1").keyId()).isEqualTo("prior-1");

    writeManifest(
        mount,
        active("active-2", HORIZON, keyMaterial(0x33)),
        retained("prior-1", NOW, keyMaterial(0x34)));
    assertUnavailable(() -> source.requireKey("prior-1"));
  }

  @Test
  void reloadsRotationAndDoesNotRetainWithdrawnIds() throws Exception {
    Path mount = tempDirectory.resolve("rotating");
    writeManifest(mount, active("key-v1", HORIZON, keyMaterial(0x41)));
    AccountMountedGameplayCredentialDigestKeySource source = source(mount);
    assertThat(source.currentKey().keyId()).isEqualTo("key-v1");

    writeManifest(
        mount,
        active("key-v2", HORIZON.plusSeconds(600), keyMaterial(0x42)),
        retained("key-v1", HORIZON, keyMaterial(0x41)));
    assertThat(source.currentKey().keyId()).isEqualTo("key-v2");
    assertThat(source.requireKey("key-v1").keyId()).isEqualTo("key-v1");

    writeManifest(mount, active("key-v3", HORIZON.plusSeconds(1200), keyMaterial(0x43)));
    assertThat(source.currentKey().keyId()).isEqualTo("key-v3");
    assertUnavailable(() -> source.requireKey("key-v1"));
  }

  @Test
  void rejectsUnknownIdsAndMalformedOrAmbiguousManifestEntries() throws Exception {
    Path mount = tempDirectory.resolve("malformed");
    String validActive = active("key-1", HORIZON, keyMaterial(0x51));
    String validRetained = retained("key-2", HORIZON, keyMaterial(0x52));
    writeManifest(mount, validActive);
    AccountMountedGameplayCredentialDigestKeySource source = source(mount);
    assertUnavailable(() -> source.requireKey("unknown"));

    String[] malformedManifests = {
      validRetained,
      validActive.replace("active key-1", "unknown key-1"),
      validActive.replace(" ", "  "),
      validActive + " extra",
      "future " + validRetained.substring("retained ".length()),
      validActive + "\n" + retained("key-1", HORIZON, keyMaterial(0x53)),
      validActive + "\n" + active("key-3", HORIZON, keyMaterial(0x54)),
      active("key-3", HORIZON, new byte[31]),
      active("bad.id", HORIZON, keyMaterial(0x55))
    };
    for (int index = 0; index < malformedManifests.length; index++) {
      Path caseMount = tempDirectory.resolve("malformed-" + index);
      writeRawManifest(caseMount, malformedManifests[index]);
      assertUnavailable(() -> source(caseMount).currentKey());
    }

    Path tooMany = tempDirectory.resolve("too-many-retained");
    String[] entries =
        new String[AccountMountedGameplayCredentialDigestKeySource.MAX_RETAINED_KEYS + 2];
    entries[0] = active("active", HORIZON, keyMaterial(0x61));
    for (int index = 1; index < entries.length; index++) {
      entries[index] = retained("retired-" + index, HORIZON, keyMaterial(index));
    }
    writeManifest(tooMany, entries);
    assertUnavailable(() -> source(tooMany).currentKey());
  }

  @Test
  void rejectsNonCanonicalEpochsEncodingOversizeAndInvalidUtf8() throws Exception {
    String encoded = encoded(keyMaterial(0x71));
    String[] invalidEpochs = {
      "0", "+" + HORIZON.toEpochMilli(), "0" + HORIZON.toEpochMilli(), "9223372036854775808"
    };
    for (int index = 0; index < invalidEpochs.length; index++) {
      Path mount = tempDirectory.resolve("epoch-" + index);
      writeRawManifest(mount, "active key-1 " + invalidEpochs[index] + " " + encoded);
      assertUnavailable(() -> source(mount).currentKey());
    }

    Path noncanonicalKey = tempDirectory.resolve("noncanonical-key");
    writeRawManifest(
        noncanonicalKey, "active key-1 " + HORIZON.toEpochMilli() + " " + encoded + "=");
    assertUnavailable(() -> source(noncanonicalKey).currentKey());

    Path oversized = tempDirectory.resolve("oversized");
    Files.createDirectories(oversized);
    Files.write(
        oversized.resolve("keyring"),
        new byte[AccountMountedGameplayCredentialDigestKeySource.MAX_MANIFEST_BYTES + 1]);
    assertUnavailable(() -> source(oversized).currentKey());

    Path invalidUtf8 = tempDirectory.resolve("invalid-utf8");
    Files.createDirectories(invalidUtf8);
    Files.write(invalidUtf8.resolve("keyring"), new byte[] {(byte) 0xc3, (byte) 0x28});
    assertUnavailable(() -> source(invalidUtf8).currentKey());

    Path nonregular = tempDirectory.resolve("nonregular");
    Files.createDirectories(nonregular.resolve("keyring"));
    assertUnavailable(() -> source(nonregular).currentKey());
  }

  @Test
  void acceptsOnlyProjectedManifestFilesWhoseResolvedPathStaysInsideTheMount() throws Exception {
    Path mount = tempDirectory.resolve("projected");
    Path version = mount.resolve("..2026_10_07_00_00_00.0000000000");
    writeManifest(version, active("projected-key", HORIZON, keyMaterial(0x81)));
    try {
      Files.createSymbolicLink(mount.resolve("..data"), version.getFileName());
      Files.createSymbolicLink(mount.resolve("keyring"), Path.of("..data", "keyring"));
    } catch (UnsupportedOperationException | java.io.IOException ex) {
      Assumptions.assumeTrue(false, "Filesystem does not support projected-secret symlinks");
    }
    assertThat(source(mount).currentKey().keyId()).isEqualTo("projected-key");

    Path outside = tempDirectory.resolve("outside");
    writeManifest(outside, active("outside-key", HORIZON, keyMaterial(0x82)));
    Path escaping = tempDirectory.resolve("escaping");
    Files.createDirectories(escaping);
    try {
      Files.createSymbolicLink(escaping.resolve("keyring"), outside.resolve("keyring"));
    } catch (UnsupportedOperationException | java.io.IOException ex) {
      Assumptions.assumeTrue(false, "Filesystem does not support symlink validation");
    }
    AccountMountedGameplayCredentialDigestKeySource.KeyUnavailableException failure =
        unavailable(() -> source(escaping).currentKey());
    assertThat(failure.getMessage())
        .doesNotContain(escaping.toString(), encoded(keyMaterial(0x82)));
    assertThat(failure).hasNoCause();
  }

  @Test
  void requiresAnAbsoluteMountPathAndDoesNotExposeKeyMaterialOnFailures() throws Exception {
    assertThatThrownBy(
            () -> new AccountMountedGameplayCredentialDigestKeySource(Path.of("relative"), CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Account digest-key mount path must be absolute");

    Path missing = tempDirectory.resolve("missing");
    AccountMountedGameplayCredentialDigestKeySource.KeyUnavailableException failure =
        unavailable(() -> source(missing).currentKey());
    assertThat(failure.errorCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(failure.getMessage()).doesNotContain(missing.toString(), "keyring");
    assertThat(failure).hasNoCause();
  }

  private static AccountMountedGameplayCredentialDigestKeySource source(Path mount) {
    return new AccountMountedGameplayCredentialDigestKeySource(mount, CLOCK);
  }

  private static void writeManifest(Path mount, String... entries) throws Exception {
    writeRawManifest(mount, String.join("\n", entries));
  }

  private static void writeRawManifest(Path mount, String entries) throws Exception {
    Files.createDirectories(mount);
    Files.writeString(
        mount.resolve("keyring"),
        "firemud-account-credential-request-digest-keyring-v1\n" + entries + "\n");
  }

  private static String active(String keyId, Instant horizon, byte[] key) {
    return "active " + keyId + " " + horizon.toEpochMilli() + " " + encoded(key);
  }

  private static String retained(String keyId, Instant horizon, byte[] key) {
    return "retained " + keyId + " " + horizon.toEpochMilli() + " " + encoded(key);
  }

  private static byte[] keyMaterial(int fill) {
    byte[] bytes = new byte[AccountMountedGameplayCredentialDigestKeySource.KEY_BYTES];
    Arrays.fill(bytes, (byte) fill);
    return bytes;
  }

  private static String encoded(byte[] key) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }

  private static void assertUnavailable(Runnable operation) {
    assertThat(unavailable(operation)).isNotNull();
  }

  private static AccountMountedGameplayCredentialDigestKeySource.KeyUnavailableException
      unavailable(Runnable operation) {
    Throwable failure = org.assertj.core.api.Assertions.catchThrowable(operation::run);
    assertThat(failure)
        .isInstanceOf(
            AccountMountedGameplayCredentialDigestKeySource.KeyUnavailableException.class);
    return (AccountMountedGameplayCredentialDigestKeySource.KeyUnavailableException) failure;
  }
}
