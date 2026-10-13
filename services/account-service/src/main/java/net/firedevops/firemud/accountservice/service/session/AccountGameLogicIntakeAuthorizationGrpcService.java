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
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationServiceGrpc;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationRequest;
import net.firedevops.firemud.account.v1.GameLogicIntakeAuthorizationResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.gamelogic.AccountGameLogicIntakeAuthorizationCredentials;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, unregistered same-namespace Game Design intake authorization producer. */
public final class AccountGameLogicIntakeAuthorizationGrpcService implements BindableService {
  private final AccountGameLogicIntakeAuthorizationService owner;
  private final AccountHostedTermsService terms;
  private final String namespace;

  public AccountGameLogicIntakeAuthorizationGrpcService(
      AccountGameLogicIntakeAuthorizationService owner,
      AccountHostedTermsService terms,
      String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.terms = Objects.requireNonNull(terms);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  /** Peer authentication precedes protected metadata parsing and all request decoding. */
  @Override
  public ServerServiceDefinition bindService() {
    var handler =
        new AccountGameLogicIntakeAuthorizationServiceGrpc
            .AccountGameLogicIntakeAuthorizationServiceImplBase() {
          @Override
          public void authorizeIntake(
              GameLogicIntakeAuthorizationRequest request,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
            AccountGameLogicIntakeAuthorizationGrpcService.this.authorizeIntake(request, observer);
          }

          @Override
          public void recoverIntake(
              GameLogicIntakeAuthorizationRequest request,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
            AccountGameLogicIntakeAuthorizationGrpcService.this.recoverIntake(request, observer);
          }

          @Override
          public void abortIntake(
              GameLogicIntakeAuthorizationRequest request,
              StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
            AccountGameLogicIntakeAuthorizationGrpcService.this.abortIntake(request, observer);
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
            if (!AccountGameLogicIntakeAuthorizationCredentials.allowedMethod(method)) {
              call.close(sanitized(Status.UNIMPLEMENTED), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            final AccountGameLogicIntakeAuthorizationCredentials.Credential credential;
            try {
              credential =
                  AccountGameLogicIntakeAuthorizationCredentials.readAuthenticated(headers, method);
            } catch (IllegalArgumentException malformed) {
              call.close(sanitized(Status.UNAUTHENTICATED), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            var context =
                Context.current()
                    .withValue(
                        AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY, credential);
            return Contexts.interceptCall(context, call, headers, next);
          }
        });
  }

  public void authorizeIntake(
      GameLogicIntakeAuthorizationRequest wire,
      StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
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
    final GameLogicIntakeAuthorizationResponse response;
    try {
      var authorization =
          owner.authorizeWithEnvironmentCapture(
              authenticated.originalCreatorCredential(),
              request.intakeRequestId(),
              request.selected(),
              terms::captureCurrentEnvironmentBoundary);
      response =
          GameLogicIntakeAuthorizationGrpcCodec.toResponse(
              GameLogicIntakeAuthorizationEvidence.Result.authorized(request, authorization));
    } catch (RuntimeException unavailable) {
      deny(observer, statusOf(unavailable));
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  public void recoverIntake(
      GameLogicIntakeAuthorizationRequest wire,
      StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
    var authenticated = parseRequest(wire, observer);
    if (authenticated == null) return;
    var request = authenticated.request();
    final GameLogicIntakeAuthorizationResponse response;
    try {
      var recovery =
          owner.recover(
              authenticated.originalCreatorCredential(),
              request.intakeRequestId(),
              request.selected());
      response = GameLogicIntakeAuthorizationGrpcCodec.toResponse(recovered(request, recovery));
    } catch (RuntimeException unavailable) {
      deny(observer, statusOf(unavailable));
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  public void abortIntake(
      GameLogicIntakeAuthorizationRequest wire,
      StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
    var authenticated = parseRequest(wire, observer);
    if (authenticated == null) return;
    var request = authenticated.request();
    final GameLogicIntakeAuthorizationResponse response;
    try {
      var recovery =
          owner.abortSourceRead(
              authenticated.originalCreatorCredential(),
              request.intakeRequestId(),
              request.selected());
      response = GameLogicIntakeAuthorizationGrpcCodec.toResponse(recovered(request, recovery));
    } catch (RuntimeException unavailable) {
      deny(observer, statusOf(unavailable));
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private AuthenticatedRequest parseRequest(
      GameLogicIntakeAuthorizationRequest wire,
      StreamObserver<GameLogicIntakeAuthorizationResponse> observer) {
    try {
      var authenticated = request(wire);
      var request = authenticated.request();
      if (!namespace.equals(request.targetNamespace())) {
        deny(observer, Status.PERMISSION_DENIED);
        return null;
      }
      return authenticated;
    } catch (StatusRuntimeException denied) {
      deny(observer, Status.fromCode(denied.getStatus().getCode()));
      return null;
    }
  }

  private AuthenticatedRequest request(GameLogicIntakeAuthorizationRequest wire) {
    Status denied = peerDenial();
    if (denied != null) throw sanitized(denied).asRuntimeException();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw sanitized(Status.FAILED_PRECONDITION).asRuntimeException();
    var protectedCredential = AccountGameLogicIntakeAuthorizationCredentials.CONTEXT_KEY.get();
    if (protectedCredential == null) throw sanitized(Status.UNAUTHENTICATED).asRuntimeException();
    final String credential;
    try {
      credential = protectedCredential.value();
    } catch (IllegalArgumentException malformed) {
      throw sanitized(Status.UNAUTHENTICATED).asRuntimeException();
    }
    try {
      return new AuthenticatedRequest(
          GameLogicIntakeAuthorizationGrpcCodec.fromRequest(wire), credential);
    } catch (IllegalArgumentException malformed) {
      throw sanitized(Status.INVALID_ARGUMENT).asRuntimeException();
    }
  }

  private static GameLogicIntakeAuthorizationEvidence.Result recovered(
      GameLogicIntakeAuthorizationEvidence.Request request,
      AccountGameLogicIntakeSourceReadRecovery recovery) {
    return new GameLogicIntakeAuthorizationEvidence.Result(
        request,
        GameLogicIntakeAuthorizationEvidence.Outcome.valueOf(recovery.state().name()),
        java.util.Optional.of(recovery.scope()),
        recovery.authorization());
  }

  private record AuthenticatedRequest(
      GameLogicIntakeAuthorizationEvidence.Request request, String originalCreatorCredential) {
    @Override
    public String toString() {
      return "AuthenticatedGameLogicIntakeAuthorizationRequest[redacted]";
    }
  }

  private Status peerDenial() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) return Status.UNAUTHENTICATED;
    return ("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())
        ? null
        : Status.PERMISSION_DENIED;
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
    return status.withDescription("Account Game Logic intake authorization denied or unavailable");
  }
}
