package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.account.v1.ReadinessReceiverLocalIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;

/**
 * Supplies receiver-local Pod and JWKS pins from protected runtime configuration, never a request.
 */
public interface GameSessionJwtReadinessLocalIdentityProvider {
  LocalObservation observe();

  record LocalObservation(
      ReadinessReceiverLocalIdentity wireIdentity, SourceIdentity jwksSourceIdentity) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "ReadinessReceiverLocalIdentity is an immutable generated protobuf value.")
    public LocalObservation {
      Objects.requireNonNull(wireIdentity, "protected receiver identity is required");
      Objects.requireNonNull(jwksSourceIdentity, "protected JWKS source identity is required");
      if (!wireIdentity.hasAccountJwksSourceIdentity()
          || !jwksSourceIdentity.equals(fromProto(wireIdentity.getAccountJwksSourceIdentity()))
          || wireIdentity.getAccountJwksTrustConfigRevision() <= 0L) {
        throw new IllegalArgumentException("Protected receiver JWKS identity is inconsistent");
      }
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "ReadinessReceiverLocalIdentity is an immutable generated protobuf value.")
    public ReadinessReceiverLocalIdentity wireIdentity() {
      return wireIdentity;
    }

    private static SourceIdentity fromProto(AccountSourceIdentity value) {
      return new SourceIdentity(
          value.getEnvironmentId(),
          value.getClusterId(),
          value.getClusterIncarnationUid(),
          value.getNamespace(),
          value.getNamespaceUid(),
          value.getConfigMapUid(),
          value.getBindingRevision(),
          value.getApiServerOrigin(),
          value.getServingCaSha256());
    }
  }
}
