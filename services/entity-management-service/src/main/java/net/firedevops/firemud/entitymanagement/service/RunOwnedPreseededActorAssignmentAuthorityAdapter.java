package net.firedevops.firemud.entitymanagement.service;

import java.util.function.Supplier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.boot.ssl.SslBundles;

/** Reads the separately provisioned run-owned action grant at both protected service boundaries. */
public final class RunOwnedPreseededActorAssignmentAuthorityAdapter
    implements RunOwnedPreseededAssignmentAuthority {
  private final String capabilityPath;
  private final String runId;
  private final String composeProjectName;
  private final String trustedGameSessionNamespace;
  private final Supplier<String> entityServerTrustRootFingerprint;

  RunOwnedPreseededActorAssignmentAuthorityAdapter(
      String capabilityPath,
      String runId,
      String composeProjectName,
      String trustedGameSessionNamespace,
      Supplier<String> entityServerTrustRootFingerprint) {
    this.capabilityPath = capabilityPath;
    this.runId = runId;
    this.composeProjectName = composeProjectName;
    this.trustedGameSessionNamespace = trustedGameSessionNamespace;
    this.entityServerTrustRootFingerprint = entityServerTrustRootFingerprint;
  }

  public static RunOwnedPreseededActorAssignmentAuthorityAdapter forActiveServerTrustBundle(
      String capabilityPath,
      String runId,
      String composeProjectName,
      String trustedGameSessionNamespace,
      SslBundles sslBundles,
      String activeServerBundleName,
      String entityServerTrustRootPath) {
    return new RunOwnedPreseededActorAssignmentAuthorityAdapter(
        capabilityPath,
        runId,
        composeProjectName,
        trustedGameSessionNamespace,
        () ->
            RunOwnedPreseededActorAssignmentCapability.activeTrustRootFingerprint(
                sslBundles, activeServerBundleName, entityServerTrustRootPath));
  }

  @Override
  public void requireAuthorized(PreseededActorAssignmentRequest request, Action dedicatedAction) {
    requireExactPeer();
    requireAction(dedicatedAction);
    RunOwnedPreseededActorAssignmentCapability capability = readCapability();
    if (!capability.matchesRequest(request)) {
      throw denied();
    }
  }

  @Override
  public void requireTargetBoundAuthorized(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence exactTarget,
      Action dedicatedAction) {
    requireExactPeer();
    requireAction(dedicatedAction);
    RunOwnedPreseededActorAssignmentCapability capability = readCapability();
    if (!capability.matchesOwnerTarget(request, exactTarget)) {
      throw denied();
    }
  }

  private RunOwnedPreseededActorAssignmentCapability readCapability() {
    try {
      return RunOwnedPreseededActorAssignmentCapability.load(
          capabilityPath,
          runId,
          composeProjectName,
          trustedGameSessionNamespace,
          entityServerTrustRootFingerprint.get());
    } catch (RuntimeException exception) {
      throw denied();
    }
  }

  private void requireExactPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (SessionContext.hasAuthenticatedCallerContext()
        || peer == null
        || !GrpcPeerIdentity.isValidNamespace(trustedGameSessionNamespace)
        || !peer.isInNamespace(trustedGameSessionNamespace)
        || !peer.isService("game-session-service")
        || !peer.uri()
            .equals(
                "spiffe://firemud/ns/"
                    + trustedGameSessionNamespace
                    + "/sa/game-session-service")) {
      throw denied();
    }
  }

  private static void requireAction(Action action) {
    if (action != Action.PRESEEDED_ACTOR_ASSIGNMENT) {
      throw denied();
    }
  }

  private static AdminAuthorizationException denied() {
    return new AdminAuthorizationException(
        "PRESEEDED_ASSIGNMENT_RUN_OWNED_ACTION_GRANT_UNAVAILABLE_OR_MISMATCHED");
  }
}
