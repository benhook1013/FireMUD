package net.firedevops.firemud.gamesession.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientRole;
import net.firedevops.firemud.gamelogic.v1.CommunicationRecipientView;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Delivers structured non-actor communication views to live recipient sessions. */
@Service
public final class CommunicationRecipientDeliveryService {
  private static final Logger LOG =
      LoggerFactory.getLogger(CommunicationRecipientDeliveryService.class);

  private final SessionContextService sessionContextService;
  private final ActiveTransportSessionRegistry activeTransportSessionRegistry;
  private final ScreenBufferService screenBufferService;
  private final MeterRegistry meterRegistry;

  public CommunicationRecipientDeliveryService(
      SessionContextService sessionContextService,
      ActiveTransportSessionRegistry activeTransportSessionRegistry,
      ScreenBufferService screenBufferService,
      MeterRegistry meterRegistry) {
    this.sessionContextService = sessionContextService;
    this.activeTransportSessionRegistry = activeTransportSessionRegistry;
    this.screenBufferService = screenBufferService;
    this.meterRegistry = meterRegistry;
  }

  public void deliver(SessionContext actorContext, SendCommunicationResponse response) {
    response.getRecipientViewsList().stream()
        .filter(
            view -> view.getRole() != CommunicationRecipientRole.COMMUNICATION_RECIPIENT_ROLE_ACTOR)
        .forEach(view -> deliverView(actorContext, view));
  }

  private void deliverView(SessionContext actorContext, CommunicationRecipientView view) {
    if (!StringUtils.hasText(view.getRenderedText())) {
      return;
    }
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

    screenBufferService.append(
        recipient.tenantId(),
        recipient.gameInstanceId(),
        recipient.characterId(),
        view.getRenderedText() + "\n");

    activeTransportSessionRegistry
        .find(recipient.sessionId())
        .filter(WebSocketSession::isOpen)
        .ifPresentOrElse(
            session -> push(session, recipient.sessionId(), view),
            () ->
                meterRegistry
                    .counter(
                        "gamesession.communication.delivery.buffered_only", "role", roleTag(view))
                    .increment());
  }

  private Optional<SessionContext> resolveRecipient(
      SessionContext actorContext, CommunicationRecipientView view) {
    Optional<Long> maybeRecipientId = parseRecipientId(view.getRecipientId());
    if (maybeRecipientId.isPresent()) {
      Optional<SessionContext> byIdentity =
          sessionContextService.findByGameplayIdentity(
              actorContext.tenantId(),
              actorContext.gameInstanceId(),
              maybeRecipientId.orElseThrow());
      if (byIdentity.isPresent()) {
        return byIdentity;
      }
    }
    if (!StringUtils.hasText(view.getRecipientName())) {
      return Optional.empty();
    }
    return sessionContextService.findByGameplayName(
        actorContext.tenantId(), actorContext.gameInstanceId(), view.getRecipientName());
  }

  private void push(WebSocketSession session, long sessionId, CommunicationRecipientView view) {
    try {
      session.sendMessage(new TextMessage(view.getRenderedText()));
      meterRegistry
          .counter("gamesession.communication.delivery.pushed", "role", roleTag(view))
          .increment();
    } catch (IOException ex) {
      LOG.warn("Failed to push {} communication view to session {}", roleTag(view), sessionId, ex);
      meterRegistry
          .counter("gamesession.communication.delivery.failed", "role", roleTag(view))
          .increment();
      activeTransportSessionRegistry.unregister(sessionId, session);
    }
  }

  private Optional<Long> parseRecipientId(String recipientId) {
    if (!StringUtils.hasText(recipientId)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(recipientId));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private String roleTag(CommunicationRecipientView view) {
    return view.getRole().name().toLowerCase();
  }
}
