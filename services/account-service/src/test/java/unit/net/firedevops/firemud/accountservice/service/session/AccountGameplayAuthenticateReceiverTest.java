package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginSourceContext;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.CanonicalGameplayLoginRequest;
import net.firedevops.firemud.accountservice.dto.InitialGameplayLoginResult;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAuthenticateReceiver;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayCanonicalLoginOwner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayPrimaryCredentialVerifier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/** Controlled peer/owner fixture proof; no authenticated transport or issuer-custody claim. */
class AccountGameplayAuthenticateReceiverTest {
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CONTEXT_ID = UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID TOKEN_JTI = UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308");
  private static final long NOW = Instant.parse("2027-01-15T12:00:00Z").getEpochSecond();
  private final AccountGameplayCanonicalLoginOwner owner =
      mock(AccountGameplayCanonicalLoginOwner.class);
  private final AccountGameplayPrimaryCredentialVerifier verifier =
      mock(AccountGameplayPrimaryCredentialVerifier.class);
  private final AccountGameplayAuthenticateReceiver receiver =
      new AccountGameplayAuthenticateReceiver(owner, verifier, "test");

  @Test
  void absentPeerDeniesBeforeInspectingAnyRequestField() {
    var request = mock(AuthenticateRequest.class);
    var observed = call(null, request);
    assertFailure(observed, Status.Code.PERMISSION_DENIED, "PERMISSION_DENIED");
    verifyNoInteractions(request, owner, verifier);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spiffe://firemud/ns/other/sa/game-session-service",
        "spiffe://firemud/ns/test/sa/entity-management-service",
        "spiffe://firemud/ns/test/sa/account-service"
      })
  void wrongCertificateDerivedPeerDeniesBeforeRequestInspection(String peer) {
    var request = mock(AuthenticateRequest.class);
    assertFailure(call(peer, request), Status.Code.PERMISSION_DENIED, "PERMISSION_DENIED");
    verifyNoInteractions(request, owner, verifier);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2, 77})
  void bootstrapUnknownAndUnspecifiedModeDenyBeforeCredentialAccess(int mode) {
    var request = mock(AuthenticateRequest.class);
    when(request.hasSourceContext()).thenReturn(true);
    when(request.getSourceContext()).thenReturn(source().setTransportClassValue(mode).build());
    assertFailure(call(WORKLOAD, request), Status.Code.PERMISSION_DENIED, "PERMISSION_DENIED");
    verify(request, never()).getPassword();
    verify(request, never()).getEmail();
    verifyNoInteractions(owner, verifier);
  }

  @Test
  void missingSourceDeniesBeforeCredentialAccess() {
    var request = mock(AuthenticateRequest.class);
    assertFailure(call(WORKLOAD, request), Status.Code.PERMISSION_DENIED, "PERMISSION_DENIED");
    verify(request, never()).getPassword();
    verifyNoInteractions(owner, verifier);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "42",
        "00000000-0000-0000-0000-000000000000",
        "1ee95a1e-83f2-1a63-a7ba-6288e246ac76",
        "1EE95A1E-83F2-4A63-A7BA-6288E246AC76"
      })
  void requestAndContextIdsRequireExactCanonicalV4(String invalidId) {
    assertFailure(
        call(WORKLOAD, request().setRequestId(invalidId).build()),
        Status.Code.INVALID_ARGUMENT,
        "INVALID_ARGUMENT");
    assertFailure(
        call(WORKLOAD, request().setSourceContext(source().setCallerContextId(invalidId)).build()),
        Status.Code.INVALID_ARGUMENT,
        "INVALID_ARGUMENT");
    verifyNoInteractions(owner, verifier);
  }

  @Test
  void boundedSourceAndCredentialInputsAndUnknownFieldsDenyBeforeOwner() {
    for (String address : List.of("", " 203.0.113.10", "203.0.113.10\n", "a".repeat(257))) {
      assertFailure(
          call(
              WORKLOAD,
              request().setSourceContext(source().setCanonicalClientAddress(address)).build()),
          Status.Code.INVALID_ARGUMENT,
          "INVALID_ARGUMENT");
    }
    assertFailure(
        call(WORKLOAD, request().setPassword("a".repeat(1025)).build()),
        Status.Code.INVALID_ARGUMENT,
        "INVALID_ARGUMENT");
    assertFailure(
        call(WORKLOAD, request().setEmail("a".repeat(321)).build()),
        Status.Code.INVALID_ARGUMENT,
        "INVALID_ARGUMENT");
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    assertFailure(
        call(WORKLOAD, request().setUnknownFields(unknown).build()),
        Status.Code.INVALID_ARGUMENT,
        "INVALID_ARGUMENT");
    assertFailure(
        call(WORKLOAD, request().setSourceContext(source().setUnknownFields(unknown)).build()),
        Status.Code.INVALID_ARGUMENT,
        "INVALID_ARGUMENT");
    verifyNoInteractions(owner, verifier);
  }

  @Test
  void exactOwnerAggregateIsProjectedWithoutNumericFallbackOrDroppedEvidence() {
    var result = result();
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier))).thenReturn(result);
    var observed = call(WORKLOAD, request().build());
    assertThat(observed.failure).isNull();
    assertThat(observed.completed).isTrue();
    assertThat(observed.responses).hasSize(1);
    var response = observed.responses.getFirst();
    assertThat(response.hasError()).isFalse();
    // Compare as a boolean so failed assertions never print the compact credential.
    assertThat(
            response
                .getAuthToken()
                .equals(new String(result.compactJwtBytes(), StandardCharsets.US_ASCII)))
        .isTrue();
    assertThat(response.getAccountId()).isEqualTo(ACCOUNT_ID.toString());
    assertThat(response.getRequestId()).isEqualTo(result.requestId().toString());
    assertThat(response.getCallerContextId()).isEqualTo(result.callerContextId().toString());
    assertThat(response.getTokenJti()).isEqualTo(result.tokenJti().toString());
    assertThat(response.getTokenSha256()).isEqualTo(result.tokenSha256());
    assertThat(response.getTokenProfile()).isEqualTo(result.profile());
    assertThat(response.getIssuedAtEpochSeconds()).isEqualTo(result.issuedAtEpochSecond());
    assertThat(response.getNotBeforeEpochSeconds()).isEqualTo(result.notBeforeEpochSecond());
    assertThat(response.getExpiresAtEpochSeconds()).isEqualTo(result.expiresAtEpochSecond());
    assertThat(response.getTokenGeneration()).isEqualTo(result.tokenGeneration());
    assertThat(response.getIssuanceFence()).isEqualTo(result.issuanceFence());
    assertThat(response.getAuthorityTupleCanonicalJson().toByteArray())
        .containsExactly(result.authorityTupleCanonicalJson());
    assertThat(response.getOutboxCheckpointsCanonicalJson().toByteArray())
        .containsExactly(result.outboxCheckpointsCanonicalJson());
    assertThat(response.getOutboxSourceEventEvidenceCanonicalJson().toByteArray())
        .containsExactly(result.outboxSourceEventEvidenceCanonicalJson());
    var captured = ArgumentCaptor.forClass(CanonicalGameplayLoginRequest.class);
    verify(owner).authenticate(captured.capture(), eq(WORKLOAD), eq(verifier));
    assertThat(captured.getValue().requestId()).isEqualTo(REQUEST_ID);
    assertThat(captured.getValue().sourceContext().contextId()).isEqualTo(CONTEXT_ID);
    assertThat(captured.getValue().sourceContext().canonicalClientAddress())
        .isEqualTo("203.0.113.10");
    assertThat(captured.getValue().sourceContext().transportClass()).isEqualTo("trusted_tcp_proxy");
    assertThat(captured.getValue().credential().equals("secret")).isTrue();
    verifyNoInteractions(verifier);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        AuthenticationErrorCodes.INVALID_CREDENTIALS,
        AuthenticationErrorCodes.ACCOUNT_LOCKED,
        AuthenticationErrorCodes.RETRY_LATER
      })
  void producedCredentialDenialContainsOnlyBoundedErrorAndNoTokenAggregate(String code) {
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier)))
        .thenThrow(new AuthenticationException(code, "private-secret-private-token"));
    var observed = call(WORKLOAD, request().build());
    assertThat(observed.failure).isNull();
    assertThat(observed.completed).isTrue();
    assertThat(observed.responses).hasSize(1);
    assertErrorOnly(observed.responses.getFirst(), code);
  }

  @Test
  void exactRequestConflictAndExpiredRecoveryAreProducedTokenFreeOutcomes() {
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier)))
        .thenThrow(
            new AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException())
        .thenThrow(new AccountGameplayDelegationIssuanceRepository.IdempotencyConflictException())
        .thenThrow(
            new AccountGameplayDelegationResponseEnvelopeRepository
                .ResponseRecoveryExpiredException());
    for (String code :
        List.of("IDEMPOTENCY_CONFLICT", "IDEMPOTENCY_CONFLICT", "RESPONSE_RECOVERY_EXPIRED")) {
      var observed = call(WORKLOAD, request().build());
      assertThat(observed.failure).isNull();
      assertThat(observed.completed).isTrue();
      assertErrorOnly(observed.responses.getFirst(), code);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        AuthenticationErrorCodes.UNAVAILABLE,
        AuthenticationErrorCodes.ABUSE_CONTROL_UNAVAILABLE
      })
  void authorityFailureUsesNonOkTransportWithBoundedStructuredDetail(String code) {
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier)))
        .thenThrow(new AuthenticationException(code, "private-secret-private-token"));
    assertFailure(call(WORKLOAD, request().build()), Status.Code.UNAVAILABLE, code);
  }

  @Test
  void unknownFailureAndContradictoryResultCannotProducePartialCredential() {
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier)))
        .thenThrow(new IllegalStateException("private-secret-private-token"));
    assertFailure(call(WORKLOAD, request().build()), Status.Code.INTERNAL, "INTERNAL");
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier))).thenReturn(null);
    assertFailure(call(WORKLOAD, request().build()), Status.Code.INTERNAL, "INTERNAL");
    var contradictory = mock(InitialGameplayLoginResult.class);
    when(contradictory.requestId()).thenReturn(UUID.randomUUID());
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier))).thenReturn(contradictory);
    assertFailure(call(WORKLOAD, request().build()), Status.Code.INTERNAL, "INTERNAL");
    verify(contradictory, never()).compactJwtBytes();
  }

  @Test
  void staleAuthorityCannotReturnAPartialSuccess() {
    when(owner.authenticate(any(), eq(WORKLOAD), eq(verifier)))
        .thenThrow(new AccountGameplayDelegationIssuanceRepository.StaleAuthorityException());
    assertFailure(
        call(WORKLOAD, request().build()), Status.Code.FAILED_PRECONDITION, "FAILED_PRECONDITION");
  }

  @Test
  void cancelledCallDeniesBeforeRequestInspectionOrOwnerMutation() {
    var request = mock(AuthenticateRequest.class);
    var cancelled = Context.current().withCancellation();
    cancelled.cancel(new IllegalStateException("private-secret-private-token"));
    cancelled.run(() -> assertFailure(call(WORKLOAD, request), Status.Code.CANCELLED, "CANCELLED"));
    verifyNoInteractions(request, owner, verifier);
  }

  @Test
  void receiverIsNotRegisteredAsSpringComponent() {
    assertThat(
            AnnotatedElementUtils.hasAnnotation(
                AccountGameplayAuthenticateReceiver.class, Component.class))
        .isFalse();
  }

  private Observed call(String peer, AuthenticateRequest request) {
    var observed = new Observed();
    GrpcPeerIdentity identity = peer == null ? null : GrpcPeerIdentity.parseUri(peer).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, identity)
        .run(() -> receiver.authenticate(request, observed));
    return observed;
  }

  @Test
  void disposalAuthenticatesPeerBeforeReadingIdentityAndReturnsOnlyLifecycleEvidence() {
    var disposal =
        mock(
            net.firedevops.firemud.accountservice.service.session
                .AccountInitialGameplayLoginDisposalOwner.class);
    var adapter = new AccountGameplayAuthenticateReceiver(owner, verifier, "test", disposal);
    var inaccessible =
        mock(net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest.class);
    var denied = new DisposalObserved();
    adapter.retireInitialGameplayLogin(inaccessible, denied);
    assertThat(Status.fromThrowable(denied.failure).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(inaccessible, disposal);
    var request =
        net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest.newBuilder()
            .setRequestId(REQUEST_ID.toString())
            .setCallerContextId(CONTEXT_ID.toString())
            .build();
    var canonical =
        new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalRequest(
            REQUEST_ID, CONTEXT_ID, null);
    UUID operation = UUID.randomUUID();
    when(disposal.retire(canonical, WORKLOAD))
        .thenReturn(
            new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult(
                REQUEST_ID,
                CONTEXT_ID,
                operation,
                net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                    .Disposition.PENDING));
    var observed = new DisposalObserved();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(WORKLOAD).orElseThrow())
        .run(() -> adapter.retireInitialGameplayLogin(request, observed));
    assertThat(observed.failure).isNull();
    assertThat(observed.response.getDisposalOperationId()).isEqualTo(operation.toString());
    assertThat(observed.response.getDisposition())
        .isEqualTo(
            net.firedevops.firemud.account.v1.InitialGameplayLoginDisposalDisposition
                .INITIAL_GAMEPLAY_LOGIN_DISPOSAL_DISPOSITION_PENDING);
    assertThat(observed.completed).isTrue();
    verifyNoInteractions(owner, verifier);
  }

  @Test
  void disposalRejectsIncompleteWitnessAndRedactsOwnerFailure() {
    var disposal =
        mock(
            net.firedevops.firemud.accountservice.service.session
                .AccountInitialGameplayLoginDisposalOwner.class);
    var adapter = new AccountGameplayAuthenticateReceiver(owner, verifier, "test", disposal);
    var request =
        net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest.newBuilder()
            .setRequestId(REQUEST_ID.toString())
            .setCallerContextId(CONTEXT_ID.toString());
    var incomplete = new DisposalObserved();
    var peer = GrpcPeerIdentity.parseUri(WORKLOAD).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () ->
                adapter.retireInitialGameplayLogin(
                    request
                        .clone()
                        .setTokenWitness(
                            net.firedevops.firemud.account.v1.InitialGameplayLoginTokenWitness
                                .newBuilder()
                                .setTokenJti(TOKEN_JTI.toString()))
                        .build(),
                    incomplete));
    assertThat(Status.fromThrowable(incomplete.failure).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(disposal);
    when(disposal.retire(any(), eq(WORKLOAD)))
        .thenThrow(new IllegalStateException("private-secret private-token"));
    var unavailable = new DisposalObserved();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> adapter.retireInitialGameplayLogin(request.build(), unavailable));
    assertThat(Status.fromThrowable(unavailable.failure).getCode())
        .isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(unavailable.failure.getMessage()).doesNotContain("private-secret", "private-token");
    assertThat(unavailable.response).isNull();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.Disposition
          .class)
  void disposalProjectsClosedProofVariantsWithoutExposingOwnerEvidence(
      net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.Disposition
          disposition) {
    // Controlled owner result fixture only; NO_TOKEN production still needs protected history.
    var disposal =
        mock(
            net.firedevops.firemud.accountservice.service.session
                .AccountInitialGameplayLoginDisposalOwner.class);
    var adapter = new AccountGameplayAuthenticateReceiver(owner, verifier, "test", disposal);
    var kind =
        switch (disposition) {
          case PENDING ->
              net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.ProofKind
                  .NONE;
          case RETIRED ->
              net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.ProofKind
                  .PHYSICAL_TOKEN_DENIAL;
          case NO_TOKEN ->
              net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult.ProofKind
                  .NO_ISSUANCE_BINDING;
        };
    var result =
        new net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult(
            REQUEST_ID, CONTEXT_ID, UUID.randomUUID(), disposition, kind);
    when(disposal.retire(any(), eq(WORKLOAD))).thenReturn(result);
    var observed = new DisposalObserved();
    var request =
        net.firedevops.firemud.account.v1.RetireInitialGameplayLoginRequest.newBuilder()
            .setRequestId(REQUEST_ID.toString())
            .setCallerContextId(CONTEXT_ID.toString())
            .build();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(WORKLOAD).orElseThrow())
        .run(() -> adapter.retireInitialGameplayLogin(request, observed));
    assertThat(observed.failure).isNull();
    assertThat(observed.response.getProofKindValue()).isEqualTo(kind.ordinal());
    assertThat(observed.response.getDispositionValue()).isEqualTo(disposition.ordinal() + 1);
    assertThat(observed.response.getAllFields())
        .hasSize(
            disposition
                    == net.firedevops.firemud.accountservice.dto.InitialGameplayLoginDisposalResult
                        .Disposition.PENDING
                ? 4
                : 5);
    verifyNoInteractions(owner, verifier);
  }

  private static final class DisposalObserved
      implements StreamObserver<
          net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse> {
    private net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse response;
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(net.firedevops.firemud.account.v1.RetireInitialGameplayLoginResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "Retain the exact StreamObserver error for transport and bounded-detail assertions.")
    public void onError(Throwable value) {
      failure = value;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private static AuthenticateRequest.Builder request() {
    return AuthenticateRequest.newBuilder()
        .setRequestId(REQUEST_ID.toString())
        .setEmail("player@example.com")
        .setPassword("secret")
        .setSourceContext(source());
  }

  private static GameplayLoginSourceContext.Builder source() {
    return GameplayLoginSourceContext.newBuilder()
        .setCallerContextId(CONTEXT_ID.toString())
        .setCanonicalClientAddress("203.0.113.10")
        .setTransportClass(
            GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY);
  }

  private static void assertErrorOnly(AuthenticateResponse response, String code) {
    assertThat(response.hasError()).isTrue();
    assertThat(response.getError().getCode()).isEqualTo(code);
    assertThat(response.getError().getMessage()).doesNotContain("private-secret", "private-token");
    assertThat(
            response.toBuilder()
                .clearError()
                .build()
                .equals(AuthenticateResponse.getDefaultInstance()))
        .isTrue();
  }

  private static void assertFailure(Observed observed, Status.Code status, String code) {
    assertThat(observed.responses).isEmpty();
    assertThat(observed.completed).isFalse();
    assertThat(Status.fromThrowable(observed.failure).getCode()).isEqualTo(status);
    var detail = StatusProto.fromThrowable(observed.failure);
    assertThat(detail).isNotNull();
    assertThat(detail.getDetailsCount()).isEqualTo(1);
    try {
      ErrorDetail error = detail.getDetails(0).unpack(ErrorDetail.class);
      assertThat(error.getCode()).isEqualTo(code);
      assertThat(error.getMessage()).doesNotContain("private-secret", "private-token");
    } catch (com.google.protobuf.InvalidProtocolBufferException failure) {
      throw new AssertionError("Expected bounded authentication error detail", failure);
    }
  }

  /** Same controlled owner-evidence construction used by the canonical LOGIN owner unit proof. */
  private static InitialGameplayLoginResult result() {
    var recovered = mock(RecoveredCredential.class);
    when(recovered.compactJwtBytes())
        .thenAnswer(invocation -> "header.payload.signature".getBytes(StandardCharsets.US_ASCII));
    when(recovered.accountId()).thenReturn(ACCOUNT_ID);
    when(recovered.tokenJti()).thenReturn(TOKEN_JTI);
    when(recovered.tokenSha256()).thenReturn("a".repeat(64));
    when(recovered.profile()).thenReturn(GameSessionAccountDelegationProfile.PROFILE);
    when(recovered.expiresAtEpochSecond())
        .thenReturn(NOW + GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS);
    when(recovered.authEvidenceBundle())
        .thenReturn(
            AccountAuthEvidenceBundle.fromOwnerEvaluation(
                new AccountAuthEvidenceBundle.OwnerEvaluation(
                    new AccountAuthEvidenceBundle.BundleReference("1", "1", "1", "12345678"),
                    "b".repeat(64),
                    "c".repeat(64),
                    ACCOUNT_ID,
                    new AccountAuthEvidenceBundle.OperationIdentity(
                        UUID.randomUUID(),
                        REQUEST_ID,
                        "d".repeat(64),
                        WORKLOAD,
                        CONTEXT_ID,
                        ACCOUNT_ID),
                    new AccountAuthEvidenceBundle.TokenIdentity(
                        TOKEN_JTI,
                        1L,
                        NOW,
                        NOW,
                        NOW + GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS),
                    GameSessionAccountDelegationProfile.authorityTuple(1L, 1L),
                    1L,
                    new AccountAuthEvidenceBundle.AuthoritySourceVersions(1L, 1L, 1L),
                    new AccountAuthEvidenceBundle.AccountIdentitySource(
                        42L, AccountIdentityProvenance.ACCOUNT_DATABASE_INSERT, 42L),
                    List.of(
                        new AccountAuthEvidenceBundle.OutboxCheckpoint(
                            "account:auth-authority:v1:account/" + ACCOUNT_ID, 0L, null, null),
                        new AccountAuthEvidenceBundle.OutboxCheckpoint(
                            "account:auth-authority:v1:issuer/firemud-account-service",
                            1L,
                            "issuer-event-1",
                            "sha256:" + "e".repeat(64))))));
    return InitialGameplayLoginResult.fromRecoveredCredential(recovered);
  }

  private static final class Observed implements StreamObserver<AuthenticateResponse> {
    private final List<AuthenticateResponse> responses = new ArrayList<>();
    private Throwable failure;
    private boolean completed;

    @Override
    public void onNext(AuthenticateResponse response) {
      responses.add(response);
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "Retain the exact StreamObserver error for transport and bounded-detail assertions.")
    public void onError(Throwable error) {
      failure = error;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
