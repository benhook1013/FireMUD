package net.firedevops.firemud.gamesession.presentation;

import java.util.Optional;
import java.util.stream.Stream;
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
    java.util.List<PromptField> fields =
        Stream.of(
                new PromptField("characterId", Long.toString(context.characterId())),
                new PromptField("gameInstanceId", Long.toString(context.gameInstanceId())),
                StringUtils.hasText(context.roomInstanceId())
                    ? new PromptField("roomId", context.roomInstanceId())
                    : null,
                StringUtils.hasText(promptBase) ? new PromptField("actorName", promptBase) : null)
            .filter(java.util.Objects::nonNull)
            .toList();
    if (!StringUtils.hasText(promptBase)) {
      return Optional.of(PlayerOutput.prompt("> ", fields));
    }
    return Optional.of(PlayerOutput.prompt(promptBase + "> ", fields));
  }
}
