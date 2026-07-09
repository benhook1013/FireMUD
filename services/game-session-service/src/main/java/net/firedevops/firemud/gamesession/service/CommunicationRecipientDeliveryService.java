package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientView;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.command.text.GameplayLoggingContext;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.CommunicationOutputMapper;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.websocket.WebSocketOutputProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Delivers structured non-actor communication views to live recipient sessions. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected service collaborators are framework-managed and retained internally")
public final class CommunicationRecipientDeliveryService {
  private static final Logger LOG =
      LoggerFactory.getLogger(CommunicationRecipientDeliveryService.class);

  private final SessionAuthenticationService sessionAuthenticationService;
  private final ActiveTransportSessionRegistry activeTransportSessionRegistry;
  private final ScreenBufferService screenBufferService;
  private final TextPlayerOutputRenderer outputRenderer;
  private final CommunicationOutputMapper communicationOutputMapper;
  private final EffectiveSettingsResolver settingsResolver;
  private final WebSocketOutputProjector outputProjector;
  private final MeterRegistry meterRegistry;

  public CommunicationRecipientDeliveryService(
      SessionAuthenticationService sessionAuthenticationService,
      ActiveTransportSessionRegistry activeTransportSessionRegistry,
      ScreenBufferService screenBufferService,
      TextPlayerOutputRenderer outputRenderer,
      CommunicationOutputMapper communicationOutputMapper,
      EffectiveSettingsResolver settingsResolver,
      WebSocketOutputProjector outputProjector,
      MeterRegistry meterRegistry) {
    this.sessionAuthenticationService = sessionAuthenticationService;
    this.activeTransportSessionRegistry = activeTransportSessionRegistry;
    this.screenBufferService = screenBufferService;
    this.outputRenderer = outputRenderer;
    this.communicationOutputMapper = communicationOutputMapper;
    this.settingsResolver = settingsResolver;
    this.outputProjector = outputProjector;
    this.meterRegistry = meterRegistry;
  }

  public void deliver(SessionContext actorContext, SendCommunicationResponse response) {
    response.getRecipientViewsList().stream()
        .filter(
            view -> view.getRole() != CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR)
        .forEach(view -> deliverView(actorContext, response, view));
  }

  private void deliverView(
      SessionContext actorContext,
      SendCommunicationResponse response,
      CommunicationRecipientView view) {
    try (GameplayLoggingContext actorLoggingContext = GameplayLoggingContext.from(actorContext)) {
      Optional<SessionContext> maybeRecipient = resolveRecipient(actorContext, view);
      if (maybeRecipient.isEmpty()) {
        meterRegistry
            .counter("gamesession.communication.delivery.missed", "role", roleTag(view))
            .increment();
        return;
      }

      SessionContext recipient = maybeRecipient.orElseThrow();
      if (recipient.gameInstanceId() <= 0 || recipient.characterId() <= 0) {
        return;
      }

      try (GameplayLoggingContext recipientLoggingContext =
          GameplayLoggingContext.from(recipient)) {
        PlayerOutput output =
            communicationOutputMapper.recipientOutput(
                response.getType(), view, response.getMessage());
        String localeTag = resolveLocaleTag(recipient, actorContext);
        PresentationProperties effectivePresentation = settingsResolver.presentation(recipient);
        String rendered = outputRenderer.render(output, localeTag, effectivePresentation);
        if (!StringUtils.hasText(rendered)) {
          return;
        }
        appendReplayableOutput(recipient, output, rendered);

        activeTransportSessionRegistry
            .find(recipient.sessionId())
            .filter(WebSocketSession::isOpen)
            .ifPresentOrElse(
                session -> push(session, recipient, view, output, localeTag, effectivePresentation),
                () ->
                    meterRegistry
                        .counter(
                            "gamesession.communication.delivery.buffered_only",
                            "role",
                            roleTag(view))
                        .increment());
      }
    }
  }

  private void appendReplayableOutput(
      SessionContext recipient, PlayerOutput output, String rendered) {
    if (!output.screenBufferEligible()) {
      return;
    }
    screenBufferService.append(
        recipient.tenantId(),
        recipient.gameInstanceId(),
        recipient.characterId(),
        java.util.List.of(outputProjector.toBufferedEntry(output, rendered + "\n")));
  }

  private Optional<SessionContext> resolveRecipient(
      SessionContext actorContext, CommunicationRecipientView view) {
    PositiveLongParsing.ParsedPositiveLong parsedRecipientId =
        PositiveLongParsing.parseOptionalText(view.getRecipientId(), "recipientId");
    if (parsedRecipientId.invalid()) {
      return Optional.empty();
    }
    if (parsedRecipientId.valid()) {
      return sessionAuthenticationService.resolveByGameplayIdentity(
          actorContext.tenantId(), actorContext.gameInstanceId(), parsedRecipientId.value());
    }
    if (!StringUtils.hasText(view.getRecipientName())) {
      return Optional.empty();
    }
    return sessionAuthenticationService.resolveByGameplayName(
        actorContext.tenantId(), actorContext.gameInstanceId(), view.getRecipientName());
  }

  private void push(
      WebSocketSession session,
      SessionContext recipient,
      CommunicationRecipientView view,
      PlayerOutput output,
      String localeTag,
      PresentationProperties effectivePresentation) {
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(recipient)) {
      try {
        String projected =
            outputProjector.projectPlayerOutput(session, output, localeTag, effectivePresentation);
        if (!StringUtils.hasText(projected)) {
          return;
        }
        session.sendMessage(new TextMessage(projected));
        meterRegistry
            .counter("gamesession.communication.delivery.pushed", "role", roleTag(view))
            .increment();
      } catch (IOException ex) {
        LOG.warn(
            "Failed to push {} communication view to session {}",
            roleTag(view),
            recipient.sessionId(),
            ex);
        meterRegistry
            .counter("gamesession.communication.delivery.failed", "role", roleTag(view))
            .increment();
        activeTransportSessionRegistry.unregister(recipient.sessionId(), session);
      }
    }
  }

  private String roleTag(CommunicationRecipientView view) {
    return view.getRole().name().toLowerCase();
  }

  private String resolveLocaleTag(SessionContext recipient, SessionContext actorContext) {
    return StringUtils.hasText(recipient.localeTag())
        ? recipient.localeTag()
        : actorContext.localeTag();
  }
}
