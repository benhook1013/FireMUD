package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionProvisioner;
import net.firedevops.firemud.accountservice.dto.AccountPlatformRestrictionBirthSource.Category;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Disposition;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProviderDispatch;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionProvisioningService;
import net.firedevops.firemud.accountservice.service.StripeWebhookIntakeService;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Actual owner/SDK composition with controlled enrollment publications and provider transport. */
@Testcontainers(disabledWithoutDocker = true)
class AccountHostingSubscriptionDispatchPostgresIntegrationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final AtomicLong GAME_ROWS = new AtomicLong(90000);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void firstRetainedExactSignedSuccessBlocksBothUnpaidCancellationPaths(boolean claimed)
      throws Exception {
    var source = unpaidCancellationDataSource(null);
    var f = CanonicalHostingSubscriptionTestFixture.create(source);
    var checkout = f.awaitFirstInvoice();
    if (claimed) {
      checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    }
    var signed = checkout.signedSuccess();
    var intake =
        new StripeWebhookIntakeService(
            checkout.verifier(),
            new StripeEventInboxRepository(f.dsl()),
            new DataSourceTransactionManager(source));
    var retained = intake.accept(signed.payload(), signed.signature());
    var redelivery = checkout.signWebhookPayload(signed.payload() + "\n");
    assertThat(intake.accept(redelivery.payload(), redelivery.signature())).isEqualTo(retained);

    f.providerStatus("canceled");
    var canceled =
        f.service()
            .reconcileUnpaidFirstInvoiceCancellation(
                UUID.randomUUID(),
                f.creation().requestId(),
                unpaidRowVersion(f, checkout),
                f.providerId());

    assertThat(canceled.outcome()).isEqualTo(Outcome.RECONCILING);
    assertUnpaidReservationHeld(f);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_first_invoice_success_evidence")))
        .isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_first_invoice_no_send_cancellations")))
        .isZero();
    assertThat(f.dsl().fetchCount(DSL.table("stripe_event_inbox"))).isOne();
    assertThat(
            f.dsl().fetchSingle("SELECT raw_payload FROM stripe_event_inbox").get(0, byte[].class))
        .isEqualTo(signed.payload().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void rawCancellationRejectsStaleSnapshotAndSeesExactInboxSuccessAtReadCommitted()
      throws Exception {
    var source = unpaidCancellationDataSource(null);
    var f = CanonicalHostingSubscriptionTestFixture.create(source);
    var checkout = f.awaitFirstInvoice();
    var signed = checkout.signedSuccess();
    var verified = checkout.verifier().verify(signed.payload(), signed.signature());
    var repeatableRead = new TransactionTemplate(new DataSourceTransactionManager(source));
    repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    var independent = new TransactionTemplate(new DataSourceTransactionManager(source));
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    assertThatThrownBy(
            () ->
                repeatableRead.executeWithoutResult(
                    status -> {
                      assertThat(f.dsl().fetchCount(DSL.table("stripe_event_inbox"))).isZero();
                      in(
                          independent,
                          () -> new StripeEventInboxRepository(f.dsl()).retainOrReplay(verified));
                      assertThat(f.dsl().fetchCount(DSL.table("stripe_event_inbox"))).isZero();
                      rawUnpaidCancellation(f);
                    }))
        .hasMessageContaining("Unpaid first-invoice cancellation requires READ COMMITTED");

    assertThatThrownBy(
            () -> f.transaction().executeWithoutResult(status -> rawUnpaidCancellation(f)))
        .hasMessageContaining(
            "Unpaid first-invoice cancellation conflicts with retained signed success");
    assertThat(f.dsl().fetchCount(DSL.table("stripe_event_inbox"))).isOne();
    assertUnpaidReservationHeld(f);
  }

  @Test
  void newNoSendMarkerUsesItsActualTransactionIdentityAndUpgradePreservesRetainedMarker() {
    var source = unpaidCancellationDataSource("1046.7");
    var f = CanonicalHostingSubscriptionTestFixture.create(source);
    var checkout = f.awaitFirstInvoice();
    checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    String markerImage =
        f.dsl()
            .fetchSingle(
                "SELECT row_to_json(m)::text || ':' || xmin::text FROM subscription_first_invoice_expired_before_post m")
            .get(0, String.class);
    migrate(source, Objects.requireNonNull(source.getSchema()), null);
    assertThat(
            f.dsl()
                .fetchSingle(
                    "SELECT row_to_json(m)::text || ':' || xmin::text FROM subscription_first_invoice_expired_before_post m")
                .get(0, String.class))
        .isEqualTo(markerImage);

    var another = CanonicalHostingSubscriptionTestFixture.create(source);
    var open = another.awaitFirstInvoice();
    var dispatch =
        open.prepareConfirmationDispatch(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    in(
        another.transaction(),
        () -> open.payments().claimCommand(dispatch, open.invoice().ownerAccountId()));
    Long claimTransaction =
        another
            .dsl()
            .fetchSingle(
                "SELECT created_transaction_id FROM subscription_first_invoice_command_claims WHERE request_id = ?",
                dispatch.requestId())
            .get(0, Long.class);

    another
        .transaction()
        .executeWithoutResult(
            status -> {
              Long currentTransaction =
                  another.dsl().fetchSingle("SELECT txid_current()").get(0, Long.class);
              assertThat(
                      another
                          .dsl()
                          .fetchSingle(
                              "INSERT INTO subscription_first_invoice_expired_before_post (request_id, observed_at, created_transaction_id) "
                                  + "VALUES (?, ?::timestamptz, ?) RETURNING created_transaction_id",
                              dispatch.requestId(),
                              dispatch.validUntil().atOffset(ZoneOffset.UTC),
                              claimTransaction)
                          .get(0, Long.class))
                  .isEqualTo(currentTransaction)
                  .isNotEqualTo(claimTransaction);
            });
  }

  private static DriverManagerDataSource unpaidCancellationDataSource(String target) {
    String schema = "hosting_unpaid_cancel_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    migrate(source, schema, target);
    return source;
  }

  private static long unpaidRowVersion(
      CanonicalHostingSubscriptionTestFixture.Fixture f,
      CanonicalHostingSubscriptionTestFixture.FirstInvoiceCheckout checkout) {
    return in(
        f.transaction(),
        () -> checkout.payments().ownerRowVersion(f.creation().subscriptionId(), f.accountUuid()));
  }

  private static void rawUnpaidCancellation(CanonicalHostingSubscriptionTestFixture.Fixture f) {
    f.dsl().execute("SET CONSTRAINTS first_invoice_unpaid_subscription_cancellation IMMEDIATE");
    f.dsl()
        .execute(
            "UPDATE subscription SET status = 'canceled', row_version = row_version + 1 WHERE subscription_id = ?",
            f.creation().subscriptionId());
  }

  private static void assertUnpaidReservationHeld(
      CanonicalHostingSubscriptionTestFixture.Fixture f) {
    assertThat(
            f.dsl()
                .fetchSingle(
                    "SELECT status FROM subscription WHERE subscription_id = ?",
                    f.creation().subscriptionId())
                .get(0, String.class))
        .isEqualTo("provisioning");
    assertThat(
            f.dsl()
                .fetchSingle(
                    "SELECT released_at FROM subscription_instrument_reservations WHERE subscription_id = ?",
                    f.creation().subscriptionId())
                .get(0))
        .isNull();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_operations"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_terminal_resolutions")))
        .isZero();
  }

  @Test
  void upgradeRetainsPriorAttemptOneAndAddsOnlyNonAuthorizingUnknownGuard() {
    Fixture f = fixture("1030");
    ProvisioningClaim claim = claim(f, 1);
    var before = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "accounts",
            "subscription_create_operations",
            "subscription",
            "subscription_provisioning_work",
            "subscription_provisioning_attempts")) {
      before.put(
          table,
          f.dsl()
              .fetch("SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM " + table + " t")
              .intoMaps());
    }
    migrate(f.source(), f.schema(), "1032");
    for (var entry : before.entrySet()) {
      assertThat(
              f.dsl()
                  .fetch(
                      "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM "
                          + entry.getKey()
                          + " t")
                  .intoMaps())
          .isEqualTo(entry.getValue());
    }
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT classification FROM subscription_provider_dispatch_claim"))
                .get(0, String.class))
        .isEqualTo("PREEXISTING_UNKNOWN");
    var prepared = f.tx().execute(s -> f.owner().prepareProviderDispatch(claim, UUID.randomUUID()));
    var read = f.tx().execute(s -> f.owner().readProviderDispatch(prepared));
    assertThat(read.readyForProviderIo()).isTrue();
    assertThat(read.initialCallAllowed()).isFalse();
  }

  @Test
  void upgradeRetainsControlledUnclaimedWorkWithoutInventingDispatchHistory() {
    Fixture f = fixture("1030");
    // Controlled retained state is inserted directly with the normal SQL guards enabled. The
    // existing production claim owner does not commit nonterminal work without its first attempt.
    f.tx()
        .executeWithoutResult(
            s ->
                f.dsl()
                    .execute(
                        "INSERT INTO subscription_provisioning_work (request_id, subscription_id, phase, row_version, "
                            + "attempt_count, max_attempts, max_stalled_attempts, lease_millis, backoff_millis, deadline_at, retry_at) "
                            + "SELECT request_id, subscription_id, 'RECONCILING', 1, 0, 4, 3, 10000, 1000, "
                            + "created_at + interval '1 hour', created_at FROM subscription_create_operations WHERE request_id = ?",
                        f.creation().requestId()));
    var retained =
        f.dsl()
            .fetch(
                "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM subscription_provisioning_work t")
            .intoMaps();
    assertThat(retained).hasSize(1);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_attempts"))).isZero();

    migrate(f.source(), f.schema(), "1032");

    assertThat(
            f.dsl()
                .fetch(
                    "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM subscription_provisioning_work t")
                .intoMaps())
        .isEqualTo(retained);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_attempts"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isZero();
    var provisioning = service(f);
    var first = claim(f, 1);
    assertThat(first.attemptNumber()).isOne();
    var result =
        (AccountHostingSubscriptionProvisioningService.AwaitingPayment)
            provisioning.provision(first, UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.invoice().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(result.invoice().subscriptionId()).isEqualTo(f.creation().subscriptionId());
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts"))).isZero();
    assertThat(f.transport().creates).isOne();
    assertThat(
            Objects.requireNonNull(
                    f.dsl().fetchOne("SELECT * FROM subscription_provider_dispatch_claim"))
                .intoMap())
        .containsEntry("classification", "FIRST_DISPATCH")
        .containsEntry("first_attempt_id", first.attemptId())
        .containsEntry("first_claimed_row_version", first.subscriptionRowVersion());
  }

  @Test
  void upgradeRetainsExpiredFirstClaimAsTerminalWithoutInventingDispatchHistory() {
    Fixture f = fixture("1030", Duration.ofSeconds(5));
    awaitDatabaseTime(f, f.creation().createdAt().plusSeconds(5));
    var failed =
        f.tx().execute(s -> f.owner().claim(f.creation().requestId(), 1, UUID.randomUUID()));
    assertThat(failed.disposition()).isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(failed.claim()).isNull();
    assertThat(
            Objects.requireNonNull(f.dsl().fetchOne("SELECT * FROM subscription_provisioning_work"))
                .intoMap())
        .containsEntry("phase", "BILLING_RECONCILIATION_TERMINAL_FAILURE")
        .containsEntry("attempt_count", 0)
        .containsEntry("failure_reason", "ABSOLUTE_DEADLINE");
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_attempts"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isOne();
    var retained = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "subscription",
            "subscription_provisioning_work",
            "subscription_provisioning_operator_outbox",
            "subscription_instrument_reservations")) {
      retained.put(
          table,
          f.dsl()
              .fetch("SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM " + table + " t")
              .intoMaps());
    }

    migrate(f.source(), f.schema(), "1032");

    var replay =
        f.tx().execute(s -> f.owner().claim(f.creation().requestId(), 1, UUID.randomUUID()));
    assertThat(replay.disposition()).isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(replay.claim()).isNull();
    for (var entry : retained.entrySet()) {
      assertThat(
              f.dsl()
                  .fetch(
                      "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM "
                          + entry.getKey()
                          + " t")
                  .intoMaps())
          .isEqualTo(entry.getValue());
    }
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_attempts"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isZero();
    assertThat(f.transport().creates).isZero();
    assertThat(f.transport().searches).isZero();
  }

  @Test
  void onlyOneFreshInsertionCarriesInitialPermissionAndIndependentReadbackIsMandatory()
      throws Exception {
    Fixture f = fixture("1032");
    ProvisioningClaim claim = claim(f, 1);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return prepareAndRead(f, claim);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return prepareAndRead(f, claim);
              });
      start.countDown();
      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results)
          .extracting(ProviderDispatch::initialCallAllowed)
          .containsExactlyInAnyOrder(true, false);
      assertThat(prepareAndRead(f, claim).initialCallAllowed()).isFalse();
    }
    assertThat(ProviderDispatch.class.getConstructors()).isEmpty();
  }

  @Test
  void uncommittedSameTransactionReadbackCannotProduceProviderPermission() {
    Fixture f = fixture("1032");
    var claim = claim(f, 1);
    assertThatThrownBy(
            () ->
                f.tx()
                    .execute(
                        s -> {
                          var uncommitted =
                              f.owner().prepareProviderDispatch(claim, UUID.randomUUID());
                          return f.owner().readProviderDispatch(uncommitted);
                        }))
        .hasMessageContaining("new transaction");
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isZero();
  }

  @Test
  void expiredExactAttemptReturnsTypedPendingAndReplacedStaleWorkerHitsBindingFence() {
    Fixture f = fixture("1032");
    var original = claim(f, 1);
    prepareAndRead(f, original);
    awaitProvisioningTime(f, "retry_at");
    var before = new java.util.LinkedHashMap<String, List<Map<String, Object>>>();
    for (String table :
        List.of(
            "subscription",
            "subscription_provisioning_work",
            "subscription_provisioning_attempts",
            "subscription_instrument_reservations",
            "subscription_provider_dispatch_claim")) {
      before.put(
          table,
          f.dsl()
              .fetch("SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM " + table + " t")
              .intoMaps());
    }
    Disposition expired = f.tx().execute(status -> f.owner().deferUnresolved(original));
    assertThat(expired).isEqualTo(Disposition.ATTEMPT_EXPIRED);
    for (var entry : before.entrySet()) {
      assertThat(
              f.dsl()
                  .fetch(
                      "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM "
                          + entry.getKey()
                          + " t")
                  .intoMaps())
          .isEqualTo(entry.getValue());
    }
    var replacement = claim(f, original.subscriptionRowVersion());
    assertThat(replacement.attemptId()).isNotEqualTo(original.attemptId());
    before.replaceAll(
        (table, rows) ->
            f.dsl()
                .fetch(
                    "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM " + table + " t")
                .intoMaps());
    // Replacement advanced the subscription version, so the original binding rejects this stale
    // worker before the later attempt-identity check can run.
    assertThatThrownBy(() -> f.tx().execute(status -> f.owner().deferUnresolved(original)))
        .isExactlyInstanceOf(FenceRejectedException.class)
        .hasMessage("Pending claim owner/customer/instrument/subscription fence rejected");
    for (var entry : before.entrySet()) {
      assertThat(
              f.dsl()
                  .fetch(
                      "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM "
                          + entry.getKey()
                          + " t")
                  .intoMaps())
          .isEqualTo(entry.getValue());
    }
    assertThat(f.transport().creates).isZero();
  }

  @Test
  void recoveredUndispatchedAttemptMayOwnFirstDispatchButReplayAndExpiredReadbackCannot() {
    Fixture f = fixture("1032");
    var first = claim(f, 1);
    // Simulate a crash after the worker claim commits but before any dispatch transaction.
    awaitProvisioningTime(f, "retry_at");
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isZero();
    assertThat(f.transport().creates).isZero();
    var second = claim(f, first.subscriptionRowVersion());
    assertThat(second.attemptNumber()).isEqualTo(2);
    var prepared =
        f.tx().execute(s -> f.owner().prepareProviderDispatch(second, UUID.randomUUID()));
    assertThat(f.tx().execute(s -> f.owner().readProviderDispatch(prepared)).initialCallAllowed())
        .isTrue();
    assertThat(prepareAndRead(f, second).initialCallAllowed()).isFalse();
    awaitProvisioningTime(f, "lease_expires_at");
    assertThatThrownBy(() -> f.tx().execute(s -> f.owner().readProviderDispatch(prepared)))
        .isInstanceOf(FenceRejectedException.class)
        .hasMessage("Exact live provisioning claim readback rejected");
  }

  @Test
  void expiredUndispatchedFirstClaimRecoversThroughExactlyOneInitialCreate() {
    Fixture f = fixture("1032");
    var provisioning = service(f);
    var first = claim(f, 1);
    awaitProvisioningTime(f, "retry_at");
    var second = claim(f, first.subscriptionRowVersion());
    var result =
        (AccountHostingSubscriptionProvisioningService.AwaitingPayment)
            provisioning.provision(second, UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.invoice().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(result.invoice().subscriptionId()).isEqualTo(f.creation().subscriptionId());
    assertThat(f.transport().creates).isEqualTo(1);
    var dispatch =
        Objects.requireNonNull(
            f.dsl().fetchOne("SELECT * FROM subscription_provider_dispatch_claim"));
    assertThat(dispatch.get("classification", String.class)).isEqualTo("FIRST_DISPATCH");
    assertThat(dispatch.get("first_attempt_id", UUID.class)).isEqualTo(second.attemptId());
    assertThat(dispatch.get("first_claimed_row_version", Long.class))
        .isEqualTo(second.subscriptionRowVersion());
  }

  @Test
  void dispatchIdentityBindingClassificationAndTimeCannotChangeOrDisappear() {
    Fixture f = fixture("1032");
    prepareAndRead(f, claim(f, 1));
    var before = f.dsl().fetch("SELECT * FROM subscription_provider_dispatch_claim").intoMaps();
    for (String command :
        List.of(
            "UPDATE subscription_provider_dispatch_claim SET classification='PREEXISTING_UNKNOWN'",
            "UPDATE subscription_provider_dispatch_claim SET recorded_at=recorded_at+interval '1 second'",
            "UPDATE subscription_provider_dispatch_claim SET dispatch_id='"
                + UUID.randomUUID()
                + "'",
            "DELETE FROM subscription_provider_dispatch_claim",
            "TRUNCATE subscription_provider_dispatch_claim")) {
      assertThatThrownBy(() -> f.dsl().execute(command))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
    }
    assertThat(f.dsl().fetch("SELECT * FROM subscription_provider_dispatch_claim").intoMaps())
        .isEqualTo(before);
  }

  @Test
  void realInitialSdkCreateOnlyLinksAwaitingInvoiceAndCannotCommitHostingAuthority() {
    Fixture f = fixture("1032");
    var provisioning = service(f);
    var result =
        (AccountHostingSubscriptionProvisioningService.AwaitingPayment)
            provisioning.provision(claim(f, 1), UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.invoice().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(result.invoice().subscriptionId()).isEqualTo(f.creation().subscriptionId());
    assertThat(f.transport().creates).isEqualTo(1);
    assertThat(f.transport().subscriptionReads).isGreaterThanOrEqualTo(2);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts"))).isZero();
    assertThat(
            Objects.requireNonNull(f.dsl().fetchOne("SELECT status FROM subscription"))
                .get("status", String.class))
        .isEqualTo("provisioning");
    assertThat(
            Objects.requireNonNull(
                    f.dsl().fetchOne("SELECT phase FROM subscription_provisioning_work"))
                .get("phase", String.class))
        .isEqualTo("AWAITING_PAYMENT");
  }

  @Test
  void lostProvisionResponseReplaysRetainedInvoiceWithoutProviderIo() {
    Fixture f = fixture("1032");
    var provisioning = service(f);
    var originalClaim = claim(f, 1);
    // Treat the committed first result as lost and retry with the exact retained claim.
    provisioning.provision(originalClaim, UUID.randomUUID(), UUID.randomUUID());
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT phase FROM subscription_provisioning_work WHERE request_id = ?",
                            f.creation().requestId()))
                .get("phase", String.class))
        .isEqualTo("AWAITING_PAYMENT");

    var retainedImage =
        f.dsl()
            .fetchSingle(
                "SELECT row_to_json(i)::text || ':' || xmin::text FROM subscription_first_invoices i")
            .get(0, String.class);
    var dispatchImage =
        f.dsl()
            .fetchSingle(
                "SELECT row_to_json(c)::text || ':' || xmin::text FROM subscription_provider_dispatch_claim c")
            .get(0, String.class);
    var providerCalls =
        List.of(
            f.transport().requests,
            f.transport().creates,
            f.transport().searches,
            f.transport().lists,
            f.transport().subscriptionReads);

    var replay =
        (AccountHostingSubscriptionProvisioningService.AwaitingPayment)
            provisioning.provision(originalClaim, UUID.randomUUID(), UUID.randomUUID());

    assertThat(replay.invoice().subscriptionId()).isEqualTo(f.creation().subscriptionId());
    assertThat(replay.invoice().creationRequestId()).isEqualTo(f.creation().requestId());
    assertThat(replay.invoice().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(
            f.dsl()
                .fetchSingle(
                    "SELECT row_to_json(i)::text || ':' || xmin::text FROM subscription_first_invoices i")
                .get(0, String.class))
        .isEqualTo(retainedImage);
    assertThat(
            f.dsl()
                .fetchSingle(
                    "SELECT row_to_json(c)::text || ':' || xmin::text FROM subscription_provider_dispatch_claim c")
                .get(0, String.class))
        .isEqualTo(dispatchImage);
    assertThat(
            List.of(
                f.transport().requests,
                f.transport().creates,
                f.transport().searches,
                f.transport().lists,
                f.transport().subscriptionReads))
        .isEqualTo(providerCalls);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts"))).isZero();
  }

  @Test
  void liveFinalBudgetDispatchSurvivesDisplayRefreshAndCompetingClaim() {
    Fixture f = fixture("1032");
    var provisioning = service(f);
    var first = claim(f, 1);
    awaitProvisioningTime(f, "retry_at");
    var second = claim(f, first.subscriptionRowVersion());
    awaitProvisioningTime(f, "retry_at");
    var finalClaim = claim(f, second.subscriptionRowVersion());
    assertThat(finalClaim.attemptNumber()).isEqualTo(3);
    f.transport().duringCreate =
        () -> {
          refreshDisplay(f);
          var competing =
              f.tx()
                  .execute(
                      s ->
                          f.owner()
                              .claim(
                                  f.creation().requestId(),
                                  finalClaim.subscriptionRowVersion(),
                                  UUID.randomUUID()));
          assertThat(competing.disposition()).isEqualTo(Disposition.WAITING);
          assertThat(competing.claim()).isNull();
          assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox")))
              .isZero();
        };

    var result =
        (AccountHostingSubscriptionProvisioningService.AwaitingPayment)
            provisioning.provision(finalClaim, UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.invoice().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(f.transport().creates).isOne();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_attempts"))).isEqualTo(3);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts"))).isZero();
  }

  @Test
  void lostCreateResponseRecoversAfterTransientEmptySearchWithoutRecreating() {
    Fixture f = fixture("1032");
    var provisioning = service(f);
    f.transport().loseCreateResponse = true;
    var first = claim(f, 1);
    var lost =
        (AccountHostingSubscriptionProvisioningService.Pending)
            provisioning.provision(first, UUID.randomUUID(), UUID.randomUUID());
    assertThat(lost.disposition()).isEqualTo(Disposition.WAITING);
    assertThat(((StripeHostingSubscriptionProvisioner.Pending) lost.providerResult()).reason())
        .isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
    assertThat(f.transport().creates).isEqualTo(1);
    f.transport().emptySearchesRemaining = 1;
    awaitProvisioningTime(f, "retry_at");
    var second = claim(f, first.subscriptionRowVersion());
    var result =
        (AccountHostingSubscriptionProvisioningService.AwaitingPayment)
            provisioning.provision(second, UUID.randomUUID(), UUID.randomUUID());
    assertThat(result.invoice().providerSubscriptionId()).isEqualTo("sub_exact");
    assertThat(f.transport().creates).isEqualTo(1);
    assertThat(f.transport().searches).isEqualTo(2);
    assertThat(f.transport().lists).isEqualTo(1);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isOne();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isZero();
    int providerRequests = f.transport().requests;
    assertThat(provisioning.provision(second, UUID.randomUUID(), UUID.randomUUID()))
        .isEqualTo(result);
    assertThatThrownBy(() -> provisioning.provision(first, UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(FenceRejectedException.class);
    assertThat(f.transport().requests).isEqualTo(providerRequests);
  }

  @Test
  void prolongedEmptySearchAfterLostCreateStopsWithOneOperatorItemAndHeldReservation() {
    Fixture f = fixture("1032");
    var provisioning = service(f);
    f.transport().loseCreateResponse = true;
    var first = claim(f, 1);
    var lost =
        (AccountHostingSubscriptionProvisioningService.Pending)
            provisioning.provision(first, UUID.randomUUID(), UUID.randomUUID());
    assertThat(lost.disposition()).isEqualTo(Disposition.WAITING);
    assertThat(((StripeHostingSubscriptionProvisioner.Pending) lost.providerResult()).reason())
        .isEqualTo("PROVIDER_OUTCOME_UNKNOWN");
    assertThat(f.transport().exists).isTrue();
    f.transport().emptySearchesRemaining = 2;
    f.transport().emptyListsRemaining = 2;
    ProvisioningClaim previous = first;
    for (int attempt = 2; attempt <= 3; attempt++) {
      awaitProvisioningTime(f, "retry_at");
      previous = claim(f, previous.subscriptionRowVersion());
      var pending =
          (AccountHostingSubscriptionProvisioningService.Pending)
              provisioning.provision(previous, UUID.randomUUID(), UUID.randomUUID());
      assertThat(pending.disposition())
          .isEqualTo(attempt == 3 ? Disposition.TERMINAL_FAILURE : Disposition.WAITING);
      assertThat(pending.providerResult())
          .isEqualTo(
              new StripeHostingSubscriptionProvisioner.Pending("PROVIDER_LOOKUP_UNRESOLVED", null));
      assertThat(f.transport().creates).isEqualTo(1);
    }
    long finalVersion = previous.subscriptionRowVersion();
    var retry =
        Objects.requireNonNull(
            f.tx()
                .execute(
                    s ->
                        f.owner()
                            .claim(f.creation().requestId(), finalVersion, UUID.randomUUID())));
    assertThat(retry.disposition()).isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(retry.claim()).isNull();
    assertThat(f.transport().searches).isEqualTo(3);
    assertThat(f.transport().lists).isEqualTo(2);
    assertThat(f.transport().creates).isEqualTo(1);
    var current =
        Objects.requireNonNull(
            f.dsl()
                .fetchOne(
                    "SELECT status, provider_subscription_id, terminal_resolution_id FROM subscription WHERE subscription_id = ?",
                    f.creation().subscriptionId()));
    assertThat(current.get("status", String.class)).isEqualTo("provisioning");
    assertThat(current.get("provider_subscription_id")).isNull();
    assertThat(current.get("terminal_resolution_id")).isNull();
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                            f.creation().requestId()))
                .get("released_at"))
        .isNull();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isOne();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_terminal_resolutions")))
        .isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts"))).isZero();
    assertThat(
            Objects.requireNonNull(
                    f.dsl()
                        .fetchOne(
                            "SELECT phase, failure_reason FROM subscription_provisioning_work WHERE request_id = ?",
                            f.creation().requestId()))
                .intoMap())
        .containsEntry("phase", "BILLING_RECONCILIATION_TERMINAL_FAILURE")
        .containsEntry("failure_reason", "STALLED_WITHOUT_COMMITTED_PROGRESS");
  }

  @Test
  void displayRefreshAfterClaimRefusesDispatchButRetainedWorkReachesOperatorDeadline() {
    Fixture f = fixture(null, Duration.ofSeconds(5));
    var provisioning = migratedService(f);
    var original = claim(f, 1);
    var originalRows = f.dsl().fetch("SELECT * FROM subscription_create_operations").intoMaps();
    var reservation =
        f.dsl().fetch("SELECT * FROM subscription_instrument_reservations").intoMaps();
    refreshDisplay(f);

    assertThatThrownBy(
            () ->
                f.tx().execute(s -> f.owner().prepareProviderDispatch(original, UUID.randomUUID())))
        .isInstanceOf(FenceRejectedException.class);
    var refused =
        (AccountHostingSubscriptionProvisioningService.Pending)
            provisioning.provision(original, UUID.randomUUID(), UUID.randomUUID());
    assertThat(refused.disposition()).isEqualTo(Disposition.WAITING);
    assertThat(refused.providerResult())
        .isEqualTo(
            new StripeHostingSubscriptionProvisioner.Pending("DISPATCH_FENCE_REJECTED", null));
    var waiting =
        f.tx()
            .execute(
                s ->
                    f.owner()
                        .claim(
                            f.creation().requestId(),
                            original.subscriptionRowVersion(),
                            UUID.randomUUID()));
    assertThat(waiting.disposition()).isEqualTo(Disposition.WAITING);
    assertThat(waiting.claim()).isNull();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_attempts"))).isOne();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim"))).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isZero();
    awaitProvisioningTime(f, "deadline_at");

    var terminal =
        f.tx()
            .execute(
                s ->
                    f.owner()
                        .claim(
                            f.creation().requestId(),
                            original.subscriptionRowVersion(),
                            UUID.randomUUID()));
    assertThat(terminal.disposition()).isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(terminal.claim()).isNull();
    assertThat(f.tx().<Disposition>execute(s -> f.owner().deferUnresolved(original)))
        .isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(
            Objects.requireNonNull(f.dsl().fetchOne("SELECT * FROM subscription_provisioning_work"))
                .intoMap())
        .containsEntry("phase", "BILLING_RECONCILIATION_TERMINAL_FAILURE")
        .containsEntry("failure_reason", "ABSOLUTE_DEADLINE");
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isOne();
    assertThat(f.dsl().fetch("SELECT * FROM subscription_create_operations").intoMaps())
        .usingRecursiveComparison()
        .isEqualTo(originalRows);
    assertThat(f.dsl().fetch("SELECT * FROM subscription_instrument_reservations").intoMaps())
        .isEqualTo(reservation);
    assertThat(Objects.requireNonNull(f.dsl().fetchOne("SELECT * FROM subscription")).intoMap())
        .containsEntry("status", "provisioning")
        .containsEntry("provider_subscription_id", null)
        .containsEntry("terminal_resolution_id", null);
    assertThat(f.transport().creates).isZero();
    assertThat(f.transport().searches).isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provider_terminal_resolutions")))
        .isZero();
    assertThat(f.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts"))).isZero();
  }

  @Test
  void displayRefreshAfterDispatchPreparationRefusesReadbackAndStillAllowsLocalDeadlineFailure() {
    Fixture f = fixture("1032", Duration.ofSeconds(5));
    var original = claim(f, 1);
    var prepared =
        f.tx().execute(s -> f.owner().prepareProviderDispatch(original, UUID.randomUUID()));
    var dispatchRows =
        f.dsl().fetch("SELECT * FROM subscription_provider_dispatch_claim").intoMaps();
    refreshDisplay(f);

    assertThatThrownBy(() -> f.tx().execute(s -> f.owner().readProviderDispatch(prepared)))
        .isInstanceOf(FenceRejectedException.class);
    assertThat(f.tx().<Disposition>execute(s -> f.owner().deferUnresolved(original)))
        .isEqualTo(Disposition.WAITING);
    awaitProvisioningTime(f, "deadline_at");
    assertThat(f.tx().<Disposition>execute(s -> f.owner().deferUnresolved(original)))
        .isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(f.dsl().fetch("SELECT * FROM subscription_provider_dispatch_claim").intoMaps())
        .isEqualTo(dispatchRows);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isOne();
    assertThat(f.transport().creates).isZero();
  }

  @Test
  void displayRefreshBeforeInitialClaimRetainsTerminalFailureWithoutDispatch() {
    Fixture unclaimed = fixture(null);
    refreshDisplay(unclaimed);
    var refused =
        unclaimed
            .tx()
            .execute(
                s ->
                    unclaimed
                        .owner()
                        .claim(unclaimed.creation().requestId(), 1, UUID.randomUUID()));
    assertThat(Objects.requireNonNull(refused).disposition())
        .isEqualTo(Disposition.TERMINAL_FAILURE);
    assertThat(refused.claim()).isNull();
    assertThat(unclaimed.dsl().fetchCount(DSL.table("subscription_provisioning_work"))).isOne();
    assertThat(
            Objects.requireNonNull(
                    unclaimed.dsl().fetchOne("SELECT * FROM subscription_provisioning_work"))
                .intoMap())
        .containsEntry("phase", "BILLING_RECONCILIATION_TERMINAL_FAILURE")
        .containsEntry("failure_reason", "RESERVED_INSTRUMENT_CHANGED");
    assertThat(unclaimed.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox")))
        .isOne();
    assertThat(unclaimed.dsl().fetchCount(DSL.table("subscription_provisioning_attempts")))
        .isZero();
    assertThat(unclaimed.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim")))
        .isZero();
    assertThat(unclaimed.transport().creates).isZero();
    assertThat(unclaimed.transport().searches).isZero();
  }

  @Test
  void displayRefreshDoesNotAuthorizeDirectDispatchInsert() {
    Fixture retained = fixture("1032");
    var original = claim(retained, 1);
    refreshDisplay(retained);
    // Exercise the unchanged V1032 insertion guard independently of the Java eligibility fence.
    assertThatThrownBy(
            () ->
                retained
                    .tx()
                    .execute(
                        s ->
                            retained
                                .dsl()
                                .execute(
                                    "INSERT INTO subscription_provider_dispatch_claim "
                                        + "(creation_request_id, subscription_id, provider_operation_id, dispatch_id, "
                                        + "classification, first_attempt_id, first_claimed_row_version, recorded_at) "
                                        + "VALUES (?, ?, ?, ?, 'FIRST_DISPATCH', ?, ?, ?::timestamptz)",
                                    retained.creation().requestId(),
                                    retained.creation().subscriptionId(),
                                    retained.creation().subscriptionProvisioningRequestId(),
                                    UUID.randomUUID(),
                                    original.attemptId(),
                                    original.subscriptionRowVersion(),
                                    retained.clock().instant().atOffset(ZoneOffset.UTC))))
        .isInstanceOf(org.jooq.exception.DataAccessException.class)
        .hasMessageContaining("Provider dispatch lacks exact live original provisioning fence");
    assertThat(retained.dsl().fetchCount(DSL.table("subscription_provider_dispatch_claim")))
        .isZero();
    assertThat(retained.transport().creates).isZero();
  }

  @Test
  void displayRefreshDoesNotLetStaleWorkerSettleReplacementOrRewriteOriginalBinding() {
    Fixture f = fixture("1032");
    var first = claim(f, 1);
    awaitProvisioningTime(f, "retry_at");
    var replacement = claim(f, first.subscriptionRowVersion());
    refreshDisplay(f);
    var before = f.dsl().fetch("SELECT * FROM subscription_provisioning_work").intoMaps();
    assertThatThrownBy(() -> f.tx().execute(s -> f.owner().deferUnresolved(first)))
        .isInstanceOf(FenceRejectedException.class);
    assertThatThrownBy(
            () ->
                f.tx()
                    .execute(s -> f.owner().claim(f.creation().requestId(), 1, first.attemptId())))
        .isInstanceOf(FenceRejectedException.class);
    assertThat(f.dsl().fetch("SELECT * FROM subscription_provisioning_work").intoMaps())
        .isEqualTo(before);
    // Private claims are not caller-constructible. A controlled changed-creation double still must
    // not substitute for the immutable retained original during the local settlement readback.
    var changedCreation = mock(AccountSubscriptionCreationRecord.class);
    when(changedCreation.requestId()).thenReturn(f.creation().requestId());
    var changedClaim = mock(ProvisioningClaim.class);
    when(changedClaim.creation()).thenReturn(changedCreation);
    when(changedClaim.attemptId()).thenReturn(replacement.attemptId());
    when(changedClaim.subscriptionRowVersion()).thenReturn(replacement.subscriptionRowVersion());
    assertThatThrownBy(() -> f.tx().execute(s -> f.owner().deferUnresolved(changedClaim)))
        .isInstanceOf(FenceRejectedException.class)
        .hasMessage("Exact retained provisioning settlement claim rejected");
    // The database also retains its existing owner-change guard; this component cannot invent an
    // unaudited transfer merely to manufacture a changed-current-owner state.
    assertThatThrownBy(
            () ->
                f.tx()
                    .execute(
                        s ->
                            f.dsl()
                                .execute(
                                    "UPDATE account_tenant_billing_owners SET owner_version = owner_version + 1, "
                                        + "owner_generation = ? WHERE tenant_uuid = ?",
                                    UUID.randomUUID(),
                                    f.creation().tenantId())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
    assertThat(f.dsl().fetch("SELECT * FROM subscription_provisioning_work").intoMaps())
        .isEqualTo(before);
    assertThat(f.dsl().fetchCount(DSL.table("subscription_provisioning_operator_outbox"))).isZero();
    assertThat(f.transport().creates).isZero();
  }

  private static void refreshDisplay(Fixture f) {
    var instruments = new AccountPaymentInstrumentRepository(f.dsl());
    var original = f.creation();
    var instrument =
        instruments
            .findByOwner(original.owner().accountUuid(), original.paymentInstrumentId())
            .orElseThrow();
    in(
        f.tx(),
        () ->
            instruments.recordAvailability(
                new AccountPaymentInstrumentRepository.AvailabilityObservation(
                    UUID.randomUUID(),
                    instrument.instrumentUuid(),
                    instrument.accountUuid(),
                    instrument.rowVersion(),
                    AccountPaymentInstrumentRecord.State.ATTACHABLE,
                    new AccountPaymentInstrumentRecord.DisplayMetadata("visa", "4242", 12, 2031),
                    DIGEST)));
  }

  private static ProviderDispatch prepareAndRead(Fixture f, ProvisioningClaim claim) {
    var prepared = f.tx().execute(s -> f.owner().prepareProviderDispatch(claim, UUID.randomUUID()));
    assertThat(prepared.readyForProviderIo()).isFalse();
    assertThat(prepared.initialCallAllowed()).isFalse();
    return f.tx().execute(s -> f.owner().readProviderDispatch(prepared));
  }

  private static ProvisioningClaim claim(Fixture f, long version) {
    ProvisioningClaim claim =
        Objects.requireNonNull(
            f.tx()
                .execute(
                    s ->
                        f.owner()
                            .claim(f.creation().requestId(), version, UUID.randomUUID())
                            .claim()));
    // The controlled provider observation clock follows the real, newly issued DB lease.
    f.clock().now = claim.claimedAt();
    return claim;
  }

  private static void awaitProvisioningTime(Fixture f, String field) {
    if (!List.of("retry_at", "lease_expires_at", "deadline_at").contains(field)) {
      throw new IllegalArgumentException("Only retained provisioning deadlines may be awaited");
    }
    Instant deadline =
        Objects.requireNonNull(
                f.dsl()
                    .fetchOne(
                        "SELECT "
                            + field
                            + " FROM subscription_provisioning_work WHERE request_id = ?",
                        f.creation().requestId()))
            .get(field, OffsetDateTime.class)
            .toInstant();
    awaitDatabaseTime(f, deadline);
  }

  private static void awaitDatabaseTime(Fixture f, Instant deadline) {
    Instant before =
        Objects.requireNonNull(f.dsl().fetchOne("SELECT clock_timestamp() AS now"))
            .get("now", OffsetDateTime.class)
            .toInstant();
    assertThat(Duration.between(before, deadline)).isLessThanOrEqualTo(Duration.ofSeconds(12));
    f.dsl().execute("SELECT pg_sleep_until(?::timestamptz)", deadline.atOffset(ZoneOffset.UTC));
    Instant after =
        Objects.requireNonNull(f.dsl().fetchOne("SELECT clock_timestamp() AS now"))
            .get("now", OffsetDateTime.class)
            .toInstant();
    assertThat(after).isAfterOrEqualTo(deadline);
    f.clock().now = after;
  }

  private static AccountHostingSubscriptionProvisioningService service(Fixture f) {
    migrate(f.source(), f.schema(), null);
    return migratedService(f);
  }

  private static AccountHostingSubscriptionProvisioningService migratedService(Fixture f) {
    f.dsl()
        .execute(
            "INSERT INTO account_hosting_billing_policy_values (provider_price_id, policy_version, quantity, amount, currency) "
                + "VALUES (?, 1, ?, 2400, 'usd') ON CONFLICT DO NOTHING",
            f.creation().planQuote().providerPriceId(),
            f.creation().planQuote().providerQuantity());
    f.dsl()
        .execute(
            "INSERT INTO account_hosting_billing_policy_current VALUES (?, 1) ON CONFLICT DO NOTHING",
            f.creation().planQuote().providerPriceId());
    var stripe = new com.stripe.StripeClient(f.transport());
    return new AccountHostingSubscriptionProvisioningService(
        f.owner(),
        new StripeHostingSubscriptionProvisioner(
            stripe, f.clock(), false, Duration.ofMillis(250), Duration.ofSeconds(1)),
        new net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient(
            stripe, f.clock(), Duration.ofMillis(250), Duration.ofSeconds(1)),
        new AccountHostingFirstInvoicePaymentRepository(f.dsl(), f.intents(), f.clock()),
        new DataSourceTransactionManager(f.source()));
  }

  private static Fixture fixture(String target) {
    return fixture(target, Duration.ofHours(1));
  }

  private static Fixture fixture(String target, Duration absoluteTimeout) {
    String schema = "hosting_dispatch_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    migrate(source, schema, target);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setTimeout(10);
    var catalog =
        new AccountHostingPlanCatalog(
            List.of(
                new AccountHostingPlanCatalog.ConfiguredPlan(
                    "hosting", "price_controlled", "1", "100", "2", "1048576")));
    var intents = new AccountSubscriptionIntentRepository(dsl, catalog);
    long row = GAME_ROWS.incrementAndGet();
    UUID accountUuid = enroll(dsl, transaction, row);
    UUID tenantId = UUID.randomUUID();
    UUID tenantCreateRequest = UUID.randomUUID();
    UUID tenantCreateOperation = UUID.randomUUID();
    String tenantKey = "hosting-fixture-game-" + row;
    String tenantRequestDigest =
        GameTenantCreationDigest.requestDigest(
            "prod", tenantCreateRequest, tenantKey, "Controlled hosting", "Storage only");
    var tenantSource =
        new FreshTenantCreationEvidence(
            1,
            "prod",
            tenantCreateRequest,
            tenantCreateOperation,
            tenantRequestDigest,
            tenantId,
            row,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "prod",
                tenantCreateRequest,
                tenantCreateOperation,
                tenantRequestDigest,
                tenantId,
                row,
                tenantKey,
                "NEW_GAME_ROW"));
    in(
        transaction,
        () -> {
          new FreshTenantIdentityAssociationRepository(dsl, "prod").importVerified(tenantSource);
          return new AccountAuthorityGenerationRepository(dsl).initializeTenantIfAbsent(tenantId);
        });

    UUID authorizationOperation = UUID.randomUUID();
    var creator =
        new FreshTenantCreatorEvidence(
            1,
            tenantSource,
            accountUuid,
            authorizationOperation,
            DIGEST,
            FreshTenantCreatorDigest.evidenceDigest(
                1, tenantSource, accountUuid, authorizationOperation, DIGEST));
    var owner =
        in(
            transaction,
            () ->
                new AccountInitialTenantBillingOwnerRepository(dsl)
                    .establishInitial(
                        new AccountInitialTenantBillingOwnerRepository.InitialRequest(
                            UUID.randomUUID(), creator, UUID.randomUUID(), 1, true)));

    var customers = new AccountBillingCustomerRepository(dsl);
    var pendingCustomer =
        in(
            transaction,
            () ->
                customers
                    .retainIntent(accountUuid, UUID.randomUUID(), UUID.randomUUID())
                    .customer());
    var customer =
        in(
            transaction,
            () ->
                customers.recordObservation(
                    accountUuid,
                    pendingCustomer.customerProvisioningRequestId(),
                    1,
                    UUID.randomUUID(),
                    AccountBillingCustomerRecord.Observation.CONFIRMED,
                    "cus_" + row,
                    DIGEST));
    var instrument =
        in(
            transaction,
            () ->
                new AccountPaymentInstrumentRepository(dsl)
                    .retainAttachment(
                        new AccountPaymentInstrumentRepository.Attachment(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            accountUuid,
                            customer.customerProvisioningRequestId(),
                            customer.providerCustomerId(),
                            "pm_" + row,
                            AccountPaymentInstrumentRecord.State.ATTACHABLE,
                            new AccountPaymentInstrumentRecord.DisplayMetadata(
                                "visa", "4242", 12, 2030),
                            DIGEST)));
    var creation =
        in(
            transaction,
            () ->
                intents.retainPending(
                    new AccountSubscriptionCreateRequest(
                        UUID.randomUUID(),
                        tenantId,
                        new OwnerFence(accountUuid, owner.ownerVersion(), owner.ownerGeneration()),
                        "hosting",
                        instrument.instrumentUuid(),
                        1,
                        new ProducerEvidence(accountUuid, UUID.randomUUID(), DIGEST),
                        null)));

    var clock = new MutableClock(creation.createdAt().plusSeconds(1));
    var lifecycleOwner =
        new AccountHostingSubscriptionLifecycleRepository(
            dsl,
            intents,
            clock,
            new AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits(
                4,
                3,
                absoluteTimeout.compareTo(Duration.ofSeconds(10)) < 0
                    ? absoluteTimeout
                    : Duration.ofSeconds(10),
                Duration.ofSeconds(1),
                absoluteTimeout),
            new AccountHostingSubscriptionLifecyclePolicy(
                Duration.ofSeconds(15),
                new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                    Duration.ofSeconds(60), Duration.ofSeconds(120))),
            "prod",
            false);
    var transport = new Transport(creation, instrument.providerPaymentMethodId(), clock);
    return new Fixture(
        source, schema, dsl, intents, transaction, creation, lifecycleOwner, clock, transport);
  }

  private static UUID enroll(DSLContext dsl, TransactionTemplate tx, long suffix) {
    return in(
        tx,
        () -> {
          // Controlled publications stipulate delivery; the actual fresh insertion/certification
          // owners
          // run normally. These fixtures do not qualify production publisher or protected-mount
          // custody.
          for (Category category : Category.values()) {
            UUID publication = UUID.randomUUID(),
                policy = UUID.randomUUID(),
                closure = UUID.randomUUID();
            String identity = AccountFreshEnrollmentPolicySource.sourceIdentity(category);
            byte[] bytes;
            try {
              bytes =
                  Rfc8785CanonicalJson.canonicalizeUtf8(
                      JsonMapper.builder()
                          .build()
                          .writeValueAsString(
                              Map.of(
                                  "schemaVersion",
                                  "account-fresh-enrollment-policy/v1",
                                  "category",
                                  category.storageValue(),
                                  "sourceIdentity",
                                  identity,
                                  "publicationId",
                                  publication.toString(),
                                  "revision",
                                  "1",
                                  "policyId",
                                  policy.toString(),
                                  "policyRule",
                                  "CERTIFY_NEW_ACTIVE_ACCOUNT",
                                  "closureId",
                                  closure.toString(),
                                  "closureRule",
                                  "EXACT_FRESH_INSERT_ONLY")));
            } catch (java.io.IOException failure) {
              throw new IllegalStateException(failure);
            }
            dsl.execute(
                "INSERT INTO account_fresh_enrollment_policy_publications "
                    + "(publication_id, category, revision, policy_id, closure_id, policy_rule, closure_rule, source_identity, source_root, publication_digest, publication_bytes) "
                    + "VALUES (?, ?, 1, ?, ?, 'CERTIFY_NEW_ACTIVE_ACCOUNT', 'EXACT_FRESH_INSERT_ONLY', ?, ?, ?, ?)",
                publication,
                category.storageValue(),
                policy,
                closure,
                identity,
                "/controlled-test/owner-protected-mount",
                AccountFreshEnrollmentPolicySource.digest(bytes),
                bytes);
            dsl.execute(
                "INSERT INTO account_fresh_enrollment_policy_current (category, publication_id, revision) VALUES (?, ?, 1)",
                category.storageValue(),
                publication);
          }
          var generations = new AccountAuthorityGenerationRepository(dsl);
          var outbox = new AccountAuthorityOutboxRepository(dsl);
          var restrictions =
              new AccountPlatformRestrictionOperationRepository(
                  dsl,
                  generations,
                  outbox,
                  new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox),
                  new AccountPlatformRestrictionBirthRepository(dsl));
          var result =
              new AccountFreshEnrollmentRepository(
                      dsl,
                      new AccountRepository(dsl),
                      new AccountFreshEnrollmentPolicyRepository(dsl),
                      restrictions)
                  .enroll(
                      new AccountFreshEnrollmentRequest(
                          UUID.randomUUID(),
                          "hosting-" + suffix,
                          "hosting-" + suffix + "@example.test",
                          "controlled-password-verifier",
                          true,
                          "PASSWORD,EMAIL_OTP"));
          return result.accountUuid();
        });
  }

  private static <T> T in(TransactionTemplate tx, java.util.function.Supplier<T> action) {
    return Objects.requireNonNull(tx.execute(s -> action.get()));
  }

  private static void migrate(DriverManagerDataSource source, String schema, String target) {
    var configuration =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration", "classpath:db/migration/saga");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private record Fixture(
      DriverManagerDataSource source,
      String schema,
      DSLContext dsl,
      AccountSubscriptionIntentRepository intents,
      TransactionTemplate tx,
      AccountSubscriptionCreationRecord creation,
      AccountHostingSubscriptionLifecycleRepository owner,
      MutableClock clock,
      Transport transport) {}

  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class Transport implements StripeResponseGetter {
    private final AccountSubscriptionCreationRecord creation;
    private final String paymentMethod;
    private final Clock clock;
    private boolean exists;
    private boolean loseCreateResponse;
    private Runnable duringCreate;
    private int emptySearchesRemaining;
    private int emptyListsRemaining;
    private int searches;
    private int lists;
    private int creates;
    private int subscriptionReads;
    private int requests;

    private Transport(
        AccountSubscriptionCreationRecord creation, String paymentMethod, Clock clock) {
      this.creation = creation;
      this.paymentMethod = paymentMethod;
      this.clock = clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress base,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      requests++;
      String response;
      if (path.equals("/v1/customers/" + creation.providerCustomerId())) {
        response =
            "{\"object\":\"customer\",\"id\":\""
                + creation.providerCustomerId()
                + "\",\"livemode\":false,\"deleted\":false,\"metadata\":{\"firemud_metadata_schema_version\":\"1\","
                + "\"firemud_operation_type\":\"customer_provisioning\",\"firemud_operation_id\":\""
                + creation.customerProvisioningRequestId()
                + "\",\"firemud_account_id\":\""
                + creation.owner().accountUuid()
                + "\"}}";
      } else if (path.equals("/v1/payment_methods/" + paymentMethod)) {
        response =
            "{\"object\":\"payment_method\",\"id\":\""
                + paymentMethod
                + "\",\"customer\":\""
                + creation.providerCustomerId()
                + "\",\"livemode\":false,\"type\":\"card\",\"card\":{\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}}";
      } else if (path.equals("/v1/subscriptions/search")) {
        searches++;
        assertThat(params.get("query").toString())
            .contains(
                creation.subscriptionProvisioningRequestId().toString(),
                creation.subscriptionId().toString());
        boolean visible = exists && emptySearchesRemaining == 0;
        if (emptySearchesRemaining > 0) {
          emptySearchesRemaining--;
        }
        response =
            "{\"object\":\"search_result\",\"has_more\":false,\"data\":["
                + (visible ? subscription() : "")
                + "]}";
      } else if ("/v1/subscriptions".equals(path) && method == RequestMethod.GET) {
        lists++;
        assertThat(params)
            .containsEntry("customer", creation.providerCustomerId())
            .containsEntry("status", "all");
        boolean visible = exists && emptyListsRemaining == 0;
        if (emptyListsRemaining > 0) {
          emptyListsRemaining--;
        }
        response =
            "{\"object\":\"list\",\"has_more\":false,\"data\":["
                + (visible ? subscription() : "")
                + "]}";
      } else if (path.equals("/v1/subscriptions") && method == RequestMethod.POST) {
        creates++;
        exists = true;
        assertThat(options.getIdempotencyKey())
            .isEqualTo(creation.subscriptionProvisioningRequestId().toString());
        assertThat(params)
            .containsEntry("customer", creation.providerCustomerId())
            .containsEntry("default_payment_method", paymentMethod)
            .containsEntry("payment_behavior", "default_incomplete");
        assertThat(params.get("items"))
            .isEqualTo(
                List.of(
                    Map.of(
                        "price",
                        creation.planQuote().providerPriceId(),
                        "quantity",
                        creation.planQuote().providerQuantity())));
        if (duringCreate != null) {
          Runnable action = duringCreate;
          duringCreate = null;
          action.run();
        }
        if (loseCreateResponse) {
          throw org.mockito.Mockito.mock(StripeException.class);
        }
        response = subscription();
      } else if (path.equals("/v1/invoices/in_exact")) {
        response =
            "{\"object\":\"invoice\",\"id\":\"in_exact\",\"customer\":\""
                + creation.providerCustomerId()
                + "\",\"livemode\":false,\"billing_reason\":\"subscription_create\",\"parent\":{\"subscription_details\":{\"subscription\":\"sub_exact\"}},"
                + "\"total\":2400,\"starting_balance\":0,\"amount_due\":2400,\"amount_paid\":0,\"amount_remaining\":2400,\"currency\":\"usd\",\"status\":\"open\","
                + "\"lines\":{\"has_more\":false,\"data\":[{\"object\":\"line_item\",\"amount\":2400,\"currency\":\"usd\",\"quantity\":"
                + creation.planQuote().providerQuantity()
                + ",\"pricing\":{\"type\":\"price_details\",\"price_details\":{\"price\":\""
                + creation.planQuote().providerPriceId()
                + "\"}}}]},\"payments\":{\"has_more\":false,\"data\":[{\"payment\":{\"type\":\"payment_intent\",\"payment_intent\":{"
                + "\"object\":\"payment_intent\",\"id\":\"pi_exact\",\"customer\":\""
                + creation.providerCustomerId()
                + "\",\"payment_method\":\""
                + paymentMethod
                + "\",\"livemode\":false,\"amount\":2400,\"currency\":\"usd\","
                + "\"confirmation_method\":\"automatic\",\"status\":\"requires_confirmation\"}}}]}}";
      } else if (path.equals("/v1/subscriptions/sub_exact")) {
        subscriptionReads++;
        response = subscription();
      } else {
        throw new AssertionError("Unexpected provider path " + path);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    private String subscription() {
      return "{\"object\":\"subscription\",\"id\":\"sub_exact\",\"customer\":\""
          + creation.providerCustomerId()
          + "\",\"default_payment_method\":\""
          + paymentMethod
          + "\",\"livemode\":false,\"status\":\"incomplete\",\"latest_invoice\":\"in_exact\",\"created\":"
          + clock.instant().getEpochSecond()
          + ",\"cancel_at_period_end\":false,"
          + "\"metadata\":{\"firemud_metadata_schema_version\":\"1\",\"firemud_operation_type\":\"subscription_provisioning\","
          + "\"firemud_operation_id\":\""
          + creation.subscriptionProvisioningRequestId()
          + "\",\"firemud_subscription_id\":\""
          + creation.subscriptionId()
          + "\"},"
          + "\"items\":{\"has_more\":false,\"data\":[{\"id\":\"si_exact\",\"object\":\"subscription_item\",\"subscription\":\"sub_exact\","
          + "\"quantity\":"
          + creation.planQuote().providerQuantity()
          + ",\"current_period_start\":"
          + clock.instant().minusSeconds(5).getEpochSecond()
          + ",\"current_period_end\":"
          + clock.instant().plusSeconds(3600).getEpochSecond()
          + ",\"price\":{\"id\":\""
          + creation.planQuote().providerPriceId()
          + "\",\"object\":\"price\"}}]}}";
    }

    @Override
    public InputStream requestStream(
        BaseAddress base,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode) {
      throw new AssertionError("No streaming");
    }
  }
}
