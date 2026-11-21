package net.firedevops.firemud.worldmanagement.client;

import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof;

/** Actual authenticated fixture owner read, never a caller-supplied terminal assertion. */
@FunctionalInterface
public interface GameSessionFixtureInitialAdmissionOwnerClient {
  WorldFixtureInitialAdmissionTerminalProof readFixture(
      WorldFixtureInitialAdmissionHoldRequest request,
      HoldIdentity identity,
      String expectedGameSessionLeafSha256);
}
