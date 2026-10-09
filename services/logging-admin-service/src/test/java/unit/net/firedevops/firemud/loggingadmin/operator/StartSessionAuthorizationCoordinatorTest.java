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
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamesession.v1.StartSessionOwnerAuthorizationProgress;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient;
import net.firedevops.firemud.loggingadmin.client.GameSessionClient.StartSessionOwnerHandoffResult;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient;
import net.firedevops.firemud.loggingadmin.client.StartSessionOperatorAuthorizationClient.AuthorizationReference;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.OwnerDispatchResult;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.Progress;
import net.firedevops.firemud.loggingadmin.operator.StartSessionAuthorizationCoordinator.Result;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.AuthorizedSnapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.OwnerExecutionHandoff;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

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
  private static final String CONTROL_UI_TOKEN = "operator-control-ui-secret";
  private static final String OPAQUE_REFERENCE = "r".repeat(43);
  private static final String FINGERPRINT = "arfp/v1/test-key/" + "b".repeat(64);
  private static final String LOGGING_IDENTITY =
      "spiffe://firemud/ns/world-runtime/sa/logging-admin-service";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final StartSessionPreAuthorizationReservationRepository repository =
      mock(StartSessionPreAuthorizationReservationRepository.class);
  private final StartSessionPreAuthorizationReservationService reservations =
      new StartSessionPreAuthorizationReservationService(
          repository, Clock.fixed(NOW, ZoneOffset.UTC));
  private final StartSessionOperatorAuthorizationClient accountClient =
      mock(StartSessionOperatorAuthorizationClient.class);
  private final StartSessionAuthorizationCoordinator coordinator =
      new StartSessionAuthorizationCoordinator(reservations, accountClient);

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
            eq(tuple), eq(tuple.mutationDigest()), any(UUID.class), eq(1L), eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(pending, true));
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L)))
        .thenReturn(response);
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
    assertThat(first.toString())
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
        .verify(accountClient)
        .issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            originalOwner.capture(),
            eq(1L),
            currentOwner.capture(),
            eq(1L));
    assertThat(originalOwner.getValue()).isEqualTo(currentOwner.getValue());
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
  void recoveryUsesOriginalOwnerAndFreshFenceAndNeverCallsIssue() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-recovery");
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 3L);
    when(repository.acquireRecoveryClaim(
            eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.RecoveryClaimResult(
                    pending, ORIGINAL_OWNER)));
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.recover(
            eq(shared(tuple)), eq(ORIGINAL_OWNER), eq(1L), any(UUID.class), eq(3L)))
        .thenReturn(response);
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
            eq(tuple), eq(tuple.mutationDigest()), any(UUID.class), eq(1L), eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(
                alreadyPending, true));
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)),
            eq(CONTROL_UI_TOKEN),
            any(UUID.class),
            eq(1L),
            any(UUID.class),
            eq(1L)))
        .thenReturn(response);
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
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.recover(
            eq(shared(tuple)), eq(ORIGINAL_OWNER), eq(1L), any(UUID.class), eq(3L)))
        .thenReturn(response);
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
            eq(tuple), eq(tuple.mutationDigest()), any(UUID.class), eq(1L), eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(
                alreadyPending, true));
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), eq(1L), any(UUID.class), eq(1L)))
        .thenReturn(response);
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
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenReturn(response);
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
    AuthorizationReference response = authorizationReference(tuple);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenReturn(response);
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

    assertThatThrownBy(() -> coordinator.issueHuman(tuple, CONTROL_UI_TOKEN))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
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
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true));
    when(repository.markAuthorizationPending(
            eq(tuple), eq(tuple.mutationDigest()), any(UUID.class), eq(1L), eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(pending, true));
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenThrow(Status.UNAVAILABLE.asRuntimeException());

    assertThatThrownBy(() -> coordinator.issueHuman(tuple, CONTROL_UI_TOKEN))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);

    verify(repository, never())
        .completeAuthorization(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), anyLong());
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
    verify(accountClient, never()).recover(any(), any(), anyLong(), any(), anyLong());
  }

  @Test
  void mismatchedResponseTupleAndStaleClaimAfterAccountReturnAreNotForwarded() throws Exception {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("coordinator-mismatch");
    StartSessionPreAuthorizationReservationTuple substituted = tuple("coordinator-substituted");
    stubInitialReservation(tuple);
    AuthorizationReference substitutedResponse = authorizationReference(substituted);
    when(accountClient.issueHuman(
            eq(shared(tuple)), anyString(), any(UUID.class), anyLong(), any(UUID.class), anyLong()))
        .thenReturn(substitutedResponse);

    assertThatThrownBy(() -> coordinator.issueHuman(tuple, CONTROL_UI_TOKEN))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never())
        .completeAuthorization(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), anyLong());
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());

    StartSessionPreAuthorizationReservationTuple staleTuple = tuple("coordinator-stale-return");
    stubInitialReservation(staleTuple);
    AuthorizationReference staleResponse = authorizationReference(staleTuple);
    when(accountClient.issueHuman(
            eq(shared(staleTuple)),
            anyString(),
            any(UUID.class),
            anyLong(),
            any(UUID.class),
            anyLong()))
        .thenReturn(staleResponse);
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

    assertThatThrownBy(() -> coordinator.issueHuman(staleTuple, CONTROL_UI_TOKEN))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
    verify(repository, never())
        .beginOwnerExecution(
            any(), anyString(), any(), anyLong(), any(), anyLong(), anyString(), any(), anyLong());
  }

  private void stubInitialReservation(StartSessionPreAuthorizationReservationTuple tuple) {
    Snapshot reserved = snapshot(tuple, State.RESERVED, 1L, 1L);
    Snapshot pending = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 1L);
    when(repository.acquire(eq(tuple), any(UUID.class), eq(NOW_MILLIS), eq(NOW_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(reserved, true));
    when(repository.markAuthorizationPending(
            eq(tuple), eq(tuple.mutationDigest()), any(UUID.class), eq(1L), eq(NOW_MILLIS)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.TransitionResult(pending, true));
  }

  private void stubAuthorizationEnrichment(
      StartSessionPreAuthorizationReservationTuple tuple,
      boolean transitioned,
      boolean mayDispatch) {
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
                      ClaimState.ACTIVE);
              return new StartSessionPreAuthorizationReservationRepository
                  .AuthorizationTransitionResult(
                  new AuthorizedSnapshot(
                      authorized, postTuple, reservationOwner, currentOwner, null),
                  transitioned);
            });
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
                      ClaimState.ACTIVE);
              return new StartSessionPreAuthorizationReservationRepository
                  .OwnerExecutionTransitionResult(
                  new AuthorizedSnapshot(
                      ownerPending,
                      postTuple,
                      reservationOwner,
                      currentOwner,
                      new OwnerExecutionHandoff(handoffId)),
                  mayDispatch);
            });
  }

  private static AuthorizationReference authorizationReference(
      StartSessionPreAuthorizationReservationTuple tuple) throws Exception {
    AuthorizationReference response = mock(AuthorizationReference.class);
    when(response.operatorAuthorizationReference()).thenReturn(OPAQUE_REFERENCE);
    when(response.authorizationReferenceFingerprint()).thenReturn(FINGERPRINT);
    when(response.expiresAt()).thenReturn(NOW.plusSeconds(300));
    when(response.authorityEvidenceBundle()).thenReturn(bundleBytes(tuple, NOW.plusSeconds(300)));
    when(response.bundleReference()).thenReturn(bundleReference());
    when(response.authenticatedLoggingWorkloadIdentity()).thenReturn(LOGGING_IDENTITY);
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
      StartSessionPreAuthorizationReservationTuple tuple, Instant expiresAt) throws IOException {
    String tenantId = TENANT_ID.toString();
    Map<String, Object> projection =
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
            NOW.toString(),
            "expiresAt",
            expiresAt.toString());
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
            ACTOR_ID.toString(),
            "controlUiTokenJti",
            TOKEN_JTI.toString(),
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    Map<String, Object> bundle =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope", Map.of("tenantId", tenantId, "targetNamespace", "world-runtime"),
                "actionFamily", tuple.actionFamily(),
                "applicableAccountId", ACTOR_ID.toString(),
                "applicableTenantId", tenantId),
            "accountProjectionEvidence",
            projection,
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
            authority,
            "membershipVersion",
            Map.of(tenantId, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            evidence);
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(bundle));
  }

  private static StartSessionAuthorityEvidenceBundle.BundleReference bundleReference() {
    return new StartSessionAuthorityEvidenceBundle.BundleReference(
        StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
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
        ClaimState.ACTIVE);
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
