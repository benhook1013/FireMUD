package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Nested;
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

  @Nested
  class DonorKeyringRegressions {
    @TempDir Path tempDirectory;

    @Test
    void loadsOneActiveAndRetiringDecryptOnlyKeysWithAnExactRetirementHorizon() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      Instant retirement = Instant.parse("2030-01-01T00:00:00Z");
      writeManifest(
          mount,
          "active active-2 " + encodedKey(2),
          "retiring prior-1 " + retirement.toEpochMilli() + " " + encodedKey(1));

      AccountResponseEnvelopeKeyring.Snapshot snapshot =
          new AccountResponseEnvelopeKeyring(mount.toString()).readCurrent();

      assertEquals("active-2", snapshot.activeKeyId());
      assertTrue(
          snapshot
              .decryptionKey("prior-1", retirement.minusMillis(1))
              .toString()
              .contains("material=redacted"));
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> snapshot.decryptionKey("prior-1", retirement));
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> snapshot.decryptionKey("withdrawn", retirement.minusMillis(1)));
    }

    @Test
    void reloadsMountedManifestSoWithdrawnKeysAreNotKeptInMemory() throws Exception {
      Path mount = tempDirectory.resolve("mount");
      writeManifest(mount, "active active-1 " + encodedKey(1));
      AccountResponseEnvelopeKeyring keyring = new AccountResponseEnvelopeKeyring(mount.toString());

      assertEquals("active-1", keyring.readCurrent().activeKeyId());
      writeManifest(mount, "active active-2 " + encodedKey(2));

      AccountResponseEnvelopeKeyring.Snapshot updated = keyring.readCurrent();
      assertEquals("active-2", updated.activeKeyId());
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> updated.decryptionKey("active-1", Instant.parse("2030-01-01T00:00:00Z")));
    }

    @Test
    void missingOrMalformedMountedManifestFailsRetryablyWithoutEchoingItsPath() throws Exception {
      Path missing = tempDirectory.resolve("not-mounted");
      AccountResponseEnvelopeKeyring.KeyUnavailableException missingFailure =
          assertThrows(
              AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
              () -> new AccountResponseEnvelopeKeyring(missing.toString()).readCurrent());
      assertFalse(missingFailure.getMessage().contains(missing.toString()));

      Path malformed = tempDirectory.resolve("malformed");
      writeManifest(
          malformed,
          "active repeated " + encodedKey(1),
          "retiring repeated 1893456000000 " + encodedKey(2));
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> new AccountResponseEnvelopeKeyring(malformed.toString()).readCurrent());
    }

    @Test
    void rejectsBadKeyEncodingAndManifestSize() throws Exception {
      Path malformed = tempDirectory.resolve("bad-key");
      writeManifest(malformed, "active active-1 not-base64url");
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> new AccountResponseEnvelopeKeyring(malformed.toString()).readCurrent());

      Path oversized = tempDirectory.resolve("oversized");
      Files.createDirectories(oversized);
      Files.write(
          oversized.resolve("keyring"),
          new byte[AccountResponseEnvelopeKeyring.MAX_MANIFEST_BYTES + 1]);
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> new AccountResponseEnvelopeKeyring(oversized.toString()).readCurrent());
    }

    @Test
    void readsKubernetesProjectedKeySymlinksOnlyWhenResolvedFileStaysInsideMountRoot()
        throws Exception {
      Path projectedMount = tempDirectory.resolve("projected");
      Path version = projectedMount.resolve("..2030_10_05_00_00_00.0000000000");
      Files.createDirectories(version);
      writeManifest(version, "active projected-1 " + encodedKey(3));
      try {
        Files.createSymbolicLink(projectedMount.resolve("..data"), version.getFileName());
        Files.createSymbolicLink(projectedMount.resolve("keyring"), Path.of("..data", "keyring"));
      } catch (UnsupportedOperationException | java.io.IOException ex) {
        Assumptions.assumeTrue(false, "Filesystem does not support projected-secret symlinks");
      }
      assertEquals(
          "projected-1",
          new AccountResponseEnvelopeKeyring(projectedMount.toString())
              .readCurrent()
              .activeKeyId());

      Path outside = tempDirectory.resolve("outside");
      writeManifest(outside, "active outside-1 " + encodedKey(4));
      Path escapingMount = tempDirectory.resolve("escaping");
      Files.createDirectories(escapingMount);
      try {
        Files.createSymbolicLink(escapingMount.resolve("keyring"), outside.resolve("keyring"));
      } catch (UnsupportedOperationException | java.io.IOException ex) {
        Assumptions.assumeTrue(false, "Filesystem does not support symlink validation");
      }
      assertThrows(
          AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
          () -> new AccountResponseEnvelopeKeyring(escapingMount.toString()).readCurrent());
    }

    @Test
    void rejectsNonCanonicalRetirementEpochs() throws Exception {
      String[] invalidEpochs = {"+1893456000000", "01893456000000", "١٨٩٣٤٥٦٠٠٠٠٠٠"};
      for (int index = 0; index < invalidEpochs.length; index++) {
        String epoch = invalidEpochs[index];
        Path mount = tempDirectory.resolve("epoch-" + index);
        writeManifest(
            mount,
            "active active-1 " + encodedKey(1),
            "retiring prior-1 " + epoch + " " + encodedKey(2));
        assertThrows(
            AccountResponseEnvelopeKeyring.KeyUnavailableException.class,
            () -> new AccountResponseEnvelopeKeyring(mount.toString()).readCurrent());
      }
    }

    private static void writeManifest(Path mount, String... entries) throws Exception {
      Files.createDirectories(mount);
      StringBuilder content = new StringBuilder("firemud-account-response-envelope-keyring-v1\n");
      for (String entry : entries) {
        content.append(entry).append('\n');
      }
      Files.writeString(mount.resolve("keyring"), content.toString());
    }

    private static String encodedKey(int fill) {
      byte[] bytes = new byte[AccountResponseEnvelopeKeyring.KEY_BYTES];
      java.util.Arrays.fill(bytes, (byte) fill);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
  }
}
