package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationRequest;
import net.firedevops.firemud.account.v1.RedeemOperatorAuthorizationResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StartSessionOperatorRedemptionClientTest {
  private static final UUID TENANT = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER =
      UUID.fromString("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID OWNER_ATTEMPT = UUID.fromString("69116466-a576-4fa6-9e11-4c0c6b2a92f0");
  private static final UUID OWNER_MUTATION =
      UUID.fromString("89da7d84-12f5-4cf8-b69b-30ba8eb115a4");
  private static final UUID CLAIM_OWNER = UUID.fromString("31f85ac3-e621-4c38-a207-0e5aab116d34");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String NAMESPACE = "world-runtime";
  private static final String REQUEST_ID = "redemption-client-request";
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String OPAQUE_REFERENCE = "A".repeat(43);
  private static final Clock CURRENT_CLOCK =
      Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"), ZoneOffset.UTC);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void sendsExactPreTupleReservationAndActualGameSessionAttemptClaimOnce() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    AttemptClaim claim = claim(REQUEST_ID);
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.redeemOperatorAuthorization(any(RedeemOperatorAuthorizationRequest.class)))
        .thenReturn(validResponse(tuple));
    StartSessionOperatorRedemptionClient client = newClient(stub, CURRENT_CLOCK);

    StartSessionOperatorRedemptionClient.RedemptionResult result =
        client.redeem(tuple, OPAQUE_REFERENCE, claim);

    var requestCaptor =
        org.mockito.ArgumentCaptor.forClass(RedeemOperatorAuthorizationRequest.class);
    verify(stub, times(1)).redeemOperatorAuthorization(requestCaptor.capture());
    RedeemOperatorAuthorizationRequest request = requestCaptor.getValue();
    assertThat(request.getCanonicalPreAuthorizationTupleBytes().toByteArray())
        .containsExactly(
            tuple.preAuthorizationTuple().canonicalJson().getBytes(StandardCharsets.UTF_8));
    assertThat(request.getOperatorAuthorizationReference()).isEqualTo(OPAQUE_REFERENCE);
    assertThat(request.getAuthorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(request.getReservationOwnerId()).isEqualTo(RESERVATION_OWNER.toString());
    assertThat(request.getReservationClaimFence()).isEqualTo(19L);
    assertThat(request.getOwnerAttemptId()).isEqualTo(OWNER_ATTEMPT.toString());
    assertThat(request.getOwnerFence()).isEqualTo(37L);
    assertThat(result.gameSessionClaim()).isEqualTo(claim);
    assertThat(result.accountReplay()).isFalse();
    AccountRedemptionProjection projection = result.projection();
    assertThat(projection.authorizationReferenceFingerprint()).isEqualTo(FINGERPRINT);
    assertThat(projection.authorityEvidenceBundleBytes())
        .containsExactly(tuple.authorityEvidenceBundleBytes());
    assertThat(projection.issuanceOperationId()).isEqualTo(ISSUANCE_ID);
    assertThat(projection.issuanceFence()).isEqualTo(23L);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void rejectsMismatchedClaimAndExpiredAuthorityBeforeCallingAccount() throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    StartSessionOperatorRedemptionClient client = newClient(stub, CURRENT_CLOCK);
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);

    assertThatThrownBy(
            () ->
                client.redeem(
                    tuple,
                    OPAQUE_REFERENCE,
                    new AttemptClaim(
                        "another-namespace",
                        REQUEST_ID,
                        OWNER_ATTEMPT,
                        OWNER_MUTATION,
                        CLAIM_OWNER,
                        37L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("claim differs");

    StartSessionPostAuthorizationExecutionTuple expired =
        tuple(REQUEST_ID, "2026-10-09T00:00:00Z", "2026-10-09T00:00:30Z");
    StartSessionOperatorRedemptionClient expiredClient =
        newClient(stub, Clock.fixed(Instant.parse("2026-10-09T00:01:00Z"), ZoneOffset.UTC));
    assertThatThrownBy(() -> expiredClient.redeem(expired, OPAQUE_REFERENCE, claim(REQUEST_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
    verifyNoInteractions(stub);
  }

  @Test
  void rejectsMalformedMissingUnknownAndSubstitutedAccountProjectionFields() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    assertRejected(tuple, RedeemOperatorAuthorizationResponse.getDefaultInstance());

    assertRejected(
        tuple,
        validResponse(tuple).toBuilder()
            .setAuthorizationReferenceFingerprint("arfp/v1/other/" + "c".repeat(64))
            .build());
    assertRejected(
        tuple, validResponse(tuple).toBuilder().setIssuanceOperationId("not-a-uuid").build());
    assertRejected(tuple, validResponse(tuple).toBuilder().setIssuanceFence(24L).build());
    assertRejected(
        tuple,
        validResponse(tuple).toBuilder()
            .setAuthorityEvidenceBundle(
                ByteString.copyFrom(
                    bundle(
                        tuple.preAuthorizationTuple(),
                        "18",
                        "2026-10-09T00:00:00Z",
                        "2026-10-09T00:05:00Z")))
            .build());

    UnknownFieldSet unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    assertRejected(tuple, validResponse(tuple).toBuilder().setUnknownFields(unknown).build());
  }

  @Test
  void letsRedemptionTimeoutRemainAmbiguousWithoutAutomaticRetryOrTerminalization() {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    StatusRuntimeException timeout =
        new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("ambiguous response"));
    when(stub.redeemOperatorAuthorization(any(RedeemOperatorAuthorizationRequest.class)))
        .thenThrow(timeout);
    StartSessionOperatorRedemptionClient client = newClient(stub, CURRENT_CLOCK);

    assertThatThrownBy(() -> client.redeem(tuple(REQUEST_ID), OPAQUE_REFERENCE, claim(REQUEST_ID)))
        .isSameAs(timeout);

    verify(stub, times(1))
        .redeemOperatorAuthorization(any(RedeemOperatorAuthorizationRequest.class));
  }

  @Test
  void refusesPlaintextOrMissingMutualTlsCredentialsAndDoesNotInitializeByConstruction() {
    CommonGrpcClientProperties plaintext = tlsProperties();
    plaintext.setPlaintext(true);
    assertThatThrownBy(
            () ->
                new StartSessionOperatorRedemptionClient(
                    new ServiceEndpointsProperties(),
                    plaintext,
                    mock(GrpcChannelFactory.class),
                    BlockingGrpcStubCustomizer.noop(),
                    CURRENT_CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutual TLS");

    CommonGrpcClientProperties missingIdentity = new CommonGrpcClientProperties();
    missingIdentity.setCaCert("ca.pem");
    assertThatThrownBy(
            () ->
                new StartSessionOperatorRedemptionClient(
                    new ServiceEndpointsProperties(),
                    missingIdentity,
                    mock(GrpcChannelFactory.class),
                    BlockingGrpcStubCustomizer.noop(),
                    CURRENT_CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mutual TLS");

    StartSessionOperatorRedemptionClient uninitialized = newClient(null, CURRENT_CLOCK);
    assertThatThrownBy(
            () -> uninitialized.redeem(tuple(REQUEST_ID), OPAQUE_REFERENCE, claim(REQUEST_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("explicitly initialized");
  }

  private static void assertRejected(
      StartSessionPostAuthorizationExecutionTuple tuple,
      RedeemOperatorAuthorizationResponse response)
      throws Exception {
    var stub =
        mock(
            StartSessionOperatorAuthorizationServiceGrpc
                .StartSessionOperatorAuthorizationServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.redeemOperatorAuthorization(any(RedeemOperatorAuthorizationRequest.class)))
        .thenReturn(response);
    StartSessionOperatorRedemptionClient client = newClient(stub, CURRENT_CLOCK);
    assertThatThrownBy(() -> client.redeem(tuple, OPAQUE_REFERENCE, claim(REQUEST_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("malformed or tuple-substituted");
    verify(stub, times(1))
        .redeemOperatorAuthorization(any(RedeemOperatorAuthorizationRequest.class));
  }

  private static StartSessionOperatorRedemptionClient newClient(
      StartSessionOperatorAuthorizationServiceGrpc
              .StartSessionOperatorAuthorizationServiceBlockingStub
          stub,
      Clock clock) {
    StartSessionOperatorRedemptionClient client =
        new StartSessionOperatorRedemptionClient(
            new ServiceEndpointsProperties(),
            tlsProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop(),
            clock);
    if (stub != null) {
      setStub(client, stub);
    }
    return client;
  }

  private static CommonGrpcClientProperties tlsProperties() {
    CommonGrpcClientProperties tls = new CommonGrpcClientProperties();
    tls.setCertChain("client-chain.pem");
    tls.setPrivateKey("client-key.pem");
    tls.setCaCert("account-ca.pem");
    tls.setPlaintext(false);
    return tls;
  }

  private static void setStub(
      StartSessionOperatorRedemptionClient client,
      StartSessionOperatorAuthorizationServiceGrpc
              .StartSessionOperatorAuthorizationServiceBlockingStub
          stub) {
    try {
      Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
      field.setAccessible(true);
      field.set(client, stub);
    } catch (ReflectiveOperationException exception) {
      throw new AssertionError(exception);
    }
  }

  private static AttemptClaim claim(String requestId) {
    return new AttemptClaim(NAMESPACE, requestId, OWNER_ATTEMPT, OWNER_MUTATION, CLAIM_OWNER, 37L);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String requestId) {
    return tuple(requestId, "2026-10-09T00:00:00Z", "2026-10-09T00:05:00Z");
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(
      String requestId, String evaluatedAt, String expiresAt) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Game Session Account redemption client contract");
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        WORKLOAD,
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        bundle(pre, "17", evaluatedAt, expiresAt),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static RedeemOperatorAuthorizationResponse validResponse(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return RedeemOperatorAuthorizationResponse.newBuilder()
        .setAuthorizationReferenceFingerprint(FINGERPRINT)
        .setAuthorityEvidenceBundle(ByteString.copyFrom(tuple.authorityEvidenceBundleBytes()))
        .setIssuanceOperationId(ISSUANCE_ID.toString())
        .setIssuanceFence(23L)
        .setReplay(false)
        .build();
  }

  private static byte[] bundle(
      StartSessionPreAuthorizationReservationTuple tuple,
      String sourceVersion,
      String evaluatedAt,
      String expiresAt) {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType",
            "ACCOUNT",
            "sourceEvidenceId",
            "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion",
            sourceVersion,
            "projectionStatus",
            "CURRENT",
            "evaluatedAt",
            evaluatedAt,
            "expiresAt",
            expiresAt);
    Map<String, Object> identity =
        Map.of(
            "issuanceOperationId", ISSUANCE_ID.toString(),
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    Map<String, Object> authority =
        Map.of(
            "issuerAuthGeneration", 1L,
            "accountAuthorityGeneration", 2L,
            "tenantAuthorityGeneration", Map.of(tenantId, 3L),
            "membershipAuthorityGeneration", Map.of(tenantId, 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            ACTOR.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            identity,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
