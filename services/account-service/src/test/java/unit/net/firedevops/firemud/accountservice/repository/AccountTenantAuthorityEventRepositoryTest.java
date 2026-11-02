package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

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

  @Test
  void genericCurrentReadReturnsTheExactSelectedFamilyCheckpointOnce() throws Exception {
    for (var kind : AccountTenantAuthorityEventRepository.SourceKind.values()) {
      SelectionFixture f = new SelectionFixture(true);
      doReturn(new AccountTenantAuthorityEventRepository.SourceSelection(kind, f.authority))
          .when(f.repository)
          .readCurrentSourceSelection(TENANT_ID);

      assertThat(f.repository.readCurrentByTenant(TENANT_ID)).isSameAs(f.authority);

      verify(f.repository).readCurrentSourceSelection(TENANT_ID);
      verifyNoInteractions(
          f.dsl, f.billingOutbox, f.authorityOutbox, f.generations, f.tenantIdentity);
    }
  }

  @Test
  void actualPaidSourceSelectionUsesHostingReceiptWithoutEnteringTheDemoCurrentReader()
      throws Exception {
    SelectionFixture f = new SelectionFixture(true);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      var selected = f.repository.readCurrentByTenant(TENANT_ID);

      assertThat(selected).isSameAs(f.authority);
      verify(f.repository).readHostingCheckpoint(TENANT_ID, REQUEST_ID);
      verify(f.billingOutbox, never()).readEvent(any(), org.mockito.ArgumentMatchers.anyLong());
      verifyNoInteractions(f.tenantIdentity, f.authorityOutbox);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void demoSelectionEntersItsPrivateCurrentReaderWithoutRecursingThroughGenericDispatch()
      throws Exception {
    SelectionFixture f = new SelectionFixture(false);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      // This controlled graph deliberately omits the fresh association. The DEMO private reader
      // must reach that exact Account SQL guard, rather than re-entering the generic selector.
      assertThatThrownBy(() -> f.repository.readCurrentByTenant(TENANT_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Exact fresh Account tenant source association is unavailable");
      verify(f.repository).readCurrentSourceSelection(TENANT_ID);
      verify(f.repository, never()).readHostingCheckpoint(any(), any());
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  @Test
  void paidSelectorStillRejectsChangedAuthorityHeadOrCommittedReceipt() throws Exception {
    for (boolean changedReceipt : new boolean[] {false, true}) {
      SelectionFixture f = new SelectionFixture(true);
      if (changedReceipt) {
        doReturn("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .when(f.receipt)
            .get("receipt_payload", byte[].class);
      } else {
        doReturn("sha256:" + "f".repeat(64)).when(f.head).get("last_event_digest", String.class);
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> f.repository.readCurrentByTenant(TENANT_ID))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.clear();
      }
    }
  }

  /** Controlled ledger/hosting-checkpoint inputs; not paid-table or lifecycle-owner proof. */
  private static final class SelectionFixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final AccountAuthorityOutboxRepository authorityOutbox =
        mock(AccountAuthorityOutboxRepository.class);
    private final AccountTenantEntitlementOutboxRepository billingOutbox =
        mock(AccountTenantEntitlementOutboxRepository.class);
    private final FreshTenantIdentityAssociationRepository tenantIdentity =
        mock(FreshTenantIdentityAssociationRepository.class);
    private final AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    private final AccountTenantAuthorityEventRepository repository =
        spy(
            new AccountTenantAuthorityEventRepository(
                dsl, authorityOutbox, billingOutbox, tenantIdentity, generations));
    private final TenantAuthorityEventV1Codec.Event authority;
    private final Record head;
    private final Record receipt;

    private SelectionFixture(boolean hosting) throws Exception {
      var source = freshTenantEvidence();
      var request =
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
      var demo = DemoTenantEntitlementEventV1Codec.seal(request, source, 1L, 2L, 2L, 1L);
      String requestDigest = demo.requestDigest();
      UUID billingEventId = TenantAuthorityEventV1Codec.tenantBillingEventId(REQUEST_ID);
      var json = JsonMapper.builder().build();
      Map<String, Object> receiptFields =
          Map.of(
              "schemaVersion",
              "account-hosting-current-receipt/v1",
              "tenantId",
              TENANT_ID.toString(),
              "requestId",
              REQUEST_ID.toString(),
              "requestDigest",
              requestDigest,
              "hardCutoff",
              false);
      byte[] receiptBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(json.writeValueAsString(receiptFields));
      byte[] payload;
      String digest;
      if (hosting) {
        // Exact public serialized profile. The dedicated repository-package codec tests use the
        // actual hosting writer; this unit only controls the committed source-selection ledger.
        Map<String, Object> fields =
            Map.of(
                "schemaVersion",
                "account-hosting-subscription-event/v1",
                "eventType",
                "SubscriptionStatusChanged",
                "tenantBillingStateChanged",
                false,
                "eventTypes",
                List.of("SubscriptionStatusChanged"),
                "tenantId",
                TENANT_ID.toString(),
                "requestId",
                REQUEST_ID.toString(),
                "requestDigest",
                requestDigest,
                "tenantBillingSequence",
                "1",
                "eventId",
                billingEventId.toString(),
                "receipt",
                receiptFields);
        payload = Rfc8785CanonicalJson.canonicalizeUtf8(json.writeValueAsString(fields));
        digest =
            "sha256:"
                + java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload));
      } else {
        payload = demo.payload();
        digest = demo.eventDigest();
        billingEventId = demo.eventId();
      }
      authority =
          TenantAuthorityEventV1Codec.seal(
              new TenantAuthorityEventV1Codec.AuthorityRequest(
                  REQUEST_ID, requestDigest, TENANT_ID, SOURCE_REQUEST_ID, SOURCE_REQUEST_DIGEST),
              source,
              2L,
              2L,
              1L,
              new TenantAuthorityEventV1Codec.BillingReceipt(
                  TENANT_ID,
                  REQUEST_ID,
                  requestDigest,
                  AccountTenantEntitlementOutboxRepository.streamKey(TENANT_ID),
                  1L,
                  billingEventId,
                  digest));
      head =
          projection(
              Map.of(
                  "tenant_billing_sequence",
                  1L,
                  "last_request_id",
                  REQUEST_ID,
                  "tenant_billing_event_id",
                  billingEventId,
                  "tenant_billing_event_digest",
                  digest,
                  "last_request_digest",
                  requestDigest,
                  "current_generation",
                  2L,
                  "current_source_version",
                  2L,
                  "last_outbox_sequence",
                  1L,
                  "last_event_id",
                  authority.eventId().toString(),
                  "last_event_digest",
                  authority.eventDigest()));
      Record billing =
          projection(
              Map.of(
                  "last_sequence",
                  1L,
                  "tenant_billing_sequence",
                  1L,
                  "request_id",
                  REQUEST_ID,
                  "event_id",
                  billingEventId,
                  "event_digest",
                  digest,
                  "payload",
                  payload));
      receipt = projection(Map.of("receipt_payload", receiptBytes));
      when(generations.read(AuthorityScope.tenant(TENANT_ID)))
          .thenReturn(new ScopeState(AuthorityScope.tenant(TENANT_ID), 2L, 2L, null));
      if (hosting)
        doReturn(authority).when(repository).readHostingCheckpoint(TENANT_ID, REQUEST_ID);
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0, String.class);
                if (sql.contains("FROM account_tenant_authority_source_records")) return head;
                if (sql.contains("JOIN account_tenant_entitlement_outbox_streams")) return billing;
                if (sql.contains("SELECT receipt_payload FROM subscription_lifecycle_receipts"))
                  return receipt;
                if (sql.contains(
                    "SELECT canonical_tenant_id FROM account_fresh_tenant_identity_associations"))
                  return null;
                throw new AssertionError("Unexpected source-selection SQL: " + sql);
              });
    }
  }

  @SuppressWarnings("unchecked")
  private static Record projection(Map<String, Object> fields) {
    Record row = mock(Record.class);
    doAnswer(
            invocation ->
                invocation
                    .getArgument(1, Class.class)
                    .cast(fields.get(invocation.getArgument(0, String.class))))
        .when(row)
        .get(anyString(), any(Class.class));
    return row;
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
