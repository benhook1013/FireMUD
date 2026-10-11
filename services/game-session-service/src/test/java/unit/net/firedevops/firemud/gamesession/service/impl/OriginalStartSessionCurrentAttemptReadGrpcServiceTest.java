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
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence.Request;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidenceGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.StaleStartSessionOperatorAttemptClaimException;
import net.firedevops.firemud.gamesession.service.impl.OriginalStartSessionCurrentAttemptReadGrpcService;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalStartSessionCurrentAttemptResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class OriginalStartSessionCurrentAttemptReadGrpcServiceTest {
  private static final String NAMESPACE = "world-runtime";
  private static final UUID READ_ID = UUID.fromString("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT = UUID.fromString("02222222-2222-4222-8222-222222222222");
  private static final UUID MUTATION = UUID.fromString("03333333-3333-4333-8333-333333333333");
  private static final long FENCE = 8L;

  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void returnsExactPendingSnapshotWithoutMutationRenewalOrRetry() {
    Request request = request(NAMESPACE);
    byte[] projection = canonicalProjection(request);
    Instant leaseExpiry = Instant.parse("2030-01-01T00:00:00.123456789Z");
    AttemptSnapshot snapshot = snapshot(request, leaseExpiry, projection);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    when(repository.readCurrentOriginalAttempt(request)).thenReturn(snapshot);
    var service = newService(repository, NAMESPACE);

    RecordingObserver<ReadOriginalStartSessionCurrentAttemptResponse> observer =
        invokeAs(
            service,
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request),
            "account-service");

    assertThat(observer.error()).isNull();
    assertThat(observer.completed()).isTrue();
    var decoded =
        OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.fromResponse(
            request, observer.response());
    assertThat(decoded.phaseState()).isEqualTo("OWNER_EXECUTION_PENDING");
    assertThat(decoded.originalLeaseExpiresAt()).isEqualTo(leaseExpiry);
    assertThat(decoded.accountRedemptionProjection()).containsExactly(projection);
    assertThat(observer.response().getRequest())
        .isEqualTo(OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request));
    verify(repository).readCurrentOriginalAttempt(request);
    verifyNoMoreInteractions(repository);
  }

  @Test
  void rejectsUnknownAndMalformedRequestsBeforeStorageAndAuthenticatesPeerBeforeDecode() {
    Request request = request(NAMESPACE);
    var wire = OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request);
    var unknown =
        wire.toBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
                    .build())
            .build();
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    var service = newService(repository, NAMESPACE);

    assertThat(invokeAs(service, unknown, "account-service").status())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    // The same malformed bytes from a disallowed peer must fail at authentication first.
    assertThat(invokeAs(service, unknown, "world-management-service").status())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(
            invokeAs(service, wire.toBuilder().setExpectedOwnerFence(0L).build(), "account-service")
                .status())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsWrongTargetNamespaceAndEndUserContextBeforeStorage() {
    Request otherNamespace = request("other-runtime");
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    var service = newService(repository, NAMESPACE);
    var wrongNamespace =
        invokeAs(
            service,
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(otherNamespace),
            "account-service");
    assertThat(wrongNamespace.status()).isEqualTo(Status.Code.PERMISSION_DENIED);

    var endUser =
        invokeAsWithEndUser(
            service,
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request(NAMESPACE)));
    assertThat(endUser.status()).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsChangedAttemptMutationFenceTupleOrPhaseReturnedByRepository() {
    Request request = request(NAMESPACE);
    byte[] exactProjection = canonicalProjection(request);
    AttemptSnapshot changedAttempt =
        snapshot(request, Instant.parse("2030-01-01T00:00:00Z"), exactProjection);
    when(changedAttempt.ownerAttemptId()).thenReturn(uuid("04444444-4444-4444-8444-444444444444"));
    AttemptSnapshot changedMutation =
        snapshot(request, Instant.parse("2030-01-01T00:00:00Z"), exactProjection);
    when(changedMutation.ownerMutationId())
        .thenReturn(uuid("05555555-5555-4555-8555-555555555555"));
    AttemptSnapshot changedFence =
        snapshot(request, Instant.parse("2030-01-01T00:00:00Z"), exactProjection);
    when(changedFence.ownerFence()).thenReturn(FENCE + 1);
    AttemptSnapshot changedTuple =
        snapshot(request, Instant.parse("2030-01-01T00:00:00Z"), exactProjection);
    when(changedTuple.postAuthorizationExecutionTuple()).thenReturn(new byte[] {4});
    AttemptSnapshot changedPhase =
        snapshot(request, Instant.parse("2030-01-01T00:00:00Z"), exactProjection);
    when(changedPhase.phaseState()).thenReturn("COMMITTED");
    AttemptSnapshot changedProjection =
        snapshot(request, Instant.parse("2030-01-01T00:00:00Z"), new byte[] {2});
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    when(repository.readCurrentOriginalAttempt(ArgumentMatchers.any(Request.class)))
        .thenReturn(
            changedAttempt,
            changedMutation,
            changedFence,
            changedTuple,
            changedPhase,
            changedProjection);
    var service = newService(repository, NAMESPACE);
    AttemptSnapshot[] mismatches = {
      changedAttempt, changedMutation, changedFence, changedTuple, changedPhase, changedProjection
    };
    for (int index = 0; index < mismatches.length; index++) {
      Request freshRequest =
          request(
              NAMESPACE, uuid(String.format("%08d-4444-4444-8444-%012d", index + 1, index + 1)));
      assertThat(
              invokeAs(
                      service,
                      OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(freshRequest),
                      "account-service")
                  .status())
          .isEqualTo(Status.Code.INTERNAL);
    }
    verify(repository, org.mockito.Mockito.times(6))
        .readCurrentOriginalAttempt(ArgumentMatchers.any(Request.class));
    verifyNoMoreInteractions(repository);
  }

  @Test
  void expiredOriginalLeaseRemainsReadOnlyAndReturnsFailedPrecondition() {
    Request request = request(NAMESPACE);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    when(repository.readCurrentOriginalAttempt(request))
        .thenThrow(
            new StaleStartSessionOperatorAttemptClaimException(
                "Game Session owner attempt claim lease is expired"));
    var service = newService(repository, NAMESPACE);

    var observer =
        invokeAs(
            service,
            OriginalStartSessionCurrentAttemptEvidenceGrpcCodec.toRequest(request),
            "account-service");

    assertThat(observer.status()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.response()).isNull();
    verify(repository).readCurrentOriginalAttempt(request);
    verifyNoMoreInteractions(repository);
  }

  private static OriginalStartSessionCurrentAttemptReadGrpcService newService(
      GameSessionStartSessionOperatorAttemptRepository repository, String namespace) {
    return new OriginalStartSessionCurrentAttemptReadGrpcService(
        repository, immediateTransactionManager(), namespace);
  }

  private static PlatformTransactionManager immediateTransactionManager() {
    return new PlatformTransactionManager() {
      @Override
      public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
      }

      @Override
      public void commit(TransactionStatus status) {}

      @Override
      public void rollback(TransactionStatus status) {}
    };
  }

  private static GameSessionStartSessionOperatorAttemptRepository mockRepository() {
    return mock(GameSessionStartSessionOperatorAttemptRepository.class);
  }

  private static AttemptSnapshot snapshot(Request request, Instant expiry, byte[] projection) {
    AttemptSnapshot snapshot = mock(AttemptSnapshot.class);
    when(snapshot.targetNamespace()).thenReturn(request.targetNamespace());
    when(snapshot.controlPlaneRequestId())
        .thenReturn(request.originalTuple().controlPlaneRequestId());
    when(snapshot.canonicalTenantId())
        .thenReturn(request.originalTuple().preAuthorizationTuple().action().scope().tenantId());
    when(snapshot.ownerAttemptId()).thenReturn(request.expectedOwnerAttemptId());
    when(snapshot.ownerMutationId()).thenReturn(request.expectedOwnerMutationId());
    when(snapshot.ownerFence()).thenReturn(request.expectedOwnerFence());
    when(snapshot.phaseState())
        .thenReturn(OriginalStartSessionCurrentAttemptEvidence.PENDING_PHASE);
    when(snapshot.postAuthorizationExecutionTuple())
        .thenReturn(request.canonicalPostAuthorizationTuple());
    when(snapshot.leaseExpiresAt()).thenReturn(expiry);
    when(snapshot.accountRedemptionProjection()).thenReturn(projection.clone());
    return snapshot;
  }

  private static byte[] canonicalProjection(Request request) {
    var tuple = request.originalTuple();
    var authority =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    return new AccountRedemptionProjection(
            tuple.authorizationReferenceFingerprint(),
            tuple.authorityEvidenceBundleBytes(),
            authority.issuanceOperationId(),
            Long.parseLong(tuple.issuanceFence()))
        .canonicalBytes();
  }

  private static Request request(String namespace) {
    return request(namespace, READ_ID);
  }

  private static Request request(String namespace, UUID readRequestId) {
    return new Request(
        readRequestId, namespace, tuple(namespace).canonicalBytes(), ATTEMPT, MUTATION, FENCE);
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String namespace) {
    UUID tenant = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
    UUID actor = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
    UUID targetOwner = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
    UUID reservationOwner = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
    UUID issuanceId = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
    UUID tokenJti = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
    String fingerprint = "arfp/v1/test-key/" + "b".repeat(64);
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "current-attempt-service-test",
            actor,
            new StartSessionOperatorAction(
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
                StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
                new StartSessionOperatorAction.Scope(tenant, namespace),
                new StartSessionOperatorAction.Target(91L, targetOwner),
                StartSessionOperatorAction.ExpectedVersion.ABSENT,
                new StartSessionOperatorAction.Mutation(
                    StartSessionOperatorAction.ClientIp.absent()),
                "canonical StartSession owner attempt"));
    Map<String, Object> evidence =
        Map.of(
            "evidenceType",
            StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            actor.toString(),
            "controlUiTokenJti",
            tokenJti.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    String tenantId = tenant.toString();
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", namespace),
                "actionFamily", pre.actionFamily(),
                "applicableAccountId", actor.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", issuanceId.toString(),
                "controlPlaneRequestId", pre.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        pre.controlPlaneRequestId()),
                "mutationDigest", pre.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenantId, 3L),
                "membershipAuthorityGeneration", Map.of(tenantId, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    byte[] authorityBytes;
    try {
      authorityBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JsonMapper.builder().build().writeValueAsString(bundle));
    } catch (IOException invalid) {
      throw new IllegalStateException(invalid);
    }
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        fingerprint,
        reservationOwner,
        19L,
        authorityBytes,
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static RecordingObserver<ReadOriginalStartSessionCurrentAttemptResponse> invokeAs(
      OriginalStartSessionCurrentAttemptReadGrpcService service,
      ReadOriginalStartSessionCurrentAttemptRequest request,
      String serviceName) {
    return invokeWithPeer(service, request, serviceName, NAMESPACE, false);
  }

  private static RecordingObserver<ReadOriginalStartSessionCurrentAttemptResponse>
      invokeAsWithEndUser(
          OriginalStartSessionCurrentAttemptReadGrpcService service,
          ReadOriginalStartSessionCurrentAttemptRequest request) {
    return invokeWithPeer(service, request, "account-service", NAMESPACE, true);
  }

  private static RecordingObserver<ReadOriginalStartSessionCurrentAttemptResponse> invokeWithPeer(
      OriginalStartSessionCurrentAttemptReadGrpcService service,
      ReadOriginalStartSessionCurrentAttemptRequest request,
      String serviceName,
      String namespace,
      boolean endUser) {
    var peer =
        new net.firedevops.firemud.common.grpc.GrpcPeerIdentity(
            "spiffe://firemud/ns/" + namespace + "/sa/" + serviceName, namespace, serviceName);
    Context context =
        Context.current()
            .withValue(net.firedevops.firemud.common.grpc.GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      if (endUser) {
        SessionContext.setContext("42", List.of("tenantAdmin"), Map.of());
      }
      RecordingObserver<ReadOriginalStartSessionCurrentAttemptResponse> observer =
          new RecordingObserver<>();
      service.readOriginalStartSessionCurrentAttempt(request, observer);
      return observer;
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class RecordingObserver<T> implements StreamObserver<T> {
    private final AtomicReference<T> response = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private volatile boolean completed;

    @Override
    public void onNext(T value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable failure) {
      error.set(failure);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    T response() {
      return response.get();
    }

    Throwable error() {
      return error.get();
    }

    boolean completed() {
      return completed;
    }

    Status.Code status() {
      Throwable failure = error.get();
      return failure instanceof StatusRuntimeException statusFailure
          ? statusFailure.getStatus().getCode()
          : null;
    }
  }
}
