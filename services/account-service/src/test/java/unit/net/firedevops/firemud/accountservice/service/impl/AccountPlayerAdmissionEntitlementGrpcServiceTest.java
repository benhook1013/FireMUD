package net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementRequest;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.security.AccountPlayerAdmissionEntitlementWorkloadGuard;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountPlayerAdmissionEntitlementOwner;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.RuntimePlayerAdmissionEntitlementGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Handler classifications use a controlled guard, not a claim of authenticated TLS custody. */
class AccountPlayerAdmissionEntitlementGrpcServiceTest {
  private static final String PEER = "spiffe://firemud/ns/prod/sa/game-session-service";
  private static final String JWT = "private-token.payload.signature";
  private static final String SCOPE = "private-scope";
  private static final String SECRET = "private-source-internal-identifier";
  private static final String MESSAGE = "Player-admission entitlement evidence is unavailable";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void authenticationPrecedesEvenNullMalformedAndOversizedCredentialParsing() throws Exception {
    var owner = mock(AccountPlayerAdmissionEntitlementOwner.class);
    var service = new AccountPlayerAdmissionEntitlementGrpcService(owner, "prod");
    for (var request :
        new ReadPlayerAdmissionEntitlementRequest[] {
          null,
          ReadPlayerAdmissionEntitlementRequest.getDefaultInstance(),
          ReadPlayerAdmissionEntitlementRequest.newBuilder()
              .addInitialLoginJwt("private-token".repeat(4000))
              .build()
        }) {
      StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer = mock(StreamObserver.class);
      service.readPlayerAdmissionEntitlement(request, observer);
      assertRefusal(observer, Status.Code.UNAUTHENTICATED);
    }
    verifyNoInteractions(owner);
  }

  @ParameterizedTest
  @MethodSource("ownerFailures")
  void ownerFailuresRetainExactAvailabilityClassificationWithoutSourceDetails(
      RuntimeException cause, Status.Code expected) throws Exception {
    try (var f = new AuthenticatedFixture()) {
      when(f.owner.evaluate(any(), any())).thenThrow(cause);
      f.service.readPlayerAdmissionEntitlement(wire("prod"), f.observer);
      assertRefusal(f.observer, expected);
      verify(f.owner).evaluate(sourceRequest("prod"), JWT);
    }
  }

