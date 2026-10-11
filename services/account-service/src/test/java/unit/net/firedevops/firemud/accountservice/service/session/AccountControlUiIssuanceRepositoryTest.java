package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
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
            Instant.class);
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
                    Instant.parse("2026-10-08T11:00:00Z")))
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(IllegalStateException.class);

    verify(repository, times(1)).captureOwnerLinearization();
    verify(dsl, times(1)).fetchOne(LINEARIZATION_QUERY);
  }
}
