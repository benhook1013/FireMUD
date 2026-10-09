package unit.net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.StartSessionLaunchDescriptorReadClient;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Association;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.InitialConfigured;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadGrpcCodec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamedesign.v1.ReadStartSessionTemplateAssociationResponse;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionLaunchDescriptorRepository.PinnedLaunchDescriptorSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.EvidenceContinuation;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionTemplateAssociationRepository.PinnedAssociationSnapshot;
import net.firedevops.firemud.gamesession.service.GameSessionStartSessionLaunchDescriptorContinuationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit-level continuation orchestration proof. The canonical tuple is an integrity-only typed
 * fixture; mocked owner and remote collaborators do not prove current Account redemption or the
 * PostgreSQL owner-claim predicates.
 */
class GameSessionStartSessionLaunchDescriptorContinuationServiceTest {
  private static final String NAMESPACE = "runtime-a";
  private static final UUID TENANT = uuid("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR = uuid("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID TARGET_OWNER = uuid("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID RESERVATION_OWNER = uuid("7c005b65-fcb1-4ac9-a714-f3d0f449edcf");
  private static final UUID ISSUANCE_ID = uuid("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = uuid("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID READ_ID = uuid("01111111-1111-4111-8111-111111111111");
  private static final UUID ATTEMPT_ID = uuid("02222222-2222-4222-8222-222222222222");
  private static final UUID VERSION_ID = uuid("03333333-3333-4333-8333-333333333333");
  private static final UUID COMMIT_ID = uuid("04444444-4444-4444-8444-444444444444");
  private static final String PUBLISH_WORKFLOW_ID = "publish-workflow-1";
  private static final String ASSOCIATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String REQUEST_DIGEST = "sha256:" + "c".repeat(64);
  private static final String RESPONSE_DIGEST = "sha256:" + "d".repeat(64);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionSynchronizationState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  void rejectsNamespaceMismatchBeforeOwnerOrRemoteWork() {
    Fixture fixture = new Fixture(NAMESPACE);

    assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(tuple("runtime-b")))
        .isInstanceOf(
            GameSessionStartSessionLaunchDescriptorContinuationService
                .EvidenceContinuationUnavailableException.class)
        .hasMessageContaining("outside the configured Game Session namespace");

    assertThat(fixture.transactionManager.startedTransactions).isZero();
    verifyNoInteractions(
        fixture.attemptRepository,
        fixture.associationRepository,
        fixture.descriptorRepository,
        fixture.accountProjectionClient,
        fixture.descriptorReadClient);
  }

  @Test
  void rejectsAmbientOwnerTransactionBeforeOwnerOrRemoteWork() {
    Fixture fixture = new Fixture(NAMESPACE);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(fixture.tuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must occur outside owner SQL transactions");

    assertThat(fixture.transactionManager.startedTransactions).isZero();
    verifyNoInteractions(
        fixture.attemptRepository,
        fixture.associationRepository,
        fixture.descriptorRepository,
        fixture.accountProjectionClient,
        fixture.descriptorReadClient);
  }

  @Test
  void missingOriginalSelectionStopsBeforeDescriptorReadOrPin() {
    Fixture fixture = new Fixture(NAMESPACE);
    when(fixture.associationRepository.findPinned(fixture.continuation))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(fixture.tuple))
        .isInstanceOf(
            GameSessionStartSessionLaunchDescriptorContinuationService
                .EvidenceContinuationUnavailableException.class)
        .hasMessageContaining("Original selection is missing");

    assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
    verify(fixture.attemptRepository).beginEvidenceContinuation(fixture.tuple);
    verify(fixture.associationRepository).findPinned(fixture.continuation);
    verifyNoMoreInteractions(fixture.attemptRepository, fixture.associationRepository);
    verifyNoInteractions(
        fixture.descriptorRepository,
        fixture.accountProjectionClient,
        fixture.descriptorReadClient);
  }

  @Test
  void accountCurrentnessFailureBeforeDescriptorReadPreventsRemoteReadAndPin() {
    Fixture fixture = new Fixture(NAMESPACE);
    Result originalResult = fixture.originalResult();
    PinnedAssociationSnapshot original = fixture.snapshot(originalResult);
    when(fixture.associationRepository.findPinned(fixture.continuation))
        .thenReturn(Optional.of(original));
    when(fixture.accountProjectionClient.read(fixture.tuple, ATTEMPT_ID, 8L))
        .thenThrow(new IllegalStateException("Account currentness is unavailable"));

    assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(fixture.tuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account currentness is unavailable");

    assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
    verify(fixture.accountProjectionClient).read(fixture.tuple, ATTEMPT_ID, 8L);
    verifyNoMoreInteractions(fixture.accountProjectionClient);
    verifyNoInteractions(fixture.descriptorReadClient, fixture.descriptorRepository);
  }

  @Test
  void accountCurrentnessFailureAfterDescriptorReadPreventsOwnerPin() {
    Fixture fixture = new Fixture(NAMESPACE);
    Result originalResult = fixture.originalResult();
    PinnedAssociationSnapshot original = fixture.snapshot(originalResult);
    when(fixture.associationRepository.findPinned(fixture.continuation))
        .thenReturn(Optional.of(original));
    when(fixture.accountProjectionClient.read(fixture.tuple, ATTEMPT_ID, 8L))
        .thenReturn(redeemedProjection("original"))
        .thenThrow(new IllegalStateException("Account currentness changed after descriptor read"));
    when(fixture.descriptorReadClient.resolve(any(Request.class)))
        .thenReturn(mock(StartSessionLaunchDescriptorGrpcCodec.Resolved.class));

    assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(fixture.tuple))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Account currentness changed after descriptor read");

    assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
    verify(fixture.accountProjectionClient, times(2)).read(fixture.tuple, ATTEMPT_ID, 8L);
    verify(fixture.descriptorReadClient).resolve(any(Request.class));
    verifyNoInteractions(fixture.descriptorRepository);
  }

  @Test
  void changedAccountProjectionAfterDescriptorReadPreventsOwnerPin() {
    Fixture fixture = new Fixture(NAMESPACE);
    Result originalResult = fixture.originalResult();
    PinnedAssociationSnapshot original = fixture.snapshot(originalResult);
    when(fixture.associationRepository.findPinned(fixture.continuation))
        .thenReturn(Optional.of(original));
    when(fixture.accountProjectionClient.read(fixture.tuple, ATTEMPT_ID, 8L))
        .thenReturn(redeemedProjection("original"), redeemedProjection("changed"));
    when(fixture.descriptorReadClient.resolve(any(Request.class)))
        .thenReturn(mock(StartSessionLaunchDescriptorGrpcCodec.Resolved.class));

    assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(fixture.tuple))
        .isInstanceOf(
            GameSessionStartSessionLaunchDescriptorContinuationService
                .EvidenceContinuationUnavailableException.class)
        .hasMessageContaining("Account's original redeemed operation projection changed");

    assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
    verify(fixture.accountProjectionClient, times(2)).read(fixture.tuple, ATTEMPT_ID, 8L);
    verify(fixture.descriptorReadClient).resolve(any(Request.class));
    verifyNoInteractions(fixture.descriptorRepository);
  }

  @Test
  void changedOriginalSelectionAfterRemoteReadPreventsDescriptorPin() {
    Fixture fixture = new Fixture(NAMESPACE);
    Result originalResult = fixture.originalResult();
    Result changedResult = fixture.originalResult();
    PinnedAssociationSnapshot original = fixture.snapshot(originalResult);
    PinnedAssociationSnapshot changed = fixture.snapshot(changedResult);
    when(fixture.associationRepository.findPinned(fixture.continuation))
        .thenReturn(Optional.of(original), Optional.of(changed));
    when(fixture.accountProjectionClient.read(fixture.tuple, ATTEMPT_ID, 8L))
        .thenReturn(redeemedProjection("original"), redeemedProjection("original"));
    StartSessionLaunchDescriptorGrpcCodec.Resolved resolved =
        mock(StartSessionLaunchDescriptorGrpcCodec.Resolved.class);
    when(fixture.descriptorReadClient.resolve(any(Request.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
              return resolved;
            });

    ReadStartSessionTemplateAssociationResponse originalWire = response(1L);
    ReadStartSessionTemplateAssociationResponse changedWire = response(2L);
    try (MockedStatic<StartSessionTemplateAssociationReadGrpcCodec> codec =
        mockStatic(StartSessionTemplateAssociationReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionTemplateAssociationReadGrpcCodec.toResponse(originalResult))
          .thenReturn(originalWire);
      codec
          .when(() -> StartSessionTemplateAssociationReadGrpcCodec.toResponse(changedResult))
          .thenReturn(changedWire);

      assertThatThrownBy(() -> fixture.service.continueOriginalEvidence(fixture.tuple))
          .isInstanceOf(
              GameSessionStartSessionLaunchDescriptorRepository
                  .StartSessionLaunchDescriptorConflictException.class)
          .hasMessageContaining("Original immutable selection changed");
    }

    assertThat(fixture.transactionManager.startedTransactions).isEqualTo(2);
    verify(fixture.descriptorReadClient).resolve(any(Request.class));
    verify(fixture.accountProjectionClient, times(2)).read(fixture.tuple, ATTEMPT_ID, 8L);
    verifyNoMoreInteractions(fixture.accountProjectionClient);
    verify(fixture.attemptRepository).beginEvidenceContinuation(fixture.tuple);
    verifyNoMoreInteractions(fixture.attemptRepository);
    verify(fixture.associationRepository, times(2)).findPinned(fixture.continuation);
    verifyNoMoreInteractions(fixture.associationRepository);
    verify(fixture.descriptorRepository, never()).pin(fixture.continuation, resolved);
  }

  @Test
  void exactUnchangedReplayReadsOutsideSqlAndPinsOnlyTheExistingOpaqueContinuation() {
    Fixture fixture = new Fixture(NAMESPACE);
    Result originalResult = fixture.originalResult();
    PinnedAssociationSnapshot original = fixture.snapshot(originalResult);
    when(fixture.associationRepository.findPinned(fixture.continuation))
        .thenReturn(Optional.of(original), Optional.of(original));
    ReadRedeemedOperationProjectionResponse projection = redeemedProjection("original");
    when(fixture.accountProjectionClient.read(fixture.tuple, ATTEMPT_ID, 8L))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
              return projection;
            });
    StartSessionLaunchDescriptorGrpcCodec.Resolved resolved =
        mock(StartSessionLaunchDescriptorGrpcCodec.Resolved.class);
    PinnedLaunchDescriptorSnapshot pinned =
        new PinnedLaunchDescriptorSnapshot(
            resolved,
            new byte[] {1},
            new byte[] {2},
            REQUEST_DIGEST,
            RESPONSE_DIGEST,
            Instant.parse("2026-10-10T00:00:00Z"));
    when(fixture.descriptorReadClient.resolve(any(Request.class)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              assertThat(fixture.transactionManager.startedTransactions).isEqualTo(1);
              return resolved;
            });
    when(fixture.descriptorRepository.pin(fixture.continuation, resolved)).thenReturn(pinned);

    ReadStartSessionTemplateAssociationResponse originalWire = response(1L);
    try (MockedStatic<StartSessionTemplateAssociationReadGrpcCodec> codec =
        mockStatic(StartSessionTemplateAssociationReadGrpcCodec.class)) {
      codec
          .when(() -> StartSessionTemplateAssociationReadGrpcCodec.toResponse(originalResult))
          .thenReturn(originalWire);

      assertThat(fixture.service.continueOriginalEvidence(fixture.tuple)).isSameAs(pinned);
    }

    assertThat(fixture.transactionManager.startedTransactions).isEqualTo(2);
    InOrder remoteOrder = inOrder(fixture.accountProjectionClient, fixture.descriptorReadClient);
    remoteOrder.verify(fixture.accountProjectionClient).read(fixture.tuple, ATTEMPT_ID, 8L);
    remoteOrder.verify(fixture.descriptorReadClient).resolve(any(Request.class));
    remoteOrder.verify(fixture.accountProjectionClient).read(fixture.tuple, ATTEMPT_ID, 8L);
    verify(fixture.accountProjectionClient, times(2)).read(fixture.tuple, ATTEMPT_ID, 8L);
    verifyNoMoreInteractions(fixture.accountProjectionClient);
    ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
    verify(fixture.descriptorReadClient).resolve(requestCaptor.capture());
    Request replayRequest = requestCaptor.getValue();
    assertThat(replayRequest.targetNamespace()).isEqualTo(NAMESPACE);
    assertThat(replayRequest.canonicalPostAuthorizationTuple())
        .isEqualTo(fixture.tuple.canonicalBytes());
    assertThat(replayRequest.ownerAttemptId()).isEqualTo(ATTEMPT_ID);
    assertThat(replayRequest.ownerFence()).isEqualTo(8L);
    assertThat(replayRequest.selection())
        .isEqualTo(new ExactReplay(VERSION_ID, COMMIT_ID, PUBLISH_WORKFLOW_ID, ASSOCIATION_DIGEST));

    verify(fixture.attemptRepository).beginEvidenceContinuation(fixture.tuple);
    verifyNoMoreInteractions(fixture.attemptRepository);
    verify(fixture.associationRepository, times(2)).findPinned(fixture.continuation);
    verifyNoMoreInteractions(fixture.associationRepository);
    verify(fixture.descriptorRepository).pin(fixture.continuation, resolved);
    verifyNoMoreInteractions(fixture.descriptorRepository);
  }

