package net.firedevops.firemud.common.gamesession;

import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;

/** Reads one exact current original Game Session attempt as a point-in-time observation. */
public interface OriginalStartSessionCurrentAttemptClient {
  /**
   * Returns an observation only. The result is not a transferable claim, lease renewal, or
   * continuous authorization to redeem, execute, or admit.
   */
  Result read(Request request);
}
