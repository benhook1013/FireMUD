package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.account.v1.AcquireOriginalStartSessionAdmissionProtectionResponse;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionInput;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic receiver proof; the owner and TLS peer are supplied by test doubles/context. */
class AccountStartSessionAdmissionProtectionAcquisitionGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String GAME_SESSION_URI =
      "spiffe://firemud/ns/world-runtime/sa/game-session-service";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID HOLD_ID = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final UUID PROTECTION_ID = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void authenticatesPeerAndRejectsEndUserBeforeInspectingRequest() {
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionAdmissionProtectionAcquisitionGrpcService(owner, NAMESPACE);
    var malformed = AcquireOriginalStartSessionAdmissionProtectionRequest.newBuilder().build();

    var missingPeer = invoke(service, null, malformed);
    assertCode(Status.Code.UNAUTHENTICATED, missingPeer.error);
    var wrongService =
        invoke(
            service,
            identity("spiffe://firemud/ns/world-runtime/sa/world-management-service"),
            malformed);
    assertCode(Status.Code.PERMISSION_DENIED, wrongService.error);
    var wrongNamespace =
        invoke(
            service,
            identity("spiffe://firemud/ns/other-runtime/sa/game-session-service"),
            malformed);
    assertCode(Status.Code.PERMISSION_DENIED, wrongNamespace.error);

    SessionContext.setContext("123", List.of(), Map.of());
    var userContext = invoke(service, identity(GAME_SESSION_URI), malformed);
    assertCode(Status.Code.PERMISSION_DENIED, userContext.error);
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientSqlBeforeParsingAndMalformedMessagesBeforeOwnerCall() {
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionAdmissionProtectionAcquisitionGrpcService(owner, NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var ambient = invoke(service, identity(GAME_SESSION_URI), null);
    assertCode(Status.Code.FAILED_PRECONDITION, ambient.error);
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    var synchronizedCall = invoke(service, identity(GAME_SESSION_URI), null);
    assertCode(Status.Code.FAILED_PRECONDITION, synchronizedCall.error);
    TransactionSynchronizationManager.clearSynchronization();

    var missing = invoke(service, identity(GAME_SESSION_URI), null);
    assertCode(Status.Code.INVALID_ARGUMENT, missing.error);
    var malformed =
        AcquireOriginalStartSessionAdmissionProtectionRequest.newBuilder()
            .setSchemaVersion(1)
            .setTargetNamespace(NAMESPACE)
            .setGameSessionOwnerMutationId("not-a-uuid")
            .build();
    var invalid = invoke(service, identity(GAME_SESSION_URI), malformed);
    assertCode(Status.Code.INVALID_ARGUMENT, invalid.error);
    verifyNoInteractions(owner);
  }

  @Test
  void delegatesExactInputAndReturnsTheCompleteCanonicalEvidence() {
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionAdmissionProtectionAcquisitionGrpcService(owner, NAMESPACE);
    var input = input();
    var evidence = evidence(input);
    when(owner.acquire(any())).thenReturn(evidence);
    var request =
        AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(input, NAMESPACE);

    var result = invoke(service, identity(GAME_SESSION_URI), request);

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.response)
        .isEqualTo(
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toResponse(
                input, NAMESPACE, evidence));
    assertThat(result.response.getEvidenceCanonicalBytes().toByteArray())
        .containsExactly(evidence.canonicalBytes());
    ArgumentCaptor<AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest>
        captured =
            ArgumentCaptor.forClass(
                AccountStartSessionAdmissionProtectionAcquisitionService.AcquisitionRequest.class);
    verify(owner).acquire(captured.capture());
    var actual = captured.getValue();
    assertThat(actual.originalPostAuthorizationTuple())
        .containsExactly(input.originalPostAuthorizationTuple());
    assertThat(actual.gameSessionOwnerMutationId()).isEqualTo(MUTATION);
    assertThat(actual.gameSessionOwnerAttemptId()).isEqualTo(ATTEMPT);
    assertThat(actual.gameSessionOwnerFence()).isEqualTo(21L);
    assertThat(actual.worldHoldIdentity().canonicalBytes())
        .containsExactly(input.worldHoldIdentity().canonicalBytes());
  }

  @Test
  void rejectsOwnerEvidenceWithSubstitutedBindingAndRedactsOwnerFailure() {
    var owner = mock(AccountStartSessionAdmissionProtectionAcquisitionService.class);
    var service =
        new AccountStartSessionAdmissionProtectionAcquisitionGrpcService(owner, NAMESPACE);
    var input = input();
    var changedInput =
        new AccountStartSessionAdmissionProtectionAcquisitionInput(
            input.originalPostAuthorizationTuple(),
            MUTATION,
            uuid("5bc4c35d-eac4-4f9e-a8fc-7ac79aebee23"),
            21L,
            input.worldHoldIdentity());
    when(owner.acquire(any())).thenReturn(evidence(changedInput));
    var substituted =
        invoke(
            service,
            identity(GAME_SESSION_URI),
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(input, NAMESPACE));
    assertCode(Status.Code.FAILED_PRECONDITION, substituted.error);
    assertThat(Status.fromThrowable(substituted.error).getDescription()).isNull();

    when(owner.acquire(any()))
        .thenThrow(
            Status.UNAVAILABLE.withDescription("sensitive owner detail").asRuntimeException());
    var unavailable =
        invoke(
            service,
            identity(GAME_SESSION_URI),
            AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec.toRequest(input, NAMESPACE));
    assertCode(Status.Code.UNAVAILABLE, unavailable.error);
    assertThat(Status.fromThrowable(unavailable.error).getDescription()).isNull();
  }

  private static Invocation invoke(
      AccountStartSessionAdmissionProtectionAcquisitionGrpcService service,
      GrpcPeerIdentity peer,
      AcquireOriginalStartSessionAdmissionProtectionRequest request) {
    var result = new Invocation();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.acquireOriginalStartSessionAdmissionProtection(request, result);
    } finally {
      context.detach(previous);
    }
    return result;
  }

  private static GrpcPeerIdentity identity(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static void assertCode(Status.Code expected, Throwable failure) {
    assertThat(failure).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(expected);
  }

  private static AccountStartSessionAdmissionProtectionAcquisitionInput input() {
    var tuple = originalTuple();
    return new AccountStartSessionAdmissionProtectionAcquisitionInput(
        tuple.canonicalBytes(), MUTATION, ATTEMPT, 21L, hold(tuple));
  }

  private static AccountStartSessionAdmissionProtectionEvidence evidence(
      AccountStartSessionAdmissionProtectionAcquisitionInput input) {
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(input.originalPostAuthorizationTuple());
    var ownerRequest =
        AccountStartSessionAdmissionProtectionRequest.create(
            input.originalPostAuthorizationTuple(),
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            input.gameSessionOwnerMutationId(),
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerFence(),
            Instant.parse("2026-10-09T10:20:30.456Z"),
            PARTICIPATION,
            22L,
            input.worldHoldIdentity());
    byte[] capture =
        canonical(
            Map.of(
                "schema",
                AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "capturedAt",
                "2026-10-09T10:11:12.123Z",
                "bundleReference",
                Map.of(
                    "bundleVersion",
                    StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                    "sourceVersion",
                    "17",
                    "sourceFence",
                    "23",
                    "linearization",
                    "18446744073709551615"),
                "snapshotSha256",
                "d".repeat(64)));
    return AccountStartSessionAdmissionProtectionEvidence.create(
        ownerRequest,
        PROTECTION_ID,
        23L,
        capture,
        sha256(capture),
        List.of(
            new SourceEvidence(
                SourceKind.ACCOUNT,
                TENANT.toString(),
                "2",
                "17",
                null,
                null,
                "exact account source".getBytes(StandardCharsets.UTF_8))));
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "receiver-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical original StartSession admission attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
        "arfp/v1/test-key/" + "b".repeat(64),
        uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf"),
        19L,
        authorityBundle(preTuple),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple) {
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2026-10-09T00:00:00Z",
            "expiresAt", "2026-10-09T00:05:00Z");
    Map<String, Object> operation =
        Map.of(
            "issuanceOperationId", "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
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
            "tenantAuthorityGeneration", Map.of(TENANT.toString(), 3L),
            "membershipAuthorityGeneration", Map.of(TENANT.toString(), 4L),
            "privateRealmGrantVersions", List.of());
    Map<String, Object> issuanceEvidence =
        Map.of(
            "evidenceType", StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId", ACTOR.toString(),
            "controlUiTokenJti", "a681bba7-c215-4cf1-a35b-14348912cbdc",
            "role", "tenantAdmin",
            "accountGeneration", "2",
            "tenantGeneration", "3");
    return canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", TENANT.toString()),
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuanceEvidence));
  }

  private static WorldCanonicalInitialAdmissionHold.HoldIdentity hold(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    return new WorldCanonicalInitialAdmissionHold.HoldIdentity(
        new WorldCanonicalInitialAdmissionHold.Request(
            NAMESPACE,
            TENANT,
            "earth",
            uuid("3916f423-2870-426a-a8aa-5e3f97412613"),
            uuid("54e6094e-11bb-4f4c-93ee-a52f715b530b"),
            "SHARED",
            uuid("a55b2e10-9a24-4adb-adb8-6fc66fe3b8e9"),
            uuid("d7280ec0-5979-4b62-8418-e9f139415184"),
            3L,
            tuple.controlPlaneRequestId(),
            "c".repeat(64),
            WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin.NO_PRIOR_POINTER,
            12L,
            null),
        HOLD_ID,
        HOLD_FENCE);
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Invocation
      implements StreamObserver<AcquireOriginalStartSessionAdmissionProtectionResponse> {
    private AcquireOriginalStartSessionAdmissionProtectionResponse response;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(AcquireOriginalStartSessionAdmissionProtectionResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The test observer retains the transport failure for status and redaction assertions")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
