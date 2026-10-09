package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test-only custody files; no production mount, registry or issuer provenance is asserted. */
class AccountControlUiResponseCryptographyTest {
  @TempDir Path temporary;
  private static final Instant NOW = Instant.parse("2026-10-08T11:00:00Z");

  @Test
  void recoversOnlyOriginalBoundedBytesAndExactOwnerBinding() throws Exception {
    custody("encryption", "enc1", 11);
    custody("request-mac", "mac1", 29);
    var crypto = crypto(NOW);
    byte[] credential = bytes("test-only-credential-not-a-production-token");
    byte[] binding = bytes("test-only/exact-owner/request1/actor1/tenant1/fence1");
    byte[] sealed = crypto.seal(credential, binding, NOW.plusSeconds(60));
    for (int offset = 0; offset <= sealed.length - credential.length; offset++) {
      assertThat(
              Arrays.equals(
                  sealed, offset, offset + credential.length, credential, 0, credential.length))
          .as("Encrypted envelope must not contain contiguous plaintext at offset %s", offset)
          .isFalse();
    }
    assertThat(crypto.open(sealed, binding, NOW.plusSeconds(60))).isEqualTo(credential);
    assertThatThrownBy(() -> crypto.open(sealed, bytes("different-owner"), NOW.plusSeconds(60)))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
    assertThatThrownBy(() -> crypto.open(sealed, binding, NOW.plusSeconds(59)))
        .isInstanceOf(AccountResponseEnvelopeCryptography.IntegrityException.class);
    assertThatThrownBy(() -> crypto(NOW.plusSeconds(60)).open(sealed, binding, NOW.plusSeconds(60)))
        .isInstanceOf(AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException.class);
  }

  @Test
  void requestMacIsExactKeyedAndIndependentFromEncryptionCustody() throws Exception {
    custody("encryption", "enc1", 11);
    custody("request-mac", "mac1", 29);
    var crypto = crypto(NOW);
    assertThat(crypto.currentKeyId()).isEqualTo("mac1");
    String original = crypto.requestDigest("mac1", bytes("test-only-secret/actor/tenant/request"));
    assertThat(original).matches("[0-9a-f]{64}");
    assertThat(crypto.requestDigest("mac1", bytes("test-only-secret/actor/tenant/request")))
        .isEqualTo(original);
    assertThat(crypto.requestDigest("mac1", bytes("changed-secret/actor/tenant/request")))
        .isNotEqualTo(original);
    custody("request-mac", "mac2", 31);
    assertThat(crypto.requestDigest("mac2", bytes("test-only-secret/actor/tenant/request")))
        .isNotEqualTo(original);
    assertThatThrownBy(() -> crypto.requestDigest("mac1", bytes("exact-request")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void absentWithdrawnAndSharedPurposeMaterialFailClosed() throws Exception {
    assertThatThrownBy(() -> crypto(NOW).currentKeyId())
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
    custody("encryption", "enc1", 11);
    custody("request-mac", "mac1", 11);
    assertThatThrownBy(() -> crypto(NOW).requestDigest("mac1", bytes("test-only-presentation")))
        .isInstanceOf(IllegalStateException.class);
    custody("request-mac", "mac1", 29);
    byte[] binding = bytes("test-only-owner");
    byte[] envelope = crypto(NOW).seal(bytes("test-only-credential"), binding, NOW.plusSeconds(60));
    Files.delete(temporary.resolve("encryption/keyring"));
    assertThatThrownBy(() -> crypto(NOW).open(envelope, binding, NOW.plusSeconds(60)))
        .isInstanceOf(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
  }

  private AccountControlUiResponseCryptography crypto(Instant now) {
    return new AccountControlUiResponseCryptography(
        new AccountControlUiKeyring(temporary), Clock.fixed(now, ZoneOffset.UTC));
  }

  private void custody(String purpose, String id, int value) throws Exception {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) value);
    Path directory = Files.createDirectories(temporary.resolve(purpose));
    Files.writeString(
        directory.resolve("keyring"),
        "firemud-account-response-envelope-keyring-v1\nactive "
            + id
            + " "
            + Base64.getUrlEncoder().withoutPadding().encodeToString(key)
            + "\n");
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
