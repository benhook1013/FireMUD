package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountResponseEnvelopeKeyringTest {
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final String KEYRING_HEADER = "firemud-account-response-envelope-keyring-v1";

  @TempDir Path temporaryDirectory;

  @Test
  void reloadsRotationAndAllowsRetiringKeyOnlyUntilItsConfiguredExpiry() throws IOException {
    Path mount = Files.createDirectory(temporaryDirectory.resolve("rotation"));
    AccountResponseEnvelopeKeyring keyring = new AccountResponseEnvelopeKeyring(mount.toString());
    writeManifest(mount, activeManifest("key-one", 1));
    assertThat(keyring.readCurrent().activeKeyId()).isEqualTo("key-one");

    Instant decryptUntil = NOW.plusSeconds(60);
    writeManifest(
        mount,
        KEYRING_HEADER
            + "\nactive key-two "
            + encodedKey(2)
            + "\nretiring key-one "
            + decryptUntil.toEpochMilli()
            + " "
            + encodedKey(1)
            + "\n");

    var rotated = keyring.readCurrent();
    assertThat(rotated.activeKeyId()).isEqualTo("key-two");
    assertThat(rotated.decryptionKey("key-one", NOW)).isNotNull();
    assertThatThrownBy(() -> rotated.decryptionKey("key-one", decryptUntil))
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);

    writeManifest(mount, activeManifest("key-two", 2));
    assertThatThrownBy(() -> keyring.readCurrent().decryptionKey("key-one", NOW))
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
  }

  @Test
  void acceptsProjectedSecretSymlinksInsideMountAndRejectsTargetsOutsideIt() throws IOException {
    Path projectedMount = Files.createDirectory(temporaryDirectory.resolve("projected"));
    Path version = Files.createDirectory(projectedMount.resolve("..2026_10_09"));
    Files.writeString(
        version.resolve("key"), activeManifest("key-one", 1), StandardCharsets.US_ASCII);
    Files.createSymbolicLink(projectedMount.resolve("..data"), Path.of("..2026_10_09"));
    Files.createSymbolicLink(projectedMount.resolve("keyring"), Path.of("..data/key"));

    assertThat(
            new AccountResponseEnvelopeKeyring(projectedMount.toString())
                .readCurrent()
                .activeKeyId())
        .isEqualTo("key-one");

    Path outsideDirectory = Files.createDirectory(temporaryDirectory.resolve("outside"));
    Files.writeString(
        outsideDirectory.resolve("keyring"),
        activeManifest("key-two", 2),
        StandardCharsets.US_ASCII);
    Path escapingMount = Files.createDirectory(temporaryDirectory.resolve("escaping"));
    Files.createSymbolicLink(escapingMount.resolve("keyring"), outsideDirectory.resolve("keyring"));

    assertThatThrownBy(
            () -> new AccountResponseEnvelopeKeyring(escapingMount.toString()).readCurrent())
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
  }

  @Test
  void failsClosedForMissingAndMalformedMounts() throws IOException {
    assertThatThrownBy(() -> new AccountResponseEnvelopeKeyring("").readCurrent())
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
    assertThatThrownBy(
            () ->
                new AccountResponseEnvelopeKeyring(temporaryDirectory.resolve("missing").toString())
                    .readCurrent())
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);

    Path malformedMount = Files.createDirectory(temporaryDirectory.resolve("malformed"));
    writeManifest(malformedMount, "not-the-keyring-header\nactive key-one " + encodedKey(1) + "\n");
    assertThatThrownBy(
            () -> new AccountResponseEnvelopeKeyring(malformedMount.toString()).readCurrent())
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
  }

  @Test
  void failsClosedWhenMountedKeyringCannotBeRead() throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "Mounted file permission proof requires POSIX file permissions");
    Path mount = Files.createDirectory(temporaryDirectory.resolve("unreadable"));
    Path keyringPath = writeManifest(mount, activeManifest("key-one", 1));
    Set<PosixFilePermission> originalPermissions = Files.getPosixFilePermissions(keyringPath);
    try {
      Files.setPosixFilePermissions(keyringPath, Set.of());
      Assumptions.assumeFalse(
          Files.isReadable(keyringPath),
          "The test process bypasses POSIX read permissions for this file");
      assertThatThrownBy(() -> new AccountResponseEnvelopeKeyring(mount.toString()).readCurrent())
          .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
    } finally {
      Files.setPosixFilePermissions(keyringPath, originalPermissions);
    }
  }

  private static Path writeManifest(Path mount, String contents) throws IOException {
    Path keyring = mount.resolve("keyring");
    Files.writeString(keyring, contents, StandardCharsets.US_ASCII);
    return keyring;
  }

  private static String activeManifest(String keyId, int keyValue) {
    return KEYRING_HEADER + "\nactive " + keyId + " " + encodedKey(keyValue) + "\n";
  }

  private static String encodedKey(int value) {
    byte[] key = new byte[AccountResponseEnvelopeKeyring.KEY_BYTES];
    Arrays.fill(key, (byte) value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
  }
}
