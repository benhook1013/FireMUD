package net.firedevops.firemud.common.account.startsession;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import tools.jackson.databind.json.JsonMapper;

/** Retained original Account tuple evidence for StartSession redemption. */
public final class StartSessionAccountRedemptionProjection {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private StartSessionAccountRedemptionProjection() {}

  /**
   * Retains the complete canonical tuple returned by Account without duplicating its bundle.
   *
   * <p>Account authentication and provenance must be established by the receiving boundary before
   * calling this method. Serialization alone does not establish currentness or a live claim.
   */
  public static byte[] fromOriginalTuple(
      StartSessionPostAuthorizationExecutionTuple originalTuple) {
    Objects.requireNonNull(originalTuple, "originalTuple is required");
    return decodeOwnerTuple(originalTuple.canonicalBytes()).canonicalBytes();
  }

  /**
   * Decodes complete owner evidence; legacy scalar projections are insufficient for current use.
   */
  public static StartSessionPostAuthorizationExecutionTuple decodeOwnerTuple(byte[] projection) {
    requireBounded(projection);
    StartSessionPostAuthorizationExecutionTuple ownerTuple =
        StartSessionPostAuthorizationExecutionTuple.decode(projection);
    if (!Arrays.equals(ownerTuple.canonicalBytes(), projection)) {
      throw new IllegalArgumentException("Original Account tuple projection is not canonical");
    }
    return ownerTuple;
  }

  /**
   * Compares the stored or independently returned owner evidence to the complete incoming tuple.
   */
  public static void requireCurrentBinding(
      byte[] projection, StartSessionPostAuthorizationExecutionTuple expectedTuple) {
    Objects.requireNonNull(expectedTuple, "expectedTuple is required");
    if (!Arrays.equals(
        decodeOwnerTuple(projection).canonicalBytes(), expectedTuple.canonicalBytes())) {
      throw new IllegalArgumentException(
          "Original Account tuple projection differs from the exact tuple");
    }
  }

  /**
   * Validates historical bytes without replacing them or upgrading old evidence to current
   * authority. Legacy scalar-v1 evidence remains readable only at historical boundaries.
   */
  public static void requireHistoricalBinding(
      byte[] projection, StartSessionPostAuthorizationExecutionTuple expectedTuple) {
    Objects.requireNonNull(expectedTuple, "expectedTuple is required");
    requireBounded(projection);
    if (!Arrays.equals(projection, expectedTuple.canonicalBytes())
        && !Arrays.equals(projection, legacyHistoricalProjection(expectedTuple))) {
      throw new IllegalArgumentException(
          "Historical Account projection differs from the exact tuple");
    }
  }

  private static void requireBounded(byte[] projection) {
    Objects.requireNonNull(projection, "Account redemption projection is required");
    if (projection.length == 0
        || projection.length
            > StartSessionPostAuthorizationExecutionTuple.MAX_CANONICAL_TUPLE_BYTES) {
      throw new IllegalArgumentException(
          "Account redemption projection is empty or exceeds its bound");
    }
  }

  private static byte[] legacyHistoricalProjection(
      StartSessionPostAuthorizationExecutionTuple originalTuple) {
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
