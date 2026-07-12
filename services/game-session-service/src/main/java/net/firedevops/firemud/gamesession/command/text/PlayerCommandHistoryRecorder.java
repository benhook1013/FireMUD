package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import net.firedevops.firemud.common.config.FiremudCommandHistoryProperties;
import net.firedevops.firemud.gamesession.config.EffectiveCommandHistorySettingsResolver;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.PlayerCommandHistoryStorageService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Persists only safe accepted commands that have a durable gameplay identity. */
@Component
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification = "Constructor validation only guards injected collaborators before recording.")
class PlayerCommandHistoryRecorder implements AcceptedCommandHistoryRecorder {
  private static final Logger LOG = LoggerFactory.getLogger(PlayerCommandHistoryRecorder.class);

  private final PlayerCommandHistoryStorageService storageService;
  private final EffectiveCommandHistorySettingsResolver settingsResolver;
  private final Executor commandHistoryExecutor;

  @Autowired
  PlayerCommandHistoryRecorder(
      PlayerCommandHistoryStorageService storageService,
      EffectiveCommandHistorySettingsResolver settingsResolver,
      @Qualifier("commandHistoryExecutor") Executor commandHistoryExecutor) {
    this.storageService = Objects.requireNonNull(storageService, "storageService must not be null");
    this.settingsResolver =
        Objects.requireNonNull(settingsResolver, "settingsResolver must not be null");
    this.commandHistoryExecutor =
        Objects.requireNonNull(commandHistoryExecutor, "commandHistoryExecutor must not be null");
  }

  PlayerCommandHistoryRecorder(
      PlayerCommandHistoryStorageService storageService,
      EffectiveCommandHistorySettingsResolver settingsResolver) {
    this(storageService, settingsResolver, Runnable::run);
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
    try {
      commandHistoryExecutor.execute(
          () ->
              persist(
                  context.tenantId(),
                  context.gameInstanceId(),
                  context.characterId(),
                  command.rawLine().trim(),
                  settings.maxEntries()));
    } catch (RuntimeException ex) {
      LOG.warn(
          "Player command history scheduling failed tenantId={} gameInstanceId={} characterId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          ex);
    }
  }

  private void persist(
      long tenantId, long gameInstanceId, long characterId, String commandText, int maxEntries) {
    try {
      storageService.append(tenantId, gameInstanceId, characterId, commandText, maxEntries);
    } catch (RuntimeException ex) {
      LOG.warn(
          "Player command history persistence failed tenantId={} gameInstanceId={} characterId={}",
          tenantId,
          gameInstanceId,
          characterId,
          ex);
    }
  }

  private boolean isRecordable(TextCommand command, CommandEnqueueResult commandResult) {
    return command != null
        && commandResult != null
        && commandResult.accepted()
        && command.type() != TextCommandType.LOGIN
        && command.credentialsPayload().isEmpty()
        && command.emailLoginChallengePayload().isEmpty()
        && command.rawLine() != null
        && !command.rawLine().isBlank();
  }

  private boolean hasHistoryScope(SessionContext context) {
    return context.tenantId() > 0L && context.hasGameplayIdentity();
  }
}
