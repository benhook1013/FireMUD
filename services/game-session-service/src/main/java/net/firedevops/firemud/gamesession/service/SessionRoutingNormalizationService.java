package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class SessionRoutingNormalizationService {
  private final SessionContextService sessionContextService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;

  public SessionRoutingNormalizationService(
      SessionContextService sessionContextService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService) {
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.gameplayAdmissionPointerAuthorityService =
        Objects.requireNonNull(
            gameplayAdmissionPointerAuthorityService,
            "gameplayAdmissionPointerAuthorityService must not be null");
  }

  public Optional<SessionContext> resolveProjectedSessionContext(String sessionIdText) {
    Optional<Long> maybeSessionId = parseSessionId(sessionIdText);
    if (maybeSessionId.isEmpty()) {
      return Optional.empty();
    }
    long sessionId = maybeSessionId.get();
    Optional<Long> maybeTenantId = findTenantId(sessionId);
    if (maybeTenantId.isEmpty()) {
      return Optional.empty();
    }
    return sessionContextService
        .findByTenantAndSessionId(maybeTenantId.get(), sessionId)
        .map(this::normalizeProjectedContext);
  }

  public SessionContext normalizeProjectedContext(SessionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    if (!context.hasGameplayBinding()) {
      return context;
    }
    if (!hasCanonicalGameplayRoomBinding(context)) {
      return clearGameplayBinding(context);
    }
    if (currentAdmissionPointerMatches(context)) {
      return context;
    }
    return clearGameplayBinding(context);
  }

  private SessionContext clearGameplayBinding(SessionContext context) {
    return new SessionContext(
        context.sessionId(),
        context.tenantId(),
        context.accountId(),
        context.loginName(),
        0L,
        null,
        0L,
        null,
        context.jwt(),
        context.localeTag(),
        context.bootstrapGameInstanceId(),
        null,
        null,
        0L,
        null,
        context.connectScopeId(),
        context.connectRequestId());
  }

  private boolean hasCanonicalGameplayRoomBinding(SessionContext context) {
    if (context.gameInstanceId() <= 0 || context.roomInstanceId() == null) {
      return true;
    }
    try {
      GameplayRuntimeRoomIds.requireCanonical(context.roomInstanceId(), "roomInstanceId");
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private boolean currentAdmissionPointerMatches(SessionContext context) {
    return GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
        gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
            context.tenantId(), context.gameInstanceId()),
        context.tenantId(),
        context.gameInstanceId(),
        context.worldSlug(),
        context.realmSlug(),
        context.pointerVersion());
  }

  private Optional<Long> parseSessionId(String text) {
    return SessionIdParsing.parse(text).optionalValue();
  }

  public Optional<Long> findTenantId(long sessionId) {
    Optional<Long> tenantFromContext =
        sessionContextService.findBySessionId(sessionId).map(SessionContext::tenantId);
    if (tenantFromContext.isPresent()) {
      return tenantFromContext;
    }
    return Optional.empty();
  }
}
