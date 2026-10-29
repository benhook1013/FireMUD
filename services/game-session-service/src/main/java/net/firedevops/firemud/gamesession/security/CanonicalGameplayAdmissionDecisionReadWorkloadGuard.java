package net.firedevops.firemud.gamesession.security;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Exact same-namespace Account-only guard for the original lease decision read. */
public final class CanonicalGameplayAdmissionDecisionReadWorkloadGuard
    implements ServerInterceptor {
  public static final String METHOD =
      "game_session.v1.CanonicalGameplayAdmissionDecisionReadService/"
          + "GetCanonicalGameplayAdmissionDecision";

  private final String workloadNamespace;

  public CanonicalGameplayAdmissionDecisionReadWorkloadGuard(String workloadNamespace) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Game Session workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  public void requireAccountCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Account workload identity required")
          .asRuntimeException();
    }
    if (SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || !"account-service".equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/account-service")
            .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Account workload required")
          .asRuntimeException();
    }
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Objects.requireNonNull(call, "gRPC server call required");
    if (!METHOD.equals(call.getMethodDescriptor().getFullMethodName())) {
      call.close(
          Status.PERMISSION_DENIED.withDescription("Exact Account decision-read method required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    try {
      requireAccountCaller();
    } catch (StatusRuntimeException denied) {
      call.close(Status.fromCode(denied.getStatus().getCode()), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return next.startCall(call, headers);
  }
}
