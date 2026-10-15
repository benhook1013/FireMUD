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
import net.firedevops.firemud.account.v1.AccountSelectedPublicationOrderServiceGrpc;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationRequest;
import net.firedevops.firemud.account.v1.AuthorizeSelectedPublicationResponse;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderCredentials;
import net.firedevops.firemud.common.publication.AccountSelectedPublicationOrderGrpcCodec;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Standalone, unregistered producer; Account obtains environment authority internally. */
public final class AccountSelectedDraftPublicationOrderGrpcService implements BindableService {
  private final AccountSelectedDraftPublicationOrderService owner;
  private final AccountHostedTermsService terms;
  private final String namespace;

  public AccountSelectedDraftPublicationOrderGrpcService(
      AccountSelectedDraftPublicationOrderService owner,
      AccountHostedTermsService terms,
      String namespace) {
    this.owner = Objects.requireNonNull(owner);
    this.terms = Objects.requireNonNull(terms);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Canonical Account namespace required");
    this.namespace = namespace;
  }

  /**
   * Binding always includes the method-local credential interceptor; peer identity runs outside it.
   */
  @Override
  public ServerServiceDefinition bindService() {
    var handler =
        new AccountSelectedPublicationOrderServiceGrpc
            .AccountSelectedPublicationOrderServiceImplBase() {
          @Override
          public void authorizeSelectedPublication(
              AuthorizeSelectedPublicationRequest request,
              StreamObserver<AuthorizeSelectedPublicationResponse> observer) {
            AccountSelectedDraftPublicationOrderGrpcService.this.authorizeSelectedPublication(
                request, observer);
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
            final AccountSelectedPublicationOrderCredentials.Credential credential;
            try {
              credential = AccountSelectedPublicationOrderCredentials.readAuthenticated(headers);
            } catch (IllegalArgumentException malformed) {
              call.close(sanitized(Status.UNAUTHENTICATED), new Metadata());
              return new ServerCall.Listener<>() {};
            }
            var context =
                Context.current()
                    .withValue(AccountSelectedPublicationOrderCredentials.CONTEXT_KEY, credential);
            return Contexts.interceptCall(context, call, headers, next);
          }
        });
  }

  public void authorizeSelectedPublication(
      AuthorizeSelectedPublicationRequest wire,
      StreamObserver<AuthorizeSelectedPublicationResponse> observer) {
    Status denied = peerDenial();
    if (denied != null) {
      deny(observer, denied);
      return;
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      deny(observer, Status.FAILED_PRECONDITION);
      return;
    }
    final AccountSelectedPublicationOrderGrpcCodec.Request request;
    final String credential;
    try {
      var protectedCredential = AccountSelectedPublicationOrderCredentials.CONTEXT_KEY.get();
      if (protectedCredential == null) {
        deny(observer, Status.UNAUTHENTICATED);
        return;
      }
      credential = protectedCredential.value();
    } catch (IllegalArgumentException malformed) {
      deny(observer, Status.UNAUTHENTICATED);
      return;
    }
    try {
      request = AccountSelectedPublicationOrderGrpcCodec.fromRequest(wire, credential);
    } catch (IllegalArgumentException malformed) {
      deny(observer, Status.INVALID_ARGUMENT);
      return;
    }
    if (!namespace.equals(request.targetNamespace())) {
      deny(observer, Status.PERMISSION_DENIED);
      return;
    }
    final AuthorizeSelectedPublicationResponse response;
    try {
      var captured = terms.captureCurrentEnvironmentBoundary();
      var binding =
          owner.authorize(request.originalCreatorCredential(), request.selection(), captured);
      response = AccountSelectedPublicationOrderGrpcCodec.toResponse(request, binding);
    } catch (StatusRuntimeException failure) {
      deny(observer, Status.fromCode(failure.getStatus().getCode()));
      return;
    } catch (RuntimeException unavailable) {
      deny(observer, Status.UNAVAILABLE);
      return;
    }
    observer.onNext(response);
    observer.onCompleted();
  }

  private static void deny(StreamObserver<?> observer, Status status) {
    observer.onError(sanitized(status).asRuntimeException());
  }

  private Status peerDenial() {
    var peer = GrpcPeerIdentity.current();
    if (peer == null) return Status.UNAUTHENTICATED;
    return ("spiffe://firemud/ns/" + namespace + "/sa/game-design-service").equals(peer.uri())
        ? null
        : Status.PERMISSION_DENIED;
  }

  private static Status sanitized(Status status) {
    return status.withDescription("Account selected-publication order denied or unavailable");
  }
}
