package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseServiceGrpc;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;

/** Method-specific gate for Account's same-namespace Game Session lease receiver. */
public final class AccountGameplayAdmissionLeaseWorkloadGuard implements ServerInterceptor {
  private static final String GAME_SESSION_SERVICE = "game-session-service";

  private final String namespace;

  public AccountGameplayAdmissionLeaseWorkloadGuard(String namespace) {
    if (!GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.namespace = namespace;
  }

  /** Checks the certificate-derived peer before the receiver examines request fields. */
  public void requireGameSessionCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null) {
      throw Status.UNAUTHENTICATED
          .withDescription("Verified Game Session workload identity required")
          .asRuntimeException();
    }
    if (hasSessionContext()
        || !GAME_SESSION_SERVICE.equals(peer.service())
        || !namespace.equals(peer.namespace())
        || !expectedPeerUri().equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
  }

  /** Authenticates the peer and closes every non-lease method before its request is parsed. */
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    try {
      requireGameSessionCaller();
    } catch (StatusRuntimeException denied) {
      call.close(
          Status.fromCode(denied.getStatus().getCode())
              .withDescription("Exact same-namespace Game Session workload required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    if (!isLeaseMethod(call.getMethodDescriptor())) {
      call.close(
          Status.PERMISSION_DENIED.withDescription(
              "Account gameplay admission lease method required"),
          new Metadata());
      return new ServerCall.Listener<>() {};
    }
    return next.startCall(call, headers);
  }

  private static boolean isLeaseMethod(MethodDescriptor<?, ?> method) {
    String fullMethodName = method.getFullMethodName();
    return AccountGameplayAdmissionLeaseServiceGrpc.getAcquireGameplayAdmissionLeaseMethod()
            .getFullMethodName()
            .equals(fullMethodName)
        || AccountGameplayAdmissionLeaseServiceGrpc.getFinalizeGameplayAdmissionLeaseMethod()
            .getFullMethodName()
            .equals(fullMethodName)
        || AccountGameplayAdmissionLeaseServiceGrpc.getReadGameplayAdmissionLeaseMethod()
            .getFullMethodName()
            .equals(fullMethodName)
        || AccountGameplayAdmissionLeaseServiceGrpc.getAbortGameplayAdmissionLeaseMethod()
            .getFullMethodName()
            .equals(fullMethodName)
        || AccountGameplayAdmissionLeaseServiceGrpc.getIssueOriginalNewGameplayCredentialMethod()
            .getFullMethodName()
            .equals(fullMethodName)
        || AccountGameplayAdmissionLeaseServiceGrpc.getConfirmOriginalNewGameplayAdmissionMethod()
            .getFullMethodName()
            .equals(fullMethodName);
  }

  public void requireAcquisitionNamespace(
      AccountGameplayAdmissionLeaseGrpcCodec.AcquireRequest request) {
    if (request == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Malformed Account gameplay admission lease request")
          .asRuntimeException();
    }
    if (!namespace.equals(request.expectedTarget().targetNamespace())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Gameplay admission lease target namespace is not authorized")
          .asRuntimeException();
    }
  }

  public void requireOriginalLeaseNamespace(
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalLeaseRequest request) {
    if (request == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Malformed Account gameplay admission lease request")
          .asRuntimeException();
    }
    Object targetNamespace = request.originalLeaseEvidence().carrier().get("targetNamespace");
    if (!namespace.equals(targetNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Gameplay admission lease target namespace is not authorized")
          .asRuntimeException();
    }
  }

  public void requireOriginalNewCredentialNamespace(
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredentialRequest request) {
    if (request == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Malformed Account original-new credential request")
          .asRuntimeException();
    }
    Object targetNamespace = request.originalLeaseEvidence().carrier().get("targetNamespace");
    if (!namespace.equals(targetNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Original-new credential lease target namespace is not authorized")
          .asRuntimeException();
    }
  }

  public void requireOriginalFinalAdmissionConfirmationNamespace(
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalFinalAdmissionConfirmationRequest request) {
    if (request == null) {
      throw Status.INVALID_ARGUMENT
          .withDescription("Malformed Account original-new confirmation request")
          .asRuntimeException();
    }
    Object targetNamespace = request.originalLeaseEvidence().carrier().get("targetNamespace");
    if (!namespace.equals(targetNamespace)) {
      throw Status.PERMISSION_DENIED
          .withDescription("Original-new confirmation lease target namespace is not authorized")
          .asRuntimeException();
    }
  }

  private static boolean hasSessionContext() {
    return SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null;
  }

  private String expectedPeerUri() {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + GAME_SESSION_SERVICE;
  }
}
