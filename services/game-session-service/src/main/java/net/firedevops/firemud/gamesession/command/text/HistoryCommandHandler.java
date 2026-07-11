package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
final class HistoryCommandHandler {
  private static final String FEATURE_UNAVAILABLE_CODE = "FEATURE_UNAVAILABLE";

  private final PlayerCommandHistoryStorageService historyStorageService;
  private final EffectiveCommandHistorySettingsResolver commandHistorySettingsResolver;

  HistoryCommandHandler(
      PlayerCommandHistoryStorageService historyStorageService,
      EffectiveCommandHistorySettingsResolver commandHistorySettingsResolver) {
    this.historyStorageService = historyStorageService;
    this.commandHistorySettingsResolver = commandHistorySettingsResolver;
  }

  @Timed(value = "gamesession.command.history")
  TextCommandInterpretationResult handle(TextCommand command, SessionContext context) {
    FiremudCommandHistoryProperties settings =
        commandHistorySettingsResolver.commandHistory(context);
    if (!settings.enabled()) {
      return new TextCommandInterpretationResult(
          CommandEnqueueResult.failure(FEATURE_UNAVAILABLE_CODE, "Command history is unavailable."),
          List.of(
              PlayerOutput.error(
                  FEATURE_UNAVAILABLE_CODE,
                  "Command history is unavailable.",
                  "error.command-history.unavailable",
                  java.util.Map.of())));
    }

    java.util.Optional<TextCommandPayload.HistoryRequest> historyRequest = command.historyPayload();
    if (historyRequest.isEmpty()) {
      return invalidCount();
    }
    Integer requestedCount = historyRequest.orElseThrow().count();
    if (requestedCount != null && requestedCount <= 0) {
      return invalidCount();
    }
    int limit = resolveLimit(requestedCount, settings.maxEntries());
    List<String> entries =
        historyStorageService.findRecent(
            context.tenantId(), context.gameInstanceId(), context.characterId(), limit);

    if (entries.isEmpty()) {
      return success("No recent commands.");
    }

    return success(String.join("\n", entries));
  }

  private int resolveLimit(Integer requestedCount, int configuredMax) {
    if (requestedCount == null) {
      return configuredMax;
    }
    return Math.min(requestedCount, configuredMax);
  }

  private TextCommandInterpretationResult invalidCount() {
    String message = "HISTORY count must be a positive integer.";
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure("INVALID_ARGUMENT", message),
        List.of(
            PlayerOutput.error(
                "INVALID_ARGUMENT",
                message,
                "error.command-history.invalid-count",
                java.util.Map.of())));
  }

  private TextCommandInterpretationResult success(String body) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(), List.of(PlayerOutput.notice(body)), false, false);
  }
}
