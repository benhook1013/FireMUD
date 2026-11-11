package net.firedevops.firemud.accountservice.service.impl;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.account.v1.AccountControlUiAuthenticationServiceGrpc;
import net.firedevops.firemud.account.v1.AuthLoginRequest;
import net.firedevops.firemud.account.v1.AuthLoginResponse;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceService;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Exact Gateway workload receiving. Primary authentication and issuance remain Account-owned. */
public final class AccountControlUiAuthenticationGrpcService
    extends AccountControlUiAuthenticationServiceGrpc
        .AccountControlUiAuthenticationServiceImplBase {
  private final AccountControlUiIssuanceService issuance;
  private final AccountControlUiActorService actors;
  private final String namespace;
  private final String gatewayPeer;

  public AccountControlUiAuthenticationGrpcService(
      AccountControlUiIssuanceService issuance,
      AccountControlUiActorService actors,
      String namespace) {
    this.issuance = Objects.requireNonNull(issuance);
    this.actors = Objects.requireNonNull(actors);
    if (!GrpcPeerIdentity.isValidNamespace(namespace))
      throw new IllegalArgumentException("Exact Account namespace required");
    this.namespace = namespace;
    this.gatewayPeer = "spiffe://firemud/ns/" + namespace + "/sa/spring-cloud-gateway";
  }

  @Override
  public void authLogin(AuthLoginRequest wire, StreamObserver<AuthLoginResponse> response) {
    var peer = GrpcPeerIdentity.current();
    if (peer == null || !gatewayPeer.equals(peer.uri())) {
      response.onError(
          Status.PERMISSION_DENIED
              .withDescription("Authenticated Gateway workload required")
              .asRuntimeException());
      return;
    }
    AccountControlUiAuthenticationGrpcCodec.Request request;
    AccountControlUiIssuanceService.AccountSignInRequest signInRequest;
    try {
      request = AccountControlUiAuthenticationGrpcCodec.parseRequest(wire);
      if (!namespace.equals(request.callerNamespace())) throw new IllegalArgumentException();
      signInRequest =
          new AccountControlUiIssuanceService.AccountSignInRequest(
              request.requestId(),
              request.email(),
              request.secret(),
              request.callerContextId(),
              request.sourceContext());
    } catch (IllegalArgumentException invalid) {
      response.onError(
          Status.INVALID_ARGUMENT
              .withDescription("Invalid Account login request")
              .asRuntimeException());
      return;
    }
    try (var credential = issuance.issueAccount(signInRequest)) {
      byte[] compact = credential.compactBytes();
      try {
        String token = new String(compact, StandardCharsets.US_ASCII);
        var actor = actors.authenticateAccount(token);
        response.onNext(
            AccountControlUiAuthenticationGrpcCodec.toResponse(
                request,
                new AccountControlUiAuthenticationGrpcCodec.Authenticated(
                    actor.accountId(), token, credential.expiresAt().getEpochSecond())));
        response.onCompleted();
      } finally {
        Arrays.fill(compact, (byte) 0);
      }
    } catch (AuthenticationException denied) {
      response.onNext(AccountControlUiAuthenticationGrpcCodec.toResponse(request, null));
      response.onCompleted();
    } catch (AccountControlUiIssuanceService.IdempotencyConflictException conflict) {
      response.onError(
          Status.ALREADY_EXISTS
              .withDescription("Account login request conflicts")
              .asRuntimeException());
    } catch (RuntimeException unavailable) {
      response.onError(
          Status.UNAVAILABLE
              .withDescription("Account login authority unavailable")
              .asRuntimeException());
    }
  }
}
