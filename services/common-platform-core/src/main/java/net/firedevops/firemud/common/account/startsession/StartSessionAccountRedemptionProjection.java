package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import tools.jackson.databind.json.JsonMapper;

/** Canonical full Account projection used by original StartSession redemption. */
public final class StartSessionAccountRedemptionProjection {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private StartSessionAccountRedemptionProjection() {}

  /**
   * Derives the complete canonical Account redemption projection from the original tuple.
   *
   * <p>The issuance fence remains a JSON number, matching the original projection bytes. This value
   * proves integrity and tuple binding only; it does not establish Account authentication,
   * currentness, or a live reservation claim.
   */
  public static byte[] fromOriginalTuple(
      StartSessionPostAuthorizationExecutionTuple originalTuple) {
    Objects.requireNonNull(originalTuple, "originalTuple is required");
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(originalTuple.authorityEvidenceBundleBytes());
    long issuanceFence;
    try {
      issuanceFence = Long.parseLong(originalTuple.issuanceFence());
    } catch (NumberFormatException malformed) {
      throw new IllegalArgumentException("Original Account issuance fence is malformed", malformed);
    }
    if (issuanceFence <= 0L) {
      throw new IllegalArgumentException("Original Account issuance fence is not positive");
    }
    Map<String, Object> projection =
        Map.of(
            "projectionSchemaId",
            "accountStartSessionRedemptionProjection",
            "projectionSchemaVersion",
            "1",
            "authorizationReferenceFingerprint",
            originalTuple.authorizationReferenceFingerprint(),
            "authorityEvidenceBundle",
            bundle.jsonValue(),
            "issuanceOperationId",
            bundle.issuanceOperationId().toString(),
            "issuanceFence",
            issuanceFence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(projection));
    } catch (IOException malformed) {
      throw new IllegalStateException(
          "Could not encode the original Account redemption projection", malformed);
    }
  }
}
