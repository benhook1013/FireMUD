package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec.Event;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.service.DemoTenantEntitlementException;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantEntitlementOutboxRepositoryTest {
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String SOURCE_REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactReplayReadsRequestIdFromTheSelectedJooqProjectionWithoutMutation() {
    DSLContext dsl = mock(DSLContext.class);
    Event committed = event(true);
    doReturn(streamProjection(1L))
        .doReturn(eventProjection(committed))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    AccountTenantEntitlementOutboxRepository repository =
        new AccountTenantEntitlementOutboxRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    AtomicInteger factoryCalls = new AtomicInteger();

    Event replay =
        repository.append(
            TENANT_ID,
            REQUEST_ID,
            sequence -> {
              factoryCalls.incrementAndGet();
              assertThat(sequence).isEqualTo(1L);
              return committed;
            });

    assertThat(replay.requestId()).isEqualTo(committed.requestId());
    assertThat(replay.eventId()).isEqualTo(committed.eventId());
    assertThat(replay.eventDigest()).isEqualTo(committed.eventDigest());
    assertThat(replay.payload()).containsExactly(committed.payload());
    assertThat(factoryCalls).hasValue(1);
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(dsl, times(2)).fetchOne(sql.capture(), any(Object[].class));
    assertThat(sql.getAllValues().get(1)).contains("request_id");
    verify(dsl, never())
        .fetchOne(org.mockito.ArgumentMatchers.startsWith("UPDATE "), any(Object[].class));
    verify(dsl, never())
        .execute(
            org.mockito.ArgumentMatchers.startsWith(
                "INSERT INTO account_tenant_entitlement_outbox_events"),
            any(Object[].class));
  }

  @Test
  void exactRequestReplayWithChangedPayloadRemainsAnIdempotencyConflict() {
    DSLContext dsl = mock(DSLContext.class);
    Event committed = event(true);
    doReturn(streamProjection(1L))
        .doReturn(eventProjection(committed))
        .when(dsl)
        .fetchOne(anyString(), any(Object[].class));
    AccountTenantEntitlementOutboxRepository repository =
        new AccountTenantEntitlementOutboxRepository(dsl);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(() -> repository.append(TENANT_ID, REQUEST_ID, sequence -> event(false)))
        .isInstanceOfSatisfying(
            DemoTenantEntitlementException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo(DemoTenantEntitlementException.Code.IDEMPOTENCY_CONFLICT));
    verify(dsl, never())
        .fetchOne(org.mockito.ArgumentMatchers.startsWith("UPDATE "), any(Object[].class));
    verify(dsl, never())
        .execute(
            org.mockito.ArgumentMatchers.startsWith(
                "INSERT INTO account_tenant_entitlement_outbox_events"),
            any(Object[].class));
  }

  private static Event event(boolean gameplayAvailable) {
    FreshTenantCreationEvidence source = sourceEvidence();
    DemoTenantEntitlementRequest request =
        new DemoTenantEntitlementRequest(
            REQUEST_ID,
            TENANT_ID,
            SOURCE_REQUEST_ID,
            SOURCE_REQUEST_DIGEST,
            null,
            null,
            null,
            gameplayAvailable,
            false,
            false,
            true,
            new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
    return DemoTenantEntitlementEventV1Codec.seal(request, source, 1L, 2L, 2L, 1L);
  }

  private static FreshTenantCreationEvidence sourceEvidence() {
    String namespace = "prod";
    String sourceTenantKey = "tenant-source-44444444";
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            namespace,
            SOURCE_REQUEST_ID,
            SOURCE_OPERATION_ID,
            SOURCE_REQUEST_DIGEST,
            TENANT_ID,
            7001L,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        namespace,
        SOURCE_REQUEST_ID,
        SOURCE_OPERATION_ID,
        SOURCE_REQUEST_DIGEST,
        TENANT_ID,
        7001L,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static Record streamProjection(long sequence) {
    var field = DSL.field("last_sequence", Long.class);
    Record projection = DSL.using(SQLDialect.POSTGRES).newRecord(field);
    projection.set(field, sequence);
    return projection;
  }

  private static Record eventProjection(Event event) {
    var sequence = DSL.field("tenant_billing_sequence", Long.class);
    var requestId = DSL.field("request_id", UUID.class);
    var eventId = DSL.field("event_id", UUID.class);
    var digest = DSL.field("event_digest", String.class);
    var payload = DSL.field("payload", byte[].class);
    Record projection =
        DSL.using(SQLDialect.POSTGRES).newRecord(sequence, requestId, eventId, digest, payload);
    projection.set(sequence, event.tenantBillingSequence());
    projection.set(requestId, event.requestId());
    projection.set(eventId, event.eventId());
    projection.set(digest, event.eventDigest());
    projection.set(payload, event.payload());
    return projection;
  }
}
