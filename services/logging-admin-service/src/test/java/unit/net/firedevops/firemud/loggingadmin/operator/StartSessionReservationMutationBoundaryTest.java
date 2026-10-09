package net.firedevops.firemud.loggingadmin.operator;

import static net.firedevops.firemud.loggingadmin.jooq.tables.StartSessionPreAuthorizationReservations.START_SESSION_PRE_AUTHORIZATION_RESERVATIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.loggingadmin.operator.StartSessionPreAuthorizationReservationService.ReadPurpose;
import net.firedevops.firemud.loggingadmin.repository.StartSessionPreAuthorizationReservationRepository;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StartSessionReservationMutationBoundaryTest {
  private static final long NOW_EPOCH_MILLIS = 1_800_000_000_000L;
  private static final UUID TENANT_ID = UUID.fromString("86376245-2491-41c1-b87f-322a486f74d9");
  private static final UUID ACTOR_ID = UUID.fromString("0f11d69b-334d-42de-a844-2d557ba4e9a4");
  private static final UUID TARGET_ID = UUID.fromString("b2800980-9e55-4c62-a07d-d10f53bcfb22");

  @AfterEach
  void clearAuthenticatedContext() {
    SessionContext.clear();
  }

  @Test
  void productionServiceDeniesEveryMutationBeforeRepositoryCallsButLeavesReadsAvailable() {
    StartSessionPreAuthorizationReservationRepository repository =
        mock(StartSessionPreAuthorizationReservationRepository.class);
    StartSessionPreAuthorizationReservationService service =
        new StartSessionPreAuthorizationReservationService(
            repository, Clock.fixed(Instant.ofEpochMilli(NOW_EPOCH_MILLIS), ZoneOffset.UTC));

    assertThatThrownBy(() -> service.acquire(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.acquireAuthorizationRecoveryClaim(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.markAuthorizationPending(null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.renew(null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.expireClaim(null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.completeAuthorization(null, null))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> service.beginOwnerExecution(null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
    verifyNoInteractions(repository);

    StartSessionPreAuthorizationReservationTuple tuple = tuple("passive-service-read");
    when(repository.find(tuple.controlPlaneRequestId())).thenReturn(Optional.empty());
    when(repository.findExact(tuple)).thenReturn(Optional.empty());
    when(repository.findAuthorizedExact(tuple)).thenReturn(Optional.empty());
    when(repository.readCurrentClaim(
            tuple, ACTOR_ID, 1L, ACTOR_ID, 1L, ReadPurpose.ISSUE, NOW_EPOCH_MILLIS))
        .thenReturn(Optional.empty());

    assertThat(service.find(tuple.controlPlaneRequestId())).isEmpty();
    assertThat(service.findExact(tuple)).isEmpty();
    assertThat(service.findAuthorizedExact(tuple)).isEmpty();
    assertThat(
            service.readCurrentClaimEvidence(
                tuple.controlPlaneRequestId(),
                tuple,
                ACTOR_ID,
                1L,
                ACTOR_ID,
                1L,
                ReadPurpose.ISSUE))
        .isEmpty();
    verify(repository).find(tuple.controlPlaneRequestId());
    verify(repository).findExact(tuple);
    verify(repository).findAuthorizedExact(tuple);
    verify(repository)
        .readCurrentClaim(tuple, ACTOR_ID, 1L, ACTOR_ID, 1L, ReadPurpose.ISSUE, NOW_EPOCH_MILLIS);
  }

  @Test
  void productionRepositoryDeniesEveryMutationBeforeDslCallsButLeavesFindAvailable() {
    DSLContext resultDsl = DSL.using(SQLDialect.POSTGRES);
    AtomicInteger executions = new AtomicInteger();
    MockDataProvider provider =
        context -> {
          executions.incrementAndGet();
          return new MockResult[] {
            new MockResult(0, resultDsl.newResult(START_SESSION_PRE_AUTHORIZATION_RESERVATIONS))
          };
        };
    DSLContext dsl = DSL.using(new MockConnection(provider), SQLDialect.POSTGRES);
    StartSessionPreAuthorizationReservationRepository repository =
        new StartSessionPreAuthorizationReservationRepository(dsl);

    assertThatThrownBy(() -> repository.acquire(null, null, 0L, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> repository.acquireRecoveryClaim(null, null, 0L, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> repository.markAuthorizationPending(null, null, null, 0L, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> repository.markAuthorizationPending(null, null, null, 0L, null, 0L, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> repository.renew(null, null, null, 0L, null, null, 0L, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> repository.expireClaim(null, null, null, 0L, null, null, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> repository.completeAuthorization(null, null, null, 0L, null, 0L, null, 0L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> repository.beginOwnerExecution(null, null, null, 0L, null, 0L, null, null, 0L))
        .isInstanceOf(UnsupportedOperationException.class);

    assertThat(executions).hasValue(0);
    StartSessionPreAuthorizationReservationTuple tuple = tuple("passive-repository-read");
    assertThat(repository.find(tuple.controlPlaneRequestId())).isEmpty();
    assertThat(repository.findExact(tuple)).isEmpty();
    assertThat(repository.findAuthorizedExact(tuple)).isEmpty();
    assertThat(
            repository.readCurrentClaim(
                tuple,
                ACTOR_ID,
                1L,
                UUID.fromString("36c9365a-5411-4e20-981c-a5b2a97e224e"),
                2L,
                ReadPurpose.RECOVER,
                NOW_EPOCH_MILLIS))
        .isEmpty();
    assertThat(executions).hasValue(4);
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
            new StartSessionOperatorAction.Target(19L, TARGET_ID),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            "unit-test passive read");
    return StartSessionPreAuthorizationReservationTuple.fromCurrentTenantAdmin(requestId, action);
  }
}
