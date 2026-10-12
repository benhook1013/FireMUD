package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationRequest;
import net.firedevops.firemud.account.v1.AcquireStartSessionWorldParticipationResponse;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldOriginalAttemptEvidenceRepository.StoredEvidence;
import net.firedevops.firemud.accountservice.repository.AccountStartSessionWorldParticipationRepository.StoredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionGrpcService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquiredParticipation;
import net.firedevops.firemud.accountservice.service.session.AccountStartSessionWorldParticipationAcquisitionService.AcquisitionRequest;
import net.firedevops.firemud.common.account.startsession.StartSessionAccountRedemptionProjection;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Result;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic receiver proof only; Account persistence and TLS workload identity are mocked. */
class AccountStartSessionWorldParticipationAcquisitionGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final String WORLD_URI =
      "spiffe://firemud/ns/world-runtime/sa/world-management-service";
  private static final UUID ACCOUNT_ID = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TENANT_ID = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID MUTATION_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID ATTEMPT_ID = uuid("4a3fc69f-a18a-4b94-b1d1-2e8b4c3f0e4b");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final long PARTICIPATION_FENCE = 31L;
  private static final long GAME_SESSION_FENCE = 37L;
  private static final String PREPARATION_JSON =
      "{\"selected\":\"immutable source\",\"revision\":3}";
  private static final Instant ORIGINAL_LEASE_EXPIRY = Instant.parse("2026-10-12T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearThreadState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsWrongPeerNamespaceAndEndUserBeforeInspectingMalformedRequest() {
    var owner = mock(AccountStartSessionWorldParticipationAcquisitionService.class);
    var service = new AccountStartSessionWorldParticipationAcquisitionGrpcService(owner, NAMESPACE);
    var malformed = AcquireStartSessionWorldParticipationRequest.newBuilder().build();

    assertCode(Status.Code.UNAUTHENTICATED, invoke(service, null, malformed).error);
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
                service,
                identity("spiffe://firemud/ns/world-runtime/sa/game-session-service"),
                malformed)
            .error);
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
                service,
                identity("spiffe://firemud/ns/default/sa/world-management-service"),
                malformed)
            .error);
    assertCode(
        Status.Code.PERMISSION_DENIED,
        invoke(
                service,
                identity("spiffe://firemud/ns/other-runtime/sa/world-management-service"),
                malformed)
            .error);

    SessionContext.setContext("123", List.of(), Map.of());
    assertCode(
        Status.Code.PERMISSION_DENIED, invoke(service, identity(WORLD_URI), malformed).error);
    verifyNoInteractions(owner);
  }

  @Test
  void rejectsAmbientSqlUnknownFieldsAndMalformedBindingsBeforeBackendCall() {
    var owner = mock(AccountStartSessionWorldParticipationAcquisitionService.class);
    var service = new AccountStartSessionWorldParticipationAcquisitionGrpcService(owner, NAMESPACE);
    var valid = request();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertCode(Status.Code.FAILED_PRECONDITION, invoke(service, identity(WORLD_URI), null).error);
    TransactionSynchronizationManager.clear();

    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1).build())
                    .build())
            .build();
    var unsupportedSchema = valid.toBuilder().setSchemaVersion(2).build();
    var wrongNamespace = valid.toBuilder().setTargetNamespace("default").build();
    var malformedUuid = valid.toBuilder().setAcquisitionRequestId("not-a-uuid").build();
    var malformedFence = valid.toBuilder().setGameSessionOwnerFence(0).build();
    var malformedDigest =
        valid.toBuilder().setPreparationInputDigest("sha256:" + "0".repeat(64)).build();
    for (var malformed :
        List.of(
            unknown,
            unsupportedSchema,
            wrongNamespace,
            malformedUuid,
            malformedFence,
            malformedDigest)) {
      assertCode(
          Status.Code.INVALID_ARGUMENT, invoke(service, identity(WORLD_URI), malformed).error);
    }
    assertCode(Status.Code.INVALID_ARGUMENT, invoke(service, identity(WORLD_URI), null).error);
    verifyNoInteractions(owner);
  }

  @Test
  void delegatesExactRequestAndReturnsBoundedFullRetainedObservation() {
    var owner = mock(AccountStartSessionWorldParticipationAcquisitionService.class);
    var service = new AccountStartSessionWorldParticipationAcquisitionGrpcService(owner, NAMESPACE);
    var request = request();
    var expected = acquired(request);
    when(owner.acquire(any())).thenReturn(expected);

    var result = invoke(service, identity(WORLD_URI), request);

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.response.getRequest()).isEqualTo(request);
    assertThat(result.response.getAccountWorldParticipationId())
        .isEqualTo(PARTICIPATION_ID.toString());
    assertThat(result.response.getAccountWorldParticipationFence()).isEqualTo(PARTICIPATION_FENCE);
    assertThat(result.response.hasOriginalAttemptEvidence()).isTrue();
    assertThat(result.response.getOriginalAttemptEvidence())
        .isEqualTo(
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(
                expected.originalAttemptEvidence().result()));
    assertThat(result.response.getSerializedSize()).isLessThan(1024 * 1024);

    ArgumentCaptor<AcquisitionRequest> captured = ArgumentCaptor.forClass(AcquisitionRequest.class);
    verify(owner).acquire(captured.capture());
    assertThat(captured.getValue().originalPostAuthorizationTuple())
        .containsExactly(request.getOriginalPostAuthorizationTuple().toByteArray());
    assertThat(captured.getValue().gameSessionOwnerMutationId()).isEqualTo(MUTATION_ID);
    assertThat(captured.getValue().gameSessionOwnerAttemptId()).isEqualTo(ATTEMPT_ID);
    assertThat(captured.getValue().gameSessionOwnerFence()).isEqualTo(GAME_SESSION_FENCE);
    assertThat(captured.getValue().canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
    assertThat(captured.getValue().preparationInputJson()).isEqualTo(PREPARATION_JSON);
    assertThat(captured.getValue().preparationInputDigest()).isEqualTo(digest(PREPARATION_JSON));

    var freshCorrelationRequest =
        request.toBuilder().setAcquisitionRequestId(UUID.randomUUID().toString()).build();
    var freshCorrelationReply = invoke(service, identity(WORLD_URI), freshCorrelationRequest);
    assertThat(freshCorrelationReply.error).isNull();
    assertThat(freshCorrelationReply.completed).isTrue();
    assertThat(freshCorrelationReply.response.getRequest()).isEqualTo(freshCorrelationRequest);
    assertThat(freshCorrelationReply.response.getAccountWorldParticipationId())
        .isEqualTo(result.response.getAccountWorldParticipationId());
    assertThat(freshCorrelationReply.response.getAccountWorldParticipationFence())
        .isEqualTo(result.response.getAccountWorldParticipationFence());
    assertThat(freshCorrelationReply.response.getOriginalAttemptEvidence())
        .isEqualTo(result.response.getOriginalAttemptEvidence());
  }

  @Test
  void rejectsSubstitutedBackendEvidenceWithoutExposingOwnerDetails() {
    var owner = mock(AccountStartSessionWorldParticipationAcquisitionService.class);
    var service = new AccountStartSessionWorldParticipationAcquisitionGrpcService(owner, NAMESPACE);
    var request = request();
    var correct = acquired(request);
    var substituted =
        new StoredEvidence(
            uuid("11111111-1111-4111-8111-111111111111"),
            MUTATION_ID,
            correct.originalAttemptEvidence().originalLeaseExpiresAt(),
            correct.originalAttemptEvidence().originalResponseBytes(),
            correct.originalAttemptEvidence().originalResponseDigest(),
            correct.originalAttemptEvidence().result());
    when(owner.acquire(any()))
        .thenReturn(new AcquiredParticipation(correct.participation(), substituted));

    var result = invoke(service, identity(WORLD_URI), request);

    assertCode(Status.Code.FAILED_PRECONDITION, result.error);
    assertThat(Status.fromThrowable(result.error).getDescription()).isNull();
  }

  private static Invocation invoke(
      AccountStartSessionWorldParticipationAcquisitionGrpcService service,
      GrpcPeerIdentity peer,
      AcquireStartSessionWorldParticipationRequest request) {
    var result = new Invocation();
    Context context = Context.current();
    if (peer != null) context = context.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.acquireStartSessionWorldParticipation(request, result);
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

  private static AcquireStartSessionWorldParticipationRequest request() {
    var tuple = originalTuple();
    return AcquireStartSessionWorldParticipationRequest.newBuilder()
        .setSchemaVersion(1)
        .setAcquisitionRequestId("263a218e-1752-47f2-97c3-6273b2ea9f15")
        .setTargetNamespace(NAMESPACE)
        .setOriginalPostAuthorizationTuple(
            com.google.protobuf.ByteString.copyFrom(tuple.canonicalBytes()))
        .setGameSessionOwnerMutationId(MUTATION_ID.toString())
        .setGameSessionOwnerAttemptId(ATTEMPT_ID.toString())
        .setGameSessionOwnerFence(GAME_SESSION_FENCE)
        .setCanonicalGameInstanceId(GAME_INSTANCE_ID.toString())
        .setPreparationInputJson(PREPARATION_JSON)
        .setPreparationInputDigest(digest(PREPARATION_JSON))
        .build();
  }

  private static AcquiredParticipation acquired(AcquireStartSessionWorldParticipationRequest wire) {
    var tuple =
        StartSessionPostAuthorizationExecutionTuple.decode(
            wire.getOriginalPostAuthorizationTuple().toByteArray());
    var observationRequest =
        new Request(
            uuid("4d66578e-a7cd-4a37-8cfb-30aa4c0c23b3"),
            NAMESPACE,
            tuple.canonicalBytes(),
            ATTEMPT_ID,
            MUTATION_ID,
            GAME_SESSION_FENCE);
    var observation =
        new Result(
            observationRequest,
            ORIGINAL_LEASE_EXPIRY,
            StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
    byte[] originalResponse =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toResponse(observation).toByteArray();
    var participation =
        new StoredParticipation(
            PARTICIPATION_ID,
            PARTICIPATION_FENCE,
            tuple.controlPlaneRequestId(),
            tuple.canonicalBytes(),
            NAMESPACE,
            TENANT_ID,
            GAME_INSTANCE_ID,
            ATTEMPT_ID,
            GAME_SESSION_FENCE,
            PREPARATION_JSON,
            digest(PREPARATION_JSON),
            17L,
            OffsetDateTime.parse("2026-10-09T10:20:30.456Z"),
            List.of(
                new SourceEvidence(
                    SourceKind.ACCOUNT,
                    TENANT_ID.toString(),
                    "2",
                    "17",
                    null,
                    null,
                    "synthetic current source".getBytes(StandardCharsets.UTF_8))));
    var evidence =
        new StoredEvidence(
            PARTICIPATION_ID,
            MUTATION_ID,
            ORIGINAL_LEASE_EXPIRY,
            originalResponse,
            digestBytes(originalResponse),
            observation);
    return new AcquiredParticipation(participation, evidence);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple() {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "world-participation-grpc-test",
            ACCOUNT_ID,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT_ID, NAMESPACE),
                new StartSessionOperatorAction.Target(
                    91L, uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a")),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "synthetic World participation transport proof"));
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
    return StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
        tuple,
        new Values(
            uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f"),
            "sha256:" + "a".repeat(64),
            "17",
            "CURRENT",
            Instant.parse("2026-10-09T00:00:00Z"),
            Instant.parse("2026-10-10T00:00:00Z"),
            1L,
            2L,
            3L,
            4L,
            5L,
            23L,
            uuid("a681bba7-c215-4cf1-a35b-14348912cbdc"),
            "2",
            "3"));
  }

  private static String digest(String value) {
    return digestBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String digestBytes(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Invocation
      implements StreamObserver<AcquireStartSessionWorldParticipationResponse> {
    private AcquireStartSessionWorldParticipationResponse response;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(AcquireStartSessionWorldParticipationResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "The test observer retains the callback failure only for assertions")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
