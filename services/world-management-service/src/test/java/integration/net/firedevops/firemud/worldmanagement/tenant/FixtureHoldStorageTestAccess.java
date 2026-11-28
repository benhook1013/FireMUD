package net.firedevops.firemud.worldmanagement.tenant;

import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;

/** Exercises the repository's exact replay with a controlled terminal proof. */
public final class FixtureHoldStorageTestAccess {
  private FixtureHoldStorageTestAccess() {}

  public static void settle(
      WorldCanonicalInitialAdmissionHoldRepository holds,
      HoldIdentity identity,
      String provenance,
      GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    holds.settleFixture(identity, provenance, proof);
  }
}
