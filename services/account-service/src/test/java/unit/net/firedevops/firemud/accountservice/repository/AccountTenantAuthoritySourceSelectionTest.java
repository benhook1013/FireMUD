package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual billing encoders and parsing, with controlled committed-source readback and SQL rows. */
class AccountTenantAuthoritySourceSelectionTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID REQUEST = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID CREATION = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID OPERATION = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void paidEnvelopeRoutesToExactHostingReceiptWithoutInterpretingRetainedDemo() {
    Fixture f = new Fixture(true);
    var selection = f.repository.readCurrentSourceSelection(TENANT);
    assertThat(selection.kind()).isEqualTo(SourceKind.PAID_HOSTING);
    assertThat(selection.matches(f.event)).isTrue();
    verify(f.repository).readHostingCheckpoint(TENANT, REQUEST);
    verify(f.repository, never()).readCurrentByTenant(any());
  }

  @Test
  void explicitDemoEnvelopeRoutesToItsCommittedDemoSource() {
    Fixture f = new Fixture(false);
    assertThat(f.repository.readCurrentSourceSelection(TENANT).kind())
        .isEqualTo(SourceKind.NON_PAID_DEMO);
    verify(f.repository).readCurrentByTenant(TENANT);
    verify(f.repository, never()).readHostingCheckpoint(any(), any());
  }

  @Test
  void unknownEnvelopeFamilyFailsBeforeEitherOwningReader() {
    Fixture f = new Fixture(true);
    byte[] payload =
        new String((byte[]) f.billing.get("payload"), java.nio.charset.StandardCharsets.UTF_8)
            .replace("account-hosting-subscription-event/v1", "unknown-billing-source/v1")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    f.billing.put("payload", payload);
    String digest = AccountHostingSubscriptionLifecycleEvidence.digest(payload);
    f.billing.put("event_digest", digest);
    f.head.put("tenant_billing_event_digest", digest);
    assertThatThrownBy(() -> f.repository.readCurrentSourceSelection(TENANT))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown");
    verify(f.repository, never()).readHostingCheckpoint(any(), any());
    verify(f.repository, never()).readCurrentByTenant(any());
  }

  @Test
  void nonlatestBillingCheckpointAndChangedAuthorityHeadFailClosed() {
    Fixture f = new Fixture(true);
    f.billing.put("last_sequence", 8L);
    assertThatThrownBy(() -> f.repository.readCurrentSourceSelection(TENANT))
        .hasMessageContaining("billing checkpoint");
    f.billing.put("last_sequence", 7L);
    f.head.put("last_event_digest", "sha256:" + "b".repeat(64));
    assertThatThrownBy(() -> f.repository.readCurrentSourceSelection(TENANT))
        .hasMessageContaining("changed");
  }

  @Test
  void rejectsTamperedPayloadAndReadOnlyOwnerTransaction() {
    Fixture f = new Fixture(true);
    f.billing.put("event_digest", "sha256:" + "b".repeat(64));
    f.head.put("tenant_billing_event_digest", "sha256:" + "b".repeat(64));
    assertThatThrownBy(() -> f.repository.readCurrentSourceSelection(TENANT))
        .hasMessageContaining("digest");
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertThatThrownBy(() -> f.repository.readCurrentSourceSelection(TENANT))
        .hasMessageContaining("writable");
  }

  private static final class Fixture {
    private final Map<String, Object> head = new LinkedHashMap<>();
    private final Map<String, Object> billing = new LinkedHashMap<>();
    private final AccountTenantAuthorityEventRepository repository;
    private final TenantAuthorityEventV1Codec.Event event;

    private Fixture(boolean paid) {
      var source =
          new FreshTenantCreationEvidence(
              1,
              "prod",
              CREATION,
              OPERATION,
              DIGEST,
              TENANT,
              71L,
              "tenant",
              "NEW_GAME_ROW",
              GameTenantCreationDigest.evidenceDigest(
                  "prod", CREATION, OPERATION, DIGEST, TENANT, 71L, "tenant", "NEW_GAME_ROW"));
      byte[] payload;
      String requestDigest;
      UUID billingId;
      String billingDigest;
      if (paid) {
        var encoded =
            AccountHostingSubscriptionLifecycleEvidence.billing(
                TENANT,
                REQUEST,
                DIGEST,
                7L,
                TenantAuthorityEventV1Codec.tenantBillingEventId(REQUEST),
                Map.of());
        payload = encoded.payload();
        requestDigest = DIGEST;
        billingId = encoded.eventId;
        billingDigest = encoded.digest();
      } else {
        var request =
            new DemoTenantEntitlementRequest(
                REQUEST,
                TENANT,
                CREATION,
                DIGEST,
                null,
                null,
                null,
                true,
                true,
                true,
                true,
                new DemoTenantEntitlementRequest.Quotas(2L, 2L, 1024L));
        var encoded = DemoTenantEntitlementEventV1Codec.seal(request, source, 3L, 5L, 5L, 7L);
        payload = encoded.payload();
        requestDigest = encoded.requestDigest();
        billingId = encoded.eventId();
        billingDigest = encoded.eventDigest();
      }
      event =
          TenantAuthorityEventV1Codec.seal(
              new TenantAuthorityEventV1Codec.AuthorityRequest(
                  REQUEST, requestDigest, TENANT, CREATION, DIGEST),
              source,
              5L,
              5L,
              4L,
              new TenantAuthorityEventV1Codec.BillingReceipt(
                  TENANT,
                  REQUEST,
                  requestDigest,
                  TenantAuthorityEventV1Codec.tenantBillingStreamKey(TENANT),
                  7L,
                  billingId,
                  billingDigest));
      head.put("tenant_billing_sequence", 7L);
      head.put("last_request_id", REQUEST);
      head.put("last_request_digest", requestDigest);
      head.put("tenant_billing_event_id", billingId);
      head.put("tenant_billing_event_digest", billingDigest);
      head.put("current_generation", 5L);
      head.put("current_source_version", 5L);
      head.put("last_outbox_sequence", 4L);
      head.put("last_event_id", event.eventId().toString());
      head.put("last_event_digest", event.eventDigest());
      billing.put("last_sequence", 7L);
      billing.put("tenant_billing_sequence", 7L);
      billing.put("request_id", REQUEST);
      billing.put("event_id", billingId);
      billing.put("event_digest", billingDigest);
      billing.put("payload", payload);
      DSLContext dsl = mock(DSLContext.class);
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                if (sql.contains("account_tenant_authority_source_records")) return row(head);
                if (sql.contains("account_tenant_entitlement_outbox_events")) return row(billing);
                if (sql.contains("subscription_lifecycle_receipts")) {
                  return row(Map.of("receipt_payload", new byte[] {'{', '}'}));
                }
                throw new AssertionError(sql);
              });
      AccountAuthorityGenerationRepository generations =
          mock(AccountAuthorityGenerationRepository.class);
      when(generations.read(AccountAuthorityGenerationRepository.AuthorityScope.tenant(TENANT)))
          .thenReturn(
              new AccountAuthorityGenerationRepository.ScopeState(
                  AccountAuthorityGenerationRepository.AuthorityScope.tenant(TENANT),
                  5L,
                  5L,
                  null));
      repository =
          spy(
              new AccountTenantAuthorityEventRepository(
                  dsl,
                  mock(AccountAuthorityOutboxRepository.class),
                  mock(AccountTenantEntitlementOutboxRepository.class),
                  mock(FreshTenantIdentityAssociationRepository.class),
                  generations));
      doReturn(event).when(repository).readHostingCheckpoint(TENANT, REQUEST);
      doReturn(event).when(repository).readCurrentByTenant(TENANT);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
  }

  private static Record row(Map<String, Object> fields) {
    Record record = mock(Record.class);
    when(record.get(anyString(), any(Class.class)))
        .thenAnswer(invocation -> fields.get(invocation.getArgument(0)));
    return record;
  }
}
