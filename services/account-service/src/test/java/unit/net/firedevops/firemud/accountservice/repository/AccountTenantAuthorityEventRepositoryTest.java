package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.LongFunction;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountTenantAuthorityEventRepositoryTest {
  private static final String NAMESPACE = "prod";
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID SOURCE_REQUEST_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION_ID =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String SOURCE_REQUEST_DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void finalAdmissionOwnerReadsRequireWritableMandatoryTransactions() throws NoSuchMethodException {
    assertWritableMandatory(
        AccountDemoTenantEntitlementRepository.class, "readCurrent", UUID.class);
    assertWritableMandatory(
        AccountDemoTenantEntitlementRepository.class,
        "revalidate",
        DemoTenantEntitlementSnapshot.class);
    assertWritableMandatory(
        AccountTenantAuthorityEventRepository.class, "readCurrentByTenant", UUID.class);
  }

  @Test
  void appendBindsBillingEventIdAsUuidAndAuthorityEventIdAsVarcharText() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthorityOutboxRepository authorityOutbox = mock(AccountAuthorityOutboxRepository.class);
    AccountTenantEntitlementOutboxRepository billingOutbox =
        mock(AccountTenantEntitlementOutboxRepository.class);
    FreshTenantIdentityAssociationRepository tenantIdentity =
        mock(FreshTenantIdentityAssociationRepository.class);
    AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    Record sourceHead = mock(Record.class);
    when(sourceHead.get("current_generation", Long.class)).thenReturn(1L);
    when(sourceHead.get("current_source_version", Long.class)).thenReturn(1L);
    when(sourceHead.get("last_outbox_sequence", Long.class)).thenReturn(0L);
    when(dsl.fetchOne(anyString(), any(Object[].class))).thenReturn(sourceHead);
    when(dsl.execute(anyString(), any(Object[].class))).thenReturn(1);

    FreshTenantCreationEvidence source = freshTenantEvidence();
    DemoTenantEntitlementRequest request =
        new DemoTenantEntitlementRequest(
            REQUEST_ID,
            TENANT_ID,
            SOURCE_REQUEST_ID,
            SOURCE_REQUEST_DIGEST,
            null,
            null,
            null,
            true,
            false,
            false,
            true,
            new DemoTenantEntitlementRequest.Quotas(3L, 2L, 4096L));
    DemoTenantEntitlementEventV1Codec.Event billingEvent =
        DemoTenantEntitlementEventV1Codec.seal(request, source, 1L, 2L, 2L, 1L);
    ScopeState nextAuthority = new ScopeState(AuthorityScope.tenant(TENANT_ID), 2L, 2L, null);
    String streamKey = TenantAuthorityEventV1Codec.streamKey(TENANT_ID);

    doAnswer(
            invocation -> {
              LongFunction<AccountAuthorityOutboxRepository.EventEvidence> evidenceFactory =
                  invocation.getArgument(2);
              AccountAuthorityOutboxRepository.EventEvidence evidence = evidenceFactory.apply(1L);
              return new AccountAuthorityOutboxRepository.Event(
                  streamKey,
                  REQUEST_ID.toString(),
                  1L,
                  evidence.eventId(),
                  evidence.eventDigest(),
                  evidence.payload());
            })
        .when(authorityOutbox)
        .append(eq(streamKey), eq(REQUEST_ID.toString()), any());

    AccountTenantAuthorityEventRepository repository =
        new AccountTenantAuthorityEventRepository(
            dsl, authorityOutbox, billingOutbox, tenantIdentity, generations);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      TenantAuthorityEventV1Codec.Event appended =
          repository.append(request, source, nextAuthority, billingEvent);

      assertThat(appended.tenantBillingEventId()).isEqualTo(billingEvent.eventId());
      assertThat(TenantAuthorityEventV1Codec.verify(appended.payload()).tenantBillingEventId())
          .isEqualTo(billingEvent.eventId());

      ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
      ArgumentCaptor<Object[]> updateBindings = ArgumentCaptor.forClass(Object[].class);
      verify(dsl).execute(updateSql.capture(), updateBindings.capture());
      assertThat(updateSql.getValue())
          .contains("tenant_billing_sequence = ?, tenant_billing_event_id = ?");
      Object[] bindings = updateBindings.getValue();
      assertThat(bindings).hasSize(14);
      assertThat(bindings[5])
          .isExactlyInstanceOf(String.class)
          .isEqualTo(appended.eventId().toString());
      assertThat(bindings[8]).isExactlyInstanceOf(UUID.class).isEqualTo(billingEvent.eventId());
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private static FreshTenantCreationEvidence freshTenantEvidence() {
    String sourceTenantKey = "tenant-source-44444444";
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            NAMESPACE,
            SOURCE_REQUEST_ID,
            SOURCE_OPERATION_ID,
            SOURCE_REQUEST_DIGEST,
            TENANT_ID,
            7001L,
            sourceTenantKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        NAMESPACE,
        SOURCE_REQUEST_ID,
        SOURCE_OPERATION_ID,
        SOURCE_REQUEST_DIGEST,
        TENANT_ID,
        7001L,
        sourceTenantKey,
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static void assertWritableMandatory(
      Class<?> repositoryType, String methodName, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Method method = repositoryType.getMethod(methodName, parameterTypes);
    Transactional transaction = method.getAnnotation(Transactional.class);

    assertThat(transaction).isNotNull();
    assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(transaction.readOnly()).isFalse();
  }
}
