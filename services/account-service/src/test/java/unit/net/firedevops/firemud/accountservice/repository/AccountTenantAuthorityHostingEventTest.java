package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.ObservedSubscription;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver.OperationType;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Preparation;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Controlled storage proves the actual producer/codec boundary, not a durable SQL commit. */
class AccountTenantAuthorityHostingEventTest {
  private static final UUID TENANT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID CREATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID ATTEMPT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String REQUEST_DIGEST = "sha256:" + "b".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void lifecycleEmitsExactBillingIdentityConsumedByCanonicalTenantCodec() throws Exception {
    UUID subscriptionId = UUID.fromString("55555555-5555-4555-8555-555555555555");
    UUID paidReceiptId = UUID.fromString("66666666-6666-4666-8666-666666666666");
    DSLContext dsl = mock(DSLContext.class);
    AccountSubscriptionIntentRepository intents = mock(AccountSubscriptionIntentRepository.class);
    Record operation = mock(Record.class);
    when(operation.get("state", String.class)).thenReturn("PENDING");
    when(operation.get("request_id", UUID.class)).thenReturn(REQUEST_ID);
    when(operation.get("request_digest", String.class)).thenReturn(REQUEST_DIGEST);
    when(operation.get("creation_request_id", UUID.class)).thenReturn(CREATION_ID);
    when(operation.get("reserved_row_version", Long.class)).thenReturn(6L);
    when(operation.get("last_attempt_id", UUID.class)).thenReturn(ATTEMPT_ID);
    when(operation.get("provider_subscription_id", String.class)).thenReturn("sub_exact");
    when(operation.get("deadline_at", OffsetDateTime.class))
        .thenReturn(NOW.plusSeconds(60).atOffset(ZoneOffset.UTC));
    when(operation.get("observation_age_millis", Long.class)).thenReturn(15000L);
    when(operation.get("warning_millis", Long.class)).thenReturn(60000L);
    when(operation.get("grace_millis", Long.class)).thenReturn(120000L);
    Record attempt = mock(Record.class);
    when(attempt.get("started_at", OffsetDateTime.class))
        .thenReturn(NOW.minusSeconds(1).atOffset(ZoneOffset.UTC));
    when(attempt.get("expires_at", OffsetDateTime.class))
        .thenReturn(NOW.plusSeconds(10).atOffset(ZoneOffset.UTC));
    Record links = mock(Record.class);
    when(links.get("lifecycle_inflight_request_id", UUID.class)).thenReturn(REQUEST_ID);
    when(links.get("current_receipt_request_id", UUID.class)).thenReturn(paidReceiptId);
    Record paidReceipt = mock(Record.class);
    when(paidReceipt.get("request_id", UUID.class)).thenReturn(paidReceiptId);
    when(paidReceipt.get("subscription_id", UUID.class)).thenReturn(subscriptionId);
    when(paidReceipt.get("subscription_row_version", Long.class)).thenReturn(5L);
    when(paidReceipt.get("status", String.class)).thenReturn("active");
    when(paidReceipt.get("provider_subscription_id", String.class)).thenReturn("sub_exact");
    Record paidCompletion = mock(Record.class);
    when(paidCompletion.get("subscription_id", UUID.class)).thenReturn(subscriptionId);
    Record sourceHead = mock(Record.class);
    when(sourceHead.get("current_generation", Long.class)).thenReturn(4L);
    when(sourceHead.get("current_source_version", Long.class)).thenReturn(4L);
    when(sourceHead.get("last_outbox_sequence", Long.class)).thenReturn(3L);
    Record version = mock(Record.class);
    when(version.get("next_version", Long.class)).thenReturn(8L);
    when(dsl.fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM subscription_lifecycle_operations")) {
                return operation;
              }
              if (sql.contains("FROM subscription_lifecycle_attempts")) {
                return attempt;
              }
              if (sql.equals(
                  "SELECT subscription_id FROM subscription_first_invoice_completions WHERE subscription_id = ?")) {
                assertThat((Object[]) invocation.getRawArguments()[1])
                    .containsExactly(subscriptionId);
                return paidCompletion;
              }
              if (sql.equals(
                  "SELECT * FROM subscription_lifecycle_receipts WHERE request_id = ?")) {
                assertThat((Object[]) invocation.getRawArguments()[1])
                    .containsExactly(paidReceiptId);
                return paidReceipt;
              }
              if (sql.contains("FROM subscription WHERE")) {
                return links;
              }
              if (sql.contains("FROM account_tenant_authority_source_records")) {
                return sourceHead;
              }
              if (sql.startsWith("SELECT greatest(")) {
                return version;
              }
              throw new AssertionError("Unexpected producer read: " + sql);
            });
    var receiptBindings = new AtomicReference<Object[]>();
    var reachedReceipt = new IllegalStateException("Stop after both actual event producers");
    when(dsl.execute(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.startsWith("INSERT INTO subscription_lifecycle_receipts")) {
                receiptBindings.set((Object[]) invocation.getRawArguments()[1]);
                throw reachedReceipt;
              }
              return 1;
            });

    var owner = new OwnerFence(ATTEMPT_ID, 1L, ATTEMPT_ID);
    var quote = new AccountHostingPlanQuote("basic", "price_exact", 1L, 3L, 2L, 4096L);
    var original =
        new AccountSubscriptionCreationRecord(
            CREATION_ID,
            REQUEST_DIGEST,
            subscriptionId,
            TENANT_ID,
            owner,
            "basic",
            quote,
            ATTEMPT_ID,
            1L,
            ATTEMPT_ID,
            "cus_exact",
            ATTEMPT_ID,
            ATTEMPT_ID,
            "pending",
            1L,
            NOW.minusSeconds(60));
    var current =
        new AccountSubscriptionCurrentRecord(
            subscriptionId,
            TENANT_ID,
            owner,
            "basic",
            quote,
            ATTEMPT_ID,
            1L,
            ATTEMPT_ID,
            "cus_exact",
            CREATION_ID,
            6L,
            "active",
            "sub_exact",
            null,
            null,
            null);
    when(intents.findCreation(CREATION_ID)).thenReturn(Optional.of(original));
    when(intents.lockForLifecycle(CREATION_ID, 6L)).thenReturn(current);
    var history =
        new AccountSubscriptionProviderHistoryRecord(
            "sub_exact",
            subscriptionId,
            "SUBSCRIPTION_PROVISIONING",
            CREATION_ID,
            original.subscriptionProvisioningRequestId(),
            "canonical",
            NOW.minusSeconds(60),
            REQUEST_DIGEST,
            NOW.minusSeconds(60));
    when(intents.lookupProviderId("sub_exact"))
        .thenReturn(
            new AccountSubscriptionIntentRepository.ProviderLookup(
                AccountSubscriptionIntentRepository.MappingDisposition.CURRENT_MAPPING,
                Optional.of(history),
                Optional.empty()));
    Preparation prepared = mock(Preparation.class);
    when(prepared.requestId()).thenReturn(REQUEST_ID);
    when(prepared.attemptId()).thenReturn(ATTEMPT_ID);
    // Reconcile an already-paid active subscription with exact retained mapping/completion.
    // Storage/provider inputs are controlled and execution stops before the receipt insert;
    // this proves real event encoding, not initial-payment qualification or a durable commit.
    ObservedSubscription observed = mock(ObservedSubscription.class, Answers.RETURNS_DEEP_STUBS);
    when(observed.providerSubscriptionId()).thenReturn("sub_exact");
    when(observed.providerCustomerId()).thenReturn("cus_exact");
    when(observed.providerPaymentMethodId()).thenReturn("pm_exact");
    when(observed.providerSubscriptionItemId()).thenReturn("si_exact");
    when(observed.providerPriceId()).thenReturn("price_exact");
    when(observed.providerQuantity()).thenReturn(1L);
    when(observed.providerStatus()).thenReturn("active");
    when(observed.currentPeriodStart()).thenReturn(Optional.of(NOW.minusSeconds(60)));
    when(observed.currentPeriodEnd()).thenReturn(Optional.of(NOW.plusSeconds(3600)));
    when(observed.cancelAt()).thenReturn(Optional.empty());
    when(observed.canceledAt()).thenReturn(Optional.empty());
    when(observed.endedAt()).thenReturn(Optional.empty());
    when(observed.providerMetadata()).thenReturn(Map.of());
    when(observed.readStartedAt()).thenReturn(NOW);
    when(observed.observedAt()).thenReturn(NOW);
    when(observed.correlation().operationType())
        .thenReturn(OperationType.SUBSCRIPTION_PROVISIONING);
    when(observed.correlation().operationId()).thenReturn(ATTEMPT_ID);
    when(observed.correlation().subscriptionId()).thenReturn(subscriptionId);
    FreshTenantCreationEvidence source = source();
    var prior = new ScopeState(AuthorityScope.tenant(TENANT_ID), 4L, 4L, null);
    var next = new ScopeState(AuthorityScope.tenant(TENANT_ID), 5L, 5L, null);
    var emittedBilling =
        new AtomicReference<AccountHostingSubscriptionLifecycleEvidence.BillingEvent>();
    var emittedAuthority = new AtomicReference<TenantAuthorityEventV1Codec.Event>();
    try (var generationMocks =
            mockConstruction(
                AccountAuthorityGenerationRepository.class,
                (repository, context) -> {
                  when(repository.read(AuthorityScope.tenant(TENANT_ID))).thenReturn(prior);
                  when(repository.advance(prior, null)).thenReturn(next);
                });
        var sourceMocks =
            mockConstruction(
                FreshTenantIdentityAssociationRepository.class,
                (repository, context) ->
                    when(repository.read(TENANT_ID)).thenReturn(Optional.of(source)));
        var walletMocks =
            mockConstruction(
                AccountPaymentInstrumentRepository.class,
                (repository, context) -> {
                  AccountPaymentInstrumentRecord wallet =
                      mock(AccountPaymentInstrumentRecord.class);
                  when(wallet.providerPaymentMethodId()).thenReturn("pm_exact");
                  when(repository.findByOwner(owner.accountUuid(), ATTEMPT_ID))
                      .thenReturn(Optional.of(wallet));
                });
        var billingMocks =
            mockConstruction(
                AccountTenantEntitlementOutboxRepository.class,
                (repository, context) ->
                    when(repository.appendHosting(any(), any(), any()))
                        .thenAnswer(
                            invocation -> {
                              LongFunction<AccountHostingSubscriptionLifecycleEvidence.BillingEvent>
                                  factory = invocation.getArgument(2);
                              var event = factory.apply(7L);
                              emittedBilling.set(event);
                              return event;
                            }));
        var outboxMocks =
            mockConstruction(
                AccountAuthorityOutboxRepository.class,
                (repository, context) ->
                    when(repository.append(anyString(), anyString(), any()))
                        .thenAnswer(
                            invocation -> {
                              LongFunction<AccountAuthorityOutboxRepository.EventEvidence> factory =
                                  invocation.getArgument(2);
                              var evidence = factory.apply(4L);
                              emittedAuthority.set(
                                  TenantAuthorityEventV1Codec.verify(evidence.payload()));
                              return new AccountAuthorityOutboxRepository.Event(
                                  invocation.getArgument(0),
                                  invocation.getArgument(1),
                                  4L,
                                  evidence.eventId(),
                                  evidence.eventDigest(),
                                  evidence.payload());
                            }))) {
      var repository =
          new AccountHostingSubscriptionLifecycleRepository(
              dsl,
              intents,
              Clock.fixed(NOW, ZoneOffset.UTC),
              new ReconciliationLimits(
                  3, 2, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
              new AccountHostingSubscriptionLifecyclePolicy(
                  Duration.ofSeconds(15),
                  new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                      Duration.ofSeconds(60), Duration.ofSeconds(120))),
              "prod",
              false);
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> repository.apply(prepared, observed)).isSameAs(reachedReceipt);
    }

    var billing = emittedBilling.get();
    var authority = emittedAuthority.get();
    assertThat(billing).isNotNull();
    assertThat(authority).isNotNull();
    assertThat(billing.eventId)
        .isEqualTo(TenantAuthorityEventV1Codec.tenantBillingEventId(REQUEST_ID));
    assertThat(authority.requestId()).isEqualTo(REQUEST_ID);
    assertThat(authority.requestDigest()).isEqualTo(REQUEST_DIGEST);
    assertThat(authority.sourceEvidence().requestDigest()).isNotEqualTo(REQUEST_DIGEST);
    assertThat(authority.sourceEvidence()).isEqualTo(source);
    assertThat(authority.tenantAuthorityGeneration()).isEqualTo(5L);
    assertThat(authority.tenantAuthoritySourceVersion()).isEqualTo(5L);
    assertThat(authority.outboxSequence()).isEqualTo(4L);
    assertThat(authority.tenantBillingSequence()).isEqualTo(7L);
    assertThat(authority.tenantBillingEventId()).isEqualTo(billing.eventId);
    assertThat(authority.tenantBillingEventDigest()).isEqualTo(billing.digest());
    var billingWire = JsonMapper.builder().build().readTree(billing.payload());
    assertThat(billingWire.get("schemaVersion").asText())
        .isEqualTo("account-hosting-subscription-event/v1");
    assertThat(billingWire.get("eventId").asText()).isEqualTo(billing.eventId.toString());
    assertThat(billingWire.get("requestDigest").asText()).isEqualTo(REQUEST_DIGEST);
    assertThat(receiptBindings.get()[18]).isEqualTo(7L);
    assertThat(receiptBindings.get()[19]).isEqualTo(billing.eventId);
    assertThat(receiptBindings.get()[20]).isEqualTo(billing.digest());
    assertThat(receiptBindings.get()[23]).isEqualTo(4L);
    assertThat(receiptBindings.get()[24]).isEqualTo(authority.eventId().toString());
    assertThat(receiptBindings.get()[25]).isEqualTo(authority.eventDigest());
  }

  private static FreshTenantCreationEvidence source() {
    String digest = "sha256:" + "a".repeat(64);
    String evidenceDigest =
        GameTenantCreationDigest.evidenceDigest(
            "prod",
            CREATION_ID,
            ATTEMPT_ID,
            digest,
            TENANT_ID,
            7001L,
            "tenant-source",
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        "prod",
        CREATION_ID,
        ATTEMPT_ID,
        digest,
        TENANT_ID,
        7001L,
        "tenant-source",
        "NEW_GAME_ROW",
        evidenceDigest);
  }
}