  private static Stream<org.junit.jupiter.params.provider.Arguments> ownerFailures() {
    return Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(
            new AccountPlayerAdmissionEntitlementOwner.TargetPreconditionException(),
            Status.Code.FAILED_PRECONDITION),
        org.junit.jupiter.params.provider.Arguments.of(
            new AccountPlayerAdmissionEntitlementOwner.SourceUnavailableException(),
            Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new AccountGameplayAdmissionCallerVerifier.CallerUnavailableException(),
            Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new AccountHostingSubscriptionLifecycleService.EntitlementUnavailableException(
                new IllegalStateException(SECRET)),
            Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new DemoTenantEntitlementException(
                DemoTenantEntitlementException.Code.ENTITLEMENT_UNAVAILABLE, SECRET),
            Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new org.jooq.exception.DataAccessException(SECRET), Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new org.springframework.dao.DataAccessResourceFailureException(SECRET),
            Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new org.springframework.transaction.CannotCreateTransactionException(SECRET),
            Status.Code.UNAVAILABLE),
        org.junit.jupiter.params.provider.Arguments.of(
            new IllegalStateException("Account gameplay caller verification failed"),
            Status.Code.INTERNAL),
        org.junit.jupiter.params.provider.Arguments.of(
            new IllegalStateException("ENTITLEMENT_UNAVAILABLE: " + SECRET), Status.Code.INTERNAL),
        org.junit.jupiter.params.provider.Arguments.of(
            new IllegalArgumentException(SECRET), Status.Code.INTERNAL),
        // Only the protected-target owner boundary may translate its transport failures.
        org.junit.jupiter.params.provider.Arguments.of(
            Status.UNAVAILABLE.withDescription(SECRET).asRuntimeException(), Status.Code.INTERNAL),
        org.junit.jupiter.params.provider.Arguments.of(
            Status.FAILED_PRECONDITION.withDescription(SECRET).asRuntimeException(),
            Status.Code.INTERNAL),
        org.junit.jupiter.params.provider.Arguments.of(
            new CanonicalGameplayAdmissionDecisionReadClient.ClientUnavailableException(
                new IllegalStateException(SECRET)),
            Status.Code.INTERNAL),
        org.junit.jupiter.params.provider.Arguments.of(
            new NullPointerException(SECRET), Status.Code.INTERNAL));
  }

  @Test
  void authenticatedMalformedAndWrongNamespaceRequestsNeverReachOwner() throws Exception {
    try (var f = new AuthenticatedFixture()) {
      f.service.readPlayerAdmissionEntitlement(null, f.observer);
      assertRefusal(f.observer, Status.Code.INVALID_ARGUMENT);
      verifyNoInteractions(f.owner);
    }
    try (var f = new AuthenticatedFixture()) {
      f.service.readPlayerAdmissionEntitlement(
          ReadPlayerAdmissionEntitlementRequest.getDefaultInstance(), f.observer);
      assertRefusal(f.observer, Status.Code.INVALID_ARGUMENT);
      verifyNoInteractions(f.owner);
    }
    try (var f = new AuthenticatedFixture()) {
      f.service.readPlayerAdmissionEntitlement(wire("other"), f.observer);
      assertRefusal(f.observer, Status.Code.PERMISSION_DENIED);
      verifyNoInteractions(f.owner);
    }
  }

  @Test
  void ambientTransactionAndSynchronizationRefuseBeforeOwnerOrParsing() throws Exception {
    try (var f = new AuthenticatedFixture()) {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      f.service.readPlayerAdmissionEntitlement(null, f.observer);
      assertRefusal(f.observer, Status.Code.FAILED_PRECONDITION);
      verifyNoInteractions(f.owner);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    try (var f = new AuthenticatedFixture()) {
      TransactionSynchronizationManager.initSynchronization();
      f.service.readPlayerAdmissionEntitlement(null, f.observer);
      assertRefusal(f.observer, Status.Code.FAILED_PRECONDITION);
      verifyNoInteractions(f.owner);
    }
  }

  @Test
  void successfulOwnerValueUsesExactCallerCredentialAndClosedResponseBinding() throws Exception {
    try (var f = new AuthenticatedFixture()) {
      var evidence = evidence();
      when(f.owner.evaluate(any(), any())).thenReturn(evidence);
      f.service.readPlayerAdmissionEntitlement(wire("prod"), f.observer);
      var response = ArgumentCaptor.forClass(ReadPlayerAdmissionEntitlementResponse.class);
      verify(f.observer).onNext(response.capture());
      assertThat(response.getValue())
          .isEqualTo(
              RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(request("prod"), evidence));
      assertThat(response.getValue().toString()).doesNotContain(JWT);
      verify(f.owner).evaluate(sourceRequest("prod"), JWT);
      verify(f.observer).onCompleted();
      verify(f.observer, never()).onError(any());
    }
  }

  @Test
  void responseBindingOrCodecFaultIsInternalAndNeverEmitsEvidence() throws Exception {
    try (var f = new AuthenticatedFixture()) {
      var evidence = evidence();
      when(evidence.fields()).thenReturn(Map.of());
      when(f.owner.evaluate(any(), any())).thenReturn(evidence);
      f.service.readPlayerAdmissionEntitlement(wire("prod"), f.observer);
      assertRefusal(f.observer, Status.Code.INTERNAL);
    }
  }

  @Test
  void responseCallbackFaultIsInternalWithoutCompletionOrPrivateDetails() throws Exception {
    try (var f = new AuthenticatedFixture()) {
      var evidence = evidence();
      when(f.owner.evaluate(any(), any())).thenReturn(evidence);
      doThrow(new IllegalStateException(SECRET)).when(f.observer).onNext(any());
      f.service.readPlayerAdmissionEntitlement(wire("prod"), f.observer);
      assertStructuredFailure(f.observer, Status.Code.INTERNAL);
      verify(f.observer, never()).onCompleted();
    }
  }

  private static void assertRefusal(
      StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer, Status.Code expected)
      throws Exception {
    assertStructuredFailure(observer, expected);
    verify(observer, never()).onNext(any());
    verify(observer, never()).onCompleted();
  }

  private static void assertStructuredFailure(
      StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer, Status.Code expected)
      throws Exception {
    var failure = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(failure.capture());
    assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(expected);
    var status = StatusProto.fromThrowable(failure.getValue());
    assertThat(status).isNotNull();
    assertThat(status.getCode()).isEqualTo(expected.value());
    assertThat(status.getMessage()).isEqualTo(MESSAGE);
    assertThat(status.getDetailsCount()).isOne();
    var detail = status.getDetails(0).unpack(ErrorDetail.class);
    assertThat(detail)
        .isEqualTo(ErrorDetail.newBuilder().setCode(expected.name()).setMessage(MESSAGE).build());
    assertThat(status.getSerializedSize()).isLessThan(256);
    assertThat(status.toString()).doesNotContain(JWT, SCOPE, SECRET, "private-token");
    assertThat(failure.getValue().getCause()).isNull();
  }

  private static RuntimePlayerAdmissionEntitlementGrpcCodec.Request request(String namespace) {
    return new RuntimePlayerAdmissionEntitlementGrpcCodec.Request(
        new RetainedTargetSelector(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            namespace,
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "tenant",
            "world",
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            "production",
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            "SHARED",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            7,
            3),
        SCOPE);
  }

  private static ReadPlayerAdmissionEntitlementRequest wire(String namespace) {
    return RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(request(namespace), JWT);
  }

  private static AccountPlayerAdmissionEntitlementOwner.Request sourceRequest(String namespace) {
    return new AccountPlayerAdmissionEntitlementOwner.Request(
        request(namespace).target(), SCOPE, PEER);
  }

  /** The owner unit tests prove canonical content; this double isolates response forwarding. */
  private static RuntimePlayerAdmissionEntitlementEvidence evidence() {
    var request = request("prod");
    var target = request.target();
    Map<String, Object> binding = new LinkedHashMap<>();
    binding.put("targetNamespace", "prod");
    binding.put("tenantId", target.tenantId().toString());
    binding.put("tenantSlug", target.tenantSlug());
    binding.put("worldSlug", target.worldSlug());
    binding.put("realmId", target.realmId().toString());
    binding.put("realmSlug", target.realmSlug());
    binding.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    binding.put("playableStateScope", target.playableStateScope());
    binding.put("gameInstanceId", target.gameInstanceId().toString());
    binding.put("catalogRevision", "7");
    binding.put("pointerVersion", "3");
    var evidence = mock(RuntimePlayerAdmissionEntitlementEvidence.class);
    String scopeHash = RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(SCOPE);
    when(evidence.verifiedConnectScopeTokenHash()).thenReturn(scopeHash);
    when(evidence.fields())
        .thenReturn(
            Map.of(
                "callerAccountId",
                target.accountId().toString(),
                "tenantId",
                target.tenantId().toString(),
                "requestId",
                target.readRequestId().toString(),
                "callerWorkload",
                PEER,
                "verifiedConnectScopeTokenHash",
                scopeHash,
                "target",
                binding));
    when(evidence.canonicalBytes())
        .thenReturn("controlled-owner-value".getBytes(StandardCharsets.UTF_8));
    return evidence;
  }

  private static final class AuthenticatedFixture implements AutoCloseable {
    private final MockedConstruction<AccountPlayerAdmissionEntitlementWorkloadGuard> guards;
    private final AccountPlayerAdmissionEntitlementOwner owner =
        mock(AccountPlayerAdmissionEntitlementOwner.class);
    private final AccountPlayerAdmissionEntitlementGrpcService service;

    @SuppressWarnings("unchecked")
    private final StreamObserver<ReadPlayerAdmissionEntitlementResponse> observer =
        mock(StreamObserver.class);

    private AuthenticatedFixture() {
      guards =
          mockConstruction(
              AccountPlayerAdmissionEntitlementWorkloadGuard.class,
              (guard, context) ->
                  when(guard.requireAuthenticatedGameSessionCaller()).thenReturn(PEER));
      service = new AccountPlayerAdmissionEntitlementGrpcService(owner, "prod");
    }

    @Override
    public void close() {
      guards.close();
    }
  }
}
