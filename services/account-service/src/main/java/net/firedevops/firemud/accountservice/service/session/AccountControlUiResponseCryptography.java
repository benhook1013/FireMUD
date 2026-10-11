package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;

/** Distinct control-ui custody and purpose over the existing tested envelope primitive. */
public final class AccountControlUiResponseCryptography {
  private final AccountControlUiKeyring keys;
  private final AccountResponseEnvelopeCryptography envelopes;
  private final Clock clock;

  public AccountControlUiResponseCryptography(AccountControlUiKeyring keys, Clock clock) {
    this.keys = Objects.requireNonNull(keys);
    this.clock = Objects.requireNonNull(clock);
    envelopes =
        new AccountResponseEnvelopeCryptography(
            keys.encryption(), clock, new java.security.SecureRandom());
  }

  String currentKeyId() {
    return keys.requestKeyId();
  }

  String requestDigest(String keyId, byte[] exactRequest) {
    if (exactRequest == null || exactRequest.length == 0 || exactRequest.length > 16384) {
      throw new IllegalArgumentException("Bounded control-ui credential request required");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(keys.requestKey(keyId, clock.instant()));
      mac.update("firemud/control-ui/credential-request/v1\0".getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(mac.doFinal(exactRequest));
    } catch (java.security.GeneralSecurityException | RuntimeException exception) {
      throw new IllegalStateException("Control-ui request MAC unavailable");
    }
  }

  byte[] seal(byte[] credential, byte[] binding, Instant recoveryExpiry) {
    return envelopes.encrypt(binding(binding), credential, recoveryExpiry).bytes();
  }

  byte[] open(byte[] envelope, byte[] binding, Instant recoveryExpiry) {
    return envelopes.decrypt(
        new AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope(envelope),
        binding(binding),
        recoveryExpiry);
  }

  private static AccountResponseEnvelopeCryptography.Binding binding(byte[] exactOwnerBinding) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(exactOwnerBinding);
      String identity = HexFormat.of().formatHex(digest);
      // Labels identify cryptographic purpose, not a workload or authenticated actor.
      return new AccountResponseEnvelopeCryptography.Binding(
          "control-ui-issuance/v1",
          identity,
          digest,
          "account-control-ui-custody",
          identity,
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          1,
          "{}".getBytes(StandardCharsets.US_ASCII),
          "{}".getBytes(StandardCharsets.US_ASCII),
          exactOwnerBinding);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
