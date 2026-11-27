package net.firedevops.firemud.accountservice.repository;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dormant Account-owned request encoding, not authentication or scope validation. Callers must
 * authenticate the current account and establish exact scope provenance separately. No credential
 * instance or mutable authority snapshot participates in immutable retry identity.
 */
public final class ConnectTokenRequestFingerprint {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ConnectTokenRequestFingerprint() {}

  /** Produces a storage lookup only; neither missing nor retained state authorizes issuance. */
  public static ConnectTokenReceiptClassifier.Lookup lookup(
      UUID accountId, UUID tenantId, String connectScopeId, String requestId) {
    requireUuid(accountId);
    requireUuid(tenantId);
    requireText(connectScopeId, 2048);
    requireText(requestId, 128);
    Map<String, String> fields =
        Map.of(
            "domain",
            "connect-token-request/v1",
            "accountId",
            accountId.toString(),
            "tenantId",
            tenantId.toString(),
            "connectScopeId",
            connectScopeId,
            "requestId",
            requestId);
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
      String digest =
          "sha256:"
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
      return new ConnectTokenReceiptClassifier.Lookup(
          accountId, tenantId, connectScopeId, requestId, 1, digest);
    } catch (IOException failure) {
      throw new IllegalStateException("Connect request encoding failed", failure);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("A non-nil canonical UUID is required");
  }

  private static void requireText(String value, int maxBytes) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Exact request identity is required");
    try {
      if (StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)).remaining() > maxBytes)
        throw new IllegalArgumentException("Request identity exceeds storage bound");
    } catch (CharacterCodingException failure) {
      throw new IllegalArgumentException("Request identity must be valid UTF-8", failure);
    }
  }
}
