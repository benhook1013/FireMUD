package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalGrpcCodec;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionAdmissionTerminalResult;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.GameSessionCanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionAdmissionTerminalReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionAdmissionTerminalResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Mocked owner dispatch proof only; genuine owner and mTLS composition requires native proof. */
class OriginalStartSessionAdmissionTerminalReadGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID MUTATION = uuid("f1a3ab1e-9147-4667-b6c4-6eb5119e8a31");
  private static final UUID ATTEMPT = uuid("ec13cc04-ec15-4eb8-a018-c2c5e8da65f8");
  private static final UUID PARTICIPATION = uuid("47b3be7f-a32f-4e19-8916-8c3b8da07a82");
  private static final UUID HOLD_ID = uuid("0db7344a-1e67-4b95-905a-83dc9c472f0c");
  private static final UUID HOLD_FENCE = uuid("52a14272-f9e4-4f67-97c9-62247b5fbcc1");
  private static final UUID PROTECTION_ID = uuid("6b763f1d-c5bc-4080-b499-d29debc0a7b8");
  private static final String PROOF_DIGEST = "sha256:" + "e".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final GameSessionCanonicalInitialAdmissionRepository repository =
      mock(GameSessionCanonicalInitialAdmissionRepository.class);
  private final OriginalStartSessionAdmissionTerminalReadGrpcService service =
      new OriginalStartSessionAdmissionTerminalReadGrpcService(repository, NAMESPACE);

  @AfterEach
  void clearAmbientState() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void missingPeerIsUnauthenticatedBeforeMalformedRequestOrStorage() {
    var observer = new RecordingObserver();
    Context.ROOT.run(
        () ->
            service.readOriginalStartSessionAdmissionTerminal(
                ReadOriginalStartSessionAdmissionTerminalRequest.getDefaultInstance(), observer));
    assertDenied(observer, Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  @Test
  void wrongOrCrossNamespacePeerAndEndUserContextAreDeniedBeforeStorage() {
    for (String uri :
        List.of(
            "spiffe://firemud/ns/world-runtime/sa/world-management-service",
            "spiffe://firemud/ns/other-runtime/sa/account-service")) {
      assertDenied(
          invoke(uri, ReadOriginalStartSessionAdmissionTerminalRequest.getDefaultInstance()),
          Status.Code.PERMISSION_DENIED);
    }

    SessionContext.setContext("42", List.of("tenantAdmin"), Map.of());
    assertDenied(invokeAccount(wire()), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void malformedUnknownAndConfiguredNamespaceSubstitutionAreDenied() {
    var unknown =
        wire().toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertDenied(
        invokeAccount(ReadOriginalStartSessionAdmissionTerminalRequest.getDefaultInstance()),
        Status.Code.INVALID_ARGUMENT);
    assertDenied(invokeAccount(unknown), Status.Code.INVALID_ARGUMENT);
    assertDenied(
        invoke("spiffe://firemud/ns/other-runtime/sa/account-service", wire()),
        Status.Code.PERMISSION_DENIED);
    assertDenied(
        invoke(
            new OriginalStartSessionAdmissionTerminalReadGrpcService(repository, "other-runtime"),
            "spiffe://firemud/ns/other-runtime/sa/account-service",
            wire()),
        Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void ambientTransactionAndSynchronizationAreRejectedBeforeOwnerRead() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertDenied(invokeAccount(wire()), Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertDenied(invokeAccount(wire()), Status.Code.FAILED_PRECONDITION);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    verifyNoInteractions(repository);
  }

  @Test
  void absentOrPendingOwnerOutcomeIsNotTerminalOrInferredAbort() {
    var expected = request();
    when(repository.readTerminal(expected)).thenReturn(Optional.empty());
    assertDenied(invokeAccount(wire()), Status.Code.FAILED_PRECONDITION);
    verify(repository).readTerminal(expected);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void unavailableOwnerStorageCannotManufactureTerminalEvidence() {
    var expected = request();
    when(repository.readTerminal(expected))
        .thenThrow(new DataAccessException("test-only unavailable storage"));
    assertDenied(invokeAccount(wire()), Status.Code.UNAVAILABLE);
    verify(repository).readTerminal(expected);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void conflictingReturnedAccountBindingIsRejected() {
    var conflictingRequest = mock(OriginalStartSessionAdmissionTerminalRequest.class);
    when(conflictingRequest.canonicalBytes()).thenReturn(new byte[] {1});
    var result = mock(OriginalStartSessionAdmissionTerminalResult.class);
    when(result.request()).thenReturn(conflictingRequest);
    when(repository.readTerminal(ArgumentMatchers.any())).thenReturn(Optional.of(result));

    assertDenied(invokeAccount(wire()), Status.Code.FAILED_PRECONDITION);
    verify(repository).readTerminal(request());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void returnsExactTerminalOwnerEncodingForCompleteAccountEvidence() {
    var expected = request();
    var result =
        terminalResult(expected, GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED);
    when(repository.readTerminal(expected)).thenReturn(Optional.of(result));

    var observer = invokeAccount(wire());

    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    assertThat(observer.response())
        .isEqualTo(OriginalStartSessionAdmissionTerminalGrpcCodec.toResponse(result));
    var decoded =
        OriginalStartSessionAdmissionTerminalGrpcCodec.fromResponse(expected, observer.response());
    assertThat(decoded.canonicalBytes()).containsExactly(result.canonicalBytes());
    verify(repository).readTerminal(expected);
    verifyNoMoreInteractions(repository);
  }

  private RecordingObserver invokeAccount(
      ReadOriginalStartSessionAdmissionTerminalRequest request) {
    return invoke("spiffe://firemud/ns/world-runtime/sa/account-service", request);
  }

  private RecordingObserver invoke(
      String uri, ReadOriginalStartSessionAdmissionTerminalRequest request) {
    return invoke(service, uri, request);
  }

  private RecordingObserver invoke(
      OriginalStartSessionAdmissionTerminalReadGrpcService target,
      String uri,
      ReadOriginalStartSessionAdmissionTerminalRequest request) {
    var observer = new RecordingObserver();
    var peer = net.firedevops.firemud.common.grpc.GrpcPeerIdentity.parseUri(uri).orElseThrow();
    Context.current()
        .withValue(net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> target.readOriginalStartSessionAdmissionTerminal(request, observer));
    return observer;
  }

  private ReadOriginalStartSessionAdmissionTerminalRequest wire() {
    return OriginalStartSessionAdmissionTerminalGrpcCodec.toRequest(request());
  }

  private static OriginalStartSessionAdmissionTerminalRequest request() {
    return new OriginalStartSessionAdmissionTerminalRequest(evidence().canonicalBytes());
  }

  private static net.firedevops.firemud.common.account.startsession
          .AccountStartSessionAdmissionProtectionEvidence
      evidence() {
    var tuple = originalTuple();
    var protectionRequest =
        net.firedevops.firemud.common.account.startsession
            .AccountStartSessionAdmissionProtectionRequest.create(
            tuple.canonicalBytes(),
            net.firedevops.firemud.common.account.startsession
                .StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple),
            MUTATION,
            ATTEMPT,
            21L,
            Instant.parse("2026-10-09T10:20:30.456Z"),
            PARTICIPATION,
            22L,
            hold(tuple));
    byte[] capture =
        canonical(
            Map.of(
                "schema",
                net.firedevops.firemud.common.account.startsession
                    .AccountStartSessionAdmissionProtectionEvidence.SOURCE_CAPTURE_REFERENCE_SCHEMA,
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
    return net.firedevops.firemud.common.account.startsession
        .AccountStartSessionAdmissionProtectionEvidence.create(
        protectionRequest,
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
            "terminal-read-test",
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
    return canonical(
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of("tenantId", TENANT.toString(), "targetNamespace", NAMESPACE),
                "actionFamily",
                tuple.actionFamily(),
                "applicableAccountId",
                ACTOR.toString(),
                "applicableTenantId",
                TENANT.toString()),
            "accountProjectionEvidence",
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion",
                "17",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                "2026-10-09T00:00:00Z",
                "expiresAt",
                "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                "f5d044bd-7e5f-4e2d-9859-9025cbdcc60f",
                "controlPlaneRequestId",
                tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
                "mutationDigest",
                tuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                2L,
                "tenantAuthorityGeneration",
                Map.of(TENANT.toString(), 3L),
                "membershipAuthorityGeneration",
                Map.of(TENANT.toString(), 4L),
                "privateRealmGrantVersions",
                List.of()),
            "membershipVersion",
            Map.of(TENANT.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                ACTOR.toString(),
                "controlUiTokenJti",
                "a681bba7-c215-4cf1-a35b-14348912cbdc",
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3")));
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

  private static OriginalStartSessionAdmissionTerminalResult terminalResult(
      OriginalStartSessionAdmissionTerminalRequest request,
      GameSessionCanonicalInitialAdmissionOwnerProof.Outcome outcome) {
    var proof =
        new GameSessionCanonicalInitialAdmissionOwnerProof(
            request.protectionEvidence().request().worldAdmissionHoldIdentity(),
            outcome,
            1L,
            23L,
            PROOF_DIGEST,
            false,
            Instant.parse("2026-10-09T10:20:30Z"));
    return new OriginalStartSessionAdmissionTerminalResult(request, proof);
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

  private static void assertDenied(RecordingObserver observer, Status.Code status) {
    assertThat(observer.response()).isNull();
    assertThat(observer.completed()).isFalse();
    assertThat(observer.error()).isEqualTo(status);
  }

  private static final class RecordingObserver
      implements StreamObserver<ReadOriginalStartSessionAdmissionTerminalResponse> {
    private ReadOriginalStartSessionAdmissionTerminalResponse response;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadOriginalStartSessionAdmissionTerminalResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    ReadOriginalStartSessionAdmissionTerminalResponse response() {
      return response;
    }

    Status.Code error() {
      return error;
    }

    boolean completed() {
      return completed;
    }
  }
}
