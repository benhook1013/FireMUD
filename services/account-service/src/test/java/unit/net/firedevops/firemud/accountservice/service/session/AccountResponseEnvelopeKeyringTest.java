package net.firedevops.firemud.accountservice.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountResponseEnvelopeKeyringTest {
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
        new AccountResponseEnvelopeKeyring(projectedMount.toString()).readCurrent().activeKeyId());

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
