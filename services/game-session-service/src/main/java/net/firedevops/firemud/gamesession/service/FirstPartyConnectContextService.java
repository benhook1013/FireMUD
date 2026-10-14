package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.Optional;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.ContextClaims;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GatewayVerificationKeySource;
import net.firedevops.firemud.gamesession.config.FirstPartyConnectContextProperties;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Configuration and authority/key sources are framework-managed dependencies")
public final class FirstPartyConnectContextService {
  private final FirstPartyConnectContextProperties properties;
  private final GatewayVerificationKeySource keySource;
  private final CanonicalPlayerRouteReadService routes;
  private final Clock clock;

  @Autowired
  public FirstPartyConnectContextService(
      FirstPartyConnectContextProperties properties,
      GatewayVerificationKeySource keySource,
      ObjectProvider<CanonicalPlayerRouteReadService> routes) {
    this(
        properties,
        keySource,
        routes.getIfAvailable(CanonicalPlayerRouteReadService::unavailable),
        Clock.systemUTC());
  }

  public FirstPartyConnectContextService(
      FirstPartyConnectContextProperties properties,
      GatewayVerificationKeySource keySource,
      CanonicalPlayerRouteReadService routes,
      Clock clock) {
    this.properties = properties;
    this.keySource = keySource;
    this.routes = routes;
    this.clock = clock;
  }

  public Optional<FirstPartyConnectContext> parse(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    try {
      var verified =
          GatewayFirstPartyConnectContextEnvelope.verify(
              token, keySource, clock.millis(), properties.getAllowedClockSkewMs());
      CanonicalPlayableTarget target = routes.readCurrentTarget(verified.claims().tenantId());
      if (!matches(verified.claims(), target)) {
        return Optional.empty();
      }
      return Optional.of(new FirstPartyConnectContext(token, verified, target));
    } catch (RuntimeException invalidOrUnavailable) {
      // Neither signed payload nor key/owner details belong in ordinary logs.
      return Optional.empty();
    }
  }

  public FirstPartyConnectContext requireRetained(FirstPartyConnectContext retained) {
    if (retained == null) {
      throw new IllegalStateException("First-party context is absent");
    }
    FirstPartyConnectContext current =
        parse(retained.compactJws())
            .orElseThrow(
                () -> new IllegalStateException("First-party context is invalid or unavailable"));
    if (!current.resolvedTarget().equals(retained.resolvedTarget())) {
      throw new IllegalStateException("First-party target changed");
    }
    return current;
  }

  private static boolean matches(ContextClaims claims, CanonicalPlayableTarget target) {
    // The current canonical resolver supports only public-production SHARED targets.
    return target != null
        && claims.tenantId().equals(target.canonicalTenantId())
        && claims.realmId().equals(target.realmId())
        && claims.worldSlug().equals(target.worldSlug())
        && claims.realmSlug().equals(target.realmSlug())
        && claims.playableStateNamespaceId().equals(target.playableStateNamespaceId())
        && "SHARED".equals(claims.playableStateScope())
        && claims.playableStateScope().equals(target.playableStateScope())
        && claims.gameInstanceId().equals(target.canonicalGameInstanceId())
        && claims.catalogRevision().equals(Long.toString(target.catalogRevision()))
        && claims.pointerVersion().equals(Long.toString(target.pointerVersion()))
        && claims.playtestLifecycleId() == null
        && claims.playtestStateGeneration() == null;
  }
}
