package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.security.JwtClaims;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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
    if (!hasGameplayBinding(context) || currentAdmissionPointerMatches(context)) {
      return context;
    }
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

  private boolean hasGameplayBinding(SessionContext context) {
    return context.gameInstanceId() > 0
        || context.characterId() > 0
        || StringUtils.hasText(context.roomInstanceId());
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
    try {
      return Optional.of(JwtClaims.requireLong(text, "sessionId", false));
    } catch (RuntimeException ex) {
      return Optional.empty();
    }
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
