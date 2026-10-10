package net.firedevops.firemud.accountservice.service.session;

import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerRequest;
import net.firedevops.firemud.account.v1.SelectedOwnerIntakeAuthorizationProducerResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerCredentials;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerProtoCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, unregistered same-namespace Game Design selected-owner intake producer. */
public final class AccountSelectedOwnerIntakeAuthorizationProducerGrpcService
    implements BindableService {
  private final AccountSelectedOwnerIntakeAuthorizationService owner;
  private final AccountHostedTermsService terms;
  private final String namespace;

  public AccountSelectedOwnerIntakeAuthorizationProducerGrpcService(
      AccountSelectedOwnerIntakeAuthorizationService owner,
      AccountHostedTermsService terms,
      String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.terms = Objects.requireNonNull(terms);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  /** Peer authentication precedes protected metadata parsing and all evidence decoding. */
  @Override
  public ServerServiceDefinition bindService() {
    var handler =
        new AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
            .AccountSelectedOwnerIntakeAuthorizationProducerServiceImplBase() {
          @Override
          public void authorizeSelectedOwnerIntake(
              SelectedOwnerIntakeAuthorizationProducerRequest request,
              StreamObserver<SelectedOwnerIntakeAuthorizationProducerResponse> observer) {
            AccountSelectedOwnerIntakeAuthorizationProducerGrpcService.this
                .authorizeSelectedOwnerIntake(request, observer);
          }
        };
    return ServerInterceptors.intercept(
        handler,
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Status denied = peerDenial();
            if (denied != null) {
              call.close(sanitized(denied), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            String method = call.getMethodDescriptor().getFullMethodName();
            if (!SelectedOwnerIntakeAuthorizationProducerCredentials.allowedMethod(method)) {
              call.close(sanitized(Status.UNIMPLEMENTED), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            if (hasAmbientSql()) {
              call.close(sanitized(Status.FAILED_PRECONDITION), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            final SelectedOwnerIntakeAuthorizationProducerCredentials.Credential credential;
            try {
              credential =
                  SelectedOwnerIntakeAuthorizationProducerCredentials.readAuthenticated(
                      headers, method);
            } catch (IllegalArgumentException malformed) {
              call.close(sanitized(Status.UNAUTHENTICATED), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            Context context =
                Context.current()
                    .withValue(
                        SelectedOwnerIntakeAuthorizationProducerCredentials.CONTEXT_KEY,
                        credential);
            return Contexts.interceptCall(context, call, headers, next);
          }
        });
  }

  public void authorizeSelectedOwnerIntake(
      SelectedOwnerIntakeAuthorizationProducerRequest wire,
      StreamObserver<SelectedOwnerIntakeAuthorizationProducerResponse> observer) {
    final AuthenticatedRequest authenticated;
    try {
      authenticated = request(wire);
    } catch (StatusRuntimeException denied) {
      deny(observer, Status.fromCode(denied.getStatus().getCode()));
      return;
    }
    var request = authenticated.request();
    if (!namespace.equals(request.targetNamespace())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }

    final SelectedOwnerIntakeAuthorizationProducerResponse response;
    try {
      var binding =
          owner.authorizeWithEnvironmentCapture(
              authenticated.originalCreatorCredential(),
              request.intakeRequestId(),
              request.owner(),
              request.selected(),
              terms::captureCurrentEnvironmentBoundary);
      var result =
          new SelectedOwnerIntakeAuthorizationProducerEvidence.Result(
              request, binding, binding.digest());
      response = SelectedOwnerIntakeAuthorizationProducerProtoCodec.toResponse(result);
    } catch (RuntimeException unavailable) {
      deny(observer, statusOf(unavailable));
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private AuthenticatedRequest request(SelectedOwnerIntakeAuthorizationProducerRequest wire) {
    Status denied = peerDenial();
    if (denied != null) throw sanitized(denied).asRuntimeException();
    if (hasAmbientSql()) throw sanitized(Status.FAILED_PRECONDITION).asRuntimeException();

    var protectedCredential = SelectedOwnerIntakeAuthorizationProducerCredentials.CONTEXT_KEY.get();
    if (protectedCredential == null) throw sanitized(Status.UNAUTHENTICATED).asRuntimeException();
    final String credential;
    try {
      credential = protectedCredential.value();
    } catch (IllegalArgumentException malformed) {
      throw sanitized(Status.UNAUTHENTICATED).asRuntimeException();
    }
    try {
      return new AuthenticatedRequest(
          SelectedOwnerIntakeAuthorizationProducerProtoCodec.fromRequest(wire), credential);
    } catch (IllegalArgumentException malformed) {
      throw sanitized(Status.INVALID_ARGUMENT).asRuntimeException();
    }
  }

  private Status peerDenial() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) return Status.UNAUTHENTICATED;
    if (SessionContext.hasAuthenticatedCallerContext()
        || !namespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())) {
      return Status.PERMISSION_DENIED;
    }
    return null;
  }

  private static boolean hasAmbientSql() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }

  private static Status statusOf(RuntimeException error) {
    if (error instanceof StatusRuntimeException status)
      return Status.fromCode(status.getStatus().getCode());
    if (error instanceof IllegalArgumentException) return Status.FAILED_PRECONDITION;
    return Status.UNAVAILABLE;
  }

  private static void deny(StreamObserver<?> observer, Status status) {
    observer.onError(sanitized(status).asRuntimeException());
  }

  private static Status sanitized(Status status) {
    return status.withDescription(
        "Account selected-owner intake authorization denied or unavailable");
  }

  private record AuthenticatedRequest(
      SelectedOwnerIntakeAuthorizationProducerEvidence.Request request,
      String originalCreatorCredential) {
    @Override
    public String toString() {
      return "AuthenticatedSelectedOwnerIntakeAuthorizationRequest[redacted]";
    }
  }
}
