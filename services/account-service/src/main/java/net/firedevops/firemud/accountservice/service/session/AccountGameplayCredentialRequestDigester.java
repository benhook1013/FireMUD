package net.firedevops.firemud.accountservice.service.session;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.GameplayCredentialSourceContext;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBinding;

/** Computes the non-reusable, Account-keyed identity of one exact gameplay LOGIN request. */
public final class AccountGameplayCredentialRequestDigester {
  private static final byte[] DOMAIN =
      "account-gameplay-credential-request/v1".getBytes(StandardCharsets.US_ASCII);

  private AccountGameplayCredentialRequestDigester() {}

  public static AccountGameplayCredentialRequestBinding bind(
      AccountGameplayCredentialRequestDigestKeySource.CredentialDigestKey key,
      CanonicalGameplayLoginRequest request,
      String normalizedEmail,
      String verifiedCallerWorkload,
      java.util.UUID accountId) {
    Objects.requireNonNull(key, "Account credential digest key is required");
    Objects.requireNonNull(request, "Canonical gameplay LOGIN request is required");
    requireVerifiedWorkload(verifiedCallerWorkload);
    Objects.requireNonNull(accountId, "Resolved Account identity is required");
    GameplayCredentialSourceContext source = request.sourceContext();
    List<byte[]> fields = new ArrayList<>();
    byte[] credentialBytes = request.credential().getBytes(StandardCharsets.UTF_8);
    fields.add(DOMAIN.clone());
    fields.add(utf8("INITIAL_GAMEPLAY_LOGIN"));
    fields.add(utf8(request.requestId().toString()));
    fields.add(utf8(accountId.toString()));
    fields.add(utf8(normalizedEmail));
    fields.add(utf8(verifiedCallerWorkload));
    fields.add(utf8(source.contextId().toString()));
    fields.add(utf8(source.canonicalClientAddress()));
    fields.add(utf8(source.transportClass()));
    fields.add(credentialBytes);
    int capacity = 0;
    for (byte[] field : fields) capacity = Math.addExact(capacity, Integer.BYTES + field.length);
    byte[] framed = new byte[capacity];
    try {
      ByteBuffer buffer = ByteBuffer.wrap(framed);
      for (byte[] field : fields) buffer.putInt(field.length).put(field);
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key.key());
      byte[] digest = mac.doFinal(framed);
      try {
        return new AccountGameplayCredentialRequestBinding(
            1, key.keyId(), HexFormat.of().formatHex(digest));
      } finally {
        java.util.Arrays.fill(digest, (byte) 0);
      }
    } catch (java.security.GeneralSecurityException | ArithmeticException ex) {
      throw new CredentialDigestUnavailableException();
    } finally {
      java.util.Arrays.fill(framed, (byte) 0);
      for (byte[] field : fields) java.util.Arrays.fill(field, (byte) 0);
    }
  }

  public static boolean matches(
      AccountGameplayCredentialRequestBinding expected,
      AccountGameplayCredentialRequestBinding candidate) {
    if (expected == null
        || candidate == null
        || expected.digestSchemaVersion() != candidate.digestSchemaVersion()
        || !expected.digestKeyId().equals(candidate.digestKeyId())) {
      return false;
    }
    return MessageDigest.isEqual(
        HexFormat.of().parseHex(expected.credentialRequestDigest()),
        HexFormat.of().parseHex(candidate.credentialRequestDigest()));
  }

  private static byte[] utf8(String value) {
    return Objects.requireNonNull(value, "Digest input field is required")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void requireVerifiedWorkload(String workload) {
    if (workload == null
        || !workload.matches(
            "^spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$")) {
      throw new IllegalArgumentException("Verified Game Session workload identity is required");
    }
  }

  public static final class CredentialDigestUnavailableException extends IllegalStateException {
    public CredentialDigestUnavailableException() {
      super("Account credential request binding is unavailable");
    }
  }
}
