package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminal;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadGrpcCodec;
import net.firedevops.firemud.common.world.WorldStartSessionExecutionTerminalReadRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceExecutionIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionLookup;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionOperation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository.ExecutionState;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldStartSessionExecutionTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldStartSessionExecutionTerminalResponse;
import net.firedevops.firemud.worldmanagement.v1.WorldStartSessionExecutionTerminalReadServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class WorldStartSessionExecutionTerminalReadGrpcServiceTest {
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d42-903a-33495456a622");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID PARTICIPATION_ID = uuid("a8c1e8c8-f237-41b7-918d-ec2281bcac10");
  private static final UUID OWNER_ATTEMPT_ID = uuid("b9d2f9d9-0438-42c8-829e-fd3392cd9d21");
  private static final UUID GAME_INSTANCE_ID = uuid("c0e30aea-1549-43d9-93af-0e44a3deae32");
  private static final UUID READ_ID = uuid("d1f41bfb-265a-4f3b-8b2a-124cba20ce43");
  private static final String CONTROL_PLANE_REQUEST_ID = "terminal-read-original-request";
  private static final String NAMESPACE = "world-runtime";
  private static final String PREPARATION_INPUT =
      "{ \"selected\" : \"immutable source\", \"revision\": 3 }\n";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearAmbientContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactAccountWorkloadIsAuthenticatedBeforeParsingOrOwnerAccess() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var service = service(repository, ignored -> {});
    var grpc = new WorldStartSessionExecutionTerminalReadGrpcService(service, NAMESPACE);
    var malformed =
        ReadWorldStartSessionExecutionTerminalRequest.newBuilder().setReadRequestId("bad").build();

    Collector noPeer = new Collector();
    grpc.readWorldStartSessionExecutionTerminal(malformed, noPeer);
    assertCode(noPeer, Status.Code.UNAUTHENTICATED);

    Collector wrongWorkload = new Collector();
    callAs(grpc, peer("game-session-service", NAMESPACE), malformed, wrongWorkload);
    assertCode(wrongWorkload, Status.Code.PERMISSION_DENIED);

    Collector wrongNamespace = new Collector();
    callAs(grpc, peer("account-service", "other-runtime"), malformed, wrongNamespace);
    assertCode(wrongNamespace, Status.Code.PERMISSION_DENIED);

    Collector endUser = new Collector();
    SessionContext.setContext("42", List.of(), Map.of());
    callAs(grpc, peer("account-service", NAMESPACE), malformed, endUser);
    assertCode(endUser, Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void actualTransactionAndSynchronizationAreRejectedBeforeDecode() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var grpc =
        new WorldStartSessionExecutionTerminalReadGrpcService(
            service(repository, ignored -> {}), NAMESPACE);
    var malformed =
        ReadWorldStartSessionExecutionTerminalRequest.newBuilder().setReadRequestId("bad").build();

    TransactionSynchronizationManager.setActualTransactionActive(true);
    Collector actualTransaction = new Collector();
    callAs(grpc, peer("account-service", NAMESPACE), malformed, actualTransaction);
    assertCode(actualTransaction, Status.Code.FAILED_PRECONDITION);

    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    Collector synchronization = new Collector();
    callAs(grpc, peer("account-service", NAMESPACE), malformed, synchronization);
    assertCode(synchronization, Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void authenticatedMalformedOrUnknownFieldsFailBeforeRecovery() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var grpc =
        new WorldStartSessionExecutionTerminalReadGrpcService(
            service(repository, ignored -> {}), NAMESPACE);

    Collector malformed = new Collector();
    callAs(
        grpc,
        peer("account-service", NAMESPACE),
        ReadWorldStartSessionExecutionTerminalRequest.newBuilder().setReadRequestId("bad").build(),
        malformed);
    assertCode(malformed, Status.Code.INVALID_ARGUMENT);

    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
            .build();
    Collector unknownField = new Collector();
    callAs(
        grpc,
        peer("account-service", NAMESPACE),
        WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request()).toBuilder()
            .setUnknownFields(unknown)
            .build(),
        unknownField);
    assertCode(unknownField, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void requestNamespaceMustMatchOwnerNamespaceBeforeTheRecoveryVerifier() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var verificationCalls = new AtomicInteger();
    var grpc =
        new WorldStartSessionExecutionTerminalReadGrpcService(
            service(repository, ignored -> verificationCalls.incrementAndGet()), NAMESPACE);
    var foreignRequest =
        WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request("other-runtime"));
    Collector collector = new Collector();
    callAs(grpc, peer("account-service", NAMESPACE), foreignRequest, collector);

    assertCode(collector, Status.Code.PERMISSION_DENIED);
    assertThat(verificationCalls).hasValue(0);
    verifyNoInteractions(repository);
  }

  @Test
  void missingAndPendingWorldRowsRemainUnresolvedAndNeverReturnTerminalBytes() {
    for (ExecutionLookup lookup :
        List.of(
            new ExecutionLookup(Optional.empty(), false),
            new ExecutionLookup(
                Optional.of(new ExecutionOperation(ExecutionState.PENDING, 73L, null)), false))) {
      var repository = mock(WorldCanonicalInstancePreparationRepository.class);
      when(repository.readExactExecution(any(WorldCanonicalInstanceExecutionIdentity.class)))
          .thenReturn(lookup);
      var verificationCalls = new AtomicInteger();
      var grpc =
          new WorldStartSessionExecutionTerminalReadGrpcService(
              service(repository, ignored -> verificationCalls.incrementAndGet()), NAMESPACE);
      Collector collector = new Collector();
      callAs(
          grpc,
          peer("account-service", NAMESPACE),
          WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request()),
          collector);

      assertCode(collector, Status.Code.FAILED_PRECONDITION);
      assertThat(Status.fromThrowable(collector.error).getDescription())
          .contains("remains unresolved")
          .contains("not terminal proof");
      assertThat(collector.value).isNull();
      assertThat(verificationCalls).hasValue(1);
    }
  }

  @Test
  void returnsOnlyExactCommittedOrAbortedTerminalAfterSyntheticOriginalVerification() {
    for (ExecutionState state : List.of(ExecutionState.COMMITTED, ExecutionState.ABORTED)) {
      var repository = mock(WorldCanonicalInstancePreparationRepository.class);
      var order = new ArrayList<String>();
      Long instanceId = state == ExecutionState.COMMITTED ? 991L : null;
      var lookup =
          new ExecutionLookup(Optional.of(new ExecutionOperation(state, 73L, instanceId)), false);
      when(repository.readExactExecution(any(WorldCanonicalInstanceExecutionIdentity.class)))
          .thenAnswer(
              invocation -> {
                order.add("owner-read");
                return lookup;
              });
      // This explicitly synthetic verifier accepts the expired historical tuple so the test
      // proves recovery reads history without applying fresh-admission expiry.
      var grpc =
          new WorldStartSessionExecutionTerminalReadGrpcService(
              service(
                  repository,
                  identity -> {
                    order.add("synthetic-original-verifier");
                    assertThat(identity.originalAuthorizationExpiry()).isBefore(Instant.now());
                  }),
              NAMESPACE);
      Collector collector = new Collector();
      callAs(
          grpc,
          peer("account-service", NAMESPACE),
          WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request()),
          collector);

      assertThat(collector.error).isNull();
      assertThat(collector.completed).isTrue();
      assertThat(order).containsExactly("synthetic-original-verifier", "owner-read");
      assertThat(collector.value.getAllFields()).hasSize(2);
      var terminal =
          WorldStartSessionExecutionTerminalReadGrpcCodec.fromResponse(request(), collector.value);
      assertThat(terminal.outcome())
          .isEqualTo(
              state == ExecutionState.COMMITTED
                  ? WorldStartSessionExecutionTerminal.Outcome.COMMITTED
                  : WorldStartSessionExecutionTerminal.Outcome.ABORTED);
      assertThat(terminal.worldExecutionFence()).isEqualTo(73L);
      assertThat(terminal.canonicalGameInstanceId()).isEqualTo(GAME_INSTANCE_ID);
      assertThat(terminal.preparationInputJson()).isEqualTo(PREPARATION_INPUT);
    }
  }

  @Test
  void defaultRecoveryVerifierDeniesWithoutInventingPositiveEvidence() {
    var repository = mock(WorldCanonicalInstancePreparationRepository.class);
    var grpc =
        new WorldStartSessionExecutionTerminalReadGrpcService(
            new WorldCanonicalInstancePreparationService(repository), NAMESPACE);
    Collector collector = new Collector();
    callAs(
        grpc,
        peer("account-service", NAMESPACE),
        WorldStartSessionExecutionTerminalReadGrpcCodec.toRequest(request()),
        collector);

    assertCode(collector, Status.Code.FAILED_PRECONDITION);
    assertThat(collector.value).isNull();
    verifyNoInteractions(repository);
  }

  private static WorldCanonicalInstancePreparationService service(
      WorldCanonicalInstancePreparationRepository repository,
      WorldCanonicalInstancePreparationService.OriginalOperationRecoveryVerifier verifier) {
    return new WorldCanonicalInstancePreparationService(
        repository,
        input -> {
          throw new AssertionError("Historical terminal read must not start fresh execution");
        },
        verifier);
  }

  private static WorldStartSessionExecutionTerminalReadRequest request() {
    return request(NAMESPACE);
  }

  private static WorldStartSessionExecutionTerminalReadRequest request(String namespace) {
    var tuple = originalTuple(namespace);
    return new WorldStartSessionExecutionTerminalReadRequest(
        READ_ID,
        namespace,
        tuple.canonicalBytes(),
        PARTICIPATION_ID,
        31L,
        OWNER_ATTEMPT_ID,
        37L,
        GAME_INSTANCE_ID,
        PREPARATION_INPUT);
  }

  private static StartSessionPostAuthorizationExecutionTuple originalTuple(String namespace) {
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            CONTROL_PLANE_REQUEST_ID,
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        preTuple,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        bundle(preTuple, namespace),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] bundle(
      StartSessionPreAuthorizationReservationTuple tuple, String namespace) {
    String tenantId = TENANT.toString();
    Map<String, Object> projection =
        Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2020-01-01T00:00:00Z",
            "expiresAt", "2020-01-01T00:05:00Z");
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
                "scope", Map.of("tenantId", tenantId, "targetNamespace", namespace),
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
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static void callAs(
      WorldStartSessionExecutionTerminalReadServiceGrpc
              .WorldStartSessionExecutionTerminalReadServiceImplBase
          service,
      GrpcPeerIdentity peer,
      ReadWorldStartSessionExecutionTerminalRequest request,
      Collector collector) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.readWorldStartSessionExecutionTerminal(request, collector);
    } finally {
      context.detach(previous);
    }
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static void assertCode(Collector collector, Status.Code code) {
    assertThat(collector.error).isNotNull();
    assertThat(Status.fromThrowable(collector.error).getCode()).isEqualTo(code);
    assertThat(collector.value).isNull();
    assertThat(collector.completed).isFalse();
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Collector
      implements StreamObserver<ReadWorldStartSessionExecutionTerminalResponse> {
    private ReadWorldStartSessionExecutionTerminalResponse value;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(ReadWorldStartSessionExecutionTerminalResponse response) {
      value = response;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "The test collector retains the response throwable for assertions.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
