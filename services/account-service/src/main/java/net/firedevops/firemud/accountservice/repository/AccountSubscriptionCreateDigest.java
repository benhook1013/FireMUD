package net.firedevops.firemud.accountservice.repository;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Account-computed request evidence; encoding does not authenticate or reserve its inputs. */
final class AccountSubscriptionCreateDigest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AccountSubscriptionCreateDigest() {}

  /**
   * Encodes the resolved owner and exact normalized create fields, independently of caller and
   * provider operation keys. The caller must resolve the plan and owner, and revalidate the owner
   * fence and instrument reservation in the intent transaction.
   */
  static EncodedRequest encode(
      UUID tenantId, UUID ownerAccountId, String planCode, UUID paymentInstrumentId) {
    requireIdentity(tenantId);
    requireIdentity(ownerAccountId);
    requireIdentity(paymentInstrumentId);
    if (planCode == null || planCode.isBlank() || !planCode.equals(planCode.strip())) {
      throw new IllegalArgumentException("An exact normalized plan code is required");
    }
    try {
      StandardCharsets.UTF_8
          .newEncoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .encode(CharBuffer.wrap(planCode));
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException("Plan code must have an exact UTF-8 encoding", malformed);
    }
    Map<String, Object> request =
        Map.of(
            "schemaVersion", "account-subscription-create-request/v1",
            "tenantId", tenantId.toString(),
            "ownerAccountId", ownerAccountId.toString(),
            "planCode", planCode,
            "instrumentSelection",
                Map.of(
                    "kind",
                    "EXACT_SAVED_INSTRUMENT",
                    "paymentInstrumentId",
                    paymentInstrumentId.toString()));
    try {
      byte[] payload = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(request));
      return new EncodedRequest(
          payload,
          "sha256:"
              + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));
    } catch (IOException encodingFailure) {
      throw new IllegalStateException(
          "Subscription create request encoding failed", encodingFailure);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void requireIdentity(UUID value) {
    if (value == null
        || (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0)) {
      throw new IllegalArgumentException("A non-nil canonical UUID is required");
    }
  }

  record EncodedRequest(byte[] payload, String digest) {
    EncodedRequest {
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }
}
