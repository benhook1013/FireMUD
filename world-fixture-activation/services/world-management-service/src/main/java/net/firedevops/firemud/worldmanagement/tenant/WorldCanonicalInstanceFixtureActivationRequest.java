package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;

/** Exact caller assertions for the explicit run-owned fixture activation owner. */
public record WorldCanonicalInstanceFixtureActivationRequest(
    UUID activationRequestId,
    UUID canonicalTenantId,
    UUID canonicalGameInstanceId,
    String fixturePolicySealEvidenceDigest,
    long expectedLifecycleEpoch,
    long expectedRowVersion) {
  public static final String SCHEMA = "world-canonical-instance-fixture-activation-request/v1";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public WorldCanonicalInstanceFixtureActivationRequest {
    requireNonNil(activationRequestId, "activationRequestId");
    requireNonNil(canonicalTenantId, "canonicalTenantId");
    requireNonNil(canonicalGameInstanceId, "canonicalGameInstanceId");
    Objects.requireNonNull(fixturePolicySealEvidenceDigest, "fixturePolicySealEvidenceDigest");
    if (!DIGEST.matcher(fixturePolicySealEvidenceDigest).matches()) {
      throw new IllegalArgumentException("fixturePolicySealEvidenceDigest must be SHA-256");
    }
    if (expectedLifecycleEpoch != 1L || expectedRowVersion != 0L) {
      throw new IllegalArgumentException(
          "Fixture activation requires the original PREPARING epoch 1 and row version 0");
    }
  }

  /** Canonical closed bytes retained as the immutable one-use operation request. */
  public byte[] canonicalBytes() {
    String json =
        "{\"request\":{" +
            "\"activationRequestId\":\"" + activationRequestId + "\"," +
            "\"authorityKind\":\"RUN_OWNED_FIXTURE\"," +
            "\"canonicalGameInstanceId\":\"" + canonicalGameInstanceId + "\"," +
            "\"canonicalTenantId\":\"" + canonicalTenantId + "\"," +
            "\"expectedLifecycleEpoch\":\"1\"," +
            "\"expectedRowVersion\":\"0\"," +
            "\"fixturePolicySealEvidenceDigest\":\"" + fixturePolicySealEvidenceDigest + "\"}," +
            "\"schema\":\"" + SCHEMA + "\"}";
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException impossible) {
      throw new IllegalStateException("Fixture activation request cannot be canonicalized", impossible);
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) throw new IllegalArgumentException(name + " must not be nil");
  }
}
