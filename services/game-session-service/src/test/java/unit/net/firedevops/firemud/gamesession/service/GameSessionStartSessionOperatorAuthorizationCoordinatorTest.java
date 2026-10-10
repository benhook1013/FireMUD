package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.gamesession.client.StartSessionOperatorRedemptionClient;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AccountRedemptionProjection;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptClaim;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.AttemptSnapshot;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationDisposition;
import net.firedevops.firemud.gamesession.repository.GameSessionStartSessionOperatorAttemptRepository.ReservationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class GameSessionStartSessionOperatorAuthorizationCoordinatorTest {
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
  private static final String REQUEST_ID = "authorization-coordinator-request";
  private static final String WORKLOAD =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String OPAQUE_REFERENCE = "A".repeat(43);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void commitsOwnerClaimBeforeOneAccountCallAndAttachesOnlyUnderSameCurrentClaim()
      throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    AttemptClaim claim = claim(REQUEST_ID);
    AccountRedemptionProjection projection = projection(tuple);
    AttemptSnapshot reserved = snapshot(tuple, claim, null);
    AttemptSnapshot attached = snapshot(tuple, claim, projection.canonicalBytes());
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    GameSessionStartSessionOperatorAuthorizationCoordinator coordinator =
        coordinator(repository, redemptionClient, transactions);
    List<String> order = new ArrayList<>();

    when(repository.reserve(tuple))
        .thenAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              order.add("reserve");
              return new ReservationResult(
                  ReservationDisposition.CLAIM_CREATED, reserved, Optional.of(claim));
            });
    when(redemptionClient.redeem(tuple, OPAQUE_REFERENCE, claim))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(transactions.commitCount).isEqualTo(1);
              order.add("account");
              return new StartSessionOperatorRedemptionClient.RedemptionResult(
                  claim, projection, false);
            });
    when(repository.validateCurrentClaim(claim))
        .thenAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              order.add("validate");
              return reserved;
            });
    when(repository.attachAccountRedemptionProjection(claim, projection))
        .thenAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              order.add("attach");
              return attached;
            });

    GameSessionStartSessionOperatorAuthorizationCoordinator.AuthorizationResult result =
        withPeer(peer(WORKLOAD), () -> coordinator.authorize(tuple, OPAQUE_REFERENCE));

    assertThat(order).containsExactly("reserve", "account", "validate", "attach");
    assertThat(transactions.commitCount).isEqualTo(2);
    assertThat(transactions.rollbackCount).isZero();
    assertThat(transactions.definitions)
        .hasSize(2)
        .allSatisfy(
            definition -> {
              assertThat(definition.getPropagationBehavior())
                  .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              assertThat(definition.getIsolationLevel())
                  .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
              assertThat(definition.isReadOnly()).isFalse();
            });
    assertThat(result.progress())
        .isEqualTo(
            GameSessionStartSessionOperatorAuthorizationCoordinator.Progress
                .ACCOUNT_PROJECTION_ATTACHED);
    assertThat(result.claim()).contains(claim);
    assertThat(result.snapshot()).isSameAs(attached);
    assertThat(result.toString()).doesNotContain(OPAQUE_REFERENCE);
    verify(repository).attachAccountRedemptionProjection(claim, projection);
  }

  @Test
  void exactRetryReturnsDurablePendingSnapshotWithoutRedeemingAgain() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    AttemptSnapshot pending = snapshot(tuple, claim(REQUEST_ID), null);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    when(repository.reserve(tuple))
        .thenReturn(
            new ReservationResult(ReservationDisposition.EXACT_REPLAY, pending, Optional.empty()));

    var result =
        withPeer(
            peer(WORKLOAD),
            () ->
                coordinator(repository, redemptionClient, transactions)
                    .authorize(tuple, OPAQUE_REFERENCE));

    assertThat(result.progress())
        .isEqualTo(GameSessionStartSessionOperatorAuthorizationCoordinator.Progress.EXACT_REPLAY);
    assertThat(result.snapshot()).isSameAs(pending);
    assertThat(result.claim()).isEmpty();
    assertThat(transactions.commitCount).isEqualTo(1);
    verifyNoInteractions(redemptionClient);
    verify(repository, never()).validateCurrentClaim(any());
    verify(repository, never()).attachAccountRedemptionProjection(any(), any());
  }

  @Test
  void rejectsWrongAuthenticatedPeerOrNamespaceBeforeOwnerAccess() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    var coordinator = coordinator(repository, redemptionClient, transactions);

    assertThatThrownBy(
            () ->
                withPeer(
                    peer("spiffe://firemud/ns/world-runtime/sa/account-service"),
                    () -> coordinator.authorize(tuple, OPAQUE_REFERENCE)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("spiffe://firemud/ns/other-runtime/sa/logging-admin-service"),
                    () -> coordinator.authorize(tuple, OPAQUE_REFERENCE)))
        .isInstanceOf(SecurityException.class);

    assertThat(transactions.definitions).isEmpty();
    verifyNoInteractions(repository, redemptionClient);
  }

  @Test
  void tupleConflictRollsBackClaimReservationAndNeverCallsAccount() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    when(repository.reserve(tuple))
        .thenThrow(
            new GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException("different immutable tuple"));

    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD),
                    () ->
                        coordinator(repository, redemptionClient, transactions)
                            .authorize(tuple, OPAQUE_REFERENCE)))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StartSessionOperatorAttemptConflictException.class);

    assertThat(transactions.commitCount).isZero();
    assertThat(transactions.rollbackCount).isEqualTo(1);
    verifyNoInteractions(redemptionClient);
  }

  @Test
  void classifiesEveryGrpcStatusWithoutRetryingOrAttachingThePendingClaim() throws Exception {
    for (Status.Code code : Status.Code.values()) {
      StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID + "-" + code);
      AttemptClaim claim = claim(tuple.controlPlaneRequestId());
      AttemptSnapshot pending = snapshot(tuple, claim, null);
      GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
      StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
      RecordingTransactionManager transactions = new RecordingTransactionManager();
      StatusRuntimeException failure = new StatusRuntimeException(Status.fromCode(code));
      when(repository.reserve(tuple))
          .thenReturn(
              new ReservationResult(
                  ReservationDisposition.CLAIM_CREATED, pending, Optional.of(claim)));
      when(redemptionClient.redeem(tuple, OPAQUE_REFERENCE, claim)).thenThrow(failure);

      if (isAmbiguousTransportStatus(code)) {
        var result =
            withPeer(
                peer(WORKLOAD),
                () ->
                    coordinator(repository, redemptionClient, transactions)
                        .authorize(tuple, OPAQUE_REFERENCE));

        assertThat(result.progress())
            .as("status %s", code)
            .isEqualTo(
                GameSessionStartSessionOperatorAuthorizationCoordinator.Progress
                    .ACCOUNT_OUTCOME_AMBIGUOUS);
        assertThat(result.snapshot()).isSameAs(pending);
        assertThat(result.claim()).contains(claim);
        assertThat(result.toString()).doesNotContain(OPAQUE_REFERENCE);
      } else {
        assertThatThrownBy(
                () ->
                    withPeer(
                        peer(WORKLOAD),
                        () ->
                            coordinator(repository, redemptionClient, transactions)
                                .authorize(tuple, OPAQUE_REFERENCE)))
            .as("status %s", code)
            .isSameAs(failure);
      }

      assertThat(transactions.commitCount).as("status %s", code).isEqualTo(1);
      assertThat(transactions.rollbackCount).as("status %s", code).isZero();
      verify(redemptionClient).redeem(tuple, OPAQUE_REFERENCE, claim);
      verify(repository, never()).validateCurrentClaim(any());
      verify(repository, never()).attachAccountRedemptionProjection(any(), any());
    }
  }

  private static boolean isAmbiguousTransportStatus(Status.Code code) {
    return switch (code) {
      case DEADLINE_EXCEEDED, UNAVAILABLE, CANCELLED, UNKNOWN, INTERNAL -> true;
      default -> false;
    };
  }

  @Test
  void staleClaimDuringProjectionAttachmentCannotProduceAttachedProgress() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    AttemptClaim claim = claim(REQUEST_ID);
    AccountRedemptionProjection projection = projection(tuple);
    AttemptSnapshot pending = snapshot(tuple, claim, null);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    RecordingTransactionManager transactions = new RecordingTransactionManager();
    when(repository.reserve(tuple))
        .thenReturn(
            new ReservationResult(
                ReservationDisposition.CLAIM_CREATED, pending, Optional.of(claim)));
    when(redemptionClient.redeem(tuple, OPAQUE_REFERENCE, claim))
        .thenReturn(
            new StartSessionOperatorRedemptionClient.RedemptionResult(claim, projection, false));
    when(repository.validateCurrentClaim(claim)).thenReturn(pending);
    when(repository.attachAccountRedemptionProjection(claim, projection))
        .thenThrow(
            new GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException("claim expired before attachment"));

    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD),
                    () ->
                        coordinator(repository, redemptionClient, transactions)
                            .authorize(tuple, OPAQUE_REFERENCE)))
        .isInstanceOf(
            GameSessionStartSessionOperatorAttemptRepository
                .StaleStartSessionOperatorAttemptClaimException.class);

    assertThat(transactions.commitCount).isEqualTo(1);
    assertThat(transactions.rollbackCount).isEqualTo(1);
    verify(repository).validateCurrentClaim(claim);
    verify(repository).attachAccountRedemptionProjection(claim, projection);
  }

  @Test
  void refusesToRunInsideAmbientTransactionBeforeOwnerAccess() throws Exception {
    StartSessionPostAuthorizationExecutionTuple tuple = tuple(REQUEST_ID);
    GameSessionStartSessionOperatorAttemptRepository repository = mockRepository();
    StartSessionOperatorRedemptionClient redemptionClient = mockRedemptionClient();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                withPeer(
                    peer(WORKLOAD),
                    () ->
                        coordinator(repository, redemptionClient, new RecordingTransactionManager())
                            .authorize(tuple, OPAQUE_REFERENCE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient owner transaction");

    verifyNoInteractions(repository, redemptionClient);
  }

  private static GameSessionStartSessionOperatorAuthorizationCoordinator coordinator(
      GameSessionStartSessionOperatorAttemptRepository repository,
      StartSessionOperatorRedemptionClient redemptionClient,
      PlatformTransactionManager transactions) {
    return new GameSessionStartSessionOperatorAuthorizationCoordinator(
        repository, redemptionClient, transactions, NAMESPACE);
  }

  private static GameSessionStartSessionOperatorAttemptRepository mockRepository() {
    return mock(GameSessionStartSessionOperatorAttemptRepository.class);
  }

  private static StartSessionOperatorRedemptionClient mockRedemptionClient() {
    return mock(StartSessionOperatorRedemptionClient.class);
  }

  private static void assertWritableReadCommittedTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static AttemptClaim claim(String requestId) {
    return new AttemptClaim(NAMESPACE, requestId, OWNER_ATTEMPT, OWNER_MUTATION, CLAIM_OWNER, 37L);
  }

  private static AttemptSnapshot snapshot(
      StartSessionPostAuthorizationExecutionTuple tuple, AttemptClaim claim, byte[] projection) {
    AttemptSnapshot snapshot = mock(AttemptSnapshot.class);
    when(snapshot.targetNamespace()).thenReturn(NAMESPACE);
    when(snapshot.controlPlaneRequestId()).thenReturn(tuple.controlPlaneRequestId());
    when(snapshot.canonicalTenantId()).thenReturn(TENANT);
    when(snapshot.ownerAttemptId()).thenReturn(claim.ownerAttemptId());
    when(snapshot.ownerMutationId()).thenReturn(claim.ownerMutationId());
    when(snapshot.ownerFence()).thenReturn(claim.ownerFence());
    when(snapshot.phaseState()).thenReturn("OWNER_EXECUTION_PENDING");
    when(snapshot.postAuthorizationExecutionTuple()).thenReturn(tuple.canonicalBytes());
    when(snapshot.accountRedemptionProjection())
        .thenReturn(projection == null ? null : projection.clone());
    return snapshot;
  }

  private static AccountRedemptionProjection projection(
      StartSessionPostAuthorizationExecutionTuple tuple) {
    StartSessionAuthorityEvidenceBundle bundle =
        StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
    return new AccountRedemptionProjection(
        tuple.authorizationReferenceFingerprint(),
        tuple.authorityEvidenceBundleBytes(),
        bundle.issuanceOperationId(),
        Long.parseLong(tuple.issuanceFence()));
  }

  private static StartSessionPostAuthorizationExecutionTuple tuple(String requestId)
      throws IOException {
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT, NAMESPACE),
            new StartSessionOperatorAction.Target(91L, TARGET_OWNER),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "Game Session operator authorization coordinator contract");
    StartSessionPreAuthorizationReservationTuple pre =
        StartSessionPreAuthorizationReservationTuple.createHuman(requestId, ACTOR, action);
    return StartSessionPostAuthorizationExecutionTuple.createHuman(
        pre,
        WORKLOAD,
        FINGERPRINT,
        RESERVATION_OWNER,
        19L,
        authorityBundle(pre),
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "17",
            "23",
            "18446744073709551615"));
  }

  private static byte[] authorityBundle(StartSessionPreAuthorizationReservationTuple tuple)
      throws IOException {
    String tenantId = TENANT.toString();
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
            Map.of(
                "sourceType", "ACCOUNT",
                "sourceEvidenceId", "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion", "17",
                "projectionStatus", "CURRENT",
                "evaluatedAt", "2026-10-09T00:00:00Z",
                "expiresAt", "2026-10-09T00:05:00Z"),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId", ISSUANCE_ID.toString(),
                "controlPlaneRequestId", tuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                    Map.of(
                        "requestIdentityKind",
                        "controlPlaneRequestId",
                        "requestId",
                        tuple.controlPlaneRequestId()),
                "mutationDigest", tuple.mutationDigest()),
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
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static GrpcPeerIdentity peer(String uri) {
    return GrpcPeerIdentity.parseUri(uri).orElseThrow();
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, ThrowingSupplier<T> work) throws Exception {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return work.get();
    } finally {
      context.detach(previous);
    }
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final List<TransactionDefinition> definitions = new ArrayList<>();
    private int commitCount;
    private int rollbackCount;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      definitions.add(definition);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commitCount++;
      TransactionSynchronizationManager.clear();
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbackCount++;
      TransactionSynchronizationManager.clear();
    }
  }
}
