package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.annotation.Timed;
import java.util.List;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.common.settings.EffectiveCommandCapabilitiesSettingsResolver;
import net.firedevops.firemud.common.settings.PlayerCommandCapability;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class HistoryCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(HistoryCommandHandler.class);
  private static final String FEATURE_UNAVAILABLE_CODE = "FEATURE_UNAVAILABLE";
  private static final String FEATURE_UNAVAILABLE_MESSAGE = "Command history is unavailable.";

  private final PlayerCommandHistoryStorageService historyStorageService;
  private final EffectiveCommandHistorySettingsResolver commandHistorySettingsResolver;
  private final EffectiveCommandCapabilitiesSettingsResolver commandCapabilitiesSettingsResolver;

  HistoryCommandHandler(
      PlayerCommandHistoryStorageService historyStorageService,
      EffectiveCommandHistorySettingsResolver commandHistorySettingsResolver,
      EffectiveCommandCapabilitiesSettingsResolver commandCapabilitiesSettingsResolver) {
    this.historyStorageService = historyStorageService;
    this.commandHistorySettingsResolver = commandHistorySettingsResolver;
    this.commandCapabilitiesSettingsResolver = commandCapabilitiesSettingsResolver;
  }

  @Timed(value = "gamesession.command.history")
  TextCommandInterpretationResult handle(TextCommand command, SessionContext context) {
    if (!isEnabled(context)) {
      return unavailable();
    }
    FiremudCommandHistoryProperties settings;
    try {
      settings = commandHistorySettingsResolver.commandHistory(context);
    } catch (RuntimeException ex) {
      return unavailable(context, "settings resolution", ex);
    }

    java.util.Optional<TextCommandPayload.HistoryRequest> historyRequest = command.historyPayload();
    if (historyRequest.isEmpty()) {
      return invalidCount();
    }
    Integer requestedCount = historyRequest.orElseThrow().count();
    if (requestedCount != null && requestedCount <= 0) {
      return invalidCount();
    }
    if (requestedCount != null && requestedCount > settings.maxEntries()) {
      return invalidCount();
    }
    int limit = requestedCount == null ? settings.maxEntries() : requestedCount;
    List<String> entries;
    try {
      entries =
          historyStorageService.findRecent(
              context.tenantId(), context.gameInstanceId(), context.characterId(), limit);
    } catch (RuntimeException ex) {
      return unavailable(context, "storage read", ex);
    }

    if (entries.isEmpty()) {
      return success("No recent commands.");
    }

    return success(String.join("\n", entries));
  }

  private boolean isEnabled(SessionContext context) {
    try {
      return commandCapabilitiesSettingsResolver.isEnabled(
          PlayerCommandCapability.COMMAND_HISTORY, context.tenantId(), context.gameInstanceId());
    } catch (RuntimeException ex) {
      LOG.warn(
          "Command history capability resolution failed tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          ex);
      return false;
    }
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

  private TextCommandInterpretationResult unavailable() {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(FEATURE_UNAVAILABLE_CODE, FEATURE_UNAVAILABLE_MESSAGE),
        List.of(
            PlayerOutput.error(
                FEATURE_UNAVAILABLE_CODE,
                FEATURE_UNAVAILABLE_MESSAGE,
                "error.command-history.unavailable",
                java.util.Map.of())));
  }

  private TextCommandInterpretationResult unavailable(
      SessionContext context, String operation, RuntimeException ex) {
    LOG.warn(
        "Command history {} failed tenantId={} gameInstanceId={} characterId={}",
        operation,
        context.tenantId(),
        context.gameInstanceId(),
        context.characterId(),
        ex);
    return unavailable();
  }

  private TextCommandInterpretationResult success(String body) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(), List.of(PlayerOutput.notice(body)), false, false);
  }
}
