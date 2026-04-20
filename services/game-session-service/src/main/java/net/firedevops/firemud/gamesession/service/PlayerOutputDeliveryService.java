package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.presentation.PromptBurstCoordinator;
import net.firedevops.firemud.gamesession.presentation.PromptComposer;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.websocket.WebSocketOutputProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected service collaborators are framework-managed and retained internally")
public final class PlayerOutputDeliveryService {
  private static final Logger LOG = LoggerFactory.getLogger(PlayerOutputDeliveryService.class);
  private static final String CONNECTION_MODE_ATTR = "firemud.websocket.connectionMode";

  private final ActiveTransportSessionRegistry activeTransportSessionRegistry;
  private final ScreenBufferService screenBufferService;
  private final TextPlayerOutputRenderer outputRenderer;
  private final EffectiveSettingsResolver settingsResolver;
  private final WebSocketOutputProjector outputProjector;
  private final PromptComposer promptComposer;
  private final PromptBurstCoordinator promptBurstCoordinator;
  private final MeterRegistry meterRegistry;

  public PlayerOutputDeliveryService(
      ActiveTransportSessionRegistry activeTransportSessionRegistry,
      ScreenBufferService screenBufferService,
      TextPlayerOutputRenderer outputRenderer,
      EffectiveSettingsResolver settingsResolver,
      WebSocketOutputProjector outputProjector,
      PromptComposer promptComposer,
      PromptBurstCoordinator promptBurstCoordinator,
      MeterRegistry meterRegistry) {
    this.activeTransportSessionRegistry = activeTransportSessionRegistry;
    this.screenBufferService = screenBufferService;
    this.outputRenderer = outputRenderer;
    this.settingsResolver = settingsResolver;
    this.outputProjector = outputProjector;
    this.promptComposer = promptComposer;
    this.promptBurstCoordinator = promptBurstCoordinator;
    this.meterRegistry = meterRegistry;
  }

  public void deliver(SessionContext context, List<PlayerOutput> outputs, boolean includePrompt) {
    if (context == null || outputs == null || outputs.isEmpty()) {
      return;
    }
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(context)) {
      PresentationProperties effectivePresentation = settingsResolver.presentation(context);
      List<PlayerOutput> preparedOutputs = prepareOutputs(context, outputs, includePrompt);
      if (preparedOutputs.isEmpty()) {
        return;
      }
      String localeTag = context.localeTag();
      appendReplayableOutputs(context, preparedOutputs, localeTag, effectivePresentation);
      activeTransportSessionRegistry
          .find(context.sessionId())
          .filter(WebSocketSession::isOpen)
          .ifPresentOrElse(
              session ->
                  pushOutputs(session, context, preparedOutputs, localeTag, effectivePresentation),
              () -> meterRegistry.counter("gamesession.output.delivery.buffered_only").increment());
      promptBurstCoordinator.recordPromptEmission(
          Long.toString(context.sessionId()), preparedOutputs);
    }
  }

  private List<PlayerOutput> prepareOutputs(
      SessionContext context, List<PlayerOutput> outputs, boolean includePrompt) {
    List<PlayerOutput> prepared = new ArrayList<>(outputs);
    if (includePrompt) {
      promptComposer.compose(context).ifPresent(prepared::add);
    }
    return promptBurstCoordinator.applyPromptWindow(
        Long.toString(context.sessionId()), context, prepared, false);
  }

  private void appendReplayableOutputs(
      SessionContext context,
      List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    List<ScreenBufferService.BufferedEntry> replayEntries =
        outputs.stream()
            .filter(PlayerOutput::screenBufferEligible)
            .map(output -> renderReplayableOutput(output, localeTag, effectivePresentation))
            .filter(StringUtils::hasText)
            .map(text -> ScreenBufferService.BufferedEntry.fromText(text + "\n"))
            .toList();
    if (replayEntries.isEmpty() || context.gameInstanceId() <= 0 || context.characterId() <= 0) {
      return;
    }
    screenBufferService.append(
        context.tenantId(), context.gameInstanceId(), context.characterId(), replayEntries);
  }

  private String renderReplayableOutput(
      PlayerOutput output, String localeTag, PresentationProperties effectivePresentation) {
    if (output.kind() == PlayerOutputKind.VIEW) {
      return outputRenderer.renderSuccessfulForCommandType(
          net.firedevops.firemud.gamesession.command.text.TextCommandType.LOOK,
          List.of(output),
          localeTag,
          effectivePresentation);
    }
    return outputRenderer.render(output, localeTag, effectivePresentation);
  }

  private void pushOutputs(
      WebSocketSession session,
      SessionContext context,
      List<PlayerOutput> outputs,
      String localeTag,
      PresentationProperties effectivePresentation) {
    for (PlayerOutput output : outputs) {
      try {
        String projected = projectOutput(session, output, localeTag, effectivePresentation);
        if (!StringUtils.hasText(projected)) {
          continue;
        }
        session.sendMessage(new TextMessage(projected));
        meterRegistry
            .counter(
                "gamesession.output.delivery.pushed", "kind", output.kind().name().toLowerCase())
            .increment();
      } catch (IOException ex) {
        LOG.warn(
            "Failed to push player output kind={} to session {}",
            output.kind(),
            context.sessionId(),
            ex);
        meterRegistry
            .counter(
                "gamesession.output.delivery.failed", "kind", output.kind().name().toLowerCase())
            .increment();
        activeTransportSessionRegistry.unregister(context.sessionId(), session);
        break;
      }
    }
  }

  private String projectOutput(
      WebSocketSession session,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentation) {
    if (!isFirstPartyWeb(session) && output.kind() == PlayerOutputKind.VIEW) {
      return outputRenderer.renderSuccessfulForCommandType(
          net.firedevops.firemud.gamesession.command.text.TextCommandType.LOOK,
          List.of(output),
          localeTag,
          effectivePresentation);
    }
    return outputProjector.projectPlayerOutput(session, output, localeTag, effectivePresentation);
  }

  private boolean isFirstPartyWeb(WebSocketSession session) {
    Object mode = session.getAttributes().get(CONNECTION_MODE_ATTR);
    return "first_party_web".equals(mode);
  }
}
