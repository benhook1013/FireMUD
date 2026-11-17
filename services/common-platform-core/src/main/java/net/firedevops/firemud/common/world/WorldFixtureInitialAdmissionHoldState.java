package net.firedevops.firemud.common.world;

import java.io.IOException;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;

/**
 * Exact fixture hold and actual World lifecycle observed in one owner snapshot. This value is not
 * authentication, a Human lifecycle proof, a lease, or player admission.
 */
public record WorldFixtureInitialAdmissionHoldState(
    WorldFixtureInitialAdmissionHoldRequest request,
    HoldIdentity holdIdentity,
    HoldStatus status,
    String lifecycleState,
    long lifecycleEpoch,
    long lifecycleRowVersion) {
  public static final String SCHEMA = "world-fixture-initial-admission-hold-state/v1";
  private static final Set<String> STATES =
      Set.of("PREPARING", "ACTIVE", "FAILED_PRE_ACTIVATION", "TERMINATING", "TERMINATED");

  public WorldFixtureInitialAdmissionHoldState {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(holdIdentity, "holdIdentity");
    Objects.requireNonNull(status, "status");
    if (!request.selector().equals(holdIdentity.request())
        || !STATES.contains(lifecycleState)
        || lifecycleEpoch <= 0
        || lifecycleRowVersion < 0) {
      throw new IllegalArgumentException(
          "Fixture hold state differs from its complete owner target");
    }
  }

  /**
   * Only a pending exact hold at the original ACTIVE consequence can precede a fresh first OPEN.
   */
  public void requirePendingActive() {
    if (status != HoldStatus.PENDING
        || !"ACTIVE".equals(lifecycleState)
        || lifecycleEpoch != request.selector().activeLifecycleEpoch()
        || lifecycleEpoch != 2L
        || lifecycleRowVersion != 1L) {
      throw new IllegalStateException(
          "Fixture first OPEN requires its exact pending ACTIVE World hold");
    }
  }

  public byte[] canonicalBytes() {
    String json =
        "{\"schema\":\""
            + SCHEMA
            + "\",\"requestBytesBase64\":\""
            + Base64.getEncoder().encodeToString(request.canonicalBytes())
            + "\",\"holdIdentityBytesBase64\":\""
            + Base64.getEncoder().encodeToString(holdIdentity.canonicalBytes())
            + "\",\"status\":\""
            + status.name()
            + "\",\"lifecycleState\":\""
            + lifecycleState
            + "\",\"lifecycleEpoch\":\""
            + lifecycleEpoch
            + "\",\"lifecycleRowVersion\":\""
            + lifecycleRowVersion
            + "\"}";
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(json);
    } catch (IOException impossible) {
      throw new IllegalStateException("Fixture hold state cannot be canonicalized", impossible);
    }
  }

  /** Bounded exact value decoding only, never owner authentication. */
  public static WorldFixtureInitialAdmissionHoldState fromStored(byte[] bytes) {
    return FixtureInitialAdmissionFrames.state(bytes);
  }
}
