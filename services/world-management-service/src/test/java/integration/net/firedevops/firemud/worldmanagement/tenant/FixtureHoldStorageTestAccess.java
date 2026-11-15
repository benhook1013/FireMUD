package net.firedevops.firemud.worldmanagement.tenant;

import java.time.Instant;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;

/** Test-only access to private storage; stipulated terminal data is not authenticated transport. */
public final class FixtureHoldStorageTestAccess {
  private FixtureHoldStorageTestAccess() {}

  public static void abort(
      WorldFixtureInitialAdmissionHoldRepository storage,
      WorldFixtureInitialAdmissionHoldRequest request,
      HoldIdentity identity) {
    var proof =
        new WorldFixtureInitialAdmissionTerminalProof(
            request,
            identity.holdId(),
            identity.holdFence(),
            WorldFixtureInitialAdmissionTerminalProof.Outcome.ABORTED,
            null,
            null,
            true,
            Instant.parse("2026-11-15T13:00:00Z"));
    storage.settle(identity, proof);
    storage.settle(identity, proof);
  }
}
