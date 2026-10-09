package unit.net.firedevops.firemud.accountservice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.IssueHumanOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceRequest;
import net.firedevops.firemud.account.v1.RecoverOperatorAuthorizationReferenceResponse;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationRequest;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationResponse;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionOperatorAuthorizationRepository.RedemptionResult;
import net.firedevops.firemud.accountservice.service.impl.StartSessionOperatorAuthorizationGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorityBundle;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.AuthorizationResponse;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.IssueRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RecoverRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RedeemRequest;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionOperatorAuthorizationService.RedeemedOperationProjection;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.StreamUtils;

class StartSessionOperatorAuthorizationGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final String ACCOUNT_URI = "spiffe://firemud/ns/test/sa/account-service";
  private static final String LOGGING_URI = "spiffe://firemud/ns/test/sa/logging-admin-service";
  private static final String GAME_SESSION_URI = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final String GAME_DESIGN_URI = "spiffe://firemud/ns/test/sa/game-design-service";
  private static final String CONTROL_UI_JWT = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0ZXN0In0.signature";
  private static final String AUTHORIZATION_REFERENCE = "A".repeat(43);
  private static final String FINGERPRINT = "arfp/v1/key-1/" + "a".repeat(64);
  private static final UUID ACTOR_ID = UUID.fromString("6bbab35c-06fd-41f8-b340-989305201e49");
  private static final UUID TENANT_ID = UUID.fromString("37e21e06-af7e-4241-9c24-6dff3501d242");
  private static final UUID TARGET_ACCOUNT_ID =
      UUID.fromString("f806636f-792b-4a23-a736-92ea1f48340b");
  private static final UUID RESERVATION_OWNER_ID =
      UUID.fromString("e6c28d8f-775c-4ea4-af84-fd53a03b0a32");
  private static final UUID RECOVERY_OWNER_ID =
      UUID.fromString("5e377426-603e-4bfb-a1a0-3f784881aab4");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("43221254-3728-47a4-b15d-37542871e685");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("4fa8b657-c6ed-4797-b61d-66083f582198");
  private final AccountStartSessionOperatorAuthorizationService ownerService =
      mock(AccountStartSessionOperatorAuthorizationService.class);
  private final StartSessionOperatorAuthorizationGrpcService service =
      new StartSessionOperatorAuthorizationGrpcService(ownerService, NAMESPACE);

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void grpcRegistrationIsExplicitlyOptIn() throws IOException {
    ConditionalOnProperty condition =
        StartSessionOperatorAuthorizationGrpcService.class.getAnnotation(
            ConditionalOnProperty.class);

    assertThat(condition).isNotNull();
    assertThat(condition.prefix())
        .isEqualTo("firemud.account.start-session-operator-authorization");
    assertThat(condition.name()).containsExactly("enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
    assertThat(condition.matchIfMissing()).isFalse();

    try (InputStream input =
        new FileSystemResource("src/main/resources/application.yml").getInputStream()) {
      String applicationConfiguration = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
      assertThat(applicationConfiguration)
          .contains(
              "start-session-operator-authorization:\n"
                  + "      enabled: "
                  + "${FIREMUD_ACCOUNT_START_SESSION_OPERATOR_AUTHORIZATION_ENABLED:false}");
    }
  }

  @Test
  void issueRecoverAndRedeemMapExactTypedInputsAndResponses() {
    byte[] tupleBytes = canonicalTupleBytes();
    AuthorizationResponse authorization = authorizationResponse();
    when(ownerService.issue(any())).thenReturn(authorization);
    when(ownerService.recover(any())).thenReturn(authorization);
    when(ownerService.redeem(any()))
        .thenReturn(
            new RedemptionResult(
                FINGERPRINT, bytes("redemption-bundle"), ISSUANCE_OPERATION_ID, 9L, true));

    TestObserver<IssueHumanOperatorAuthorizationReferenceResponse> issueObserver =
        callIssue(issueRequest(tupleBytes), LOGGING_URI);
    assertThat(issueObserver.failure).isNull();
    assertThat(issueObserver.completed).isTrue();
    assertThat(issueObserver.value.getOperatorAuthorizationReference())
        .isEqualTo(AUTHORIZATION_REFERENCE);
    assertThat(issueObserver.value.getAuthorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(issueObserver.value.getExpiresAt().getSeconds())
        .isEqualTo(Instant.parse("2026-10-09T00:03:00Z").getEpochSecond());
    assertThat(issueObserver.value.getAuthorityEvidenceBundle().toByteArray())
        .containsExactly(bytes("authority-bundle"));
    assertThat(issueObserver.value.getBundleReference().getBundleVersion())
        .isEqualTo(AccountStartSessionOperatorAuthorityBundle.BUNDLE_VERSION);
    assertThat(issueObserver.value.getBundleReference().getSourceVersion()).isEqualTo("17");
    assertThat(issueObserver.value.getBundleReference().getSourceFence()).isEqualTo("4");
    assertThat(issueObserver.value.getBundleReference().getLinearization()).isEqualTo("3");
    assertThat(issueObserver.value.getAuthenticatedLoggingWorkloadIdentity())
        .isEqualTo(LOGGING_URI);

    var issueCaptor = org.mockito.ArgumentCaptor.forClass(IssueRequest.class);
    verify(ownerService).issue(issueCaptor.capture());
    assertThat(issueCaptor.getValue().controlUiJwt()).isEqualTo(CONTROL_UI_JWT);
    assertThat(issueCaptor.getValue().canonicalTupleBytes()).containsExactly(tupleBytes);
    assertThat(issueCaptor.getValue().reservationOwnerId()).isEqualTo(RESERVATION_OWNER_ID);
    assertThat(issueCaptor.getValue().reservationClaimFence()).isEqualTo(3L);
    assertThat(issueCaptor.getValue().claimOwnerId()).isEqualTo(RESERVATION_OWNER_ID);
    assertThat(issueCaptor.getValue().claimFence()).isEqualTo(3L);
    assertThat(issueCaptor.getValue().toString()).doesNotContain(CONTROL_UI_JWT);

    TestObserver<RecoverOperatorAuthorizationReferenceResponse> recoverObserver =
        callRecover(recoverRequest(tupleBytes), LOGGING_URI);
    assertThat(recoverObserver.failure).isNull();
    assertThat(recoverObserver.value.getOperatorAuthorizationReference())
        .isEqualTo(AUTHORIZATION_REFERENCE);
    assertThat(recoverObserver.value.getAuthenticatedLoggingWorkloadIdentity())
        .isEqualTo(LOGGING_URI);
    var recoverCaptor = org.mockito.ArgumentCaptor.forClass(RecoverRequest.class);
    verify(ownerService).recover(recoverCaptor.capture());
    assertThat(recoverCaptor.getValue().canonicalTupleBytes()).containsExactly(tupleBytes);
    assertThat(recoverCaptor.getValue().reservationOwnerId()).isEqualTo(RESERVATION_OWNER_ID);
    assertThat(recoverCaptor.getValue().reservationClaimFence()).isEqualTo(3L);
    assertThat(recoverCaptor.getValue().claimOwnerId()).isEqualTo(RECOVERY_OWNER_ID);
    assertThat(recoverCaptor.getValue().claimFence()).isEqualTo(5L);

    TestObserver<RedeemOperatorAuthorizationResponse> redeemObserver =
        callRedeem(redeemRequest(tupleBytes), GAME_SESSION_URI);
    assertThat(redeemObserver.failure).isNull();
    assertThat(redeemObserver.value.getAuthorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(redeemObserver.value.getAuthorityEvidenceBundle().toByteArray())
        .containsExactly(bytes("redemption-bundle"));
    assertThat(redeemObserver.value.getIssuanceOperationId())
        .isEqualTo(ISSUANCE_OPERATION_ID.toString());
    assertThat(redeemObserver.value.getIssuanceFence()).isEqualTo(9L);
    assertThat(redeemObserver.value.getReplay()).isTrue();
    var redeemCaptor = org.mockito.ArgumentCaptor.forClass(RedeemRequest.class);
    verify(ownerService).redeem(redeemCaptor.capture());
    assertThat(redeemCaptor.getValue().canonicalTupleBytes()).containsExactly(tupleBytes);
    assertThat(redeemCaptor.getValue().operatorAuthorizationReference())
        .isEqualTo(AUTHORIZATION_REFERENCE);
    assertThat(redeemCaptor.getValue().authorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(redeemCaptor.getValue().reservationOwnerId()).isEqualTo(RESERVATION_OWNER_ID);
    assertThat(redeemCaptor.getValue().reservationClaimFence()).isEqualTo(3L);
    assertThat(redeemCaptor.getValue().ownerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID);
    assertThat(redeemCaptor.getValue().ownerFence()).isEqualTo(11L);
    assertThat(redeemCaptor.getValue().toString())
        .doesNotContain(AUTHORIZATION_REFERENCE, FINGERPRINT);
  }

  @Test
  void readsExactRedeemedOperationProjectionWithoutCredentialMaterial() {
    byte[] tupleBytes = canonicalTupleBytes();
    when(ownerService.readRedeemedOperationProjection(any()))
        .thenReturn(redeemedOperationProjection(tupleBytes));

    TestObserver<ReadRedeemedOperationProjectionResponse> observer =
        callReadProjection(readProjectionRequest(tupleBytes), GAME_DESIGN_URI);

    assertThat(observer.failure).isNull();
    assertThat(observer.completed).isTrue();
    ReadRedeemedOperationProjectionResponse response = observer.value;
    assertThat(response.getControlPlaneRequestId()).isEqualTo("start-session/operator/βeta");
    assertThat(response.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        .containsExactly(tupleBytes);
    assertThat(response.getMutationDigest())
        .isEqualTo(
            StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                    new String(tupleBytes, StandardCharsets.UTF_8))
                .mutationDigest());
    assertThat(response.getAuthorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(response.getReservationOwnerId()).isEqualTo(RESERVATION_OWNER_ID.toString());
    assertThat(response.getReservationClaimFence()).isEqualTo(3L);
    assertThat(response.getAuthenticatedRedeemerWorkloadIdentity()).isEqualTo(GAME_SESSION_URI);
    assertThat(response.getOwnerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID.toString());
    assertThat(response.getOwnerFence()).isEqualTo(11L);
    assertThat(response.getReferenceExpiresAt().getSeconds())
        .isEqualTo(Instant.parse("2026-10-09T00:03:00Z").getEpochSecond());
    assertThat(response.getRedeemedAt().getSeconds())
        .isEqualTo(Instant.parse("2026-10-09T00:02:00Z").getEpochSecond());
    assertThat(response.getIssuanceOperationId()).isEqualTo(ISSUANCE_OPERATION_ID.toString());
    assertThat(response.getIssuanceFence()).isEqualTo(9L);
    assertThat(response.getBundleReference().getBundleVersion())
        .isEqualTo(AccountStartSessionOperatorAuthorityBundle.BUNDLE_VERSION);
    assertThat(response.getBundleReference().getSourceVersion()).isEqualTo("17");
    assertThat(response.getBundleReference().getSourceFence()).isEqualTo("4");
    assertThat(response.getBundleReference().getLinearization()).isEqualTo("3");
    assertThat(response.getAuthorityEvidenceBundle().toByteArray())
        .containsExactly(bytes("authority-bundle"));
    assertThat(response.toString()).doesNotContain(CONTROL_UI_JWT, AUTHORIZATION_REFERENCE);

    var readCaptor =
        org.mockito.ArgumentCaptor.forClass(
            AccountStartSessionOperatorAuthorizationService.ReadRedeemedOperationProjectionRequest
                .class);
    verify(ownerService).readRedeemedOperationProjection(readCaptor.capture());
    assertThat(readCaptor.getValue().canonicalPreAuthorizationTuple()).containsExactly(tupleBytes);
    assertThat(readCaptor.getValue().authorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(readCaptor.getValue().reservationOwnerId()).isEqualTo(RESERVATION_OWNER_ID);
    assertThat(readCaptor.getValue().reservationClaimFence()).isEqualTo(3L);
    assertThat(readCaptor.getValue().ownerAttemptId()).isEqualTo(OWNER_ATTEMPT_ID);
    assertThat(readCaptor.getValue().ownerFence()).isEqualTo(11L);
    verifyNoMoreInteractions(ownerService);
  }

  @Test
  void deniesWrongPeerBeforeParsingAndRejectsMalformedProjectionRead() {
    ReadRedeemedOperationProjectionRequest malformed =
        readProjectionRequest(new byte[] {(byte) 0xc3, 0x28});
    for (String peerUri :
        new String[] {
          null,
          "spiffe://firemud/ns/other/sa/game-design-service",
          GAME_SESSION_URI,
          LOGGING_URI,
          ACCOUNT_URI
        }) {
      assertThat(status(callReadProjection(malformed, peerUri)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    assertThat(status(callReadProjection(malformed, GAME_DESIGN_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                callReadProjection(
                    readProjectionRequest(canonicalTupleBytes()).toBuilder()
                        .setUnknownFields(unknownFields())
                        .build(),
                    GAME_DESIGN_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(
            status(
                callReadProjection(
                    readProjectionRequest(canonicalTupleBytes()).toBuilder()
                        .setOwnerFence(0L)
                        .build(),
                    GAME_DESIGN_URI)))
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(ownerService);
  }

  @Test
  void rejectsWrongOrMissingPeersBeforeParsingOrInvokingOwnerService() {
    IssueHumanOperatorAuthorizationReferenceRequest malformedIssue =
        issueRequest(new byte[] {(byte) 0xc3, 0x28});
    for (String peerUri :
        new String[] {
          null,
          "spiffe://firemud/ns/other/sa/logging-admin-service",
          "spiffe://firemud/ns/test/sa/game-session-service",
          ACCOUNT_URI
        }) {
      assertThat(status(callIssue(malformedIssue, peerUri)))
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    }
    assertThat(
            status(
                callRecover(
                    recoverRequest(canonicalTupleBytes()),
                    "spiffe://firemud/ns/other/sa/logging-admin-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            status(
                callRedeem(
                    redeemRequest(canonicalTupleBytes()),
                    "spiffe://firemud/ns/test/sa/logging-admin-service")))
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    StartSessionOperatorAuthorizationGrpcService unconfigured =
        new StartSessionOperatorAuthorizationGrpcService(ownerService, "");
    TestObserver<IssueHumanOperatorAuthorizationReferenceResponse> unconfiguredObserver =
        callIssue(unconfigured, malformedIssue, LOGGING_URI);
    assertThat(status(unconfiguredObserver)).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(ownerService);
  }

  @Test
  void rejectsAuthenticatedCallerContextBeforeParsingOrInvokingOwnerService() {
    SessionContext.setContext(ACTOR_ID.toString(), List.of("tenantAdmin"), Map.of());

    assertThat(status(callIssue(issueRequest(new byte[] {0x01}), LOGGING_URI)))
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(ownerService);
  }

  @Test
  void sanitizesUnavailableOwnerFailureWithoutExposingItsDetails() {
    when(ownerService.issue(any()))
        .thenThrow(
            Status.UNAVAILABLE.withDescription("private downstream detail").asRuntimeException());

    TestObserver<IssueHumanOperatorAuthorizationReferenceResponse> observer =
        callIssue(issueRequest(canonicalTupleBytes()), LOGGING_URI);

    assertThat(status(observer)).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(observer.failure.getDescription())
        .isEqualTo("Account operator authorization is temporarily unavailable")
        .doesNotContain("private downstream detail");
  }

  @Test
  void rejectsMalformedUnknownAndOverBoundRequestsBeforeInvokingOwnerService() {
    byte[] tupleBytes = canonicalTupleBytes();
    List<IssueHumanOperatorAuthorizationReferenceRequest> invalidIssues =
        List.of(
            issueRequest(tupleBytes).toBuilder().setUnknownFields(unknownFields()).build(),
            issueRequest(new byte[] {(byte) 0xc3, 0x28}),
            issueRequest(new byte[8 * 1024 + 1]),
            issueRequest(tupleBytes).toBuilder().setControlUiJwt("x".repeat(16 * 1024 + 1)).build(),
            issueRequest(tupleBytes).toBuilder().setReservationOwnerId("not-a-uuid").build(),
            issueRequest(tupleBytes).toBuilder()
                .setReservationOwnerId("00000000-0000-0000-0000-000000000000")
                .build(),
            issueRequest(tupleBytes).toBuilder().setReservationClaimFence(0L).build(),
            issueRequest(tupleBytes).toBuilder().setCurrentClaimFence(-1L).build());
    for (IssueHumanOperatorAuthorizationReferenceRequest invalid : invalidIssues) {
      assertThat(status(callIssue(invalid, LOGGING_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    List<RecoverOperatorAuthorizationReferenceRequest> invalidRecoveries =
        List.of(
            recoverRequest(tupleBytes).toBuilder().setUnknownFields(unknownFields()).build(),
            recoverRequest(new byte[] {(byte) 0xc3, 0x28}),
            recoverRequest(tupleBytes).toBuilder().setCurrentClaimOwnerId("not-a-uuid").build(),
            recoverRequest(tupleBytes).toBuilder().setCurrentClaimFence(-1L).build());
    for (RecoverOperatorAuthorizationReferenceRequest invalid : invalidRecoveries) {
      assertThat(status(callRecover(invalid, LOGGING_URI))).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    List<RedeemOperatorAuthorizationRequest> invalidRedemptions =
        List.of(
            redeemRequest(tupleBytes).toBuilder().setUnknownFields(unknownFields()).build(),
            redeemRequest(new byte[] {(byte) 0xc3, 0x28}),
            redeemRequest(tupleBytes).toBuilder()
                .setOperatorAuthorizationReference("x".repeat(129))
                .build(),
            redeemRequest(tupleBytes).toBuilder()
                .setAuthorizationReferenceFingerprint("changed")
                .build(),
            redeemRequest(tupleBytes).toBuilder().setOwnerAttemptId("not-a-uuid").build(),
            redeemRequest(tupleBytes).toBuilder().setOwnerFence(-1L).build());
    for (RedeemOperatorAuthorizationRequest invalid : invalidRedemptions) {
      assertThat(status(callRedeem(invalid, GAME_SESSION_URI)))
          .isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
    verifyNoInteractions(ownerService);
  }

  private TestObserver<IssueHumanOperatorAuthorizationReferenceResponse> callIssue(
      IssueHumanOperatorAuthorizationReferenceRequest request, String peerUri) {
    return callIssue(service, request, peerUri);
  }

  private TestObserver<IssueHumanOperatorAuthorizationReferenceResponse> callIssue(
      StartSessionOperatorAuthorizationGrpcService receiver,
      IssueHumanOperatorAuthorizationReferenceRequest request,
      String peerUri) {
    TestObserver<IssueHumanOperatorAuthorizationReferenceResponse> observer = new TestObserver<>();
    runWithPeer(
        peerUri, () -> receiver.issueHumanOperatorAuthorizationReference(request, observer));
    return observer;
  }

  private TestObserver<RecoverOperatorAuthorizationReferenceResponse> callRecover(
      RecoverOperatorAuthorizationReferenceRequest request, String peerUri) {
    TestObserver<RecoverOperatorAuthorizationReferenceResponse> observer = new TestObserver<>();
    runWithPeer(peerUri, () -> service.recoverOperatorAuthorizationReference(request, observer));
    return observer;
  }

  private TestObserver<RedeemOperatorAuthorizationResponse> callRedeem(
      RedeemOperatorAuthorizationRequest request, String peerUri) {
    TestObserver<RedeemOperatorAuthorizationResponse> observer = new TestObserver<>();
    runWithPeer(peerUri, () -> service.redeemOperatorAuthorization(request, observer));
    return observer;
  }

  private TestObserver<ReadRedeemedOperationProjectionResponse> callReadProjection(
      ReadRedeemedOperationProjectionRequest request, String peerUri) {
    TestObserver<ReadRedeemedOperationProjectionResponse> observer = new TestObserver<>();
    runWithPeer(peerUri, () -> service.readRedeemedOperationProjection(request, observer));
    return observer;
  }

  private static void runWithPeer(String peerUri, Runnable invocation) {
    if (peerUri == null) {
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, null).run(invocation);
    } else {
      GrpcPeerIdentity peer = GrpcPeerIdentity.parseUri(peerUri).orElseThrow();
      Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
    }
  }

  private static IssueHumanOperatorAuthorizationReferenceRequest issueRequest(byte[] tupleBytes) {
    return IssueHumanOperatorAuthorizationReferenceRequest.newBuilder()
        .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
        .setControlUiJwt(CONTROL_UI_JWT)
        .setReservationOwnerId(RESERVATION_OWNER_ID.toString())
        .setReservationClaimFence(3L)
        .setCurrentClaimOwnerId(RESERVATION_OWNER_ID.toString())
        .setCurrentClaimFence(3L)
        .build();
  }

  private static RecoverOperatorAuthorizationReferenceRequest recoverRequest(byte[] tupleBytes) {
    return RecoverOperatorAuthorizationReferenceRequest.newBuilder()
        .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
        .setReservationOwnerId(RESERVATION_OWNER_ID.toString())
        .setReservationClaimFence(3L)
        .setCurrentClaimOwnerId(RECOVERY_OWNER_ID.toString())
        .setCurrentClaimFence(5L)
        .build();
  }

  private static RedeemOperatorAuthorizationRequest redeemRequest(byte[] tupleBytes) {
    return RedeemOperatorAuthorizationRequest.newBuilder()
        .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
        .setOperatorAuthorizationReference(AUTHORIZATION_REFERENCE)
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setReservationOwnerId(RESERVATION_OWNER_ID.toString())
        .setReservationClaimFence(3L)
        .setOwnerAttemptId(OWNER_ATTEMPT_ID.toString())
        .setOwnerFence(11L)
        .build();
  }

  private static ReadRedeemedOperationProjectionRequest readProjectionRequest(byte[] tupleBytes) {
    return ReadRedeemedOperationProjectionRequest.newBuilder()
        .setCanonicalPreAuthorizationTupleBytes(ByteString.copyFrom(tupleBytes))
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setReservationOwnerId(RESERVATION_OWNER_ID.toString())
        .setReservationClaimFence(3L)
        .setOwnerAttemptId(OWNER_ATTEMPT_ID.toString())
        .setOwnerFence(11L)
        .build();
  }

  private static RedeemedOperationProjection redeemedOperationProjection(byte[] tupleBytes) {
    StartSessionPreAuthorizationReservationTuple tuple =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
            new String(tupleBytes, StandardCharsets.UTF_8));
    return new RedeemedOperationProjection(
        tuple.controlPlaneRequestId(),
        tupleBytes,
        tuple.mutationDigest(),
        FINGERPRINT,
        RESERVATION_OWNER_ID,
        3L,
        GAME_SESSION_URI,
        OWNER_ATTEMPT_ID,
        11L,
        Instant.parse("2026-10-09T00:03:00Z"),
        Instant.parse("2026-10-09T00:02:00Z"),
        ISSUANCE_OPERATION_ID,
        9L,
        new AccountStartSessionOperatorAuthorityBundle.BundleReference(
            AccountStartSessionOperatorAuthorityBundle.BUNDLE_VERSION, "17", "4", "3"),
        bytes("authority-bundle"));
  }

  private static byte[] canonicalTupleBytes() {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "tenant-runtime"),
            new StartSessionOperatorAction.Target(42L, TARGET_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "unit test StartSession authorization");
    return StartSessionPreAuthorizationReservationTuple.createHuman(
            "start-session/operator/βeta", ACTOR_ID, action)
        .canonicalJson()
        .getBytes(StandardCharsets.UTF_8);
  }

  private static AuthorizationResponse authorizationResponse() {
    return new AuthorizationResponse(
        AUTHORIZATION_REFERENCE,
        FINGERPRINT,
        Instant.parse("2026-10-09T00:03:00Z"),
        bytes("authority-bundle"),
        new AccountStartSessionOperatorAuthorityBundle.BundleReference(
            AccountStartSessionOperatorAuthorityBundle.BUNDLE_VERSION, "17", "4", "3"));
  }

  private static UnknownFieldSet unknownFields() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static Status.Code status(TestObserver<?> observer) {
    assertThat(observer.failure).isNotNull();
    return observer.failure.getCode();
  }

  private static final class TestObserver<T> implements StreamObserver<T> {
    private T value;
    private Status failure;
    private boolean completed;

    @Override
    public void onNext(T response) {
      value = response;
    }

    @Override
    public void onError(Throwable throwable) {
      failure = Status.fromThrowable(throwable);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
