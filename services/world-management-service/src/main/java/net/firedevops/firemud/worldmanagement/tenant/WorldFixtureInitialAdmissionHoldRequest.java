package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;

/**
 * Private fixture acquisition assertions, distinct from Human lifecycle and hold authority. The
 * selector retains the original Game Session attempt; it grants no authority by itself.
 */
public record WorldFixtureInitialAdmissionHoldRequest(
    WorldCanonicalInitialAdmissionHold.Request selector,
    WorldCanonicalInstanceFixtureActivationRequest activation) {
  public static final String SCHEMA = "world-fixture-initial-admission-hold-request/v1";

  public WorldFixtureInitialAdmissionHoldRequest {
    Objects.requireNonNull(selector, "selector");
    Objects.requireNonNull(activation, "activation");
    if (!selector.canonicalTenantId().equals(activation.canonicalTenantId())
        || !selector.canonicalGameInstanceId().equals(activation.canonicalGameInstanceId())
        || selector.activeLifecycleEpoch() != 2L) {
      throw new IllegalArgumentException("Fixture hold differs from its exact activation target");
    }
  }

  /** Binds the complete original attempt and exact activation identity, not a Human proof. */
  public byte[] canonicalBytes() {
    String json =
        "{\"schema\":\""
            + SCHEMA
            + "\",\"authorityKind\":\"RUN_OWNED_FIXTURE\",\"fixturePolicySealEvidenceDigest\":\""
            + activation.fixturePolicySealEvidenceDigest()
            + "\",\"selectorBytesBase64\":\""
            + Base64.getEncoder().encodeToString(selector.canonicalRequestBytes())
            + "\",\"activationRequestBytesBase64\":\""
            + Base64.getEncoder().encodeToString(activation.canonicalBytes())
            + "\"}";
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException impossible) {
      throw new IllegalStateException("Fixture hold request cannot be canonicalized", impossible);
    }
  }
}
