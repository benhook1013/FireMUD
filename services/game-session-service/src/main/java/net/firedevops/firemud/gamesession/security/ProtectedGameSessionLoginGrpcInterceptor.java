package net.firedevops.firemud.gamesession.security;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Set;

/** Allows only health and exact owner-read methods in the protected LOGIN composition. */
public final class ProtectedGameSessionLoginGrpcInterceptor implements ServerInterceptor {
  // Do not derive this from AuthTokenInterceptor's public-method configuration: each owner-read
  // entry below is also guarded by its existing exact workload/method handler check.
  private static final Set<String> ALLOWED_METHODS =
      Set.of(
          "game_session.v1.GameSessionService/Ping",
          "game_session.v1.GameSessionControlPlaneService/GetInitialAdmissionBindProof",
          "game_session.v1.GameSessionControlPlaneService/GetPublishedRealmAdmissionOwnerRead",
          "game_session.v1.GameSessionControlPlaneService/GetCanonicalGameplayAdmissionDecision",
          "game_session.v1.GameSessionControlPlaneService/GetCanonicalGameInstanceLaunchAssociation",
          "game_session.v1.GameSessionControlPlaneService/GetRuntimeRegionInitializationCheckpoint",
          "game_session.v1.GameSessionControlPlaneService/GetCanonicalInitialAdmissionOwnerProof",
          "game_session.v1.CanonicalGameplayRosterOwnerReadService/GetCanonicalGameplayRosterOwnerRead",
          "game_session.v1.CanonicalGameplayRosterOwnerReadService/GetPreseededActorAssignmentOwnerRead");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (ALLOWED_METHODS.contains(call.getMethodDescriptor().getFullMethodName())) {
      return next.startCall(call, headers);
    }
    call.close(
        Status.PERMISSION_DENIED.withDescription(
            "Game Session LOGIN-only composition denies this gRPC method"),
        new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
