package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.ContextClaims;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.FailureReason;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.GatewayVerificationKeySource;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.VerificationException;
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
  private static final String PARSE_FAILURES_METRIC =
      "gamesession.first_party_connect_context.parse.failures";
  private static final String FAILURE_REASON_TAG = "reason";
  private static final String KEY_UNAVAILABLE = "key_unavailable";
  private static final String INVALID_ENVELOPE = "invalid_envelope";
  private static final String OWNER_UNAVAILABLE = "owner_unavailable";
  private static final String TARGET_MISMATCH = "target_mismatch";

  private final FirstPartyConnectContextProperties properties;
  private final GatewayVerificationKeySource keySource;
  private final CanonicalPlayerRouteReadService routes;
  private final Clock clock;
  private final MeterRegistry meterRegistry;

  @Autowired
  public FirstPartyConnectContextService(
      FirstPartyConnectContextProperties properties,
      GatewayVerificationKeySource keySource,
      ObjectProvider<CanonicalPlayerRouteReadService> routes,
      MeterRegistry meterRegistry) {
    this(
        properties,
        keySource,
        routes.getIfAvailable(CanonicalPlayerRouteReadService::unavailable),
        Clock.systemUTC(),
        meterRegistry);
  }

  public FirstPartyConnectContextService(
      FirstPartyConnectContextProperties properties,
      GatewayVerificationKeySource keySource,
      CanonicalPlayerRouteReadService routes,
      Clock clock,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.keySource = keySource;
    this.routes = routes;
    this.clock = clock;
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
  }

  public Optional<FirstPartyConnectContext> parse(String token) {
    if (token == null || token.isBlank()) {
      recordParseFailure(INVALID_ENVELOPE);
      return Optional.empty();
    }
    GatewayFirstPartyConnectContextEnvelope.VerifiedContext verified;
    try {
      verified =
          GatewayFirstPartyConnectContextEnvelope.verify(
              token, keySource, clock.millis(), properties.getAllowedClockSkewMs());
    } catch (VerificationException invalidEnvelope) {
      recordParseFailure(
          invalidEnvelope.reason() == FailureReason.KEY_SOURCE_UNAVAILABLE
              ? KEY_UNAVAILABLE
              : INVALID_ENVELOPE);
      return Optional.empty();
    } catch (RuntimeException invalidOrUnavailable) {
      // Neither signed payload nor key/owner details belong in ordinary logs.
      recordParseFailure(INVALID_ENVELOPE);
      return Optional.empty();
    }
    CanonicalPlayableTarget target;
    try {
      target = routes.readCurrentTarget(verified.claims().tenantId());
    } catch (CanonicalPlayerRouteReadService.ReadUnavailableException ownerUnavailable) {
      recordParseFailure(OWNER_UNAVAILABLE);
      return Optional.empty();
    } catch (RuntimeException invalidOrUnavailable) {
      recordParseFailure(INVALID_ENVELOPE);
      return Optional.empty();
    }
    if (!matches(verified.claims(), target)) {
      recordParseFailure(TARGET_MISMATCH);
      return Optional.empty();
    }
    return Optional.of(new FirstPartyConnectContext(token, verified, target));
  }

  private void recordParseFailure(String reason) {
    meterRegistry.counter(PARSE_FAILURES_METRIC, FAILURE_REASON_TAG, reason).increment();
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
