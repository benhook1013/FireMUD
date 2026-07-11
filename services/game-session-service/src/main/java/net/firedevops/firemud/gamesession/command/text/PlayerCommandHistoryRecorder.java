package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

/** Persists only safe accepted commands that have a durable gameplay identity. */
@Component
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Constructor validation only guards injected collaborators before recording.")
class PlayerCommandHistoryRecorder implements AcceptedCommandHistoryRecorder {
  private final PlayerCommandHistoryStorageService storageService;
  private final EffectiveCommandHistorySettingsResolver settingsResolver;

  PlayerCommandHistoryRecorder(
      PlayerCommandHistoryStorageService storageService,
      EffectiveCommandHistorySettingsResolver settingsResolver) {
    this.storageService = Objects.requireNonNull(storageService, "storageService must not be null");
    this.settingsResolver =
        Objects.requireNonNull(settingsResolver, "settingsResolver must not be null");
  }

  @Override
  public void record(
      TextCommand command,
      CommandEnqueueResult commandResult,
      Optional<SessionContext> contextBefore,
      Optional<SessionContext> contextAfter) {
    if (!isRecordable(command, commandResult)) {
      return;
    }

    // PLAY establishes its character identity during dispatch; LOGOUT clears it there.
    Optional<SessionContext> historyContext =
        contextAfter
            .filter(this::hasHistoryScope)
            .or(() -> contextBefore.filter(this::hasHistoryScope));
    if (historyContext.isEmpty()) {
      return;
    }

    SessionContext context = historyContext.get();
    FiremudCommandHistoryProperties settings = settingsResolver.commandHistory(context);
    if (!settings.enabled()) {
      return;
    }
    storageService.append(
        context.tenantId(),
        context.gameInstanceId(),
        context.characterId(),
        command.rawLine().trim(),
        settings.maxEntries());
  }

  private boolean isRecordable(TextCommand command, CommandEnqueueResult commandResult) {
    return command != null
        && commandResult != null
        && commandResult.accepted()
        && command.type() != TextCommandType.LOGIN
        && command.type() != TextCommandType.HISTORY
        && command.credentialsPayload().isEmpty()
        && command.emailLoginChallengePayload().isEmpty()
        && command.rawLine() != null
        && !command.rawLine().isBlank();
  }

  private boolean hasHistoryScope(SessionContext context) {
    return context.tenantId() > 0L && context.hasGameplayIdentity();
  }
}
