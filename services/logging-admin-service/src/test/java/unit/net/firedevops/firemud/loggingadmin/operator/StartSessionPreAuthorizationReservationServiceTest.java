package net.firedevops.firemud.loggingadmin.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Acquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ClaimState;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Phase;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.RecoveryAcquisition;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.Snapshot;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.State;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class StartSessionPreAuthorizationReservationServiceTest {
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final UUID TENANT_ID = UUID.fromString("3d3c5ca5-6d43-4db6-821d-8a0de883a467");
  private static final UUID ACTOR_ID = UUID.fromString("863843ee-f00a-4905-a9ad-11f706f1b693");
  private final StartSessionPreAuthorizationReservationRepository repository =
      mock(StartSessionPreAuthorizationReservationRepository.class);
  private final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW_EPOCH_MILLIS), ZoneOffset.UTC);
  private final StartSessionPreAuthorizationReservationService service =
      new StartSessionPreAuthorizationReservationService(repository, clock);

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void firstAcquisitionGetsOneBoundedClaimAndExactDuplicateGetsNoClaim() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("service-unit-01");
    Snapshot initial = snapshot(tuple, State.RESERVED, 1L, 1L, ClaimState.ACTIVE);
    when(repository.acquire(any(), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L)))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(initial, true))
        .thenReturn(
            new StartSessionPreAuthorizationReservationRepository.AcquireResult(initial, false));

    Acquisition first = service.acquire(tuple);
    Acquisition duplicate = service.acquire(tuple);

    assertThat(first.newlyAcquired()).isTrue();
    assertThat(first.claim()).isNotNull();
    assertThat(first.claim().claimFence()).isEqualTo(1L);
    assertThat(duplicate.newlyAcquired()).isFalse();
    assertThat(duplicate.claim()).isNull();
    verify(repository, org.mockito.Mockito.times(2))
        .acquire(any(), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L));
  }

  @Test
  void parsedTupleCannotBeUsedAsAnInitialReservationAuthorityAssertion() {
    StartSessionPreAuthorizationReservationTuple parsed =
        StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
            tuple("service-unit-02").canonicalJson());

    assertThat(parsed.isAuthorityDerived()).isFalse();
    assertThatThrownBy(() -> service.acquire(parsed))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("actor derived");
  }

  @Test
  void expiredPendingClaimCanOnlyBeReacquiredAsReadOnlyRecoveryClaim() {
    StartSessionPreAuthorizationReservationTuple tuple = tuple("service-unit-03");
    Snapshot recovered = snapshot(tuple, State.AUTHORIZATION_PENDING, 1L, 3L, ClaimState.ACTIVE);
    when(repository.acquireRecoveryClaim(
            eq(tuple), any(), eq(NOW_EPOCH_MILLIS), eq(NOW_EPOCH_MILLIS + 30_000L)))
        .thenReturn(
            Optional.of(
                new StartSessionPreAuthorizationReservationRepository.RecoveryClaimResult(
                    recovered, UUID.fromString("6e0ef777-8661-4139-80eb-904b75de5860"))));

    Optional<RecoveryAcquisition> acquisition = service.acquireAuthorizationRecoveryClaim(tuple);

    assertThat(acquisition).isPresent();
    assertThat(acquisition.orElseThrow().claim().isRecoveryLookupOnly()).isTrue();
    assertThat(acquisition.orElseThrow().snapshot().reservationClaimFence()).isEqualTo(1L);
    assertThat(acquisition.orElseThrow().snapshot().claimFence()).isEqualTo(3L);
    assertThat(acquisition.orElseThrow().snapshot().state()).isEqualTo(State.AUTHORIZATION_PENDING);
    assertThat(acquisition.orElseThrow().claim().claimEvidence().reservationClaimFence())
        .isEqualTo(1L);
    assertThat(acquisition.orElseThrow().claim().claimEvidence().currentClaimFence()).isEqualTo(3L);
    assertThatThrownBy(() -> service.markAuthorizationPending(acquisition.orElseThrow().claim()))
        .isInstanceOf(
            StartSessionPreAuthorizationReservationService.StaleReservationClaimException.class);
  }

  @Test
  void phaseQualifiedStateModelHasNoSyntheticTerminalOutcome() {
    assertThat(State.values()).containsExactly(State.RESERVED, State.AUTHORIZATION_PENDING);
    assertThat(Phase.values()).containsExactly(Phase.ACCOUNT_AUTHORIZATION);
  }

  @Test
  void annotationConfigContextSelectsRepositoryConstructorWithoutDatabase() {
    AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    context.registerBean(StartSessionPreAuthorizationReservationRepository.class, () -> repository);
    context.register(StartSessionPreAuthorizationReservationService.class);

    try {
      context.refresh();

      assertThat(context.getBean(StartSessionPreAuthorizationReservationService.class)).isNotNull();
    } finally {
      context.close();
    }
  }

  private static Snapshot snapshot(
      StartSessionPreAuthorizationReservationTuple tuple,
      State state,
      long reservationFence,
      long currentFence,
      ClaimState claimState) {
    return new Snapshot(
        tuple,
        tuple.mutationDigest(),
        Phase.ACCOUNT_AUTHORIZATION,
        state,
        reservationFence,
        currentFence,
        NOW_EPOCH_MILLIS + 30_000L,
        claimState);
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
            new StartSessionOperatorAction.Target(
                17L, UUID.fromString("2a9a4d47-a544-4984-bbc4-8e85f84116e4")),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "unit-test StartSession prerequisite");
    return StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
  }
}
