package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiPrimaryIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiSignerOwner;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountControlUiIssuanceRepositoryTest {
  private static final String LINEARIZATION_QUERY =
      "SELECT pg_current_xact_id()::TEXT AS xid, "
          + "nextval('account_control_ui_source_fence') AS fence";
  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID TENANT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void prepareReusesOwnerLinearizationAndAllocatesItsFenceExactlyOnce() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    Record linearizationRow = mock(Record.class);
    when(dsl.fetchOne(LINEARIZATION_QUERY)).thenReturn(linearizationRow);
    when(linearizationRow.get("xid", String.class)).thenReturn("123456");
    when(linearizationRow.get("fence", Long.class)).thenReturn(99L);
    var repository = spy(new AccountControlUiIssuanceRepository(dsl));
    AccountControlUiAuthority.Snapshot source = mock(AccountControlUiAuthority.Snapshot.class);
    ControlUiPrimaryIdentity authenticated = mock(ControlUiPrimaryIdentity.class);
    when(authenticated.accountId()).thenReturn(ACTOR_ID);
    when(source.actor()).thenReturn(ACTOR_ID);
    when(source.tenant()).thenReturn(TENANT_ID);
    when(source.authorityTuple())
        .thenReturn(
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                1L,
                "tenantAuthorityGeneration",
                Map.of(TENANT_ID.toString(), 1L),
                "membershipAuthorityGeneration",
                Map.of(TENANT_ID.toString(), 1L),
                "privateRealmGrantVersions",
                List.of()));
    when(source.membershipVersion()).thenReturn(Map.of(TENANT_ID.toString(), 1L));
    when(source.issuanceFence()).thenReturn(1L);
    when(source.evidence()).thenReturn(new byte[] {1});
    when(source.sources()).thenReturn(List.of());
    when(source.accountIdentitySource()).thenReturn(Map.of("accountId", ACTOR_ID.toString()));
    when(source.outboxCheckpoints()).thenReturn(List.of());
    TransactionSynchronizationManager.setActualTransactionActive(true);

    UUID requestId = UUID.fromString("30000000-0000-4000-8000-000000000003");
    UUID callerContextId = UUID.fromString("40000000-0000-4000-8000-000000000004");
    var signer = mock(AccountControlUiSignerOwner.Capture.class);
    var prepare =
        AccountControlUiIssuanceRepository.class.getDeclaredMethod(
            "prepare",
            UUID.class,
            String.class,
            UUID.class,
            String.class,
            String.class,
            ControlUiPrimaryIdentity.class,
            AccountControlUiAuthority.Snapshot.class,
            AccountControlUiSignerOwner.Capture.class,
            Instant.class,
            long.class);
    prepare.setAccessible(true);
    assertThatThrownBy(
            () ->
                prepare.invoke(
                    repository,
                    requestId,
                    "spiffe://firemud/ns/test/sa/logging-admin-service",
                    callerContextId,
                    "test-mac-key",
                    "a".repeat(64),
                    authenticated,
                    source,
                    signer,
                    Instant.parse("2026-10-08T11:00:00Z"),
                    300_000L))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(IllegalStateException.class);

    verify(repository, times(1)).captureOwnerLinearization();
    verify(dsl, times(1)).fetchOne(LINEARIZATION_QUERY);
  }

  @Test
  void cleanupDeadlineUsesCheckedPerIssuanceMarginAndAllowsExplicitZero() throws Exception {
    long tokenExpiryMillis = Instant.parse("2035-04-05T06:12:08Z").toEpochMilli();

    assertThat(checkedCleanupDeadlineMillis(tokenExpiryMillis, 0L)).isEqualTo(tokenExpiryMillis);
    assertThat(checkedCleanupDeadlineMillis(tokenExpiryMillis, 300_000L))
        .isEqualTo(tokenExpiryMillis + 300_000L);
    assertThatThrownBy(() -> checkedCleanupDeadlineMillis(tokenExpiryMillis, -1L))
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> checkedCleanupDeadlineMillis(maxRedisSafeInteger(), 1L))
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void retainedLegacyIssuanceWithoutCleanupDeadlineCannotBeReinterpreted() throws Exception {
    Record row = mock(Record.class);
    when(row.get(anyString(), any(Class.class)))
        .thenAnswer(
            call ->
                switch (call.getArgument(0, String.class)) {
                  case "issued_at_epoch_second" -> 1_800_000_000L;
                  case "expires_at_epoch_second" -> 1_800_000_300L;
                  case "recovery_expires_at" -> OffsetDateTime.parse("2027-01-15T08:01:00Z");
                  default -> null;
                });
    var retained = stored(row);

    assertThatThrownBy(() -> storedExpiryMillis(retained))
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void committedCurrentSourceReadDeniesMissingLegacyCleanupDeadline() {
    DSLContext dsl = mock(DSLContext.class);
    Record row = mock(Record.class);
    Map<String, Object> values = new HashMap<>();
    values.put("request_id", UUID.fromString("30000000-0000-4000-8000-000000000003"));
    values.put("operation_id", UUID.fromString("40000000-0000-4000-8000-000000000004"));
    values.put("token_jti", UUID.fromString("50000000-0000-4000-8000-000000000005"));
    values.put("account_uuid", UUID.fromString("10000000-0000-4000-8000-000000000001"));
    values.put("tenant_uuid", UUID.fromString("20000000-0000-4000-8000-000000000002"));
    values.put("caller_workload", "test-only-caller");
    values.put("caller_context_id", UUID.fromString("60000000-0000-4000-8000-000000000006"));
    values.put("request_digest", "a".repeat(64));
    for (String field : List.of("claims_payload", "source_payload", "bundle_payload")) {
      values.put(field, new byte[] {1});
    }
    values.put("signer_receipt", new byte[] {1});
    values.put("issued_at_epoch_second", 1_800_000_000L);
    values.put("expires_at_epoch_second", 1_800_000_300L);
    values.put("registry_cleanup_deadline_epoch_millis", null);
    values.put("recovery_expires_at", OffsetDateTime.parse("2027-01-15T08:01:00Z"));
    values.put("status", "COMMITTED");
    values.put("token_hash", "b".repeat(64));
    values.put("pending_registry", new byte[] {1});
    values.put("active_registry", new byte[] {1});
    values.put("pending_receipt", new byte[] {1});
    values.put("committed_at", OffsetDateTime.parse("2027-01-15T08:00:10Z"));
    values.put("recovery_failed_at", null);
    when(row.get(anyString(), any(Class.class)))
        .thenAnswer(call -> values.get(call.getArgument(0, String.class)));
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThatThrownBy(
            () ->
                new AccountControlUiCommittedIssuanceRepository(dsl).readCommitted("b".repeat(64)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static long checkedCleanupDeadlineMillis(long tokenExpiryMillis, long marginMillis)
      throws Exception {
    Method method =
        AccountControlUiIssuanceRepository.class.getDeclaredMethod(
            "checkedCleanupDeadlineMillis", long.class, long.class);
    method.setAccessible(true);
    return (long) method.invoke(null, tokenExpiryMillis, marginMillis);
  }

  private static long maxRedisSafeInteger() throws Exception {
    var field = AccountControlUiIssuanceRepository.class.getDeclaredField("MAX_REDIS_SAFE_INTEGER");
    field.setAccessible(true);
    return field.getLong(null);
  }

  private static Object stored(Record row) throws Exception {
    Class<?> storedClass =
        java.util.Arrays.stream(AccountControlUiIssuanceRepository.class.getDeclaredClasses())
            .filter(nested -> nested.getSimpleName().equals("Stored"))
            .findFirst()
            .orElseThrow();
    var constructor = storedClass.getDeclaredConstructor(Record.class);
    constructor.setAccessible(true);
    return constructor.newInstance(row);
  }

  private static long storedExpiryMillis(Object stored) throws Exception {
    Method method = stored.getClass().getDeclaredMethod("expiryMillis");
    method.setAccessible(true);
    return (long) method.invoke(stored);
  }
}
