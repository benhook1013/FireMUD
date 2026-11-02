package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ReplacementFence;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountSubscriptionIntentRepositoryTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID INSTRUMENT = UUID.randomUUID();
  private static final OwnerFence OWNER = new OwnerFence(ACCOUNT, 1, UUID.randomUUID());
  private static final ProducerEvidence PRODUCER =
      new ProducerEvidence(ACCOUNT, UUID.randomUUID(), "sha256:" + "a".repeat(64));
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountSubscriptionIntentRepository repository =
      new AccountSubscriptionIntentRepository(dsl, new AccountHostingPlanCatalog(List.of()));

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void pendingWritesAndClaimReadsRefuseAbsentOrReadOnlyTransactionsBeforeSql() {
    assertTransactionRefusal();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    assertTransactionRefusal();
    verifyNoInteractions(dsl);
  }

  private void assertTransactionRefusal() {
    var request = request("basic-hosting", 1);
    assertThatThrownBy(() -> repository.retainPending(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
    assertThatThrownBy(() -> repository.lockPendingForClaim(request.requestId(), 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active writable Account transaction");
  }

  @Test
  void exactNormalizedSavedInstrumentInputsAndAdvanceableVersionsAreRequired() {
    assertThatThrownBy(() -> request(" basic-hosting", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("", 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("a".repeat(256), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("basic-hosting", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> request("basic-hosting", Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OwnerFence(ACCOUNT, 0, UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReplacementFence(UUID.randomUUID(), Long.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProducerEvidence(ACCOUNT, UUID.randomUUID(), "unverified claim"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountSubscriptionCreateRequest(
                    new UUID(0L, 0L),
                    TENANT,
                    OWNER,
                    "basic-hosting",
                    INSTRUMENT,
                    1,
                    PRODUCER,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void invalidReadIdentitiesCannotBecomeSqlLookupsOrProviderMappings() {
    assertThatThrownBy(() -> repository.findCreation(new UUID(0L, 0L)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.findCurrentByTenant(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.lookupProviderId("customer-or-latest-subscription"))
        .isInstanceOf(IllegalArgumentException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> repository.lockPendingForClaim(UUID.randomUUID(), 0))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(dsl);
  }

  @Test
  void lifecycleRetainsOriginalBindingAcrossCurrentVersionDisplayUpdatesAndUnavailability() {
    for (String observation : List.of("original", "updated-display", "unavailable")) {
      BindingFixture f = new BindingFixture();
      if (!observation.equals("original")) {
        f.instrument.put("row_version", 4L);
        f.instrument.put("display_last_four", "4242");
        f.instrument.put("display_expiry_year", 2031);
      }
      if (observation.equals("unavailable")) f.instrument.put("state", "UNAVAILABLE");
      TransactionSynchronizationManager.setActualTransactionActive(true);

      var current = f.repository.lockForLifecycle(f.requestId, 7L);

      assertThat(current.subscriptionId()).isEqualTo(f.subscriptionId);
      assertThat(current.paymentInstrumentId()).isEqualTo(INSTRUMENT);
      assertThat(current.reservedInstrumentVersion()).isEqualTo(3L);
      assertThat(current.providerCustomerId()).isEqualTo("cus_original");
      assertThat(f.reservation.get("instrument_version")).isEqualTo(3L);
      assertThat(f.reservation.get("released_at")).isNull();
      assertThat(f.reads).hasSize(7);
      assertThat(f.reads.get(1)).contains("FROM accounts", "FOR UPDATE");
      assertThat(f.reads.get(2)).contains("FROM billing_customer", "FOR UPDATE");
      assertThat(f.reads.get(3)).contains("FROM payment_instrument", "FOR UPDATE");
      assertThat(f.reads.get(4)).contains("FROM account_tenant_billing_owners", "FOR UPDATE");
      assertThat(f.reads.get(5)).contains("FROM subscription s", "FOR UPDATE OF s");
      assertThat(f.reads.get(6))
          .contains("FROM subscription_instrument_reservations", "FOR UPDATE OF r");
      verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    }
  }

  @Test
  void lifecycleRejectsRolledBackWalletVersionAndChangedImmutableInstrumentBinding() {
    for (String changed :
        List.of(
            "row_version",
            "instrument_uuid",
            "account_uuid",
            "provider_customer_id",
            "customer_provisioning_request_id")) {
      BindingFixture f = new BindingFixture();
      switch (changed) {
        case "row_version" -> f.instrument.put(changed, 2L);
        case "provider_customer_id" -> f.instrument.put(changed, "cus_other");
        default -> f.instrument.put(changed, UUID.randomUUID());
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);

      assertThatThrownBy(() -> f.repository.lockForLifecycle(f.requestId, 7L))
          .as("changed %s", changed)
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);

      verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    }
  }

  @Test
  void lifecycleRejectsChangedSubscriptionIdentityExpectedVersionAndOriginalReservation() {
    for (String changed :
        List.of(
            "subscription_id",
            "tenant_id",
            "account_uuid",
            "owner_version",
            "creation_request_id",
            "payment_instrument_id",
            "reserved_instrument_version",
            "customer_provisioning_request_id",
            "provider_customer_id",
            "row_version",
            "superseded_at")) {
      BindingFixture f = new BindingFixture();
      switch (changed) {
        case "owner_version", "reserved_instrument_version", "row_version" ->
            f.current.put(changed, 8L);
        case "provider_customer_id" -> f.current.put(changed, "cus_other");
        case "superseded_at" -> f.current.put(changed, f.time);
        default -> f.current.put(changed, UUID.randomUUID());
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> f.repository.lockForLifecycle(f.requestId, 7L))
          .as("changed current %s", changed)
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
      verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    }
    for (String changed :
        List.of(
            "missing",
            "subscription_id",
            "payment_instrument_id",
            "instrument_version",
            "released_at")) {
      BindingFixture f = new BindingFixture();
      switch (changed) {
        case "missing" -> f.missingReservation = true;
        case "instrument_version" -> f.reservation.put(changed, 4L);
        case "released_at" -> f.reservation.put(changed, f.time);
        default -> f.reservation.put(changed, UUID.randomUUID());
      }
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> f.repository.lockForLifecycle(f.requestId, 7L))
          .as("changed reservation %s", changed)
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
      verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    }
  }

  @Test
  void canceledLifecycleReadRetainsOnlyItsExactTerminalReservationAfterWalletUnavailability() {
    BindingFixture f = new BindingFixture();
    UUID terminal = UUID.randomUUID();
    f.current.put("status", "canceled");
    f.current.put("terminal_resolution_id", terminal);
    f.instrument.put("row_version", 4L);
    f.instrument.put("state", "UNAVAILABLE");
    f.reservation.put("released_at", f.time);
    f.reservation.put("terminal_resolution_id", terminal);
    TransactionSynchronizationManager.setActualTransactionActive(true);

    assertThat(f.repository.lockForLifecycle(f.requestId, 7L).terminalResolutionId())
        .isEqualTo(terminal);
    assertThat(f.reservation.get("instrument_version")).isEqualTo(3L);
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));

    f.reservation.put("terminal_resolution_id", UUID.randomUUID());
    assertThatThrownBy(() -> f.repository.lockForLifecycle(f.requestId, 7L))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
  }

  @Test
  void provisioningClaimAndRecoveryStillRequireExactReservedVersionAndAttachableState() {
    for (boolean recovery : new boolean[] {false, true}) {
      BindingFixture original = new BindingFixture();
      original.current.put("status", recovery ? "provisioning" : "pending");
      TransactionSynchronizationManager.setActualTransactionActive(true);
      var accepted =
          recovery
              ? original.repository.lockForProvisioningRecovery(original.requestId, 7L)
              : original.repository.lockPendingForClaim(original.requestId, 7L);
      assertThat(accepted.reservedInstrumentVersion()).isEqualTo(3L);
      verify(original.dsl, never()).execute(anyString(), any(Object[].class));

      for (String changed : List.of("version", "state", "both")) {
        BindingFixture f = new BindingFixture();
        f.current.put("status", recovery ? "provisioning" : "pending");
        if (!changed.equals("state")) f.instrument.put("row_version", 4L);
        if (!changed.equals("version")) f.instrument.put("state", "UNAVAILABLE");
        assertThatThrownBy(
                () -> {
                  if (recovery) f.repository.lockForProvisioningRecovery(f.requestId, 7L);
                  else f.repository.lockPendingForClaim(f.requestId, 7L);
                })
            .as("%s updated %s", recovery ? "recovery" : "claim", changed)
            .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
        verify(f.dsl, never()).execute(anyString(), any(Object[].class));
      }
    }
  }

  /** Real row decoders over controlled SQL projections; no paid schema or provider proof. */
  private static final class BindingFixture {
    private final DSLContext dsl = mock(DSLContext.class);
    private final AccountSubscriptionIntentRepository repository =
        new AccountSubscriptionIntentRepository(dsl, new AccountHostingPlanCatalog(List.of()));
    private final UUID requestId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID customerRequest = UUID.randomUUID();
    private final OffsetDateTime time = OffsetDateTime.parse("2026-11-03T00:00:00Z");
    private final Map<String, Object> current = new LinkedHashMap<>();
    private final Map<String, Object> instrument = new LinkedHashMap<>();
    private final Map<String, Object> reservation = new LinkedHashMap<>();
    private final List<String> reads = new ArrayList<>();
    private boolean missingReservation;

    private BindingFixture() {
      var quote = new AccountHostingPlanQuote("basic-hosting", "price_basic", 1L, 3L, 2L, 4096L);
      Map<String, Object> creation = new LinkedHashMap<>();
      creation.put("request_id", requestId);
      creation.put(
          "request_digest",
          AccountSubscriptionCreateDigest.encode(TENANT, ACCOUNT, "basic-hosting", INSTRUMENT)
              .digest());
      creation.put("subscription_id", subscriptionId);
      creation.put("tenant_id", TENANT);
      creation.put("account_uuid", ACCOUNT);
      creation.put("owner_version", OWNER.version());
      creation.put("owner_generation", OWNER.generation());
      creation.put("plan_code", quote.planCode());
      creation.put("provider_price_id", quote.providerPriceId());
      creation.put("provider_quantity", quote.providerQuantity());
      creation.put("max_active_sessions", quote.maxActiveSessions());
      creation.put("max_concurrent_game_instances", quote.maxConcurrentGameInstances());
      creation.put("max_storage_bytes", quote.maxStorageBytes());
      creation.put("plan_quote_payload", quote.payload());
      creation.put("plan_quote_digest", quote.digest());
      creation.put("payment_instrument_id", INSTRUMENT);
      creation.put("reserved_instrument_version", 3L);
      creation.put("customer_provisioning_request_id", customerRequest);
      creation.put("provider_customer_id", "cus_original");
      creation.put("subscription_provisioning_request_id", UUID.randomUUID());
      creation.put("cleanup_provider_identity", UUID.randomUUID());
      creation.put("result_status", "pending");
      creation.put("result_row_version", 1L);
      creation.put("created_at", time);
      current.putAll(creation);
      current.put("creation_request_id", requestId);
      current.put("row_version", 7L);
      current.put("status", "active");
      current.put("provider_subscription_id", "sub_original");
      instrument.put("instrument_uuid", INSTRUMENT);
      instrument.put("account_uuid", ACCOUNT);
      instrument.put("customer_provisioning_request_id", customerRequest);
      instrument.put("provider_customer_id", "cus_original");
      instrument.put("provider_payment_method_id", "pm_original");
      instrument.put("attachment_request_id", UUID.randomUUID());
      instrument.put("row_version", 3L);
      instrument.put("state", "ATTACHABLE");
      instrument.put("display_brand", "visa");
      instrument.put("display_last_four", "1234");
      instrument.put("display_expiry_month", 12);
      instrument.put("display_expiry_year", 2030);
      instrument.put("change_request_id", UUID.randomUUID());
      instrument.put("evidence_digest", "sha256:" + "a".repeat(64));
      instrument.put("created_at", time);
      instrument.put("updated_at", time);
      Map<String, Object> customer = new LinkedHashMap<>();
      customer.put("account_uuid", ACCOUNT);
      customer.put("customer_provisioning_request_id", customerRequest);
      customer.put("provider_idempotency_id", UUID.randomUUID());
      customer.put("row_version", 2L);
      customer.put("state", "CONFIRMED");
      customer.put("provider_customer_id", "cus_original");
      customer.put("change_request_id", UUID.randomUUID());
      customer.put("observation", "CONFIRMED");
      customer.put("evidence_digest", "sha256:" + "b".repeat(64));
      customer.put("created_at", time);
      customer.put("updated_at", time);
      reservation.put("subscription_id", subscriptionId);
      reservation.put("payment_instrument_id", INSTRUMENT);
      reservation.put("instrument_version", 3L);
      Record creationRow = projection(creation);
      Record currentRow = projection(current);
      Record instrumentRow = projection(instrument);
      Record customerRow = projection(customer);
      Record ownerRow =
          projection(
              Map.of(
                  "owner_account_uuid",
                  ACCOUNT,
                  "owner_version",
                  OWNER.version(),
                  "owner_generation",
                  OWNER.generation()));
      Record reservationRow = projection(reservation);
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0, String.class);
                reads.add(sql);
                if (sql.startsWith("SELECT * FROM subscription_create_operations"))
                  return creationRow;
                if (sql.contains("FROM accounts")) return ownerRow;
                if (sql.contains("FROM billing_customer")) return customerRow;
                if (sql.contains("FROM payment_instrument")) return instrumentRow;
                if (sql.contains("FROM account_tenant_billing_owners")) return ownerRow;
                if (sql.contains("FROM subscription s")) return currentRow;
                if (sql.contains("FROM subscription_instrument_reservations"))
                  return missingReservation ? null : reservationRow;
                throw new AssertionError("Unexpected original binding SQL: " + sql);
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
    when(row.get(anyString()))
        .thenAnswer(invocation -> fields.get(invocation.getArgument(0, String.class)));
    return row;
  }

  private static AccountSubscriptionCreateRequest request(String plan, long instrumentVersion) {
    return new AccountSubscriptionCreateRequest(
        UUID.randomUUID(), TENANT, OWNER, plan, INSTRUMENT, instrumentVersion, PRODUCER, null);
  }
}
