package net.firedevops.firemud.gamesession.presentation;

import java.util.Optional;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Builds the current prompt text from the best available session/gameplay identity. */
@Component
public class PromptComposer {

  public Optional<PlayerOutput> compose(SessionContext context) {
    if (context == null || context.characterId() <= 0 || context.gameInstanceId() <= 0) {
      return Optional.empty();
    }
    String promptBase =
        StringUtils.hasText(context.characterName())
            ? context.characterName()
            : StringUtils.hasText(context.loginName()) ? context.loginName() : null;
    if (!StringUtils.hasText(promptBase)) {
      return Optional.of(PlayerOutput.prompt("> "));
    }
    return Optional.of(PlayerOutput.prompt(promptBase + "> "));
  }
}
