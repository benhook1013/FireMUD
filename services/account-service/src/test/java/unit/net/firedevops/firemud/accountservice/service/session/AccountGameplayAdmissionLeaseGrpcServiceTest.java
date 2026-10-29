package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ConfirmOriginalNewGameplayAdmissionRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.IssueOriginalNewGameplayCredentialRequest;
import net.firedevops.firemud.account.v1.OriginalNewAdmissionConfirmationResponse;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.repository.AccountGameplayAdmissionLeaseOwner;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayCredentialOwner;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OriginKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.OwnerProofOutcome;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Target;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountGameplayAdmissionLeaseGrpcServiceTest {
  private static final String NAMESPACE = "game-session-admission-test";
  private static final UUID REQUEST_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID ACCOUNT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION_ID = UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID HOLD_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID HOLD_FENCE = UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final UUID DECISION_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final String HIGH = "9007199254740993";
  private static final String JWT = "header.transient-test-payload.signature";

  private final AccountGameplayAdmissionLeaseOwner owner =
      mock(AccountGameplayAdmissionLeaseOwner.class);
  private final AccountOriginalNewBoundGameplayCredentialOwner credentialOwner =
      mock(AccountOriginalNewBoundGameplayCredentialOwner.class);
  private final AccountGameplayAdmissionLeaseGrpcService service =
      AccountGameplayAdmissionLeaseManualFactory.createUnregisteredReceiver(
          owner, credentialOwner, NAMESPACE);

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesTheExactPeerBeforeInspectingEachOfTheSixRequests() {
    for (Call call : callsWithDefaultRequests()) {
      assertCode(call, null, Status.Code.UNAUTHENTICATED);
      assertCode(call, peer(NAMESPACE, "world-management-service"), Status.Code.PERMISSION_DENIED);
      assertCode(call, peer("other", "game-session-service"), Status.Code.PERMISSION_DENIED);

      SessionContext.setContext("123", List.of(), Map.of());
      assertCode(call, peer(NAMESPACE, "game-session-service"), Status.Code.PERMISSION_DENIED);
      SessionContext.clear();

      SessionContext.setContext(
          null, List.of(), Map.of(), true, "game-session-service", "instance");
      assertCode(call, peer(NAMESPACE, "game-session-service"), Status.Code.PERMISSION_DENIED);
      SessionContext.clear();
    }
    for (ConfirmationCall call : confirmationCallsWithDefaultRequests()) {
      assertConfirmationCode(call, null, Status.Code.UNAUTHENTICATED);
      assertConfirmationCode(
          call, peer(NAMESPACE, "world-management-service"), Status.Code.PERMISSION_DENIED);
      assertConfirmationCode(
          call, peer("other", "game-session-service"), Status.Code.PERMISSION_DENIED);

      SessionContext.setContext("123", List.of(), Map.of());
      assertConfirmationCode(
          call, peer(NAMESPACE, "game-session-service"), Status.Code.PERMISSION_DENIED);
      SessionContext.clear();

      SessionContext.setContext(
          null, List.of(), Map.of(), true, "game-session-service", "instance");
      assertConfirmationCode(
          call, peer(NAMESPACE, "game-session-service"), Status.Code.PERMISSION_DENIED);
      SessionContext.clear();
    }
    verifyNoInteractions(owner, credentialOwner);
  }

  @Test
  void rejectsNullOversizedAndClosedShapeRequestsBeforeCallingOwner() {
    assertCode(
        observer -> service.acquireGameplayAdmissionLease(null, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertCode(
        observer -> service.finalizeGameplayAdmissionLease(null, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertCode(
        observer -> service.readGameplayAdmissionLease(null, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertCode(
        observer -> service.abortGameplayAdmissionLease(null, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);

    assertCode(
        observer -> service.issueOriginalNewGameplayCredential(null, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertConfirmationCode(
        observer -> service.confirmOriginalNewGameplayAdmission(null, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);

    var oversizedAcquire =
        AcquireGameplayAdmissionLeaseRequest.newBuilder()
            .addPresentedInitialGsaCompactJwt(
                "x"
                    .repeat(
                        AccountGameplayAdmissionLeaseGrpcCodec.MAX_ACQUIRE_REQUEST_WIRE_BYTES + 1))
            .build();
    ByteString oversizedOriginalBytes =
        ByteString.copyFrom(
            new byte
                [AccountGameplayAdmissionLeaseGrpcCodec.MAX_ORIGINAL_LEASE_REQUEST_WIRE_BYTES + 1]);
    var oversizedFinalize =
        FinalizeGameplayAdmissionLeaseRequest.newBuilder()
            .addOriginalLeaseEvidenceUtf8(oversizedOriginalBytes)
            .build();
    var oversizedRead =
        ReadGameplayAdmissionLeaseRequest.newBuilder()
            .addOriginalLeaseEvidenceUtf8(oversizedOriginalBytes)
            .build();
    var oversizedAbort =
        AbortGameplayAdmissionLeaseRequest.newBuilder()
            .addOriginalLeaseEvidenceUtf8(oversizedOriginalBytes)
            .build();
    assertCode(
        observer -> service.acquireGameplayAdmissionLease(oversizedAcquire, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertCode(
        observer -> service.finalizeGameplayAdmissionLease(oversizedFinalize, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertCode(
        observer -> service.readGameplayAdmissionLease(oversizedRead, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertCode(
        observer -> service.abortGameplayAdmissionLease(oversizedAbort, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);

    var oversizedIssue =
        IssueOriginalNewGameplayCredentialRequest.newBuilder()
            .addOriginalLeaseEvidenceUtf8(oversizedOriginalBytes)
            .build();
    var oversizedConfirmation =
        ConfirmOriginalNewGameplayAdmissionRequest.newBuilder()
            .addOriginalLeaseEvidenceUtf8(oversizedOriginalBytes)
            .build();
    assertCode(
        observer -> service.issueOriginalNewGameplayCredential(oversizedIssue, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    assertConfirmationCode(
        observer -> service.confirmOriginalNewGameplayAdmission(oversizedConfirmation, observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);

    var malformedAcquire =
        AcquireGameplayAdmissionLeaseRequest.newBuilder().addSchemaVersion(1).build();
    var malformedFinalize = FinalizeGameplayAdmissionLeaseRequest.newBuilder().build();
    var malformedRead = ReadGameplayAdmissionLeaseRequest.newBuilder().build();
    var malformedAbort = AbortGameplayAdmissionLeaseRequest.newBuilder().build();
    for (Call call :
        List.<Call>of(
            observer -> service.acquireGameplayAdmissionLease(malformedAcquire, observer),
            observer -> service.finalizeGameplayAdmissionLease(malformedFinalize, observer),
            observer -> service.readGameplayAdmissionLease(malformedRead, observer),
            observer -> service.abortGameplayAdmissionLease(malformedAbort, observer),
            observer ->
                service.issueOriginalNewGameplayCredential(
                    IssueOriginalNewGameplayCredentialRequest.getDefaultInstance(), observer))) {
      assertCode(call, peer(NAMESPACE, "game-session-service"), Status.Code.INVALID_ARGUMENT);
    }
    assertConfirmationCode(
        observer ->
            service.confirmOriginalNewGameplayAdmission(
                ConfirmOriginalNewGameplayAdmissionRequest.getDefaultInstance(), observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner, credentialOwner);
  }

  @Test
  void manualFactoryExposesExactlyTheSevenPurposeSpecificMethodsWithoutSpringRegistration() {
    assertThat(service.bindService().getServiceDescriptor().getMethods())
        .extracting(method -> method.getFullMethodName())
        .containsExactlyInAnyOrder(
            "account.v1.AccountGameplayAdmissionLeaseService/AcquireGameplayAdmissionLease",
            "account.v1.AccountGameplayAdmissionLeaseService/FinalizeGameplayAdmissionLease",
            "account.v1.AccountGameplayAdmissionLeaseService/ReadGameplayAdmissionLease",
            "account.v1.AccountGameplayAdmissionLeaseService/AbortGameplayAdmissionLease",
            "account.v1.AccountGameplayAdmissionLeaseService/IssueOriginalNewGameplayCredential",
            "account.v1.AccountGameplayAdmissionLeaseService/ConfirmOriginalNewGameplayAdmission",
            "account.v1.AccountGameplayAdmissionLeaseService/AbortNeverAdmittedGameplayAdmissionLease");
  }

  @Test
  void rejectsAmbientTransactionBeforeParsing() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(
        observer ->
            service.acquireGameplayAdmissionLease(
                AcquireGameplayAdmissionLeaseRequest.getDefaultInstance(), observer),
        peer(NAMESPACE, "game-session-service"),
        Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(owner, credentialOwner);
  }

  @Test
  void rejectsForeignTargetNamespaceForAcquisitionAndAllOriginalLeaseMethods() {
    var foreignAcquisition = acquisition("other");
    var foreignLease = lease("other");
    byte[] originalBytes = foreignLease.canonicalJson().getBytes(StandardCharsets.UTF_8);
    var foreignFinalize =
        AccountGameplayAdmissionLeaseGrpcCodec.toFinalizeRequest(JWT, originalBytes, DECISION_ID);
    var foreignRead = AccountGameplayAdmissionLeaseGrpcCodec.toReadRequest(JWT, originalBytes);
    var foreignAbort =
        AccountGameplayAdmissionLeaseGrpcCodec.toAbortRequest(JWT, originalBytes, null);
    var foreignIssue =
        AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialRequest(
            JWT, originalBytes);
    var foreignConfirmation =
        AccountGameplayAdmissionLeaseGrpcCodec.toConfirmOriginalNewGameplayAdmissionRequest(
            JWT, originalBytes);
    var peer = peer(NAMESPACE, "game-session-service");

    assertCode(
        observer -> service.acquireGameplayAdmissionLease(foreignAcquisition, observer),
        peer,
        Status.Code.PERMISSION_DENIED);
    assertCode(
        observer -> service.finalizeGameplayAdmissionLease(foreignFinalize, observer),
        peer,
        Status.Code.PERMISSION_DENIED);
    assertCode(
        observer -> service.readGameplayAdmissionLease(foreignRead, observer),
        peer,
        Status.Code.PERMISSION_DENIED);
    assertCode(
        observer -> service.abortGameplayAdmissionLease(foreignAbort, observer),
        peer,
        Status.Code.PERMISSION_DENIED);
    assertCode(
        observer -> service.issueOriginalNewGameplayCredential(foreignIssue, observer),
        peer,
        Status.Code.PERMISSION_DENIED);
    assertConfirmationCode(
        observer -> service.confirmOriginalNewGameplayAdmission(foreignConfirmation, observer),
        peer,
        Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner, credentialOwner);
  }

  @Test
  void unexpectedParserRuntimeFailureTerminatesTheObserverWithoutCallingOwner() {
    var request = acquisition(NAMESPACE);
    // Fault injection checks the handler boundary, not a reproduced malformed-wire vector.
    for (RuntimeException parserFailure :
        List.of(
            new NullPointerException("private parser detail " + JWT),
            new IllegalStateException("private parser detail " + JWT))) {
      try (var codec =
          mockStatic(AccountGameplayAdmissionLeaseGrpcCodec.class, CALLS_REAL_METHODS)) {
        codec
            .when(() -> AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(request))
            .thenThrow(parserFailure);
        var failed = new Capture<AccountGameplayAdmissionLeaseResponse>();
        withPeer(
            peer(NAMESPACE, "game-session-service"),
            () -> service.acquireGameplayAdmissionLease(request, failed));
        assertThat(failed.code()).isEqualTo(Status.Code.INTERNAL);
        assertThat(failed.description)
            .isEqualTo("Account gameplay admission lease operation denied or unavailable")
            .doesNotContain(JWT, "private parser detail");
        assertThat(failed.failureHadCause).isFalse();
        assertThat(failed.errors).isEqualTo(1);
        assertThat(failed.responses).isZero();
        assertThat(failed.completed).isFalse();
        codec.verify(() -> AccountGameplayAdmissionLeaseGrpcCodec.parseAcquireRequest(request));
      }
    }
    verifyNoInteractions(owner, credentialOwner);
  }

  @Test
  void ownerFailureIsSanitizedAndObserverCallbackFailureIsNotReclassified() {
    var request = acquisition(NAMESPACE);
    when(owner.acquire(any()))
        .thenThrow(
            Status.FAILED_PRECONDITION
                .withDescription("private token detail")
                .asRuntimeException());
    var failed = new Capture<AccountGameplayAdmissionLeaseResponse>();
    withPeer(
        peer(NAMESPACE, "game-session-service"),
        () -> service.acquireGameplayAdmissionLease(request, failed));
    assertThat(failed.code()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(failed.description)
        .isEqualTo("Account gameplay admission lease operation denied or unavailable");
    assertThat(failed.description).doesNotContain("private token detail");
    verify(owner).acquire(request);

    doReturn(AccountGameplayAdmissionLeaseResponse.getDefaultInstance()).when(owner).acquire(any());
    var callbackFailure = new IllegalStateException("observer failure");
    StreamObserver<AccountGameplayAdmissionLeaseResponse> observer =
        new StreamObserver<>() {
          @Override
          public void onNext(AccountGameplayAdmissionLeaseResponse value) {
            throw callbackFailure;
          }

          @Override
          public void onError(Throwable failure) {
            throw new AssertionError("callback must not be converted to an owner error", failure);
          }

          @Override
          public void onCompleted() {
            throw new AssertionError("completion must not follow failed onNext");
          }
        };
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-session-service"),
                    () -> service.acquireGameplayAdmissionLease(request, observer)))
        .isSameAs(callbackFailure);
  }

  @Test
  void originalNewCredentialOwnerUnavailabilityIsSanitizedUnavailable() {
    assertCredentialIssuanceFailure(
        new IllegalStateException("private owner detail " + JWT), Status.Code.UNAVAILABLE);
  }

  @Test
  void unexpectedOriginalNewCredentialOwnerFailureRemainsSanitizedInternal() {
    assertCredentialIssuanceFailure(
        new NullPointerException("private owner detail " + JWT), Status.Code.INTERNAL);
  }

  private void assertCredentialIssuanceFailure(RuntimeException failure, Status.Code expected) {
    var request =
        AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialRequest(
            JWT, lease(NAMESPACE).canonicalJson().getBytes(StandardCharsets.UTF_8));
    when(credentialOwner.issue(any())).thenThrow(failure);
    var observer = new Capture<AccountGameplayAdmissionLeaseResponse>();
    withPeer(
        peer(NAMESPACE, "game-session-service"),
        () -> service.issueOriginalNewGameplayCredential(request, observer));
    assertThat(observer.code()).isEqualTo(expected);
    assertThat(observer.description)
        .isEqualTo("Account gameplay admission lease operation denied or unavailable")
        .doesNotContain(JWT, "private owner detail");
    assertThat(observer.failureHadCause).isFalse();
    assertThat(observer.errors).isEqualTo(1);
    assertThat(observer.responses).isZero();
    assertThat(observer.completed).isFalse();
    verify(credentialOwner).issue(any());
    verifyNoInteractions(owner);
  }

  private List<Call> callsWithDefaultRequests() {
    return List.of(
        observer ->
            service.acquireGameplayAdmissionLease(
                AcquireGameplayAdmissionLeaseRequest.getDefaultInstance(), observer),
        observer ->
            service.finalizeGameplayAdmissionLease(
                FinalizeGameplayAdmissionLeaseRequest.getDefaultInstance(), observer),
        observer ->
            service.readGameplayAdmissionLease(
                ReadGameplayAdmissionLeaseRequest.getDefaultInstance(), observer),
        observer ->
            service.abortGameplayAdmissionLease(
                AbortGameplayAdmissionLeaseRequest.getDefaultInstance(), observer),
        observer ->
            service.issueOriginalNewGameplayCredential(
                IssueOriginalNewGameplayCredentialRequest.getDefaultInstance(), observer));
  }

  private List<ConfirmationCall> confirmationCallsWithDefaultRequests() {
    return List.of(
        observer ->
            service.confirmOriginalNewGameplayAdmission(
                ConfirmOriginalNewGameplayAdmissionRequest.getDefaultInstance(), observer));
  }

  private void assertConfirmationCode(
      ConfirmationCall call, GrpcPeerIdentity peer, Status.Code expected) {
    var observer = new Capture<OriginalNewAdmissionConfirmationResponse>();
    withPeer(peer, () -> call.invoke(observer));
    assertThat(observer.code()).isEqualTo(expected);
    assertThat(observer.responses).isZero();
    assertThat(observer.errors).isEqualTo(1);
    assertThat(observer.completed).isFalse();
  }

  private void assertCode(Call call, GrpcPeerIdentity peer, Status.Code expected) {
    var observer = new Capture<AccountGameplayAdmissionLeaseResponse>();
    withPeer(peer, () -> call.invoke(observer));
    assertThat(observer.code()).isEqualTo(expected);
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(action);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static AcquireGameplayAdmissionLeaseRequest acquisition(String namespace) {
    Target target = target(namespace);
    return AccountGameplayAdmissionLeaseGrpcCodec.toAcquireRequest(
        REQUEST_ID, JWT, candidate(), target, new BigInteger(HIGH));
  }

  private static Candidate candidate() {
    return new Candidate(
        ACCOUNT_ID,
        TENANT_ID,
        OTHER_ID,
        OTHER_ID,
        "SHARED",
        OTHER_ID,
        71,
        OTHER_ID,
        OTHER_ID,
        new BigInteger(HIGH),
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        new BigInteger(HIGH),
        new BigInteger(HIGH),
        new BigInteger(HIGH));
  }

  private static Target target(String namespace) {
    var ownerRequest =
        new WorldCanonicalInitialAdmissionHold.Request(
            namespace,
            TENANT_ID,
            "world",
            OTHER_ID,
            OTHER_ID,
            "SHARED",
            OTHER_ID,
            VERSION_ID,
            new BigInteger(HIGH).longValueExact(),
            REQUEST_ID.toString(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            new BigInteger(HIGH).longValueExact(),
            null);
    var hold =
        new WorldCanonicalInitialAdmissionHold.HoldIdentity(ownerRequest, HOLD_ID, HOLD_FENCE);
    return new Target(
        namespace,
        "tenant",
        TENANT_ID,
        "world",
        "World",
        OTHER_ID,
        "realm",
        "Realm",
        11,
        71,
        OTHER_ID,
        "SHARED",
        OTHER_ID,
        VERSION_ID,
        99,
        new BigInteger(HIGH).longValueExact(),
        new BigInteger(HIGH).longValueExact(),
        "d".repeat(64),
        new BigInteger(HIGH).longValueExact(),
        REQUEST_ID.toString(),
        "c".repeat(64),
        OriginKind.NO_PRIOR_POINTER,
        null,
        HOLD_ID,
        HOLD_FENCE,
        hold.holdBindingDigest(),
        101,
        "sha256:" + "a".repeat(64),
        OwnerProofOutcome.COMMITTED,
        false,
        Instant.parse("2026-10-25T00:00:00Z"),
        "PRESEEDED_ONLY");
  }

  private static AccountGameplayAdmissionLeaseEvidence lease(String namespace) {
    Map<String, Object> value = new LinkedHashMap<>();
    String peerUri = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", namespace);
    value.put("callerWorkload", peerUri);
    value.put("requestId", REQUEST_ID.toString());
    value.put("leaseId", TENANT_ID.toString());
    value.put("leaseFence", HIGH);
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", ACCOUNT_ID.toString());
    scope.put("tenantId", TENANT_ID.toString());
    scope.put("realmId", OTHER_ID.toString());
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateNamespaceId", OTHER_ID.toString());
    scope.put("playableStateScope", "SHARED");
    scope.put("gameInstanceId", OTHER_ID.toString());
    scope.put("characterId", OTHER_ID.toString());
    scope.put("sessionId", OTHER_ID.toString());
    scope.put("bindingGeneration", HIGH);
    scope.put("catalogRevision", HIGH);
    scope.put("pointerVersion", HIGH);
    scope.put("regionId", OTHER_ID.toString());
    scope.put("regionEpoch", HIGH);
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(TENANT_ID.toString(), HIGH),
            "membershipAuthorityGeneration", Map.of(TENANT_ID.toString(), HIGH),
            "privateRealmGrantVersions", List.of()));
    value.put("issuanceFence", HIGH);
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT_ID.toString(), HIGH),
            "membershipAuthorityGeneration",
            HIGH));
    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (String suffix :
        List.of(
            "account/" + ACCOUNT_ID,
            "issuer/firemud-account-service",
            "membership/" + ACCOUNT_ID + "/" + TENANT_ID,
            "tenant/" + TENANT_ID)) {
      checkpoints.add(
          Map.of(
              "outboxStreamKey",
              "account:auth-authority:v1:" + suffix,
              "outboxSequence",
              suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : HIGH));
    }
    value.put("outboxCheckpoints", checkpoints);
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", ACCOUNT_ID.toString()),
            Map.entry("operationId", OTHER_ID.toString()),
            Map.entry("issuanceRequestId", OTHER_ID.toString()),
            Map.entry("tokenJti", OTHER_ID.toString()),
            Map.entry("tokenSHA256", jwtSha256(JWT)),
            Map.entry("tokenGeneration", HIGH),
            Map.entry("issuanceFence", HIGH),
            Map.entry("tokenIdentityFence", HIGH),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("issuedAt", "999"),
            Map.entry("notBefore", "999"),
            Map.entry("expiresAt", "1300")));
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  private static String jwtSha256(String jwt) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(jwt.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception unavailable) {
      throw new IllegalStateException("SHA-256 unavailable");
    }
  }

  private interface Call {
    void invoke(StreamObserver<AccountGameplayAdmissionLeaseResponse> observer);
  }

  private interface ConfirmationCall {
    void invoke(StreamObserver<OriginalNewAdmissionConfirmationResponse> observer);
  }

  private static final class Capture<T> implements StreamObserver<T> {
    private Status.Code code;
    private String description;
    private boolean failureHadCause;
    private int errors;
    private int responses;
    private boolean completed;

    @Override
    public void onNext(T value) {
      responses++;
    }

    @Override
    public void onError(Throwable failure) {
      failureHadCause = failure.getCause() != null;
      errors++;
      Status status = Status.fromThrowable(failure);
      code = status.getCode();
      description = status.getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private Status.Code code() {
      return code;
    }
  }
}
