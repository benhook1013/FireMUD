package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AccountControlUiAuthLoginStatus;
import net.firedevops.firemud.account.v1.AuthLoginRequest;
import net.firedevops.firemud.account.v1.AuthLoginResponse;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceService;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec;
import net.firedevops.firemud.common.account.auth.AccountControlUiAuthenticationGrpcCodec.SourceContext;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class AccountControlUiAuthenticationGrpcServiceTest {
  @Test
  void sharedPolicyRefusalsRemainClosedWireOutcomesRatherThanCredentialDenials() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var request = request("test");
    for (var outcome :
        java.util.List.of(
            net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Outcome
                .RETRY_LATER,
            net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService.Outcome
                .UNAVAILABLE)) {
      var refusal =
          net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseRefusal.from(
              new net.firedevops.firemud.accountservice.service.auth.AccountCredentialAbuseService
                  .Decision(outcome, java.time.Duration.ofMillis(1501), null));
      org.mockito.Mockito.doThrow(refusal).when(issuer).issueAccount(any());
      var response = new Result();
      gateway()
          .run(
              () ->
                  receiver.authLogin(
                      AccountControlUiAuthenticationGrpcCodec.toRequest(request), response));
      assertThat(response.completed).isTrue();
      assertThat(response.failureCode).isNull();
      var parsed = AccountControlUiAuthenticationGrpcCodec.requireResponse(request, response.value);
      assertThat(parsed.outcome())
          .isEqualTo(
              refusal.retryLater()
                  ? AccountControlUiAuthenticationGrpcCodec.Outcome.RETRY_LATER
                  : AccountControlUiAuthenticationGrpcCodec.Outcome.ABUSE_UNAVAILABLE);
      assertThat(parsed.retryAfterMillis()).isEqualTo(1501L);
      assertThat(parsed.authenticated()).isNull();
    }
    verifyNoInteractions(actors);
  }

  @Test
  void missingAndWrongPeerAreDeniedBeforeDecodeOrPrimaryAuthentication() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var result = new Result();
    receiver.authLogin(AuthLoginRequest.getDefaultInstance(), result);
    assertThat(result.failureCode).isEqualTo(Status.Code.PERMISSION_DENIED);
    var wrongPeer = new Result();
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service")
                .orElseThrow())
        .run(() -> receiver.authLogin(AuthLoginRequest.getDefaultInstance(), wrongPeer));
    assertThat(wrongPeer.failureCode).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(issuer, actors);
  }

  @Test
  void exactGatewayUsesCommittedIssuerAndCurrentActorAndDoesNotEchoCredentials() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    var credential = mock(AccountControlUiIssuanceService.IssuedCredential.class);
    byte[] returned = "controlled.compact.jwt".getBytes(StandardCharsets.US_ASCII);
    when(credential.compactBytes()).thenReturn(returned);
    when(credential.expiresAt()).thenReturn(Instant.ofEpochSecond(2_000_000_300L));
    when(issuer.issueAccount(any())).thenReturn(credential);
    var actor = mock(AccountControlUiActorService.AccountAuthenticatedActor.class);
    UUID accountId = UUID.randomUUID();
    when(actor.accountId()).thenReturn(accountId);
    when(actors.authenticateAccount("controlled.compact.jwt")).thenReturn(actor);
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var request = request("test");
    var result = new Result();
    gateway()
        .run(
            () ->
                receiver.authLogin(
                    AccountControlUiAuthenticationGrpcCodec.toRequest(request), result));
    assertThat(result.failureCode).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value.toString()).doesNotContain(request.secret(), request.email());
    assertThat(result.value.getAccountIdList()).containsExactly(accountId.toString());
    assertThat(result.value.getAuthTokenList()).containsExactly("controlled.compact.jwt");
    assertThat(result.value.getExpiresAtEpochSecondsList()).containsExactly(2_000_000_300L);
    assertThat(returned).containsOnly((byte) 0);
    var signIn =
        org.mockito.ArgumentCaptor.forClass(
            AccountControlUiIssuanceService.AccountSignInRequest.class);
    verify(issuer).issueAccount(signIn.capture());
    assertThat(signIn.getValue().sourceContext()).isEqualTo(request.sourceContext());
    verify(credential).close();
    verify(actors).authenticateAccount("controlled.compact.jwt");
  }

  @Test
  void missingMalformedOrNonHttpSourceRefusesBeforeIssuance() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var wire = AccountControlUiAuthenticationGrpcCodec.toRequest(request("test"));
    for (var invalid :
        java.util.List.of(
            wire.toBuilder().clearCanonicalClientAddress().build(),
            wire.toBuilder().setCanonicalClientAddress(0, "untrusted-host.test").build(),
            wire.toBuilder().addCanonicalClientAddress("192.0.2.11").build(),
            wire.toBuilder().clearTransportClass().build(),
            wire.toBuilder().setTransportClassValue(0, 99).build())) {
      var result = new Result();
      gateway().run(() -> receiver.authLogin(invalid, result));
      assertThat(result.failureCode).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(issuer, actors);
  }

  @Test
  void primaryDenialIsAClosedResultAndNamespaceConflictNeverAuthenticates() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var result = new Result();
    gateway()
        .run(
            () ->
                receiver.authLogin(
                    AccountControlUiAuthenticationGrpcCodec.toRequest(request("other")), result));
    assertThat(result.failureCode).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(issuer);
    when(issuer.issueAccount(any()))
        .thenThrow(new AuthenticationException("AUTH_INVALID_CREDENTIALS", "not disclosed"));
    var denied = new Result();
    gateway()
        .run(
            () ->
                receiver.authLogin(
                    AccountControlUiAuthenticationGrpcCodec.toRequest(request("test")), denied));
    assertThat(denied.failureCode).isNull();
    assertThat(denied.completed).isTrue();
    assertThat(denied.value.getStatusList())
        .containsExactly(
            AccountControlUiAuthLoginStatus.ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_DENIED);
    assertThat(denied.value.toString()).doesNotContain("not disclosed", "controlled-secret");
    verifyNoInteractions(actors);
  }

  @Test
  void overlongSecretIsInvalidArgumentBeforeIssuance() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var wire =
        AuthLoginRequest.newBuilder()
            .addSchemaVersion(1)
            .addTargetNamespace("test")
            .addRequestId(UUID.randomUUID().toString())
            .addCallerContextId(UUID.randomUUID().toString())
            .addEmail("owner@example.test")
            .addSecret("s".repeat(1025))
            .addCanonicalClientAddress("192.0.2.10")
            .addTransportClassValue(1)
            .build();
    var result = new Result();

    gateway().run(() -> receiver.authLogin(wire, result));

    assertThat(result.failureCode).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(issuer, actors);
  }

  @Test
  void issuanceConflictRemainsAlreadyExists() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    when(issuer.issueAccount(any()))
        .thenThrow(new AccountControlUiIssuanceService.IdempotencyConflictException());
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var result = new Result();

    gateway()
        .run(
            () ->
                receiver.authLogin(
                    AccountControlUiAuthenticationGrpcCodec.toRequest(request("test")), result));

    assertThat(result.failureCode).isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(result.failureDescription).isEqualTo("Account login request conflicts");
    verifyNoInteractions(actors);
  }

  @Test
  void signerStyleIllegalArgumentIsUnavailableAndSanitized() {
    var issuer = mock(AccountControlUiIssuanceService.class);
    var actors = mock(AccountControlUiActorService.class);
    when(issuer.issueAccount(any()))
        .thenThrow(new IllegalArgumentException("mounted signer rejected configured key material"));
    var receiver = new AccountControlUiAuthenticationGrpcService(issuer, actors, "test");
    var request = request("test");
    var result = new Result();

    gateway()
        .run(
            () ->
                receiver.authLogin(
                    AccountControlUiAuthenticationGrpcCodec.toRequest(request), result));

    assertThat(result.failureCode).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.failureDescription).isEqualTo("Account login authority unavailable");
    assertThat(result.failureDescription)
        .doesNotContain("mounted signer rejected", request.email(), request.secret());
    verifyNoInteractions(actors);
  }

  private static AccountControlUiAuthenticationGrpcCodec.Request request(String namespace) {
    return new AccountControlUiAuthenticationGrpcCodec.Request(
        UUID.randomUUID(),
        UUID.randomUUID(),
        namespace,
        "owner@example.test",
        "controlled-secret",
        new SourceContext("192.0.2.10"));
  }

  private static Context gateway() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/spring-cloud-gateway")
                .orElseThrow());
  }

  private static final class Result implements StreamObserver<AuthLoginResponse> {
    AuthLoginResponse value;
    Status.Code failureCode;
    String failureDescription;
    boolean completed;

    @Override
    public void onNext(AuthLoginResponse value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable failure) {
      Status status = Status.fromThrowable(failure);
      failureCode = status.getCode();
      failureDescription = status.getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