  private static ReadStartSessionTemplateAssociationResponse response(long phaseEpoch) {
    return ReadStartSessionTemplateAssociationResponse.newBuilder()
        .setReferencePhaseEpoch(phaseEpoch)
        .build();
  }

  private static ReadRedeemedOperationProjectionResponse redeemedProjection(String identity) {
    // Synthetic marker only: this unit test proves equality/order, not Account currentness.
    return ReadRedeemedOperationProjectionResponse.newBuilder()
        .setControlPlaneRequestId(identity)
        .build();
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String namespace) {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, namespace),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "unit-only continuation identity fixture");
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            "descriptor-continuation-request", ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        "spiffe://firemud/ns/" + namespace + "/sa/logging-admin-service",
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre, namespace),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      StartSessionPreAuthorizationReservationTuple tuple, String namespace) {
    String tenant = TENANT.toString();
    Map<String, Object> value =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenant, "targetNamespace", namespace),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR.toString(),
                "applicableTenantId", tenant),
            "accountProjectionEvidence",
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                "sha256:" + "e".repeat(64),
                "sourceEvidenceVersion",
                "17",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                "2030-01-01T00:00:00Z",
                "expiresAt",
                "2030-01-01T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                ISSUANCE_ID.toString(),
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
                "issuerAuthGeneration", 1L,
                "accountAuthorityGeneration", 2L,
                "tenantAuthorityGeneration", Map.of(tenant, 3L),
                "membershipAuthorityGeneration", Map.of(tenant, 4L),
                "privateRealmGrantVersions", List.of()),
            "membershipVersion",
            Map.of(tenant, 5L),
            "issuanceFence",
            "23",
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
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException invalid) {
      throw new IllegalStateException("Could not encode the typed tuple fixture", invalid);
    }
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Fixture {
    private final TrackingTransactionManager transactionManager = new TrackingTransactionManager();
    private final GameSessionStartSessionOperatorAttemptRepository attemptRepository =
        mock(GameSessionStartSessionOperatorAttemptRepository.class);
    private final GameSessionStartSessionTemplateAssociationRepository associationRepository =
        mock(GameSessionStartSessionTemplateAssociationRepository.class);
    private final GameSessionStartSessionLaunchDescriptorRepository descriptorRepository =
        mock(GameSessionStartSessionLaunchDescriptorRepository.class);
    private final StartSessionRedeemedOperationProjectionClient accountProjectionClient =
        mock(StartSessionRedeemedOperationProjectionClient.class);
    private final StartSessionLaunchDescriptorReadClient descriptorReadClient =
        mock(StartSessionLaunchDescriptorReadClient.class);
    private final StartSessionPostAuthorizationExecutionTuple tuple;
    private final EvidenceContinuation continuation = mock(EvidenceContinuation.class);
    private final GameSessionStartSessionLaunchDescriptorContinuationService service;

    private Fixture(String workloadNamespace) {
      tuple = tuple(workloadNamespace);
      when(attemptRepository.beginEvidenceContinuation(tuple)).thenReturn(continuation);
      service =
          new GameSessionStartSessionLaunchDescriptorContinuationService(
              attemptRepository,
              associationRepository,
              descriptorRepository,
              accountProjectionClient,
              descriptorReadClient,
              transactionManager,
              workloadNamespace);
    }

    private Result originalResult() {
      Request originalRequest =
          new Request(
              StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION,
              NAMESPACE,
              READ_ID,
              tuple.canonicalBytes(),
              ATTEMPT_ID,
              8L,
              new InitialConfigured());
      Result result = mock(Result.class);
      Association association = mock(Association.class);
      when(result.request()).thenReturn(originalRequest);
      when(result.association()).thenReturn(association);
      when(association.canonicalVersionId()).thenReturn(VERSION_ID);
      when(association.selectedCommitId()).thenReturn(COMMIT_ID);
      when(association.publishWorkflowId()).thenReturn(PUBLISH_WORKFLOW_ID);
      when(association.associationDigest()).thenReturn(ASSOCIATION_DIGEST);
      return result;
    }

    private PinnedAssociationSnapshot snapshot(Result result) {
      return new PinnedAssociationSnapshot(
          result, REQUEST_DIGEST, RESPONSE_DIGEST, Instant.parse("2026-10-10T00:00:00Z"));
    }
  }

  /** A resource-free manager that exposes TransactionTemplate's active callback boundary. */
  private static final class TrackingTransactionManager implements PlatformTransactionManager {
    private int startedTransactions;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()) {
        throw new IllegalStateException("Nested test transactions are not supported");
      }
      if (definition.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRES_NEW
          || definition.getIsolationLevel() != TransactionDefinition.ISOLATION_READ_COMMITTED
          || definition.isReadOnly()) {
        throw new IllegalStateException("Continuation transaction settings changed");
      }
      startedTransactions++;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      clearActiveTransaction();
    }

    @Override
    public void rollback(TransactionStatus status) {
      clearActiveTransaction();
    }

    private static void clearActiveTransaction() {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.clearSynchronization();
      }
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
