package net.firedevops.firemud.gamesession.service;

import java.util.Optional;

/** Resolves published authored help for the template backing an admitted gameplay session. */
public interface GameAuthoredHelpReader {
  Optional<ResolvedTopic> resolve(SessionContext context, String topic);

  record ResolvedTopic(String title, String body) {}
}
