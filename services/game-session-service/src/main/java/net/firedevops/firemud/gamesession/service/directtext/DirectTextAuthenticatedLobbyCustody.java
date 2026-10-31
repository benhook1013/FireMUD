package net.firedevops.firemud.gamesession.service.directtext;

import java.util.function.Function;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;

/**
 * Missing protected receiving producer, deliberately not a bean or an observation-to-authority
 * adapter. Implementations must authenticate the current exact original/result association and pin
 * its private-result and transport custody for the entire callback, excluding replacement, closure
 * and reset. A UUID observation, SQL witness or boolean current flag cannot implement this
 * contract. Implementations must not retain the borrowed result beyond the current invocation.
 */
public interface DirectTextAuthenticatedLobbyCustody {
  <T> T withCurrent(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult borrowedResult,
      Function<ObservedDirectTextTransport, T> action);
}
