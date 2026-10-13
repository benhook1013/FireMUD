package unit.net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadGrpcCodec;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalInstanceLaunchRepository;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionLaunchReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionLaunchResponse;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mocked owner dispatch proof; independent owner storage and authenticated mTLS remain separate.
 */
class OriginalStartSessionLaunchReadGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID GAME_INSTANCE = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final GameSessionCanonicalInstanceLaunchRepository repository =
      mock(GameSessionCanonicalInstanceLaunchRepository.class);
  private final OriginalStartSessionLaunchReadGrpcService service =
      new OriginalStartSessionLaunchReadGrpcService(repository, NAMESPACE);

  @AfterEach
  void clearAmbientState() {
    SessionContext.clear();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void missingPeerRejectsMalformedInputBeforeStorage() {
    var observer = new RecordingObserver();
    Context.ROOT.run(
        () ->
            service.readOriginalStartSessionLaunch(
                ReadOriginalStartSessionLaunchRequest.getDefaultInstance(), observer));
    assertDenied(observer, Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(repository);
  }

  @Test
  void onlyExactSameNamespaceAccountWithoutEndUserContextIsAccepted() {
    for (String uri :
        List.of(
            "spiffe://firemud/ns/world-runtime/sa/world-management-service",
            "spiffe://firemud/ns/other-runtime/sa/account-service")) {
      assertDenied(
          invoke(uri, ReadOriginalStartSessionLaunchRequest.getDefaultInstance()),
          Status.Code.PERMISSION_DENIED);
    }
    SessionContext.setContext("42", List.of("tenantAdmin"), Map.of());
    assertDenied(invokeAccount(wire(request(NAMESPACE))), Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void malformedAndUnknownRequestsFailBeforeOwnerStorage() {
    var valid = wire(request(NAMESPACE));
    var unknown =
        valid.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    assertDenied(
        invokeAccount(ReadOriginalStartSessionLaunchRequest.getDefaultInstance()),
        Status.Code.INVALID_ARGUMENT);
    assertDenied(invokeAccount(unknown), Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void configuredNamespaceAndAmbientSynchronizationAreRejectedBeforeOwnerStorage() {
    assertDenied(invokeAccount(wire(request("other-runtime"))), Status.Code.PERMISSION_DENIED);

    TransactionSynchronizationManager.initSynchronization();
    assertDenied(invokeAccount(wire(request(NAMESPACE))), Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(repository);
  }

  @Test
  void exactTupleIsForwardedAndMissingOwnerAssociationDoesNotBecomeEvidence() {
    var request = request(NAMESPACE);
    when(repository.readByOriginalTuple(
            eq(request.selector()), aryEq(request.originalPostAuthorizationTupleBytes())))
        .thenReturn(Optional.empty());

    assertDenied(invokeAccount(wire(request)), Status.Code.FAILED_PRECONDITION);

    verify(repository)
        .readByOriginalTuple(
            eq(request.selector()), aryEq(request.originalPostAuthorizationTupleBytes()));
  }

  @Test
  void storageFailureCannotManufactureAnOwnerReadback() {
    var request = request(NAMESPACE);
    when(repository.readByOriginalTuple(
            eq(request.selector()), aryEq(request.originalPostAuthorizationTupleBytes())))
        .thenThrow(new DataAccessException("test-only owner storage failure"));

    assertDenied(invokeAccount(wire(request)), Status.Code.UNAVAILABLE);
    verify(repository)
        .readByOriginalTuple(
            eq(request.selector()), aryEq(request.originalPostAuthorizationTupleBytes()));
  }

  private RecordingObserver invokeAccount(ReadOriginalStartSessionLaunchRequest wire) {
    return invoke("spiffe://firemud/ns/world-runtime/sa/account-service", wire);
  }

  private RecordingObserver invoke(String uri, ReadOriginalStartSessionLaunchRequest wire) {
    var observer = new RecordingObserver();
    var peer = GrpcPeerIdentity.parseUri(uri).orElseThrow();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(() -> service.readOriginalStartSessionLaunch(wire, observer));
    return observer;
  }

  private static OriginalStartSessionLaunchReadEvidence.Request request(String namespace) {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(namespace);
    var selector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            READ_ID,
            namespace,
            TENANT,
            "world",
            GAME_INSTANCE,
            tuple.controlPlaneRequestId(),
            "launch-descriptor",
            "sha256:" + "a".repeat(64),
            "sha256:" + "b".repeat(64),
            "sha256:" + "c".repeat(64));
    return new OriginalStartSessionLaunchReadEvidence.Request(selector, tuple.canonicalBytes());
  }

  private static ReadOriginalStartSessionLaunchRequest wire(
      OriginalStartSessionLaunchReadEvidence.Request request) {
    return OriginalStartSessionLaunchReadGrpcCodec.toRequest(request);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String namespace) {
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "original-launch-read-service-test",
            ACTOR,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(TENANT, namespace),
                new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "exact Account original launch read"));
    String tenant = TENANT.toString();
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion", StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
                Map.of(
                    "scope", Map.of("tenantId", tenant, "targetNamespace", namespace),
                    "actionFamily", pre.actionFamily(),
                    "applicableAccountId", ACTOR.toString(),
                    "applicableTenantId", tenant),
            "accountProjectionEvidence",
                Map.of(
                    "sourceType", "ACCOUNT",
                    "sourceEvidenceId", "sha256:" + "d".repeat(64),
                    "sourceEvidenceVersion", "17",
                    "projectionStatus", "CURRENT",
                    "evaluatedAt", "2026-10-09T00:00:00Z",
                    "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
                Map.of(
                    "issuanceOperationId", ISSUANCE_ID.toString(),
                    "controlPlaneRequestId", pre.controlPlaneRequestId(),
                    "actionFamilyRequestIdentity",
                        Map.of(
                            "requestIdentityKind",
                            "controlPlaneRequestId",
                            "requestId",
                            pre.controlPlaneRequestId()),
                    "mutationDigest", pre.mutationDigest()),
            "issuanceKind", "human_operator",
            "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", 1L,
                    "accountAuthorityGeneration", 2L,
                    "tenantAuthorityGeneration", Map.of(tenant, 3L),
                    "membershipAuthorityGeneration", Map.of(tenant, 4L),
                    "privateRealmGrantVersions", List.of()),
            "membershipVersion", Map.of(tenant, 5L),
            "issuanceFence", "23",
            "issuanceEvidence",
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
                    "3"));
    byte[] authorityBytes;
    try {
      authorityBytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBytes,
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static void assertDenied(RecordingObserver observer, Status.Code expected) {
    assertThat(observer.response).isNull();
    assertThat(observer.completed).isFalse();
    assertThat(observer.error).isEqualTo(expected);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class RecordingObserver
      implements StreamObserver<ReadOriginalStartSessionLaunchResponse> {
    private ReadOriginalStartSessionLaunchResponse response;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadOriginalStartSessionLaunchResponse value) {
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
  }
}
