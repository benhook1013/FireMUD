package net.firedevops.firemud.loggingadmin.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundleFixture.Values;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.StartSessionOwnerAuthorizationProgress;
import net.firedevops.firemud.loggingadmin.StartSessionReservationMutationTestFixtures;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient.StartSessionOwnerHandoffResult;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient.AuthorizationReference;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.OwnerDispatchResult;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.Progress;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.Result;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.AuthorizedSnapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimPurpose;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionHandoff;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class StartSessionAuthorizationCoordinatorTest {
  private static final UUID TENANT_ID = UUID.fromString("9f8f06b4-36e5-4d11-9c2a-5adfd7f41531");
  private static final UUID ACTOR_ID = UUID.fromString("a4f5f4eb-8243-4d42-903a-33495456a622");
  private static final UUID OWNER_ACCOUNT_ID =
      UUID.fromString("36aa9ce5-0ebc-4c14-9f6b-d160edc6059a");
  private static final UUID ORIGINAL_OWNER =
      UUID.fromString("a137588f-4ac6-45ae-984d-5792bdf934b4");
  private static final UUID ISSUANCE_ID = UUID.fromString("f5d044bd-7e5f-4e2d-9859-9025cbdcc60f");
  private static final UUID TOKEN_JTI = UUID.fromString("a681bba7-c215-4cf1-a35b-14348912cbdc");
  private static final UUID OWNER_ATTEMPT_ID =
      UUID.fromString("69116466-a576-4fa6-9e11-4c0c6b2a92f0");
  private static final UUID OWNER_MUTATION_ID =
      UUID.fromString("89da7d84-12f5-4cf8-b69b-30ba8eb115a4");
  private static final Instant NOW = Instant.parse("2026-10-09T00:00:00Z");
  private static final long NOW_MILLIS = NOW.toEpochMilli();
  private static final String CONTROL_UI_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2ln";
  private static final String OPAQUE_REFERENCE = "r".repeat(43);
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String LOGGING_IDENTITY =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";

  private final StartSessionPreAuthorizationReservationRepository repository =
      mock(StartSessionPreAuthorizationReservationRepository.class);
  private final StartSessionPreAuthorizationReservationService reservations =
      StartSessionReservationMutationTestFixtures.forMockBackedUnitTest(
          repository, Clock.fixed(NOW, ZoneOffset.UTC));
  private final StartSessionOperatorAuthorizationClient accountClient =
      mock(StartSessionOperatorAuthorizationClient.class);
  private final AtomicReference<AuthorizedSnapshot> exactReadback = new AtomicReference<>();
  private final StartSessionAuthorizationCoordinator coordinator =
      new StartSessionAuthorizationCoordinator("world-runtime", reservations, accountClient);

  @BeforeEach
  void setAuthenticatedTenantAdminContext() {
    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
  }

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void onlyReservationWinnerIssuesAndOnlyDurableOwnerPendingReturnsTransientReference()
      throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-winner");
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L);
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true),
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(pending, false));
    when(repository.markAuthorizationPending(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(pending, true));
    when(accountClient.issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L)))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);

    Result first = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);
    Result duplicate = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(first.progress()).isEqualTo(Progress.OWNER_EXECUTION_PENDING);
    assertThat(first.handoff()).isNotNull();
    assertThat(first.handoff().ownerExecutionHandoff().handoffId()).isNotNull();
    assertThat(first.handoff().accountResponse().operatorAuthorizationReference())
        .isEqualTo(OPAQUE_REFERENCE);
    assertThat(first.handoff().postAuthorizationTuple().authenticatedWorkloadIdentity())
        .isEqualTo(LOGGING_IDENTITY);
    assertThat(duplicate.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);
    assertThat(duplicate.handoff()).isNull();
    assertThat(duplicate.authorizedReadback()).isPresent();
    assertThat(first.toString())
        .doesNotContain(CONTROL_UI_TOKEN)
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(FINGERPRINT);
    assertThat(duplicate.toString())
        .doesNotContain(CONTROL_UI_TOKEN)
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(FINGERPRINT);
    assertThat(first.handoff().toString())
        .doesNotContain(CONTROL_UI_TOKEN)
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(FINGERPRINT);

    ArgumentCaptor<UUID> originalOwner = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> currentOwner = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<String> persistedTuple = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<UUID> handoffId = ArgumentCaptor.forClass(UUID.class);
    InOrder persistenceOrder = inOrder(accountClient, repository);
    persistenceOrder
        .verify(repository)
        .acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L));
    persistenceOrder
        .verify(repository)
        .markAuthorizationPending(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(NOW_MILLIS));
    persistenceOrder
        .verify(accountClient)
        .issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            originalOwner.capture(),
            eq(1L),
            currentOwner.capture(),
            eq(1L));
    assertThat(originalOwner.getValue()).isEqualTo(currentOwner.getValue());
    verify(accountClient, org.mockito.Mockito.times(1))
        .issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L));
    verify(accountClient, never()).recover(any(), any(), anyLong(), any(), anyLong());
    persistenceOrder
        .verify(repository)
        .completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            persistedTuple.capture(),
            eq(NOW_MILLIS));
    assertThat(persistedTuple.getValue())
        .contains(LOGGING_IDENTITY)
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(CONTROL_UI_TOKEN);
    assertThat(persistedTuple.getValue())
        .isEqualTo(first.handoff().postAuthorizationTuple().canonicalJson());
    assertThat(
            StartSessionPostAuthorizationExecutionTuple.decode(
                    persistedTuple.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .canonicalBytes())
        .containsExactly(first.handoff().postAuthorizationTuple().canonicalBytes());
    persistenceOrder.verify(repository).findAuthorizedExact(eq(tuple));
    persistenceOrder
        .verify(repository)
        .beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(persistedTuple.getValue()),
            handoffId.capture(),
            eq(NOW_MILLIS));
    assertThat(handoffId.getValue()).isEqualTo(first.handoff().ownerExecutionHandoff().handoffId());
  }

  @Test
  void namespaceJwtAndHumanTenantAdminGuardsRunBeforeStorage() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-input-guards");
    StartSessionOperatorAction action = tuple.action();
    StartSessionOperatorAction wrongNamespaceAction =
        new StartSessionOperatorAction(
            action.actionFamilySchemaId(),
            action.actionFamilySchemaVersion(),
            new StartSessionOperatorAction.Scope(TENANT_ID, "other-runtime"),
            action.target(),
            action.expectedVersion(),
            action.mutation(),
            action.auditReason());

    assertThatThrownBy(
            () ->
                coordinator.begin(
                    tuple.controlPlaneRequestId(), wrongNamespaceAction, CONTROL_UI_TOKEN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured workload namespace");
    assertThatThrownBy(() -> coordinator.begin(tuple.controlPlaneRequestId(), action, "not-a-jwt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JWT envelope is malformed");
    assertThatThrownBy(
            () ->
                coordinator.begin(
                    tuple.controlPlaneRequestId(), action, CONTROL_UI_TOKEN + ".extra"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JWT envelope is malformed");

    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("moderator")),
        false,
        null,
        null);
    assertThatThrownBy(
            () -> coordinator.begin(tuple.controlPlaneRequestId(), action, CONTROL_UI_TOKEN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantAdmin authority");

    verifyNoInteractions(repository, accountClient);
  }

  @Test
  void beginAcceptsCanonicalCompactThreeSegmentJwtEnvelope() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-valid-jwt-envelope");
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(pending, false));
    when(repository.findAuthorizedExact(eq(tuple))).thenReturn(Optional.empty());

    Result result =
        coordinator.begin(tuple.controlPlaneRequestId(), tuple.action(), CONTROL_UI_TOKEN);

    assertThat(CONTROL_UI_TOKEN.split("\\.", -1)).hasSize(3);
    assertThat(result.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);
    verify(repository)
        .acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L));
    verify(accountClient, never())
        .issueHuman(any(), anyString(), any(), anyLong(), any(), anyLong());
  }

  @Test
  void changedTupleForExistingRequestConflictsWithoutAccountCall() {
    StartSessionPreAuthorizationReservationTuple requested = tuple("coordinator-tuple-conflict");
    StartSessionOperatorAction action = requested.action();
    StartSessionOperatorAction changedAction =
        new StartSessionOperatorAction(
            action.actionFamilySchemaId(),
            action.actionFamilySchemaVersion(),
            action.scope(),
            new StartSessionOperatorAction.Target(92L, action.target().ownerAccountId()),
            action.expectedVersion(),
            action.mutation(),
            action.auditReason());
    StartSessionPreAuthorizationReservationTuple conflicting =
        StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(
            requested.controlPlaneRequestId(), changedAction);
    Snapshot existing = snapshot(conflicting, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(
            eq(requested), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(existing, false));

    assertThatThrownBy(() -> coordinator.issueHuman(requested, CONTROL_UI_TOKEN))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.IdempotencyConflictException.class);

    verify(accountClient, never())
        .issueHuman(any(), anyString(), any(), anyLong(), any(), anyLong());
    verify(repository, never())
        .markAuthorizationPending(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyLong());
  }

  @Test
  void productionReservationMutationRemainsDeniedByDefault() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-default-denial");
    StartSessionPreAuthorizationReservationService productionDefault =
        new StartSessionPreAuthorizationReservationService(repository);

    assertThatThrownBy(() -> productionDefault.acquire(tuple))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("disabled for production construction");

    verifyNoInteractions(repository);
  }

  @Test
  void missingIndependentAuthorizedReadbackLeavesAuthorizationUnknownAndDoesNotHandoff()
      throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-readback-missing");
    stubInitialReservation(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);
    when(repository.findAuthorizedExact(eq(tuple))).thenReturn(Optional.empty());

    Result result = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(result.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
    assertThat(result.pendingReason())
        .contains(
            StartSessionAuthorizationCoordinator.PendingReason.AUTHORIZATION_READBACK_MISMATCH);
    assertThat(result.snapshot().state()).isEqualTo(State.AUTHORIZED);
    assertThat(result.handoff()).isNull();
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
  }

  @Test
  void conflictingIndependentAuthorizedReadbackCannotAdvanceOwnerPending() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-readback-conflict");
    stubInitialReservation(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);
    when(repository.findAuthorizedExact(eq(tuple)))
        .thenAnswer(
            invocation -> {
              AuthorizedSnapshot exact = exactReadback.get();
              Snapshot snapshot = exact.reservationSnapshot();
              Snapshot substituted =
                  new Snapshot(
                      snapshot.tuple(),
                      snapshot.mutationDigest(),
                      snapshot.phase(),
                      snapshot.state(),
                      snapshot.reservationClaimFence(),
                      snapshot.claimFence(),
                      snapshot.claimExpiresAtEpochMillis() + 1L,
                      snapshot.claimState(),
                      snapshot.claimPurpose());
              return Optional.of(
                  new AuthorizedSnapshot(
                      substituted,
                      exact.postAuthorizationTuple(),
                      exact.reservationOwnerId(),
                      exact.currentClaimOwnerId(),
                      exact.handoff()));
            });

    Result result = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(result.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
    assertThat(result.pendingReason())
        .contains(
            StartSessionAuthorizationCoordinator.PendingReason.AUTHORIZATION_READBACK_MISMATCH);
    assertThat(result.handoff()).isNull();
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
  }

  @Test
  void recoveryUsesOriginalOwnerAndFreshFenceAndNeverCallsIssue() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-recovery");
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 3L);
    when(repository.acquireRecoveryClaim(
            eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.RecoveryClaimResult(
                    pending, ORIGINAL_OWNER)));
    when(accountClient.recover(
            eq(shared(tuple)), eq(ORIGINAL_OWNER), eq(1L), any(UUID.class), eq(3L)))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(1),
                    ((Number) invocation.getArgument(2)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);

    Result result = coordinator.recover(tuple);

    assertThat(result.progress()).isEqualTo(Progress.OWNER_EXECUTION_PENDING);
    assertThat(result.handoff().postAuthorizationTuple().reservationOwnerId())
        .isEqualTo(ORIGINAL_OWNER);
    ArgumentCaptor<UUID> currentOwner = ArgumentCaptor.forClass(UUID.class);
    verify(accountClient)
        .recover(eq(shared(tuple)), eq(ORIGINAL_OWNER), eq(1L), currentOwner.capture(), eq(3L));
    assertThat(currentOwner.getValue()).isNotEqualTo(ORIGINAL_OWNER);
    verify(accountClient, never())
        .issueHuman(any(), anyString(), any(), anyLong(), any(), anyLong());
  }

  @Test
  void issueHumanAndDispatchForwardsOnlyAfterDurableOwnerPendingAndOnlyOnce() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-issue-dispatch");
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L);
    Snapshot alreadyPending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true),
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(
                alreadyPending, false));
    when(repository.markAuthorizationPending(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(
                alreadyPending, true));
    when(accountClient.issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L)))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);
    GameSessionClient ownerClient = mock(GameSessionClient.class);
    StartSessionOwnerHandoffResult ownerEcho = ownerEcho(tuple);
    when(ownerClient.authorizeStartSession(any())).thenReturn(ownerEcho);

    OwnerDispatchResult first =
        coordinator.issueHumanAndDispatch(tuple, CONTROL_UI_TOKEN, ownerClient);
    OwnerDispatchResult duplicate =
        coordinator.issueHumanAndDispatch(tuple, CONTROL_UI_TOKEN, ownerClient);

    assertThat(first.progress()).isEqualTo(Progress.OWNER_EXECUTION_PENDING);
    assertThat(first.ownerEcho()).contains(ownerEcho);
    assertThat(first.toString())
        .doesNotContain(CONTROL_UI_TOKEN)
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(FINGERPRINT);
    assertThat(duplicate.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);
    assertThat(duplicate.ownerEcho()).isEmpty();

    ArgumentCaptor<String> persistedTuple = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<StartSessionAuthorizationCoordinator.TransientOwnerExecutionHandoff>
        ownerHandoff =
            ArgumentCaptor.forClass(
                StartSessionAuthorizationCoordinator.TransientOwnerExecutionHandoff.class);
    InOrder dispatchOrder = inOrder(accountClient, repository, ownerClient);
    dispatchOrder
        .verify(accountClient)
        .issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L));
    dispatchOrder
        .verify(repository)
        .completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            persistedTuple.capture(),
            eq(NOW_MILLIS));
    dispatchOrder
        .verify(repository)
        .beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(persistedTuple.getValue()),
            any(UUID.class),
            eq(NOW_MILLIS));
    dispatchOrder.verify(ownerClient).authorizeStartSession(ownerHandoff.capture());
    assertThat(ownerHandoff.getValue().postAuthorizationTuple().canonicalJson())
        .isEqualTo(persistedTuple.getValue());
    verify(ownerClient, org.mockito.Mockito.times(1)).authorizeStartSession(any());
  }

  @Test
  void recoverAndDispatchForwardsTheOriginalRecoveredOperationOnce() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-recover-dispatch");
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 3L);
    when(repository.acquireRecoveryClaim(
            eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.RecoveryClaimResult(
                    pending, ORIGINAL_OWNER)));
    when(accountClient.recover(
            eq(shared(tuple)), eq(ORIGINAL_OWNER), eq(1L), any(UUID.class), eq(3L)))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(1),
                    ((Number) invocation.getArgument(2)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);
    GameSessionClient ownerClient = mock(GameSessionClient.class);
    StartSessionOwnerHandoffResult ownerEcho = ownerEcho(tuple);
    when(ownerClient.authorizeStartSession(any())).thenReturn(ownerEcho);

    OwnerDispatchResult result = coordinator.recoverAndDispatch(tuple, ownerClient);

    assertThat(result.progress()).isEqualTo(Progress.OWNER_EXECUTION_PENDING);
    assertThat(result.ownerEcho()).contains(ownerEcho);
    InOrder dispatchOrder = inOrder(accountClient, repository, ownerClient);
    dispatchOrder
        .verify(accountClient)
        .recover(eq(shared(tuple)), eq(ORIGINAL_OWNER), eq(1L), any(UUID.class), eq(3L));
    dispatchOrder
        .verify(repository)
        .completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(3L),
            anyString(),
            eq(NOW_MILLIS));
    dispatchOrder
        .verify(repository)
        .beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(3L),
            anyString(),
            any(UUID.class),
            eq(NOW_MILLIS));
    dispatchOrder.verify(ownerClient).authorizeStartSession(any());
    verify(ownerClient, org.mockito.Mockito.times(1)).authorizeStartSession(any());
  }

  @Test
  void nonWinningAndUnavailableAuthorizationPathsNeverDispatchOwner() {
    StartSessionPreAuthorizationReservationTuple duplicateTuple =
        tuple("coordinator-dispatch-duplicate");
    Snapshot pending = snapshot(duplicateTuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(
            eq(duplicateTuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(pending, false));
    when(repository.findAuthorizedExact(eq(duplicateTuple))).thenReturn(Optional.empty());
    StartSessionPreAuthorizationReservationTuple unavailableTuple =
        tuple("coordinator-dispatch-unavailable");
    when(repository.acquireRecoveryClaim(
            eq(unavailableTuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(Optional.empty());
    GameSessionClient ownerClient = mock(GameSessionClient.class);

    OwnerDispatchResult duplicate =
        coordinator.issueHumanAndDispatch(duplicateTuple, CONTROL_UI_TOKEN, ownerClient);
    OwnerDispatchResult unavailable = coordinator.recoverAndDispatch(unavailableTuple, ownerClient);

    assertThat(duplicate.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);
    assertThat(duplicate.ownerEcho()).isEmpty();
    assertThat(unavailable.progress()).isEqualTo(Progress.RECOVERY_UNAVAILABLE);
    assertThat(unavailable.ownerEcho()).isEmpty();
    verifyNoInteractions(ownerClient);
    verify(accountClient, never())
        .issueHuman(any(), anyString(), any(), anyLong(), any(), anyLong());
    verify(accountClient, never()).recover(any(), any(), anyLong(), any(), anyLong());
  }

  @Test
  void ambiguousOwnerDispatchPropagatesAndExactDuplicateDoesNotRedeliver() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-dispatch-timeout");
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L);
    Snapshot alreadyPending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true),
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(
                alreadyPending, false));
    when(repository.markAuthorizationPending(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(
                alreadyPending, true));
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), eq(1L), any(UUID.class), eq(1L)))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);
    GameSessionClient ownerClient = mock(GameSessionClient.class);
    io.grpc.StatusRuntimeException ambiguous = Status.UNAVAILABLE.asRuntimeException();
    when(ownerClient.authorizeStartSession(any())).thenThrow(ambiguous);

    assertThatThrownBy(
            () -> coordinator.issueHumanAndDispatch(tuple, CONTROL_UI_TOKEN, ownerClient))
        .isSameAs(ambiguous);
    OwnerDispatchResult duplicate =
        coordinator.issueHumanAndDispatch(tuple, CONTROL_UI_TOKEN, ownerClient);

    assertThat(duplicate.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);
    assertThat(duplicate.ownerEcho()).isEmpty();
    verify(ownerClient, org.mockito.Mockito.times(1)).authorizeStartSession(any());
    verify(accountClient, org.mockito.Mockito.times(1))
        .issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), eq(1L), any(UUID.class), eq(1L));
  }

  @Test
  void ownerPendingDuplicateCasDoesNotReturnTransientHandoff() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-owner-duplicate");
    stubInitialReservation(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, false);

    Result result = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(result.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);
    assertThat(result.handoff()).isNull();
    assertThat(result.toString())
        .doesNotContain(CONTROL_UI_TOKEN)
        .doesNotContain(OPAQUE_REFERENCE)
        .doesNotContain(FINGERPRINT);
    verify(repository)
        .beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            anyString(),
            any(UUID.class),
            eq(NOW_MILLIS));
  }

  @Test
  void ownerPendingTransitionFailureNeverReturnsTransientPayload() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-owner-stale");
    stubInitialReservation(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    tuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    stubAuthorizationEnrichment(tuple, true, true);
    when(repository.beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            anyString(),
            any(UUID.class),
            eq(NOW_MILLIS)))
        .thenThrow(
            new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                tuple.controlPlaneRequestId()));

    Result result = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(result.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
    assertThat(result.pendingReason())
        .contains(
            StartSessionAuthorizationCoordinator.PendingReason
                .OWNER_PENDING_TRANSITION_OUTCOME_UNKNOWN);
    assertThat(result.handoff()).isNull();
    verify(repository)
        .completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            anyString(),
            eq(NOW_MILLIS));
    verify(repository)
        .beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            anyString(),
            any(UUID.class),
            eq(NOW_MILLIS));
  }

  @Test
  void uncertainAccountCallLeavesPendingAndIsNotRetried() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-timeout");
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L);
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true),
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(pending, false));
    when(repository.markAuthorizationPending(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(pending, true));
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());
    when(repository.findAuthorizedExact(eq(tuple))).thenReturn(Optional.empty());

    Result first = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);
    Result duplicate = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(first.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
    assertThat(first.pendingReason())
        .contains(
            StartSessionAuthorizationCoordinator.PendingReason.ACCOUNT_ISSUANCE_OUTCOME_UNKNOWN);
    assertThat(first.snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(duplicate.progress()).isEqualTo(Progress.ALREADY_IN_PROGRESS);

    verify(repository, never())
        .completeAuthorization(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), anyLong());
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
    verify(accountClient, never()).recover(any(), any(), anyLong(), any(), anyLong());
    verify(accountClient, org.mockito.Mockito.times(1))
        .issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong());
  }

  @Test
  void mismatchedResponseTupleAndStaleClaimAfterAccountReturnAreNotForwarded() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-mismatch");
    StartSessionPreAuthorizationReservationTuple substituted = tuple("coordinator-substituted");
    stubInitialReservation(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    substituted,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));

    Result invalidAccountResult = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

    assertThat(invalidAccountResult.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
    assertThat(invalidAccountResult.pendingReason())
        .contains(StartSessionAuthorizationCoordinator.PendingReason.ACCOUNT_RESPONSE_INVALID);
    verify(repository, never())
        .completeAuthorization(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), anyLong());
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());

    StartSessionPreAuthorizationReservationTuple staleTuple = tuple("coordinator-stale-return");
    stubInitialReservation(staleTuple);
    when(accountClient.issueHuman(
            eq(shared(staleTuple)),
            anyString(),
            any(UUID.class),
            anyLong(),
            any(UUID.class),
            anyLong()))
        .thenAnswer(
            invocation ->
                authorizationReference(
                    staleTuple,
                    (UUID) invocation.getArgument(2),
                    ((Number) invocation.getArgument(3)).longValue()));
    when(repository.completeAuthorization(
            eq(staleTuple),
            eq(staleTuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            anyString(),
            eq(NOW_MILLIS)))
        .thenThrow(
            new StartSessionPreAuthorizationReservationService.StaleReservationClaimException(
                staleTuple.controlPlaneRequestId()));

    Result staleCompletion = coordinator.issueHuman(staleTuple, CONTROL_UI_TOKEN);
    assertThat(staleCompletion.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
    assertThat(staleCompletion.pendingReason())
        .contains(
            StartSessionAuthorizationCoordinator.PendingReason.LOCAL_COMPLETION_OUTCOME_UNKNOWN);
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
  }

  @Test
  void sourceFenceAndLinearizationMismatchesNeverReachLoggingCompletion() throws Exception {
    List<StartSessionAuthorityEvidenceBundle.BundleReference> alteredTupleReferences =
        List.of(
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "25",
                "18446744073709551615"),
            new StartSessionAuthorityEvidenceBundle.BundleReference(
                StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
                "17",
                "24",
                "18446744073709551614"));
    int caseNumber = 0;
    for (var alteredReference : alteredTupleReferences) {
      StartSessionPreAuthorizationReservationTuple tuple =
          tuple("coordinator-capture-reference-mismatch-" + caseNumber++);
      stubInitialReservation(tuple);
      when(accountClient.issueHuman(
              eq(shared(tuple)),
              anyString(),
              any(UUID.class),
              anyLong(),
              any(UUID.class),
              anyLong()))
          .thenAnswer(
              invocation ->
                  authorizationReference(
                      tuple,
                      (UUID) invocation.getArgument(2),
                      ((Number) invocation.getArgument(3)).longValue(),
                      alteredReference));

      Result result = coordinator.issueHuman(tuple, CONTROL_UI_TOKEN);

      assertThat(result.progress()).isEqualTo(Progress.AUTHORIZATION_OUTCOME_UNKNOWN);
      assertThat(result.pendingReason())
          .contains(StartSessionAuthorizationCoordinator.PendingReason.ACCOUNT_RESPONSE_INVALID);
      verify(repository, never())
          .completeAuthorization(
              eq(tuple),
              eq(tuple.mutationDigest()),
              any(UUID.class),
              eq(1L),
              any(UUID.class),
              eq(1L),
              anyString(),
              eq(NOW_MILLIS));
      verify(repository, never())
          .beginOwnerExecution(
              eq(tuple),
              eq(tuple.mutationDigest()),
              any(UUID.class),
              eq(1L),
              any(UUID.class),
              eq(1L),
              anyString(),
              any(UUID.class),
              eq(NOW_MILLIS));
    }
  }

  private void stubInitialReservation(StartSessionPreAuthorizationReservationTuple tuple) {
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L);
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true));
    when(repository.markAuthorizationPending(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L),
            eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(pending, true));
  }

  private void stubAuthorizationEnrichment(
      StartSessionPreAuthorizationReservationTuple tuple,
      boolean transitioned,
      boolean mayDispatch) {
    exactReadback.set(null);
    when(repository.completeAuthorization(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            anyLong(),
            any(UUID.class),
            anyLong(),
            anyString(),
            eq(NOW_MILLIS)))
        .thenAnswer(
            invocation -> {
              UUID reservationOwner = invocation.getArgument(2);
              long reservationFence = invocation.getArgument(3);
              UUID currentOwner = invocation.getArgument(4);
              long currentFence = invocation.getArgument(5);
              String exactPostTupleJson = invocation.getArgument(6);
              StartSessionPostAuthorizationExecutionTuple postTuple =
                  StartSessionPostAuthorizationExecutionTuple.decode(
                      exactPostTupleJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              Snapshot authorized =
                  new Snapshot(
                      tuple,
                      tuple.mutationDigest(),
                      Phase.ACCOUNT_AUTHORIZATION,
                      State.AUTHORIZED,
                      reservationFence,
                      currentFence,
                      NOW_MILLIS + 30_000L,
                      ClaimState.ACTIVE,
                      currentOwner.equals(reservationOwner) && currentFence == reservationFence
                          ? ClaimPurpose.ORIGINAL
                          : ClaimPurpose.AUTHORIZATION_RECOVERY);
              AuthorizedSnapshot authorizedSnapshot =
                  new AuthorizedSnapshot(
                      authorized, postTuple, reservationOwner, currentOwner, null);
              exactReadback.set(authorizedSnapshot);
              return new StartSessionPreAuthorizationReservationRepository
                  .AuthorizationTransitionResult(authorizedSnapshot, transitioned);
            });
    when(repository.findAuthorizedExact(eq(tuple)))
        .thenAnswer(invocation -> Optional.ofNullable(exactReadback.get()));
    when(repository.beginOwnerExecution(
            eq(tuple),
            eq(tuple.mutationDigest()),
            any(UUID.class),
            anyLong(),
            any(UUID.class),
            anyLong(),
            anyString(),
            any(UUID.class),
            eq(NOW_MILLIS)))
        .thenAnswer(
            invocation -> {
              UUID reservationOwner = invocation.getArgument(2);
              long reservationFence = invocation.getArgument(3);
              UUID currentOwner = invocation.getArgument(4);
              long currentFence = invocation.getArgument(5);
              String exactPostTupleJson = invocation.getArgument(6);
              UUID handoffId = invocation.getArgument(7);
              StartSessionPostAuthorizationExecutionTuple postTuple =
                  StartSessionPostAuthorizationExecutionTuple.decode(
                      exactPostTupleJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
              Snapshot ownerPending =
                  new Snapshot(
                      tuple,
                      tuple.mutationDigest(),
                      Phase.OWNER_EXECUTION,
                      State.OWNER_EXECUTION_PENDING,
                      reservationFence,
                      currentFence,
                      NOW_MILLIS + 30_000L,
                      ClaimState.ACTIVE,
                      currentOwner.equals(reservationOwner) && currentFence == reservationFence
                          ? ClaimPurpose.ORIGINAL
                          : ClaimPurpose.AUTHORIZATION_RECOVERY);
              AuthorizedSnapshot ownerPendingSnapshot =
                  new AuthorizedSnapshot(
                      ownerPending,
                      postTuple,
                      reservationOwner,
                      currentOwner,
                      new OwnerExecutionHandoff(handoffId));
              exactReadback.set(ownerPendingSnapshot);
              return new StartSessionPreAuthorizationReservationRepository
                  .OwnerExecutionTransitionResult(ownerPendingSnapshot, mayDispatch);
            });
  }

  private static AuthorizationReference authorizationReference(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwner,
      long reservationFence)
      throws Exception {
    return authorizationReference(tuple, reservationOwner, reservationFence, bundleReference());
  }

  private static AuthorizationReference authorizationReference(
      StartSessionPreAuthorizationReservationTuple tuple,
      UUID reservationOwner,
      long reservationFence,
      StartSessionAuthorityEvidenceBundle.BundleReference tupleReference)
      throws Exception {
    AuthorizationReference response = mock(AuthorizationReference.class);
    when(response.operatorAuthorizationReference()).thenReturn(OPAQUE_REFERENCE);
    when(response.authorizationReferenceFingerprint()).thenReturn(FINGERPRINT);
    when(response.expiresAt()).thenReturn(NOW.plusSeconds(300));
    byte[] bundle = bundleBytes(tuple, NOW.plusSeconds(300));
    when(response.authorityEvidenceBundle()).thenReturn(bundle);
    when(response.bundleReference()).thenReturn(bundleReference());
    when(response.authenticatedLoggingWorkloadIdentity()).thenReturn(LOGGING_IDENTITY);
    when(response.postAuthorizationTuple())
        .thenReturn(
            StartSessionPostAuthorizationExecutionTuple.createHuman(
                shared(tuple),
                LOGGING_IDENTITY,
                FINGERPRINT,
                reservationOwner,
                reservationFence,
                bundle,
                tupleReference));
    return response;
  }

  private static StartSessionOwnerHandoffResult ownerEcho(
      StartSessionPreAuthorizationReservationTuple tuple) {
    return new StartSessionOwnerHandoffResult(
        tuple.action().scope().targetNamespace(),
        tuple.controlPlaneRequestId(),
        tuple.mutationDigest(),
        OWNER_ATTEMPT_ID,
        OWNER_MUTATION_ID,
        7L,
        "OWNER_EXECUTION_PENDING",
        StartSessionOwnerAuthorizationProgress
            .START_SESSION_OWNER_AUTHORIZATION_PROGRESS_ACCOUNT_PROJECTION_ATTACHED);
  }

  private static byte[] bundleBytes(
      StartSessionPreAuthorizationReservationTuple tuple, Instant expiresAt) {
    return StartSessionAuthorityEvidenceBundleFixture.canonicalBytes(
        shared(tuple),
        new Values(
            ISSUANCE_ID,
            "sha256:" + "a".repeat(64),
            "17",
            "CURRENT",
            NOW,
            expiresAt,
            1L,
            2L,
            3L,
            4L,
            5L,
            23L,
            TOKEN_JTI,
            "2",
            "3"));
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference bundleReference() {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "24", "18446744073709551615");
  }

  private static Snapshot snapshot(
      StartSessionPreAuthorizationReservationTuple tuple,
      State state,
      long reservationFence,
      long claimFence) {
    return new Snapshot(
        tuple,
        tuple.mutationDigest(),
        Phase.ACCOUNT_AUTHORIZATION,
        state,
        reservationFence,
        claimFence,
        NOW_MILLIS + 30_000L,
        ClaimState.ACTIVE,
        claimFence == reservationFence
            ? ClaimPurpose.ORIGINAL
            : ClaimPurpose.AUTHORIZATION_RECOVERY);
  }

  private static StartSessionPreAuthorizationReservationTuple tuple(String requestId) {
    SessionContext.setContext(
        ACTOR_ID.toString(),
        List.of(),
        Map.of(TENANT_ID.toString(), List.of("tenantAdmin")),
        false,
        null,
        null);
    StartSessionOperatorAction action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(TENANT_ID, "world-runtime"),
            new StartSessionOperatorAction.Target(91L, OWNER_ACCOUNT_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "StartSession coordinator unit proof");
    return StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
  }

  private static net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
      shared(StartSessionPreAuthorizationReservationTuple tuple) {
    return net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
        .fromCanonicalJson(tuple.canonicalJson());
  }
}
