package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl.ControlUiPrimaryIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiIssuanceRepository;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiSignerOwner;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class AccountControlUiIssuanceRepositoryTest {
  private static final String LINEARIZATION_QUERY =
      "SELECT pg_current_xact_id()::TEXT AS xid, "
          + "nextval('account_control_ui_source_fence') AS fence";
  private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID TENANT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    when(source.scopeKind()).thenReturn(AccountControlUiAuthority.ScopeKind.TENANT);
    when(source.optionalTenantId()).thenReturn(Optional.of(TENANT_ID));
    when(source.tenant()).thenReturn(TENANT_ID);
    when(source.scopedRoles()).thenReturn(Map.of(TENANT_ID.toString(), List.of("tenantAdmin")));
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
                  case "authorization_scope" -> "TENANT";
                  case "tenant_uuid" -> TENANT_ID;
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
    values.put("authorization_scope", "TENANT");
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

  @Test
  void accountPreparationPersistsClosedAccountScopeAndGlobalRoleEvidence() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    Record linearizationRow = mock(Record.class);
    when(dsl.fetchOne(LINEARIZATION_QUERY)).thenReturn(linearizationRow);
    when(linearizationRow.get("xid", String.class)).thenReturn("123456");
    when(linearizationRow.get("fence", Long.class)).thenReturn(99L);
    Map<String, Object> storedValues = new HashMap<>();
    Record issuanceRow = mock(Record.class);
    when(issuanceRow.get(anyString(), any(Class.class)))
        .thenAnswer(call -> storedValues.get(call.getArgument(0, String.class)));
    when(dsl.execute(
            startsWith("INSERT INTO account_control_ui_issuance_operations"), any(Object[].class)))
        .thenAnswer(
            call -> {
              Object[] args = (Object[]) call.getRawArguments()[1];
              storedValues.put("request_id", args[0]);
              storedValues.put("operation_id", args[1]);
              storedValues.put("token_jti", args[2]);
              storedValues.put("account_uuid", args[3]);
              storedValues.put("tenant_uuid", args[4]);
              storedValues.put("authorization_scope", args[5]);
              storedValues.put("caller_workload", args[6]);
              storedValues.put("caller_context_id", args[7]);
              storedValues.put("request_mac_key_id", args[8]);
              storedValues.put("request_digest", args[9]);
              storedValues.put("claims_payload", args[10]);
              storedValues.put("source_payload", args[11]);
              storedValues.put("bundle_payload", args[12]);
              storedValues.put("signer_receipt", args[13]);
              storedValues.put("issued_at_epoch_second", args[14]);
              storedValues.put("expires_at_epoch_second", args[15]);
              storedValues.put("registry_cleanup_deadline_epoch_millis", args[16]);
              storedValues.put("recovery_expires_at", args[17]);
              storedValues.put("status", "PREPARED");
              return 1;
            });
    when(dsl.fetchOne(
            startsWith("SELECT * FROM account_control_ui_issuance_operations WHERE request_id = ?"),
            any(Object[].class)))
        .thenReturn(issuanceRow);

    AccountControlUiAuthority.Snapshot source = mock(AccountControlUiAuthority.Snapshot.class);
    when(source.actor()).thenReturn(ACTOR_ID);
    when(source.scopeKind()).thenReturn(AccountControlUiAuthority.ScopeKind.ACCOUNT);
    when(source.optionalTenantId()).thenReturn(Optional.empty());
    when(source.authorityTuple())
        .thenReturn(
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                2L,
                "tenantAuthorityGeneration",
                Map.of(),
                "membershipAuthorityGeneration",
                Map.of(),
                "privateRealmGrantVersions",
                List.of()));
    when(source.membershipVersion()).thenReturn(Map.of());
    when(source.issuanceFence()).thenReturn(3L);
    when(source.scopedRoles()).thenReturn(Map.of());
    when(source.globalRoles()).thenReturn(List.of("platformAdmin"));
    when(source.evidence()).thenReturn(new byte[] {1, 2, 3});
    when(source.sources())
        .thenReturn(
            List.of(
                new SourceEvidence(
                    SourceKind.ISSUER,
                    "firemud-account-service",
                    "1",
                    "2",
                    null,
                    null,
                    new byte[] {4}),
                new SourceEvidence(
                    SourceKind.ACCOUNT, ACTOR_ID.toString(), "3", "4", null, null, new byte[] {5}),
                new SourceEvidence(
                    SourceKind.GLOBAL_ROLES,
                    ACTOR_ID.toString(),
                    "3",
                    "5",
                    null,
                    null,
                    new byte[] {6})));
    when(source.accountIdentitySource()).thenReturn(Map.of("accountId", ACTOR_ID.toString()));
    when(source.outboxCheckpoints()).thenReturn(List.of());
    ControlUiPrimaryIdentity authenticated = mock(ControlUiPrimaryIdentity.class);
    when(authenticated.accountId()).thenReturn(ACTOR_ID);
    AccountControlUiSignerOwner.Capture signer = mock(AccountControlUiSignerOwner.Capture.class);
    when(signer.kid()).thenReturn("test-kid");
    when(signer.generation()).thenReturn("7");
    when(signer.receipt()).thenReturn(new byte[] {9});
    TransactionSynchronizationManager.setActualTransactionActive(true);

    UUID requestId = UUID.fromString("30000000-0000-4000-8000-000000000003");
    UUID callerContextId = UUID.fromString("40000000-0000-4000-8000-000000000004");
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
    prepare.invoke(
        new AccountControlUiIssuanceRepository(dsl),
        requestId,
        "spiffe://firemud/ns/test/sa/logging-admin-service",
        callerContextId,
        "test-mac-key",
        "a".repeat(64),
        authenticated,
        source,
        signer,
        Instant.parse("2026-10-08T11:00:00Z"),
        300_000L);

    assertThat(storedValues.get("tenant_uuid")).isNull();
    assertThat(storedValues.get("authorization_scope")).isEqualTo("ACCOUNT");
    Map<String, Object> bundle =
        JSON.readValue((byte[]) storedValues.get("bundle_payload"), new TypeReference<>() {});
    assertThat(bundle)
        .containsEntry(
            "scope",
            Map.of("kind", "ACCOUNT", "accountId", ACTOR_ID.toString(), "tenantIds", List.of()));
    @SuppressWarnings("unchecked")
    Map<String, Object> authorityTuple = (Map<String, Object>) bundle.get("authorityTuple");
    assertThat(authorityTuple)
        .containsEntry("tenantAuthorityGeneration", Map.of())
        .containsEntry("membershipAuthorityGeneration", Map.of());
    assertThat(bundle.get("membershipVersion")).isEqualTo(Map.of());
    assertThat(bundle.get("accountIdentitySource")).isEqualTo(source.accountIdentitySource());
    assertThat(bundle.get("authoritySourceVersions"))
        .isEqualTo(
            Map.of(
                "ISSUER:firemud-account-service",
                "2",
                "ACCOUNT:" + ACTOR_ID,
                "4",
                "GLOBAL_ROLES:" + ACTOR_ID,
                "5"));
    Map<String, Object> claims =
        JSON.readValue((byte[]) storedValues.get("claims_payload"), new TypeReference<>() {});
    assertThat(claims)
        .containsEntry("scopedRoles", Map.of())
        .containsEntry("globalRoles", List.of("platformAdmin"));
    assertThat(bundle.get("roleEvidence"))
        .isEqualTo(
            Map.of(
                "scopedRoles",
                Map.of(),
                "freshnessSource",
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"));
  }

  @Test
  void bothStoredMappersDecodeClosedScopeAndRejectTenantShapeMismatch() throws Exception {
    Object account = stored(storedRow("ACCOUNT", null));
    assertThat(storedScopeKind(account)).isEqualTo(AccountControlUiAuthority.ScopeKind.ACCOUNT);
    assertThat(storedOptionalTenant(account)).isEqualTo(Optional.empty());
    assertThatThrownBy(() -> storedTenant(account)).hasCauseInstanceOf(IllegalStateException.class);

    Object tenant = stored(storedRow("TENANT", TENANT_ID));
    assertThat(storedScopeKind(tenant)).isEqualTo(AccountControlUiAuthority.ScopeKind.TENANT);
    assertThat(storedOptionalTenant(tenant)).isEqualTo(Optional.of(TENANT_ID));
    assertThat(storedTenant(tenant)).isEqualTo(TENANT_ID);
    assertThatThrownBy(() -> stored(storedRow("OTHER", TENANT_ID)))
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> stored(storedRow("ACCOUNT", TENANT_ID)))
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> stored(storedRow("TENANT", null)))
        .hasCauseInstanceOf(IllegalStateException.class);

    DSLContext dsl = mock(DSLContext.class);
    Record accountRow = committedRow("ACCOUNT", null, 1_800_000_300_000L);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(accountRow);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var accountEvidence =
        new AccountControlUiCommittedIssuanceRepository(dsl)
            .readCommitted("b".repeat(64))
            .orElseThrow();
    assertThat(accountEvidence.scopeKind()).isEqualTo(AccountControlUiAuthority.ScopeKind.ACCOUNT);
    assertThat(accountEvidence.optionalTenantId()).isEmpty();
    assertThatThrownBy(accountEvidence::tenantId).isInstanceOf(IllegalStateException.class);

    DSLContext tenantDsl = mock(DSLContext.class);
    Record tenantRow = committedRow("TENANT", TENANT_ID, 1_800_000_300_000L);
    when(tenantDsl.fetchOne(anyString(), any(Object[].class))).thenReturn(tenantRow);
    var tenantEvidence =
        new AccountControlUiCommittedIssuanceRepository(tenantDsl)
            .readCommitted("b".repeat(64))
            .orElseThrow();
    assertThat(tenantEvidence.scopeKind()).isEqualTo(AccountControlUiAuthority.ScopeKind.TENANT);
    assertThat(tenantEvidence.optionalTenantId()).isEqualTo(Optional.of(TENANT_ID));
    assertThat(tenantEvidence.tenantId()).isEqualTo(TENANT_ID);

    assertCommittedMapperRejects("ACCOUNT", TENANT_ID);
    assertCommittedMapperRejects("TENANT", null);
    assertCommittedMapperRejects("OTHER", TENANT_ID);
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

  private static Record storedRow(String scopeKind, UUID tenantId) {
    Record row = mock(Record.class);
    Map<String, Object> values = new HashMap<>();
    values.put("authorization_scope", scopeKind);
    values.put("tenant_uuid", tenantId);
    values.put("issued_at_epoch_second", 1_800_000_000L);
    values.put("expires_at_epoch_second", 1_800_000_300L);
    values.put("recovery_expires_at", OffsetDateTime.parse("2027-01-15T08:01:00Z"));
    when(row.get(anyString(), any(Class.class)))
        .thenAnswer(call -> values.get(call.getArgument(0, String.class)));
    return row;
  }

  private static AccountControlUiAuthority.ScopeKind storedScopeKind(Object stored)
      throws Exception {
    Method method = stored.getClass().getDeclaredMethod("scopeKind");
    method.setAccessible(true);
    return (AccountControlUiAuthority.ScopeKind) method.invoke(stored);
  }

  @SuppressWarnings("unchecked")
  private static Optional<UUID> storedOptionalTenant(Object stored) throws Exception {
    Method method = stored.getClass().getDeclaredMethod("optionalTenantId");
    method.setAccessible(true);
    return (Optional<UUID>) method.invoke(stored);
  }

  private static UUID storedTenant(Object stored) throws Exception {
    Method method = stored.getClass().getDeclaredMethod("tenantId");
    method.setAccessible(true);
    return (UUID) method.invoke(stored);
  }

  private static Record committedRow(String scopeKind, UUID tenantId, long cleanupDeadline) {
    Record row = mock(Record.class);
    Map<String, Object> values = new HashMap<>();
    values.put("request_id", UUID.fromString("30000000-0000-4000-8000-000000000003"));
    values.put("operation_id", UUID.fromString("40000000-0000-4000-8000-000000000004"));
    values.put("token_jti", UUID.fromString("50000000-0000-4000-8000-000000000005"));
    values.put("account_uuid", ACTOR_ID);
    values.put("tenant_uuid", tenantId);
    values.put("authorization_scope", scopeKind);
    values.put("caller_workload", "test-only-caller");
    values.put("caller_context_id", UUID.fromString("60000000-0000-4000-8000-000000000006"));
    values.put("request_digest", "a".repeat(64));
    for (String field : List.of("claims_payload", "source_payload", "bundle_payload")) {
      values.put(field, new byte[] {1});
    }
    values.put("signer_receipt", new byte[] {1});
    values.put("issued_at_epoch_second", 1_800_000_000L);
    values.put("expires_at_epoch_second", 1_800_000_300L);
    values.put("registry_cleanup_deadline_epoch_millis", cleanupDeadline);
    values.put("status", "COMMITTED");
    values.put("token_hash", "b".repeat(64));
    values.put("pending_registry", new byte[] {1});
    values.put("active_registry", new byte[] {1});
    values.put("pending_receipt", new byte[] {1});
    values.put("committed_at", OffsetDateTime.parse("2027-01-15T08:00:10Z"));
    values.put("recovery_failed_at", null);
    when(row.get(anyString(), any(Class.class)))
        .thenAnswer(call -> values.get(call.getArgument(0, String.class)));
    return row;
  }

  private static void assertCommittedMapperRejects(String scopeKind, UUID tenantId) {
    DSLContext dsl = mock(DSLContext.class);
    Record row = committedRow(scopeKind, tenantId, 1_800_000_300_000L);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(row);
    assertThatThrownBy(
            () ->
                new AccountControlUiCommittedIssuanceRepository(dsl).readCommitted("b".repeat(64)))
        .isInstanceOf(IllegalStateException.class);
  }

  private static long storedExpiryMillis(Object stored) throws Exception {
    Method method = stored.getClass().getDeclaredMethod("expiryMillis");
    method.setAccessible(true);
    return (long) method.invoke(stored);
  }
}
