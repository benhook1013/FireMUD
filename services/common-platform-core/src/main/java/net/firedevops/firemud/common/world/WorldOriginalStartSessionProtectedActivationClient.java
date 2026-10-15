package net.firedevops.firemud.common.world;

import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;

/** Explicit Game Session-side client for World's standalone protected-activation boundary. */
public interface WorldOriginalStartSessionProtectedActivationClient {
  /**
   * Sends the exact Account acquisition statement and returns World's full immutable first receipt.
   */
  WorldOriginalStartSessionActivationTerminalEvidence activate(
      AccountStartSessionActivationProtectionAcquisitionInput input);
}
