package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;
import java.util.Objects;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.ContextClaims;
import net.firedevops.firemud.common.security.GatewayFirstPartyConnectContextEnvelope.VerifiedContext;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;

/** Retains the full signed handoff and its resolved owner snapshot, never login authority. */
public final class FirstPartyConnectContext implements Serializable {
  private static final long serialVersionUID = 2L;
  private final String compactJws;
  private final CanonicalPlayableTarget resolvedTarget;
  private final transient VerifiedContext verifiedContext;

  FirstPartyConnectContext(
      String compactJws, VerifiedContext verifiedContext, CanonicalPlayableTarget resolvedTarget) {
    this.compactJws = Objects.requireNonNull(compactJws);
    this.verifiedContext = Objects.requireNonNull(verifiedContext);
    this.resolvedTarget = Objects.requireNonNull(resolvedTarget);
  }

  public String compactJws() {
    return compactJws;
  }

  public CanonicalPlayableTarget resolvedTarget() {
    return resolvedTarget;
  }

  public ContextClaims claims() {
    if (verifiedContext == null) {
      throw new IllegalStateException("Retained first-party context requires verification");
    }
    return verifiedContext.claims();
  }

  public String accountId() {
    return claims().accountId().toString();
  }

  /** Private row handles are supplied only by the canonical owner read. */
  public long gameSessionTenantId() {
    return resolvedTarget.gameSessionTenantId();
  }

  public long runtimeGameInstanceId() {
    return resolvedTarget.gameInstanceId();
  }

  public String worldSlug() {
    return claims().worldSlug();
  }

  public String realmSlug() {
    return claims().realmSlug();
  }

  public long pointerVersion() {
    return resolvedTarget.pointerVersion();
  }

  public String connectScopeId() {
    return claims().connectScopeId();
  }

  public String connectRequestId() {
    return claims().connectRequestId();
  }

  public String connectTokenJti() {
    return claims().connectTokenJti();
  }

  public String gatewayRequestId() {
    return claims().gatewayRequestId();
  }

  public boolean hasCompleteRoutingScope() {
    return verifiedContext != null && resolvedTarget != null;
  }

  @Override
  public String toString() {
    return "FirstPartyConnectContext[redacted]";
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof FirstPartyConnectContext that
        && Objects.equals(compactJws, that.compactJws)
        && Objects.equals(resolvedTarget, that.resolvedTarget);
  }

  @Override
  public int hashCode() {
    return Objects.hash(compactJws, resolvedTarget);
  }
}
