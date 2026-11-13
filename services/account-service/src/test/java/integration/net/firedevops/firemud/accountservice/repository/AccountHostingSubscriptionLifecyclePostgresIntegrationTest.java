package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture.ControlledFirstInvoiceTransport;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture.MutableClock;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecyclePolicy.Status;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.AuthorizedActor;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionTerminalResolution.Request;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL + actual Stripe SDK over CONTROLLED transport. Initial positive authority uses
 * real primary reauthentication, exact billing consent and a verified test-signed first invoice.
 * Creator/customer/wallet, policy and signing-key inputs are controlled, not live Stripe or
 * protected production-custody qualification. No trigger is disabled, no confirmed SDK value is
 * fabricated, and no remote Stripe call is made.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountHostingSubscriptionLifecyclePostgresIntegrationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final AtomicLong ROWS = new AtomicLong(10_000);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualSdkReadCommitsExactMappingReceiptCanonicalStreamsAndConsistentReaderThenReplays() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = paidClaim(f);
    UUID request = claim.lifecycleRequestId();
    var committed =
        f.service()
            .reconcile(
                request,
                f.creation().requestId(),
                claim.expectedRowVersion(),
                f.providerId(),
                null);
    assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(committed.committedRowVersion()).isEqualTo(5);
    assertThat(
            in(
                c,
                () ->
                    new AccountHostingFirstInvoicePaymentRepository(c.dsl(), c.intents(), f.clock())
                        .completed(f.creation().subscriptionId(), request)))
        .isTrue();
    assertThat(
            one(
                    c,
                    "SELECT completion.committed_transaction_id = receipt.committed_transaction_id AS same_transaction "
                        + "FROM subscription_first_invoice_completions completion JOIN subscription_lifecycle_receipts receipt "
                        + "ON receipt.request_id = completion.lifecycle_request_id JOIN account_audit_outbox audit "
                        + "ON audit.audit_event_id = completion.audit_event_id WHERE completion.lifecycle_request_id = ?",
                    request)
                .get("same_transaction", Boolean.class))
        .isTrue();
    for (String table :
        List.of(
            "subscription_first_invoices",
            "subscription_first_invoice_confirmations",
            "subscription_first_invoice_dispatches",
            "subscription_first_invoice_success_evidence",
            "subscription_first_invoice_completions")) {
      assertSqlState(
          "23514",
          () -> c.setup().execute("UPDATE " + table + " SET subscription_id = subscription_id"));
    }
    var current = f.service().readCurrent(f.creation().tenantId());
    assertThat(current.subscriptionStatus()).isEqualTo(Status.ACTIVE);
    assertThat(current.gameplayAvailable()).isTrue();
    assertThat(current.allowPublicJoin()).isTrue();
    assertThat(current.current().planQuote()).isEqualTo(f.creation().planQuote());
    assertThat(current.current().providerSubscriptionId()).isEqualTo(f.providerId());
    assertThat(current.entitlementVersion()).isOne();
    assertThat(current.tenantBillingSequence()).isOne();
    assertThat(current.tenantAuthorityEvent().tenantBillingSequence())
        .isEqualTo(current.tenantBillingSequence());
    assertThat(current.tenantAuthorityEvent().tenantBillingEventDigest())
        .isEqualTo(current.billingEventDigest());
    var source =
        one(
            c,
            "SELECT * FROM account_tenant_authority_source_records WHERE tenant_uuid = ?",
            f.creation().tenantId());
    assertThat(source.get("last_request_id", UUID.class)).isEqualTo(request);
    assertThat(source.get("current_generation", Long.class)).isEqualTo(2);
    assertThat(
            one(
                    c,
                    "SELECT o.recorded_transaction_id = r.committed_transaction_id AS same_transaction "
                        + "FROM subscription_lifecycle_observations o JOIN subscription_lifecycle_receipts r USING (attempt_id) WHERE r.request_id = ?",
                    request)
                .get("same_transaction", Boolean.class))
        .isTrue();
    assertThat(
            one(
                    c,
                    "SELECT phase FROM subscription_provisioning_work WHERE request_id = ?",
                    f.creation().requestId())
                .get("phase", String.class))
        .isEqualTo("CONFIRMED");
    assertThat(count(c, "subscription_provider_history")).isOne();
    assertThat(count(c, "account_demo_tenant_entitlements")).isZero();
    int reads = f.transport().reads;
    assertThat(
            f.service()
                .reconcile(
                    request,
                    f.creation().requestId(),
                    claim.expectedRowVersion(),
                    f.providerId(),
                    null))
        .isEqualTo(committed);
    assertThat(f.transport().reads).isEqualTo(reads);
    assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
    assertThat(count(c, "account_tenant_entitlement_outbox_events")).isOne();
    assertThat(count(c, "account_authority_outbox_events")).isOne();
    assertThatThrownBy(
            () ->
                f.service()
                    .reconcile(
                        request,
                        f.creation().requestId(),
                        claim.expectedRowVersion(),
                        "sub_other",
                        null))
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThat(f.transport().reads).isEqualTo(reads);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1046", "latest"})
  void confirmedWorkRejectsMissingOrMismatchedFirstInvoiceCompletionAndRollsBackFulfillment(
      String migrationTarget) throws StripeException {
    for (String fault : List.of("missing", "tuple", "receipt", "transaction")) {
      Context c = context(migrationTarget);
      Fixture f = fixture(c);
      var claim = migrationTarget.equals("1046") ? paidClaimAt1046(f) : paidClaim(f);
      var prepared =
          in(
              c,
              () ->
                  f.owner()
                      .prepare(
                          claim.lifecycleRequestId(),
                          f.creation().requestId(),
                          claim.expectedRowVersion(),
                          f.providerId(),
                          null));
      var sdk = f.observer().observe(prepared.binding());
      List<String> before = firstInvoiceImage(c);
      // PostgreSQL orders same-kind triggers by name. This fault runs after the genuine
      // first_invoice_completion validator, changing only otherwise schema-valid evidence.
      // All production validators, foreign keys and deferred guards remain enabled.
      String mutation =
          switch (fault) {
            case "missing" -> "RETURN NULL;";
            case "tuple" ->
                "NEW.tuple_digest := 'sha256:' || repeat(CASE WHEN NEW.tuple_digest = 'sha256:' || repeat('b', 64) THEN 'c' ELSE 'b' END, 64);";
            case "receipt" ->
                "NEW.receipt_digest := 'sha256:' || repeat(CASE WHEN NEW.receipt_digest = 'sha256:' || repeat('b', 64) THEN 'c' ELSE 'b' END, 64);";
            case "transaction" ->
                "NEW.committed_transaction_id := NEW.committed_transaction_id + 1;";
            default -> throw new AssertionError("Unknown completion fault: " + fault);
          };
      c.setup()
          .execute(
              "CREATE FUNCTION reject_exact_first_invoice_completion() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                  + mutation
                  + " RETURN NEW; END; $$");
      c.setup()
          .execute(
              "CREATE TRIGGER zz_first_invoice_completion_fault BEFORE INSERT ON subscription_first_invoice_completions FOR EACH ROW EXECUTE FUNCTION reject_exact_first_invoice_completion()");
      AtomicInteger applied = new AtomicInteger();
      // V1046.1 rejects a missing completion at the earlier subscription consistency event.
      // Keep the older frontier to witness the provisioning predicate independently; malformed
      // completions still reach that exact predicate on both frontiers. Neither guard is disabled.
      String expectedDiagnostic =
          migrationTarget.equals("latest") && fault.equals("missing")
              ? "Current positive hosting subscription requires exact first-invoice completion"
              : "Provisioning confirmation lacks its exact lifecycle receipt";
      assertSqlState(
          "23514",
          expectedDiagnostic,
          () ->
              in(
                  c,
                  () -> {
                    var result = f.owner().apply(prepared, sdk);
                    assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
                    assertThat(c.dsl().fetchCount(DSL.table("subscription_lifecycle_receipts")))
                        .isOne();
                    assertThat(
                            c.dsl()
                                .fetchCount(DSL.table("account_tenant_entitlement_outbox_events")))
                        .isOne();
                    assertThat(c.dsl().fetchCount(DSL.table("account_authority_outbox_events")))
                        .isOne();
                    applied.incrementAndGet();
                    return result;
                  }));
      assertThat(applied).as(fault).hasValue(1);
      assertThat(firstInvoiceImage(c)).as(fault).isEqualTo(before);
      assertThat(
              one(
                      c,
                      "SELECT phase FROM subscription_provisioning_work WHERE request_id = ?",
                      f.creation().requestId())
                  .get("phase", String.class))
          .isEqualTo("AWAITING_PAYMENT");
    }
  }

  @Test
  void originalProvisioningClaimStillCannotConfirmWithoutItsExactLifecycleReceipt() {
    Context c = context("latest");
    Fixture f = fixture(c);
    claim(f);
    List<String> before = firstInvoiceImage(c);
    assertSqlState(
        "23514",
        "Provisioning confirmation lacks its exact lifecycle receipt",
        () ->
            in(
                c,
                () ->
                    c.dsl()
                        .execute(
                            "UPDATE subscription_provisioning_work SET phase = 'CONFIRMED', row_version = row_version + 1 WHERE request_id = ?",
                            f.creation().requestId())));
    assertThat(firstInvoiceImage(c)).isEqualTo(before);
  }

  @Test
  void providerActiveOrTrialingWithoutFirstInvoiceConsentCannotCreateHostingAuthority() {
    for (String status : List.of("active", "trialing")) {
      Context c = context("latest");
      Fixture f = fixture(c);
      f.transport().json = response(f, status);
      var claim = claim(f);
      assertThatThrownBy(
              () ->
                  f.service()
                      .reconcile(
                          UUID.randomUUID(),
                          f.creation().requestId(),
                          claim.subscriptionRowVersion(),
                          f.providerId(),
                          claim.attemptId()))
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class)
          .hasMessageContaining("first-invoice");
      for (String table :
          List.of(
              "subscription_first_invoices",
              "subscription_first_invoice_confirmations",
              "subscription_first_invoice_dispatches",
              "subscription_first_invoice_completions",
              "subscription_lifecycle_receipts",
              "account_tenant_entitlement_outbox_events")) {
        assertThat(count(c, table)).as(table).isZero();
      }
      assertThat(
              one(
                      c,
                      "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                      f.creation().requestId())
                  .get("released_at"))
          .isNull();
      assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
          .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    }
  }

  @Test
  void lateSignedSuccessCompletesConsumedAuthorizationWithoutAnotherProviderConfirmation() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var authorized = checkout.authorize(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    var generations = new AccountAuthorityGenerationRepository(c.dsl());
    var accountScope =
        AccountAuthorityGenerationRepository.AuthorityScope.account(
            f.creation().owner().accountUuid());
    var authorityBefore = in(c, () -> generations.read(accountScope));
    var accounts =
        new AccountRepository(
            c.dsl(),
            new AccountAuthoritySourceEvidenceRepository(
                c.dsl(), generations, new AccountAuthorityOutboxRepository(c.dsl())));
    in(
        c,
        () -> {
          var account =
              accounts.findByAccountUuidForUpdate(f.creation().owner().accountUuid()).orElseThrow();
          account.setRole("admin");
          return accounts.save(account);
        });
    assertThat(in(c, () -> generations.read(accountScope)).generation())
        .isGreaterThan(authorityBefore.generation());
    // This generic source advance cannot renew actor/charge authority, but cannot erase an already
    // consumed exact payment authorization or prevent read-only signed outcome reconciliation.
    f.clock().advance(Duration.ofSeconds(301));
    assertThat(f.clock().instant()).isAfter(authorized.validUntil());
    var success = checkout.retainSignedSuccess();
    assertThat(success.requestId()).isEqualTo(authorized.requestId());
    assertThat(success.providerIdempotencyId()).isEqualTo(authorized.providerIdempotencyId());
    assertThat(
            f.service()
                .reconcile(
                    success.lifecycleRequestId(),
                    f.creation().requestId(),
                    success.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(f.service().readCurrent(f.creation().tenantId()).receiptRequestId())
        .isEqualTo(success.lifecycleRequestId());
    assertThat(
            in(
                c,
                () ->
                    checkout
                        .payments()
                        .completed(f.creation().subscriptionId(), success.lifecycleRequestId())))
        .isTrue();
    assertThat(checkout.confirmations()).isOne();
    assertThatThrownBy(
            () ->
                checkout
                    .provider()
                    .confirm(
                        authorized,
                        () -> {
                          throw new AssertionError(
                              "Expired consent must not request a provider command claim");
                        }))
        .hasMessageContaining("consent");
    assertThat(checkout.confirmations()).isOne();
    assertThat(count(c, "subscription_first_invoice_dispatches")).isOne();
    assertThat(count(c, "subscription_first_invoice_completions")).isOne();
  }

  @Test
  void firstInvoiceConsentHonorsTheConfiguredIssuingClockSkew() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    f.clock().advance(Duration.ofSeconds(30));
    var dispatch =
        checkout.prepareConfirmationDispatch(
            CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD,
            () -> f.clock().advance(Duration.ofSeconds(-30)));
    var retained =
        one(
            c,
            "SELECT consent.consented_at, auth.consented_at, auth.valid_until, "
                + "proof.issued_at FROM subscription_first_invoice_confirmations consent "
                + "JOIN subscription_first_invoice_authorizations auth USING (request_id) "
                + "JOIN account_recent_reauthentication_evidence proof "
                + "ON proof.evidence_id = auth.reauthentication_evidence_id "
                + "WHERE consent.request_id = ?",
            dispatch.requestId());
    var issuedAt = retained.get(3, java.time.OffsetDateTime.class).toInstant();
    assertThat(retained.get(0, java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(issuedAt.minusSeconds(30));
    assertThat(retained.get(1, java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(issuedAt.minusSeconds(30));
    assertThat(retained.get(2, java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(issuedAt.plusSeconds(270));
    assertThat(checkout.confirmations()).isZero();
  }

  @Test
  void authorizationExpiryAfterCommittedCommandClaimRetainsImmutableNoSendEvidence() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var dispatch =
        checkout.prepareConfirmationDispatch(
            CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    var observation =
        assertThrows(
            net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient
                .ExpiredBeforePost.class,
            () ->
                checkout
                    .provider()
                    .confirm(
                        dispatch,
                        () -> {
                          var claim =
                              in(
                                  c,
                                  () ->
                                      checkout
                                          .payments()
                                          .claimCommand(
                                              dispatch, checkout.invoice().ownerAccountId()));
                          assertThat(claim).isNotNull();
                          f.clock().advanceToAtLeast(dispatch.validUntil());
                          assertThat(f.clock().instant()).isEqualTo(dispatch.validUntil());
                          return claim;
                        }));
    assertThat(observation.requestId()).isEqualTo(dispatch.requestId());
    assertThat(observation.observedAt()).isEqualTo(dispatch.validUntil());
    assertThat(checkout.confirmations()).isZero();
    assertThat(count(c, "subscription_first_invoice_command_claims")).isOne();

    // Mirrors the production owner's independent REQUIRES_NEW retention transaction.
    in(
        c,
        () -> {
          checkout.payments().retainExpiredBeforePost(observation);
          return true;
        });
    in(
        c,
        () -> {
          checkout.payments().retainExpiredBeforePost(observation);
          return true;
        });
    var retained =
        one(
            c,
            "SELECT evidence.request_id, evidence.observed_at, claim.claimed_at, auth.valid_until "
                + "FROM subscription_first_invoice_expired_before_post evidence "
                + "JOIN subscription_first_invoice_command_claims claim USING (request_id) "
                + "JOIN subscription_first_invoice_authorizations auth "
                + "USING (authorization_request_id) WHERE evidence.request_id = ?",
            observation.requestId());
    assertThat(retained.get("request_id", UUID.class)).isEqualTo(dispatch.requestId());
    assertThat(retained.get(1, java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(observation.observedAt());
    assertThat(retained.get(2, java.time.OffsetDateTime.class).toInstant())
        .isBefore(observation.observedAt());
    assertThat(retained.get(3, java.time.OffsetDateTime.class).toInstant())
        .isEqualTo(observation.observedAt());
    assertThat(count(c, "subscription_first_invoice_expired_before_post")).isOne();
    assertThat(checkout.confirmations()).isZero();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
    assertThat(
            one(
                    c,
                    "SELECT phase FROM subscription_provisioning_work WHERE request_id = ?",
                    f.creation().requestId())
                .get("phase", String.class))
        .isEqualTo("AWAITING_PAYMENT");

    for (String sql :
        List.of(
            "UPDATE subscription_first_invoice_expired_before_post SET observed_at = observed_at",
            "DELETE FROM subscription_first_invoice_expired_before_post",
            "TRUNCATE subscription_first_invoice_expired_before_post, subscription_first_invoice_no_send_cancellations")) {
      assertSqlState("23514", () -> c.setup().execute(sql));
    }
  }

  @Test
  void trialUsesRetainedQuoteWithoutTodaysCatalogAndSqlShapedRowsAloneNeverAuthorize() {
    Context c = context("latest");
    Fixture f = fixture(c);
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.transport().reads).isZero();
    f.transport().json = response(f, "active");
    var emptyCatalogRepository =
        new AccountSubscriptionIntentRepository(c.dsl(), new AccountHostingPlanCatalog(List.of()));
    var owner = owner(c, f.clock(), emptyCatalogRepository);
    var service = service(c, owner, f.transport(), f.clock());
    var claim = paidClaim(f);
    assertThat(
            service
                .reconcile(
                    claim.lifecycleRequestId(),
                    f.creation().requestId(),
                    claim.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(
            in(
                c,
                () ->
                    new AccountHostingFirstInvoicePaymentRepository(c.dsl(), c.intents(), f.clock())
                        .completed(f.creation().subscriptionId(), claim.lifecycleRequestId())))
        .isTrue();
    f.transport().json = response(f, "trialing");
    long currentVersion =
        c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow().rowVersion();
    assertThat(
            service
                .reconcile(
                    UUID.randomUUID(),
                    f.creation().requestId(),
                    currentVersion,
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(service.readCurrent(f.creation().tenantId()).subscriptionStatus())
        .isEqualTo(Status.TRIALING);
    assertThat(service.readCurrent(f.creation().tenantId()).current().planQuote())
        .isEqualTo(f.creation().planQuote());
    assertSqlState(
        "23514",
        () -> c.setup().execute("UPDATE subscription_lifecycle_receipts SET status = 'active'"));
    assertSqlState(
        "23514",
        () ->
            c.setup().execute("UPDATE subscription_lifecycle_observations SET payload = payload"));
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET plan_quote_digest = ?, row_version = row_version + 1 WHERE subscription_id = ?",
                    "sha256:" + "f".repeat(64),
                    f.creation().subscriptionId()));
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE billing_customer SET provider_customer_id = 'cus_wrong', row_version = row_version + 1 WHERE account_uuid = ?",
                    f.creation().owner().accountUuid()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"trialing", "active", "past_due", "grace"})
  void populatedPreCompletionSchemaRefusesActivationWithoutRewritingPositiveRows(String status) {
    Context c = context("1040");
    Fixture f = fixture(c);
    retainUnqualifiedPositive(f, status);
    assertThat(c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow().status())
        .isEqualTo(status);
    // Controlled schema-valid predecessor data, not a genuine historical signed receipt.
    var before =
        one(
            c,
            "SELECT to_jsonb(s)::text, xmin::text FROM subscription s WHERE subscription_id = ?",
            f.creation().subscriptionId());
    var history = c.setup().fetch("SELECT * FROM subscription_provider_history").intoMaps();
    configuration(c).target("1046").load().migrate();
    List<String> evidenceBefore = firstInvoiceImage(c);
    var flywayBefore =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
    assertSqlState(
        "23514",
        "Retained positive hosting subscriptions",
        () -> configuration(c).target("1046.1").load().migrate());
    assertThat(
            c.setup()
                .fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank")
                .intoMaps())
        .isEqualTo(flywayBefore);
    assertThatThrownBy(() -> inspectRetainedPositivePrerequisite(f))
        .hasMessageContaining("HOSTING_ACTIVATION_UNAVAILABLE");
    assertThat(
            one(
                c,
                "SELECT to_jsonb(s)::text, xmin::text FROM subscription s WHERE subscription_id ="
                    + " ?",
                f.creation().subscriptionId()))
        .isEqualTo(before);
    assertThat(c.setup().fetch("SELECT * FROM subscription_provider_history").intoMaps())
        .isEqualTo(history);
    assertThat(firstInvoiceImage(c)).isEqualTo(evidenceBefore);
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThatThrownBy(
            () ->
                f.service()
                    .reconcile(
                        UUID.randomUUID(), f.creation().requestId(), 2, f.providerId(), null))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    assertThat(f.transport().reads).isZero();
    assertThat(count(c, "subscription_lifecycle_receipts")).isZero();
    assertThat(count(c, "account_tenant_authority_source_records")).isZero();
  }

  @Test
  void completedAndNegativeSubscriptionsDoNotFailRetainedPositivePrerequisite() {
    for (String status : List.of("active", "canceled")) {
      Context c = context("latest");
      Fixture f = fixture(c);
      f.transport().json = response(f, status);
      activate(f);
      List<String> before = firstInvoiceImage(c);
      inspectRetainedPositivePrerequisite(f);
      assertThat(firstInvoiceImage(c)).isEqualTo(before);
    }
  }

  private static void retainUnqualifiedPositive(Fixture f, String status) {
    Context c = f.context();
    c.tx()
        .executeWithoutResult(
            s -> {
              c.dsl()
                  .execute(
                      "INSERT INTO subscription_provider_history (provider_subscription_id,"
                          + " subscription_id, operation_kind, request_id, provider_operation_id,"
                          + " role, effective_at, evidence_digest) VALUES (?, ?,"
                          + " 'SUBSCRIPTION_PROVISIONING', ?, ?, 'canonical', ?::timestamptz, ?)",
                      f.providerId(),
                      f.creation().subscriptionId(),
                      f.creation().requestId(),
                      f.creation().subscriptionProvisioningRequestId(),
                      f.clock().instant().atOffset(ZoneOffset.UTC),
                      DIGEST);
              c.dsl()
                  .execute(
                      "UPDATE subscription SET status = ?, provider_subscription_id = ?,"
                          + " row_version = row_version + 1 WHERE subscription_id = ?",
                      status,
                      f.providerId(),
                      f.creation().subscriptionId());
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"trialing", "active", "past_due", "grace"})
  void committedPositiveWithoutCompletionIsRejectedAfterUpgrade(String status) {
    Context c = context("latest");
    Fixture f = fixture(c);
    List<String> before = firstInvoiceImage(c);
    assertSqlState(
        "23514",
        "Current positive hosting subscription",
        () -> retainUnqualifiedPositive(f, status));
    assertThat(firstInvoiceImage(c)).isEqualTo(before);
    assertThat(c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow().status())
        .isEqualTo("pending");
  }

  @Test
  void genuineSameTransactionCompletionRemainsImmutableAfterUpgrade() {
    Context c = context("latest");
    Fixture f = fixture(c);
    activate(f);
    assertThat(count(c, "subscription_first_invoice_completions")).isOne();
    List<String> before = firstInvoiceImage(c);
    for (String sql :
        List.of(
            "DELETE FROM subscription",
            "DELETE FROM subscription_first_invoice_completions",
            "UPDATE subscription_first_invoice_completions SET committed_at = committed_at",
            "TRUNCATE subscription_first_invoice_completions")) {
      assertThatThrownBy(() -> c.setup().execute(sql))
          .isInstanceOf(org.jooq.exception.DataAccessException.class);
      assertThat(firstInvoiceImage(c)).isEqualTo(before);
    }
  }

  @Test
  void upgradeSerializesConcurrentInsertAndUpdateThenEnforcesFinalCompletion() throws Exception {
    Context c = context("1046");
    Fixture existing = fixture(c);
    String writerName = "hosting_gate_" + UUID.randomUUID().toString().replace("-", "");
    try (var workers = Executors.newVirtualThreadPerTaskExecutor();
        var migration = c.source().getConnection()) {
      migration.setAutoCommit(false);
      try (var sql = migration.createStatement();
          var resource =
              Objects.requireNonNull(
                  getClass()
                      .getResourceAsStream(
                          "/db/migration/V1046.1__hosting_retained_positive_completion_gate.sql"))) {
        sql.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
      }
      var update =
          workers.submit(
              () -> {
                assertSqlState(
                    "23514",
                    "Current positive hosting subscription",
                    () ->
                        c.tx()
                            .executeWithoutResult(
                                ignored -> {
                                  c.dsl()
                                      .execute(
                                          "SELECT set_config('application_name', ?, true), set_config('statement_timeout', '10s', true)",
                                          writerName + "_update");
                                  retainUnqualifiedPositive(existing, "active");
                                }));
              });
      var insert =
          workers.submit(
              () ->
                  in(
                      c,
                      () -> {
                        c.dsl()
                            .execute(
                                "SELECT set_config('application_name', ?, true), set_config('statement_timeout', '10s', true)",
                                writerName + "_insert");
                        return fixture(c);
                      }));
      awaitSubscriptionWriterLock(c, writerName + "_update");
      awaitSubscriptionWriterLock(c, writerName + "_insert");
      migration.commit();
      update.get(5, TimeUnit.SECONDS);
      Fixture pending = insert.get(5, TimeUnit.SECONDS);
      assertThat(
              c.intents().findCurrentByTenant(pending.creation().tenantId()).orElseThrow().status())
          .isEqualTo("pending");
      assertThat(
              c.intents()
                  .findCurrentByTenant(existing.creation().tenantId())
                  .orElseThrow()
                  .status())
          .isEqualTo("pending");
      assertThat(count(c, "subscription_first_invoice_completions")).isZero();
      // Failed UPDATE cannot leave its inserted provider mapping behind. The successful INSERT
      // is an ordinary pending subscription, not synthesized payment or migration authority.
      assertThat(c.setup().fetchCount(DSL.table("subscription_provider_history"))).isZero();
    }
  }

  private static void awaitSubscriptionWriterLock(Context c, String applicationName)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    do {
      Boolean waiting =
          Objects.requireNonNull(
                  c.setup()
                      .fetchOne(
                          "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name = ? "
                              + "AND wait_event_type = 'Lock')",
                          applicationName))
              .get(0, Boolean.class);
      if (Boolean.TRUE.equals(waiting)) return;
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Concurrent subscription writer never reached the migration lock");
  }

  private static void inspectRetainedPositivePrerequisite(Fixture f) {
    Context c = f.context();
    var readOnly = new TransactionTemplate(Objects.requireNonNull(c.tx().getTransactionManager()));
    readOnly.setReadOnly(true);
    readOnly.setTimeout(5);
    readOnly.executeWithoutResult(
        ignored -> {
          assertThat(c.dsl().fetchSingle("SHOW transaction_read_only").get(0, String.class))
              .isEqualTo("on");
          f.owner().requireNoRetainedPositiveCompletionGaps(2);
        });
  }

  @Test
  void hostingExtendsExistingDemoStreamsWithoutRewritingOrReinterpretingDemoEvidence() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var identity = new FreshTenantIdentityAssociationRepository(c.dsl(), "prod");
    var generations = new AccountAuthorityGenerationRepository(c.dsl());
    var billing = new AccountTenantEntitlementOutboxRepository(c.dsl());
    var events =
        new AccountTenantAuthorityEventRepository(
            c.dsl(), new AccountAuthorityOutboxRepository(c.dsl()), billing, identity, generations);
    var demo =
        new AccountDemoTenantEntitlementRepository(c.dsl(), identity, generations, billing, events);
    var source = in(c, () -> identity.read(f.creation().tenantId()).orElseThrow());
    var demoRequest =
        new DemoTenantEntitlementRequest(
            UUID.randomUUID(),
            source.canonicalTenantId(),
            source.creationRequestId(),
            source.requestDigest(),
            null,
            null,
            null,
            true,
            true,
            true,
            true,
            new DemoTenantEntitlementRequest.Quotas(5, 1, 1024));
    in(c, () -> demo.provision(demoRequest, source));
    var demoRow =
        one(
            c,
            "SELECT row_to_json(e)::text AS value, xmin::text AS xmin FROM account_demo_tenant_entitlements e WHERE tenant_uuid = ?",
            source.canonicalTenantId());
    byte[] demoEvent =
        one(
                c,
                "SELECT payload FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND tenant_billing_sequence = 1",
                source.canonicalTenantId())
            .get("payload", byte[].class);
    var authorityStreamKeys =
        c.setup()
            .fetch(
                "SELECT outbox_stream_key FROM account_authority_outbox_streams ORDER BY outbox_stream_key")
            .getValues(0, String.class);
    activate(f);
    var current = f.service().readCurrent(source.canonicalTenantId());
    assertThat(current.entitlementVersion()).isEqualTo(2);
    assertThat(current.tenantBillingSequence()).isEqualTo(2);
    assertThat(current.tenantAuthorityEvent().tenantAuthorityGeneration()).isEqualTo(3);
    assertThat(current.subscriptionStatus()).isEqualTo(Status.ACTIVE);
    assertThat(
            one(
                c,
                "SELECT row_to_json(e)::text AS value, xmin::text AS xmin FROM account_demo_tenant_entitlements e WHERE tenant_uuid = ?",
                source.canonicalTenantId()))
        .isEqualTo(demoRow);
    assertThat(
            one(
                    c,
                    "SELECT payload FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND tenant_billing_sequence = 1",
                    source.canonicalTenantId())
                .get("payload", byte[].class))
        .isEqualTo(demoEvent);
    assertThat(count(c, "account_tenant_entitlement_outbox_streams")).isOne();
    assertThat(
            c.setup()
                .fetch(
                    "SELECT outbox_stream_key FROM account_authority_outbox_streams ORDER BY outbox_stream_key")
                .getValues(0, String.class))
        .containsExactlyElementsOf(authorityStreamKeys);
    assertThat(
            one(
                    c,
                    "SELECT last_sequence FROM account_authority_outbox_streams WHERE outbox_stream_key = ?",
                    TenantAuthorityEventV1Codec.streamKey(source.canonicalTenantId()))
                .get("last_sequence", Long.class))
        .isEqualTo(current.tenantAuthorityEvent().outboxSequence());
    assertThat(count(c, "account_tenant_authority_source_records")).isOne();
  }

  @Test
  void concurrentSameRequestObservesOneInflightOwnerAndReplaysOneCommittedTransition()
      throws Exception {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = paidClaim(f);
    UUID request = claim.lifecycleRequestId();
    CountDownLatch reading = new CountDownLatch(1);
    CountDownLatch completeRead = new CountDownLatch(1);
    f.transport().duringRead =
        () -> {
          reading.countDown();
          await(completeRead);
        };
    var executor = Executors.newSingleThreadExecutor();
    try {
      var winner =
          executor.submit(
              () ->
                  f.service()
                      .reconcile(
                          request,
                          f.creation().requestId(),
                          claim.expectedRowVersion(),
                          f.providerId(),
                          null));
      assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(
              f.service()
                  .reconcile(
                      request,
                      f.creation().requestId(),
                      claim.expectedRowVersion(),
                      f.providerId(),
                      null)
                  .outcome())
          .isEqualTo(Outcome.RECONCILING);
      completeRead.countDown();
      var committed = winner.get(10, TimeUnit.SECONDS);
      assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
      assertThat(
              f.service()
                  .reconcile(
                      request,
                      f.creation().requestId(),
                      claim.expectedRowVersion(),
                      f.providerId(),
                      null))
          .isEqualTo(committed);
      assertThat(f.transport().reads).isOne();
      assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
      assertThat(count(c, "subscription_provider_history")).isOne();
    } finally {
      completeRead.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void providerIdMetadataCustomerInstrumentPriceQuantityAndUnknownStatusesCannotBecomeAuthority() {
    Context c = context("latest");
    for (String mismatch :
        List.of("id", "metadata", "customer", "instrument", "price", "quantity", "unknown")) {
      Fixture f = fixture(c);
      var claim = claim(f);
      String json = response(f, "active");
      f.transport().json =
          switch (mismatch) {
            case "id" -> json.replace(f.providerId(), "sub_wrong");
            case "metadata" ->
                json.replace(
                    f.creation().subscriptionProvisioningRequestId().toString(),
                    UUID.randomUUID().toString());
            case "customer" -> json.replace(f.creation().providerCustomerId(), "cus_wrong");
            case "instrument" -> json.replace(f.instrument().providerPaymentMethodId(), "pm_wrong");
            case "price" -> json.replace(f.creation().planQuote().providerPriceId(), "price_wrong");
            case "quantity" -> json.replace("\"quantity\":1", "\"quantity\":2");
            default -> json.replace("\"active\"", "\"unrecognized_status\"");
          };
      UUID request = UUID.randomUUID();
      assertThat(
              f.service()
                  .reconcile(
                      request, f.creation().requestId(), 2, f.providerId(), claim.attemptId())
                  .outcome())
          .as(mismatch)
          .isEqualTo(Outcome.RECONCILING);
      assertThat(
              one(
                      c,
                      "SELECT state FROM subscription_lifecycle_operations WHERE request_id = ?",
                      request)
                  .get("state", String.class))
          .isEqualTo("PENDING");
      assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
          .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
      assertThat(
              one(
                      c,
                      "SELECT provider_subscription_id FROM subscription WHERE subscription_id = ?",
                      f.creation().subscriptionId())
                  .get("provider_subscription_id"))
          .isNull();
    }
    assertThat(count(c, "subscription_lifecycle_receipts")).isZero();
    assertThat(count(c, "subscription_provider_history")).isZero();
    assertThat(count(c, "account_tenant_entitlement_outbox_events")).isZero();
  }

  @Test
  void providerExceptionIsNotAbsenceAndSameRequestRecoversWithoutAnotherProviderIdentity() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = paidClaim(f);
    UUID request = claim.lifecycleRequestId();
    f.transport().failure = mock(StripeException.class);
    assertThat(
            f.service()
                .reconcile(
                    request,
                    f.creation().requestId(),
                    claim.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.RECONCILING);
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
    f.transport().failure = null;
    f.clock().advance(Duration.ofSeconds(2));
    assertThat(
            f.service()
                .reconcile(
                    request,
                    f.creation().requestId(),
                    claim.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(count(c, "subscription_create_operations")).isOne();
    assertThat(count(c, "subscription_create_provider_identities")).isEqualTo(2);
    assertThat(count(c, "subscription_lifecycle_attempts")).isEqualTo(2);
    assertThat(f.service().readCurrent(f.creation().tenantId()).allowPublicJoin()).isTrue();
  }

  @Test
  void walletDisplayAdvanceDuringSdkReadPreservesBindingAndStaleRowOwnerInputsRejectBeforeRead() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = paidClaim(f);
    assertThatThrownBy(
            () ->
                f.service()
                    .reconcile(
                        UUID.randomUUID(), f.creation().requestId(), 1, f.providerId(), null))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    assertThat(f.transport().reads).isZero();
    var wrongOwner =
        new AccountSubscriptionCreateRequest(
            UUID.randomUUID(),
            f.creation().tenantId(),
            new OwnerFence(
                f.creation().owner().accountUuid(),
                f.creation().owner().version() + 1,
                UUID.randomUUID()),
            f.creation().planCode(),
            f.creation().paymentInstrumentId(),
            f.creation().reservedInstrumentVersion(),
            new ProducerEvidence(f.creation().owner().accountUuid(), UUID.randomUUID(), DIGEST),
            null);
    assertThatThrownBy(() -> c.tx().execute(s -> c.intents().retainPending(wrongOwner)))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    f.transport().duringRead =
        () ->
            in(
                c,
                () ->
                    new AccountPaymentInstrumentRepository(c.dsl())
                        .recordAvailability(
                            new AccountPaymentInstrumentRepository.AvailabilityObservation(
                                UUID.randomUUID(),
                                f.instrument().instrumentUuid(),
                                f.creation().owner().accountUuid(),
                                1L,
                                AccountPaymentInstrumentRecord.State.ATTACHABLE,
                                new AccountPaymentInstrumentRecord.DisplayMetadata(
                                    "visa", "4242", 12, 2031),
                                DIGEST)));
    assertThat(
            f.service()
                .reconcile(
                    claim.lifecycleRequestId(),
                    f.creation().requestId(),
                    claim.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(
            new AccountPaymentInstrumentRepository(c.dsl())
                .findByOwner(f.creation().owner().accountUuid(), f.instrument().instrumentUuid())
                .orElseThrow()
                .rowVersion())
        .isEqualTo(2L);
    assertThat(
            c.intents()
                .findCurrentByTenant(f.creation().tenantId())
                .orElseThrow()
                .reservedInstrumentVersion())
        .isEqualTo(1L);
    assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
    assertThat(count(c, "account_tenant_entitlement_outbox_events")).isOne();
    assertThat(count(c, "account_tenant_authority_source_records")).isOne();
    assertThat(f.service().readCurrent(f.creation().tenantId()).subscriptionStatus())
        .isEqualTo(Status.ACTIVE);
  }

  @Test
  void
      deferredSqlFailureRollsBackMappingObservationReceiptGenerationAndBothEventsThenRetryRecovers() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = paidClaim(f);
    UUID request = claim.lifecycleRequestId();
    var prepared =
        in(
            c,
            () ->
                f.owner()
                    .prepare(
                        request,
                        f.creation().requestId(),
                        claim.expectedRowVersion(),
                        f.providerId(),
                        null));
    var actualSdk = f.observer().observe(prepared.binding());
    var mappingBefore =
        c.setup()
            .fetch(
                "SELECT row_to_json(h)::text AS value, xmin::text AS xmin FROM subscription_provider_history h")
            .intoMaps();
    assertSqlState(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s -> {
                      assertThat(f.owner().apply(prepared, actualSdk).outcome())
                          .isEqualTo(Outcome.COMMITTED);
                      var sameTransactionFence =
                          f.owner().prepareCurrentRead(f.creation().tenantId());
                      assertThat(
                              f.owner()
                                  .readCurrent(sameTransactionFence, actualSdk)
                                  .allowPublicJoin())
                          .isTrue();
                      // A real monotonic generation advance without its source event must fail at
                      // transaction
                      // completion, after the lifecycle owner's ordinary writes and exact readback
                      // have executed.
                      c.dsl()
                          .execute(
                              "UPDATE account_authority_generations SET generation = generation + 1, source_version = source_version + 1 "
                                  + "WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                              f.creation().tenantId());
                    }));
    for (String table :
        List.of(
            "subscription_lifecycle_observations",
            "subscription_lifecycle_receipts",
            "account_tenant_entitlement_outbox_events",
            "account_authority_outbox_events",
            "account_tenant_authority_source_records")) {
      assertThat(count(c, table)).as(table).isZero();
    }
    assertThat(count(c, "subscription_provider_history")).isOne();
    assertThat(
            c.setup()
                .fetch(
                    "SELECT row_to_json(h)::text AS value, xmin::text AS xmin FROM subscription_provider_history h")
                .intoMaps())
        .isEqualTo(mappingBefore);
    assertThat(
            one(
                    c,
                    "SELECT count(*) FROM account_audit_outbox WHERE event_type = 'HOSTING_FIRST_INVOICE_COMPLETED'")
                .get(0, Long.class))
        .isZero();
    assertThat(count(c, "subscription_first_invoice_success_evidence")).isOne();
    assertThat(count(c, "subscription_first_invoice_completions")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT generation FROM account_authority_generations WHERE scope_kind = 'TENANT' AND tenant_uuid = ?",
                    f.creation().tenantId())
                .get("generation", Long.class))
        .isOne();
    assertThat(
            one(
                    c,
                    "SELECT state FROM subscription_lifecycle_operations WHERE request_id = ?",
                    request)
                .get("state", String.class))
        .isEqualTo("PENDING");
    f.clock().advance(Duration.ofSeconds(12));
    assertThat(
            f.service()
                .reconcile(
                    request,
                    f.creation().requestId(),
                    claim.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(f.service().readCurrent(f.creation().tenantId()).tenantBillingSequence()).isOne();
  }

  @Test
  void databaseGuardRejectsUnauditedOwnerUpdateDuringSdkTransport() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = claim(f);
    // The actual database refuses an unaudited transfer. Preserve that guard rather than
    // manufacturing an owner change that this source boundary cannot legitimately produce.
    f.transport().duringRead =
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        c.dsl()
                            .execute(
                                "UPDATE account_tenant_billing_owners SET owner_version = owner_version + 1, owner_generation = ? WHERE tenant_uuid = ?",
                                UUID.randomUUID(),
                                f.creation().tenantId()));
    UUID reconciliationRequest = UUID.randomUUID();
    assertSqlState(
        "23514",
        () ->
            f.service()
                .reconcile(
                    reconciliationRequest,
                    f.creation().requestId(),
                    2,
                    f.providerId(),
                    claim.attemptId()));
    assertThat(count(c, "subscription_lifecycle_receipts")).isZero();
    assertThat(count(c, "subscription_provider_history")).isZero();
    assertThat(count(c, "account_authority_outbox_events")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT state FROM subscription_lifecycle_operations WHERE request_id = ?",
                    reconciliationRequest)
                .get("state", String.class))
        .isEqualTo("PENDING");
    assertThat(
            one(
                    c,
                    "SELECT lifecycle_inflight_request_id FROM subscription WHERE subscription_id = ?",
                    f.creation().subscriptionId())
                .get("lifecycle_inflight_request_id", UUID.class))
        .isEqualTo(reconciliationRequest);
    assertThat(
            one(
                    c,
                    "SELECT current_receipt_request_id FROM subscription WHERE subscription_id = ?",
                    f.creation().subscriptionId())
                .get("current_receipt_request_id", UUID.class))
        .isNull();
    assertThat(
            one(
                    c,
                    "SELECT owner_version FROM account_tenant_billing_owners WHERE tenant_uuid = ?",
                    f.creation().tenantId())
                .get("owner_version", Long.class))
        .isEqualTo(f.creation().owner().version());
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
  }

  @Test
  void realInitialCanceledEvidenceRetiresOnlyItsHoldAndCommittedReceiptCannotBeDetached() {
    Context c = context("latest");
    Fixture f = fixture(c);
    f.transport().json = response(f, "canceled");
    var claim = claim(f);
    assertThat(
            f.service()
                .reconcile(
                    UUID.randomUUID(),
                    f.creation().requestId(),
                    2,
                    f.providerId(),
                    claim.attemptId())
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
    assertThat(f.service().readCurrent(f.creation().tenantId()).gameplayAvailable()).isFalse();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isOne();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNotNull();
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET current_receipt_request_id = NULL, row_version = row_version + 1 WHERE subscription_id = ?",
                    f.creation().subscriptionId()));
    assertThat(f.service().readCurrent(f.creation().tenantId()).subscriptionStatus())
        .isEqualTo(Status.CANCELED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"canceled", "incomplete_expired"})
  void unclaimedFirstInvoiceCancellationKeepsAmbiguityHeldThenReleasesExactReservation(
      String providerStatus) {
    Context c = context("1046.1");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    UUID subscriptionId = checkout.invoice().subscriptionId();
    UUID creationId = checkout.invoice().creationRequestId();
    UUID cancellationId = UUID.randomUUID();
    long version =
        in(
            c,
            () ->
                checkout
                    .payments()
                    .ownerRowVersion(subscriptionId, checkout.invoice().ownerAccountId()));
    List<String> beforeUpgrade = firstInvoiceImage(c);
    configuration(c).target("1046.2").load().migrate();
    assertThat(firstInvoiceImage(c)).isEqualTo(beforeUpgrade);
    assertSqlState(
        "23514",
        "Canceled first-invoice provisioning requires exact unclaimed terminal release",
        () ->
            c.tx()
                .executeWithoutResult(
                    ignored ->
                        c.dsl()
                            .execute(
                                "UPDATE subscription_provisioning_work SET phase = 'CANCELED', row_version = row_version + 1 WHERE request_id = ?",
                                creationId)));
    configuration(c).load().migrate();
    for (String providerState : List.of("active", "incomplete")) {
      f.transport().json = response(f, providerState);
      assertThat(
              f.service()
                  .reconcileUnpaidFirstInvoiceCancellation(
                      cancellationId, creationId, version, f.providerId())
                  .outcome())
          .isEqualTo(Outcome.RECONCILING);
      assertThat(count(c, "subscription_lifecycle_operations")).isZero();
      assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
      assertThat(
              one(
                      c,
                      "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                      creationId)
                  .get("released_at"))
          .isNull();
      assertThat(
              one(
                      c,
                      "SELECT row_version FROM subscription WHERE subscription_id = ?",
                      subscriptionId)
                  .get("row_version", Long.class))
          .isEqualTo(version);
    }

    f.transport().json = response(f, providerStatus);
    var committed =
        f.service()
            .reconcileUnpaidFirstInvoiceCancellation(
                cancellationId, creationId, version, f.providerId());
    assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
    var settled =
        one(
            c,
            "SELECT w.phase, s.status, s.current_receipt_request_id, r.released_at, t.outcome, "
                + "t.evidence_digest, receipt.observation_digest "
                + "FROM subscription s JOIN subscription_provisioning_work w USING (subscription_id) "
                + "JOIN subscription_instrument_reservations r ON r.subscription_id = s.subscription_id "
                + "JOIN subscription_provider_terminal_resolutions t ON t.resolution_id = s.terminal_resolution_id "
                + "AND r.terminal_resolution_id = t.resolution_id "
                + "JOIN subscription_lifecycle_receipts receipt ON receipt.request_id = s.current_receipt_request_id "
                + "WHERE s.subscription_id = ?",
            subscriptionId);
    assertThat(settled.get("phase", String.class)).isEqualTo("CANCELED");
    assertThat(settled.get("status", String.class)).isEqualTo("canceled");
    assertThat(settled.get("current_receipt_request_id", UUID.class)).isEqualTo(cancellationId);
    assertThat(settled.get("released_at")).isNotNull();
    assertThat(settled.get("outcome", String.class)).isEqualTo("CANCELED");
    assertThat(settled.get("evidence_digest", String.class))
        .isEqualTo(settled.get("observation_digest", String.class));
    assertThat(
            one(
                    c,
                    "SELECT raw_status FROM subscription_lifecycle_observations WHERE request_id = ?",
                    cancellationId)
                .get("raw_status", String.class))
        .isEqualTo(providerStatus);
    assertThat(count(c, "subscription_first_invoice_command_claims")).isZero();
    assertThat(count(c, "subscription_first_invoice_success_evidence")).isZero();
    assertThat(count(c, "subscription_first_invoice_completions")).isZero();
    assertThat(checkout.confirmations()).isZero();
    if (providerStatus.equals("incomplete_expired")) {
      assertThat(f.terminalInvoiceTransport().readPaths())
          .containsExactly(
              "/v1/invoices/in_" + subscriptionId.toString().replace("-", ""),
              "/v1/payment_intents/pi_" + subscriptionId.toString().replace("-", ""));
      assertThat(f.terminalInvoiceTransport().reads()).isEqualTo(2);
    } else {
      assertThat(f.terminalInvoiceTransport().readPaths()).isEmpty();
    }
    var current = f.service().readCurrent(f.creation().tenantId());
    assertThat(current.subscriptionStatus()).isEqualTo(Status.CANCELED);
    assertThat(current.gameplayAvailable()).isFalse();
    int providerReads = f.transport().reads;
    int terminalInvoiceReads = f.terminalInvoiceTransport().reads();
    assertThat(
            f.service()
                .reconcileUnpaidFirstInvoiceCancellation(
                    cancellationId, creationId, version, f.providerId()))
        .isEqualTo(committed);
    assertThat(f.transport().reads).isEqualTo(providerReads);
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isOne();
    assertThat(f.terminalInvoiceTransport().confirmations()).isZero();
    assertThat(f.terminalInvoiceTransport().reads()).isEqualTo(terminalInvoiceReads);
  }

  @ParameterizedTest
  @ValueSource(strings = {"canceled", "incomplete_expired"})
  void exactTerminalInvoiceAndIntentSettleOnlyClaimWithRetainedNoSendWitness(
      String providerStatus) {
    Context c = context("1046.4");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var witness =
        checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    var witnessImage =
        c.setup()
            .fetch(
                "SELECT request_id, observed_at FROM subscription_first_invoice_expired_before_post "
                    + "WHERE request_id = ?",
                witness.requestId())
            .intoMaps();
    var claimImage =
        c.setup()
            .fetch(
                "SELECT * FROM subscription_first_invoice_command_claims WHERE request_id = ?",
                witness.requestId())
            .intoMaps();
    assertThat(witnessImage).hasSize(1);
    assertThat(claimImage).hasSize(1);
    assertThat(count(c, "subscription_first_invoice_command_claims")).isOne();
    assertThat(checkout.confirmations()).isZero();
    configuration(c).target("latest").load().migrate();
    assertThat(count(c, "subscription_first_invoice_no_send_cancellations")).isZero();
    long version =
        in(
            c,
            () ->
                checkout
                    .payments()
                    .ownerRowVersion(
                        f.creation().subscriptionId(), checkout.invoice().ownerAccountId()));
    f.transport().json = response(f, providerStatus);

    var committed =
        f.service()
            .reconcileUnpaidFirstInvoiceCancellation(
                UUID.randomUUID(), f.creation().requestId(), version, f.providerId());

    assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isOne();
    assertThat(count(c, "subscription_first_invoice_command_claims")).isOne();
    assertThat(
            one(
                    c,
                    "SELECT j.confirmation_request_id FROM subscription_first_invoice_no_send_cancellations j "
                        + "JOIN subscription_lifecycle_receipts r ON r.request_id = j.cancellation_request_id "
                        + "AND r.committed_transaction_id = j.created_transaction_id "
                        + "WHERE r.request_id = ?",
                    committed.requestId())
                .get("confirmation_request_id", UUID.class))
        .isEqualTo(witness.requestId());
    assertThat(
            c.setup()
                .fetch(
                    "SELECT request_id, observed_at FROM subscription_first_invoice_expired_before_post "
                        + "WHERE request_id = ?",
                    witness.requestId())
                .intoMaps())
        .isEqualTo(witnessImage);
    assertThat(
            c.setup()
                .fetch(
                    "SELECT * FROM subscription_first_invoice_command_claims WHERE request_id = ?",
                    witness.requestId())
                .intoMaps())
        .isEqualTo(claimImage);
    var settlement =
        one(
            c,
            "SELECT w.phase, s.status, s.current_receipt_request_id, r.released_at, "
                + "r.terminal_resolution_id = t.resolution_id AS exact_release "
                + "FROM subscription s JOIN subscription_provisioning_work w USING (subscription_id) "
                + "JOIN subscription_instrument_reservations r ON r.subscription_id = s.subscription_id "
                + "JOIN subscription_provider_terminal_resolutions t ON t.resolution_id = s.terminal_resolution_id "
                + "WHERE s.subscription_id = ?",
            f.creation().subscriptionId());
    assertThat(settlement.get("phase", String.class)).isEqualTo("CANCELED");
    assertThat(settlement.get("status", String.class)).isEqualTo("canceled");
    assertThat(settlement.get("current_receipt_request_id", UUID.class))
        .isEqualTo(committed.requestId());
    assertThat(settlement.get("released_at")).isNotNull();
    assertThat(settlement.get("exact_release", Boolean.class)).isTrue();
    assertThat(f.terminalInvoiceTransport().readPaths())
        .containsExactly(
            "/v1/invoices/in_" + f.creation().subscriptionId().toString().replace("-", ""),
            "/v1/payment_intents/pi_" + f.creation().subscriptionId().toString().replace("-", ""));
    assertThat(f.terminalInvoiceTransport().confirmations()).isZero();
    assertThat(f.terminalInvoiceTransport().reads()).isEqualTo(2);
    int subscriptionReads = f.transport().reads;
    assertThat(
            f.service()
                .reconcileUnpaidFirstInvoiceCancellation(
                    committed.requestId(), f.creation().requestId(), version, f.providerId()))
        .isEqualTo(committed);
    assertThat(f.transport().reads).isEqualTo(subscriptionReads);
    assertThat(f.terminalInvoiceTransport().reads()).isEqualTo(2);
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isOne();
  }

  @ParameterizedTest
  @ValueSource(strings = {"canceled", "incomplete_expired"})
  void nonExactTerminalInvoiceOrIntentEvidenceRetainsHoldAndNeverPosts(String providerStatus) {
    for (String failure : List.of("nonvoid-invoice", "live-intent", "wrong-intent", "received")) {
      Context c = context("latest");
      Fixture f = fixture(c);
      var checkout =
          CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
              c.source(),
              c.dsl(),
              c.tx(),
              f.creation(),
              f.instrument(),
              f.providerId(),
              f.clock(),
              f.owner());
      var witness =
          checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
      assertThat(witness.requestId()).isNotNull();
      long version =
          in(
              c,
              () ->
                  checkout
                      .payments()
                      .ownerRowVersion(
                          f.creation().subscriptionId(), checkout.invoice().ownerAccountId()));
      UUID cancellationId = UUID.randomUUID();
      f.transport().json = response(f, providerStatus);
      switch (failure) {
        case "nonvoid-invoice" -> f.terminalInvoiceTransport().terminalInvoiceStatus("open");
        case "live-intent" ->
            f.terminalInvoiceTransport().terminalIntentStatus("requires_confirmation");
        case "wrong-intent" ->
            f.terminalInvoiceTransport().terminalIntentId("pi_wrong_controlled_binding");
        case "received" -> f.terminalInvoiceTransport().terminalAmounts(0, 0, 1);
        default -> throw new AssertionError("Unexpected terminal evidence case: " + failure);
      }

      assertThat(
              f.service()
                  .reconcileUnpaidFirstInvoiceCancellation(
                      cancellationId, f.creation().requestId(), version, f.providerId())
                  .outcome())
          .isEqualTo(Outcome.RECONCILING);
      assertThat(count(c, "subscription_lifecycle_operations")).isZero();
      assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
      assertThat(count(c, "subscription_first_invoice_completions")).isZero();
      assertThat(
              one(
                      c,
                      "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                      f.creation().requestId())
                  .get("released_at"))
          .isNull();
      int expectedReads = failure.equals("wrong-intent") ? 2 : 1;
      assertThat(f.terminalInvoiceTransport().reads()).isEqualTo(expectedReads);
      assertThat(f.terminalInvoiceTransport().confirmations()).isZero();
      var expectedPaths =
          new ArrayList<>(
              List.of(
                  "/v1/invoices/in_" + f.creation().subscriptionId().toString().replace("-", "")));
      if (failure.equals("wrong-intent"))
        expectedPaths.add(
            "/v1/payment_intents/pi_" + f.creation().subscriptionId().toString().replace("-", ""));
      assertThat(f.terminalInvoiceTransport().readPaths()).containsExactlyElementsOf(expectedPaths);
    }
  }

  @Test
  void expiredTerminalObservationCannotReleaseExactInvoiceReservation() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var witness =
        checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    assertThat(witness.requestId()).isNotNull();
    long version =
        in(
            c,
            () ->
                checkout
                    .payments()
                    .ownerRowVersion(
                        f.creation().subscriptionId(), checkout.invoice().ownerAccountId()));
    f.transport().json = response(f, "canceled");
    f.terminalInvoiceTransport().duringRead(() -> f.clock().advance(Duration.ofSeconds(11)));

    assertThat(
            f.service()
                .reconcileUnpaidFirstInvoiceCancellation(
                    UUID.randomUUID(), f.creation().requestId(), version, f.providerId())
                .outcome())
        .isEqualTo(Outcome.RECONCILING);
    assertThat(count(c, "subscription_lifecycle_operations")).isZero();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
    assertThat(f.terminalInvoiceTransport().confirmations()).isZero();
  }

  @Test
  void verifiedPaidInboxEventRetainedDuringProviderReadWinsBeforeCancellationApply()
      throws Exception {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var witness =
        checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    assertThat(witness.requestId()).isNotNull();
    long version =
        in(
            c,
            () ->
                checkout
                    .payments()
                    .ownerRowVersion(
                        f.creation().subscriptionId(), checkout.invoice().ownerAccountId()));
    var signed = checkout.signedSuccess();
    var verified = checkout.verifier().verify(signed.payload(), signed.signature());
    f.transport().json = response(f, "canceled");
    f.terminalInvoiceTransport()
        .duringRead(
            () -> in(c, () -> new StripeEventInboxRepository(c.dsl()).retainOrReplay(verified)));

    assertThat(
            f.service()
                .reconcileUnpaidFirstInvoiceCancellation(
                    UUID.randomUUID(), f.creation().requestId(), version, f.providerId())
                .outcome())
        .isEqualTo(Outcome.RECONCILING);
    assertThat(count(c, "subscription_lifecycle_operations")).isZero();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(count(c, "subscription_first_invoice_success_evidence")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT processing_status FROM stripe_event_inbox WHERE provider_event_id = ?",
                    verified.providerEventId())
                .get("processing_status", String.class))
        .isEqualTo("PENDING");
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
    assertThat(f.terminalInvoiceTransport().readPaths())
        .containsExactly(
            "/v1/invoices/in_" + f.creation().subscriptionId().toString().replace("-", ""),
            "/v1/payment_intents/pi_" + f.creation().subscriptionId().toString().replace("-", ""));
    assertThat(f.terminalInvoiceTransport().confirmations()).isZero();
  }

  @Test
  void claimedFirstInvoiceCannotReleaseThroughUnpaidCancellation() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var claimed = checkout.authorize(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    assertThat(count(c, "subscription_first_invoice_command_claims")).isOne();
    assertThat(count(c, "subscription_first_invoice_success_evidence")).isZero();
    f.transport().json = response(f, "canceled");
    assertThatThrownBy(
            () ->
                f.service()
                    .reconcileUnpaidFirstInvoiceCancellation(
                        UUID.randomUUID(),
                        f.creation().requestId(),
                        claimed.expectedRowVersion(),
                        f.providerId()))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class)
        .hasMessageContaining("unclaimed unpaid");
    assertThat(f.transport().reads).isZero();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void lateSignedPaymentAfterUnpaidCancellationRetainsOneOperatorConflict(boolean noSendWitness)
      throws Exception {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    UUID confirmationRequestId;
    long expectedRowVersion;
    if (noSendWitness) {
      var witness =
          checkout.claimExpiredBeforePost(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
      confirmationRequestId = witness.requestId();
      expectedRowVersion =
          in(
              c,
              () ->
                  checkout
                      .payments()
                      .ownerRowVersion(
                          checkout.invoice().subscriptionId(),
                          checkout.invoice().ownerAccountId()));
    } else {
      var dispatch =
          checkout.prepareConfirmationDispatch(
              CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
      confirmationRequestId = dispatch.requestId();
      expectedRowVersion = dispatch.expectedRowVersion();
    }
    UUID cancellationId = UUID.randomUUID();
    f.transport().json = response(f, "canceled");
    var cancellation =
        f.service()
            .reconcileUnpaidFirstInvoiceCancellation(
                cancellationId, f.creation().requestId(), expectedRowVersion, f.providerId());
    assertThat(cancellation.outcome()).isEqualTo(Outcome.COMMITTED);
    UUID laterReceiptId = UUID.randomUUID();
    var laterReceipt =
        f.service()
            .reconcile(
                laterReceiptId,
                f.creation().requestId(),
                cancellation.committedRowVersion(),
                f.providerId(),
                null);
    assertThat(laterReceipt.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(
            one(
                    c,
                    "SELECT current_receipt_request_id FROM subscription WHERE subscription_id = ?",
                    checkout.invoice().subscriptionId())
                .get("current_receipt_request_id", UUID.class))
        .isEqualTo(laterReceiptId);
    List<String> canceledImage = firstInvoiceImage(c);

    f.clock().advanceToAtLeast(checkout.invoice().expiresAt().plusSeconds(1));
    checkout.status("succeeded");
    var signed = checkout.signedSuccess();
    var owner =
        new net.firedevops.firemud.accountservice.service.session
            .AccountHostingFirstInvoicePaymentService(
            mock(
                net.firedevops.firemud.accountservice.service.session.AccountControlUiActorService
                    .class),
            mock(
                net.firedevops.firemud.accountservice.service.session
                    .AccountRecentReauthenticationService.class),
            checkout.payments(),
            new StripeEventInboxRepository(c.dsl()),
            checkout.provider(),
            checkout.verifier(),
            f.service(),
            new DataSourceTransactionManager(c.source()));
    var verified = checkout.verifier().verify(signed.payload(), signed.signature());
    in(c, () -> new StripeEventInboxRepository(c.dsl()).retainOrReplay(verified));
    var equivalent = checkout.signWebhookPayload(" \n" + signed.payload() + "\n ");
    var conflict = owner.completeSigned(equivalent.payload(), equivalent.signature());
    assertThat(conflict.state()).isEqualTo("OPERATOR_RECONCILIATION_REQUIRED");
    assertThat(conflict.requestId()).isEqualTo(confirmationRequestId);
    assertThat(count(c, "subscription_first_invoice_late_paid_conflicts")).isOne();
    assertThat(
            c.setup()
                .fetchSingle(
                    "SELECT cancellation_request_id FROM subscription_first_invoice_late_paid_conflicts")
                .get(0, UUID.class))
        .isEqualTo(cancellationId);
    var paidObservation = checkout.provider().observe(checkout.invoice());
    assertThatThrownBy(
            () -> in(c, () -> checkout.payments().retainSignedSuccess(verified, paidObservation)))
        .isInstanceOf(IllegalStateException.class);
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "INSERT INTO subscription_first_invoice_success_evidence "
                        + "(request_id, subscription_id, provider_event_id, event_payload_digest, "
                        + "tuple_digest, observed_at) VALUES (?, ?, ?, ?, ?, ?::timestamptz)",
                    confirmationRequestId,
                    checkout.invoice().subscriptionId(),
                    verified.providerEventId(),
                    verified.payloadSha256(),
                    checkout.invoice().digest(),
                    java.time.OffsetDateTime.ofInstant(f.clock().instant(), ZoneOffset.UTC)));
    assertThat(firstInvoiceImage(c)).isEqualTo(canceledImage);

    checkout.status("requires_confirmation");
    assertThat(owner.completeSigned(signed.payload(), signed.signature())).isEqualTo(conflict);
    assertThat(owner.completeSigned(equivalent.payload(), equivalent.signature()))
        .isEqualTo(conflict);
    assertThat(
            c.setup()
                .fetchSingle(
                    "SELECT event_payload_digest FROM subscription_first_invoice_late_paid_conflicts")
                .get(0, String.class))
        .isEqualTo(verified.payloadSha256());
    var unrelated =
        checkout.signWebhookPayload(
            signed
                .payload()
                .replace("evt_", "evt_unrelated_")
                .replace("payment_intent.succeeded", "invoice.paid"));
    assertThat(owner.completeSigned(unrelated.payload(), unrelated.signature()).state())
        .isEqualTo("IGNORED");
    var wrongMethod =
        checkout.signWebhookPayload(
            signed
                .payload()
                .replace("evt_", "evt_wrongmethod_")
                .replace(checkout.invoice().providerPaymentMethodId(), "pm_other"));
    assertThatThrownBy(() -> owner.completeSigned(wrongMethod.payload(), wrongMethod.signature()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(firstInvoiceImage(c)).isEqualTo(canceledImage);
    assertThat(count(c, "subscription_first_invoice_late_paid_conflicts")).isOne();
    assertThat(count(c, "subscription_first_invoice_success_evidence")).isZero();
    assertThat(count(c, "subscription_first_invoice_completions")).isZero();
    assertThat(f.service().readCurrent(f.creation().tenantId()).gameplayAvailable()).isFalse();
  }

  @Test
  void unavailableReservedInstrumentRefusesFirstInvoiceCommandWithoutClaimOrProviderPost()
      throws Exception {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var dispatch =
        checkout.prepareConfirmationDispatch(
            CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    var instrument = f.instrument();
    in(
        c,
        () ->
            new AccountPaymentInstrumentRepository(c.dsl())
                .recordAvailability(
                    new AccountPaymentInstrumentRepository.AvailabilityObservation(
                        UUID.randomUUID(),
                        instrument.instrumentUuid(),
                        instrument.accountUuid(),
                        instrument.rowVersion(),
                        AccountPaymentInstrumentRecord.State.UNAVAILABLE,
                        instrument.display(),
                        "sha256:" + "e".repeat(64))));

    assertThatThrownBy(
            () ->
                checkout
                    .provider()
                    .confirm(
                        dispatch,
                        () ->
                            in(
                                c,
                                () ->
                                    checkout
                                        .payments()
                                        .claimCommand(
                                            dispatch, checkout.invoice().ownerAccountId()))))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    assertThat(checkout.confirmations()).isZero();
    assertThat(count(c, "subscription_first_invoice_command_claims")).isZero();
  }

  @Test
  void paidFirstInvoiceWithCanceledProviderKeepsCustodyAndRecordsOperatorWork() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            c.source(),
            c.dsl(),
            c.tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var paid =
        checkout.authorizeAndRetainSuccess(
            CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    f.transport().json = response(f, "canceled");

    var result =
        f.service()
            .reconcile(
                paid.lifecycleRequestId(),
                f.creation().requestId(),
                paid.expectedRowVersion(),
                f.providerId(),
                null);

    assertThat(result.outcome()).isEqualTo(Outcome.TERMINAL_FAILURE);
    assertThat(
            one(
                    c,
                    "SELECT reason FROM subscription_lifecycle_operator_outbox WHERE request_id = ?",
                    paid.lifecycleRequestId())
                .get("reason", String.class))
        .isEqualTo("PAID_FIRST_INVOICE_PROVIDER_CANCELED");
    var held =
        one(
            c,
            "SELECT s.status, w.phase, r.released_at FROM subscription s "
                + "JOIN subscription_provisioning_work w USING (subscription_id) "
                + "JOIN subscription_instrument_reservations r USING (subscription_id) "
                + "WHERE s.subscription_id = ?",
            paid.invoice().subscriptionId());
    assertThat(held.get("status", String.class)).isEqualTo("provisioning");
    assertThat(held.get("phase", String.class)).isEqualTo("AWAITING_PAYMENT");
    assertThat(held.get("released_at")).isNull();
    assertThat(count(c, "subscription_lifecycle_receipts")).isZero();
    assertThat(count(c, "subscription_first_invoice_completions")).isZero();
    assertThat(count(c, "subscription_first_invoice_success_evidence")).isOne();
  }

  @Test
  void freshReadsBeyondObservationAgePreserveCommittedReceiptAndAuthorityUntilRealPeriodEnd() {
    for (String status : List.of("active", "trialing")) {
      Context c = context("latest");
      Fixture f = fixture(c);
      activate(f);
      long checkpoints = 1;
      if ("trialing".equals(status)) {
        f.transport().json = response(f, status);
        refresh(f);
        checkpoints = 2;
      }
      var receipt =
          one(
              c,
              "SELECT row_to_json(r)::text AS value, xmin::text AS xmin FROM subscription_lifecycle_receipts r WHERE request_id = (SELECT current_receipt_request_id FROM subscription)");
      var authority =
          one(
              c,
              "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_tenant_authority_source_records a");
      Instant periodEnd = Instant.ofEpochSecond(f.creation().createdAt().getEpochSecond() + 3600);
      assertThat(
              one(
                      c,
                      "SELECT valid_until FROM subscription_lifecycle_receipts WHERE request_id = (SELECT current_receipt_request_id FROM subscription)")
                  .get(0, java.time.OffsetDateTime.class)
                  .toInstant())
          .isEqualTo(periodEnd);
      int reads = f.transport().reads;
      for (int laterRead = 1; laterRead <= 2; laterRead++) {
        f.clock().advance(Duration.ofSeconds(30));
        var current = f.service().readCurrent(f.creation().tenantId());
        assertThat(current.gameplayAvailable()).isTrue();
        assertThat(current.validUntil()).isEqualTo(f.clock().instant().plusSeconds(15));
        assertThat(current.tenantBillingSequence()).isEqualTo(checkpoints);
        assertThat(current.tenantAuthorityEvent().tenantAuthorityGeneration())
            .isEqualTo(checkpoints + 1);
        assertThat(f.transport().reads).isEqualTo(reads + laterRead);
        assertThat(
                one(
                    c,
                    "SELECT row_to_json(r)::text AS value, xmin::text AS xmin FROM subscription_lifecycle_receipts r WHERE request_id = (SELECT current_receipt_request_id FROM subscription)"))
            .isEqualTo(receipt);
        assertThat(
                one(
                    c,
                    "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_tenant_authority_source_records a"))
            .isEqualTo(authority);
        assertThat(count(c, "account_tenant_entitlement_outbox_events")).isEqualTo(checkpoints);
        assertThat(count(c, "account_authority_outbox_events")).isEqualTo(checkpoints);
      }
      f.clock().advance(Duration.between(f.clock().instant(), periodEnd));
      int beforeExpiredRead = f.transport().reads;
      assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
          .isInstanceOf(
              AccountHostingSubscriptionLifecycleService.EntitlementUnavailableException.class)
          .hasMessageContaining("ENTITLEMENT_UNAVAILABLE")
          .cause()
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class)
          .hasMessageContaining("current receipt is expired");
      assertThat(f.transport().reads).isEqualTo(beforeExpiredRead);
      assertThat(count(c, "subscription_lifecycle_receipts")).isEqualTo(checkpoints);
    }
  }

  @Test
  void canceledReceiptsReturnFreshNegativeProjectionAfterOriginalObservationAgeWithoutMutation() {
    for (boolean providerCanceled : new boolean[] {true, false}) {
      Context c = context("latest");
      Fixture f = fixture(c);
      String canceledFacts =
          providerCanceled
              ? response(f, "canceled")
              : response(f, "active")
                  .replace(
                      "\"cancel_at\":null",
                      "\"cancel_at\":" + f.clock().instant().getEpochSecond());
      f.transport().json = providerCanceled ? canceledFacts : response(f, "active");
      activate(f);
      f.transport().json = canceledFacts;
      if (!providerCanceled) refresh(f);
      long expectedSequence = providerCanceled ? 1L : 2L;
      var receipt =
          one(
              c,
              "SELECT row_to_json(r)::text AS value, xmin::text AS xmin FROM subscription_lifecycle_receipts r WHERE entitlement_version = ?",
              expectedSequence);
      var authority =
          one(
              c,
              "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_tenant_authority_source_records a");
      for (int laterRead = 0; laterRead < 2; laterRead++) {
        f.clock().advance(Duration.ofSeconds(30));
        var canceled = f.service().readCurrent(f.creation().tenantId());
        assertThat(canceled.subscriptionStatus()).isEqualTo(Status.CANCELED);
        assertThat(canceled.gameplayAvailable()).isFalse();
        assertThat(canceled.allowPublicJoin()).isFalse();
        assertThat(canceled.allowNewGameplayBindings()).isFalse();
        assertThat(canceled.allowNewInstanceStarts()).isFalse();
        assertThat(canceled.validUntil()).isEqualTo(f.clock().instant().plusSeconds(15));
        assertThat(canceled.tenantBillingSequence()).isEqualTo(expectedSequence);
      }
      // An aged terminal receipt is not permission to turn unknown or changed facts into denial.
      f.transport().duringRead = () -> f.clock().advance(Duration.ofSeconds(15));
      assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
          .isInstanceOf(
              AccountHostingSubscriptionLifecycleService.EntitlementUnavailableException.class);
      f.transport().json = response(f, "active");
      assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
          .isInstanceOf(
              AccountHostingSubscriptionLifecycleService.EntitlementUnavailableException.class);
      f.transport().json = canceledFacts;
      assertThat(f.service().readCurrent(f.creation().tenantId()).gameplayAvailable()).isFalse();
      assertThat(
              one(
                  c,
                  "SELECT row_to_json(r)::text AS value, xmin::text AS xmin FROM subscription_lifecycle_receipts r WHERE entitlement_version = ?",
                  expectedSequence))
          .isEqualTo(receipt);
      assertThat(
              one(
                  c,
                  "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_tenant_authority_source_records a"))
          .isEqualTo(authority);
      assertThat(count(c, "account_tenant_entitlement_outbox_events")).isEqualTo(expectedSequence);
      assertThat(count(c, "account_authority_outbox_events")).isEqualTo(expectedSequence);
      assertThat(count(c, "subscription_lifecycle_receipts")).isEqualTo(expectedSequence);
      assertThat(count(c, "subscription_provider_terminal_resolutions"))
          .isEqualTo(providerCanceled ? 1 : 0);
    }
  }

  @Test
  void inFlightAndStaleObservationOrChangedSdkFactsNeverRefreshAnOldPositiveSnapshot() {
    Context c = context("latest");
    Fixture f = fixture(c);
    activate(f);
    var fence = in(c, () -> f.owner().prepareCurrentRead(f.creation().tenantId()));
    f.transport().json = response(f, "past_due");
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    f.transport().json = response(f, "active");
    f.clock().advance(Duration.ofSeconds(15));
    assertThat(f.service().readCurrent(f.creation().tenantId()).allowPublicJoin()).isTrue();
    f.transport().duringRead = () -> f.clock().advance(Duration.ofSeconds(15));
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThat(f.service().readCurrent(f.creation().tenantId()).allowPublicJoin()).isTrue();
    assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
    assertThat(count(c, "account_tenant_entitlement_outbox_events")).isOne();
    assertThat(count(c, "account_authority_outbox_events")).isOne();
    long currentVersion =
        c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow().rowVersion();
    var prepared =
        in(
            c,
            () ->
                f.owner()
                    .prepare(
                        UUID.randomUUID(),
                        f.creation().requestId(),
                        currentVersion,
                        f.providerId(),
                        null));
    var actualSdk = f.observer().observe(prepared.binding());
    assertThatThrownBy(() -> in(c, () -> f.owner().readCurrent(fence, actualSdk)))
        .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    in(c, () -> f.owner().apply(prepared, actualSdk));
    assertThat(f.service().readCurrent(f.creation().tenantId()).tenantBillingSequence())
        .isEqualTo(2);
  }

  @Test
  void unchangedInvalidEvidenceExhaustsFrozenStallBudgetOnceAndNeverReleasesBillingWork() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = claim(f);
    UUID request = UUID.randomUUID();
    f.transport().json = response(f, "active").replace("price_controlled", "price_wrong");
    for (int attempt = 1; attempt <= 3; attempt++) {
      var outcome =
          f.service()
              .reconcile(request, f.creation().requestId(), 2, f.providerId(), claim.attemptId())
              .outcome();
      assertThat(outcome).isEqualTo(attempt == 3 ? Outcome.TERMINAL_FAILURE : Outcome.RECONCILING);
      f.clock().advance(Duration.ofSeconds(2));
    }
    int reads = f.transport().reads;
    assertThat(
            f.service()
                .reconcile(request, f.creation().requestId(), 2, f.providerId(), claim.attemptId())
                .outcome())
        .isEqualTo(Outcome.TERMINAL_FAILURE);
    assertThat(f.transport().reads).isEqualTo(reads);
    assertThat(count(c, "subscription_lifecycle_operator_outbox")).isOne();
    assertThat(count(c, "subscription_lifecycle_receipts")).isZero();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
  }

  @Test
  void firstPastDueObservationWithoutRetainedRecoveryOriginStaysUnresolvedAndHoldsReservation() {
    Context c = context("latest");
    Fixture f = fixture(c);
    activate(f);
    var receipt =
        one(
            c,
            "SELECT row_to_json(r)::text AS value, xmin::text AS xmin, past_due_since FROM subscription_lifecycle_receipts r");
    assertThat(receipt.get("past_due_since")).isNull();
    var authority =
        one(
            c,
            "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_tenant_authority_source_records a");
    var generations =
        c.setup()
            .fetch(
                "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_authority_generations a")
            .intoMaps();
    long version =
        c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow().rowVersion();
    UUID request = UUID.randomUUID();
    f.transport().json = response(f, "past_due");
    var unresolved =
        f.service().reconcile(request, f.creation().requestId(), version, f.providerId(), null);
    assertThat(unresolved.requestId()).isEqualTo(request);
    assertThat(unresolved.outcome()).isEqualTo(Outcome.RECONCILING);
    assertThat(unresolved.reason()).isEqualTo("RECOVERY_ORIGIN_UNRESOLVED");
    assertThat(unresolved.committedRowVersion()).isZero();
    var pending =
        one(
            c,
            "SELECT state, failure_reason FROM subscription_lifecycle_operations WHERE request_id = ?",
            request);
    assertThat(pending.get("state", String.class)).isEqualTo("PENDING");
    assertThat(pending.get("failure_reason", String.class)).isEqualTo("RECOVERY_ORIGIN_UNRESOLVED");
    var observation =
        one(
            c,
            "SELECT raw_status, mapping_reason FROM subscription_lifecycle_observations WHERE request_id = ?",
            request);
    assertThat(observation.get("raw_status", String.class)).isEqualTo("past_due");
    assertThat(observation.get("mapping_reason", String.class))
        .isEqualTo("RECOVERY_ORIGIN_UNRESOLVED");
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .isInstanceOf(
            AccountHostingSubscriptionLifecycleService.EntitlementUnavailableException.class);
    assertThat(
            one(
                c,
                "SELECT row_to_json(r)::text AS value, xmin::text AS xmin, past_due_since FROM subscription_lifecycle_receipts r"))
        .isEqualTo(receipt);
    assertThat(
            one(
                c,
                "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_tenant_authority_source_records a"))
        .isEqualTo(authority);
    assertThat(
            c.setup()
                .fetch(
                    "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM account_authority_generations a")
                .intoMaps())
        .isEqualTo(generations);
    assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
    assertThat(count(c, "account_tenant_entitlement_outbox_events")).isOne();
    assertThat(count(c, "account_authority_outbox_events")).isOne();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
  }

  @Test
  void effectiveCancellationAdvancesMonotonicCutoffWithoutPrematureReservationRelease() {
    Context c = context("latest");
    Fixture f = fixture(c);
    activate(f);
    Instant cancellation =
        Instant.ofEpochSecond(f.clock().instant().plusSeconds(2).getEpochSecond());
    f.transport().json =
        response(f, "active")
            .replace("\"cancel_at\":null", "\"cancel_at\":" + cancellation.getEpochSecond());
    refresh(f);
    f.clock().advance(Duration.between(f.clock().instant(), cancellation));
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    refresh(f);
    var canceled = f.service().readCurrent(f.creation().tenantId());
    assertThat(canceled.subscriptionStatus()).isEqualTo(Status.CANCELED);
    assertThat(canceled.allowPublicJoin()).isFalse();
    assertThat(canceled.gameplayAvailable()).isFalse();
    assertThat(canceled.allowNewGameplayBindings()).isFalse();
    assertThat(canceled.allowNewInstanceStarts()).isFalse();
    assertThat(canceled.cutoffAt()).isEqualTo(cancellation);
    assertThat(canceled.tenantBillingSequence()).isEqualTo(3);
    assertThat(
            new String(
                one(
                        c,
                        "SELECT payload FROM account_tenant_entitlement_outbox_events WHERE tenant_uuid = ? AND tenant_billing_sequence = 3",
                        f.creation().tenantId())
                    .get("payload", byte[].class),
                java.nio.charset.StandardCharsets.UTF_8))
        .contains("TenantBillingStateChanged");
    f.clock().advance(Duration.ofSeconds(30));
    var laterCanceled = f.service().readCurrent(f.creation().tenantId());
    assertThat(laterCanceled.subscriptionStatus()).isEqualTo(Status.CANCELED);
    assertThat(laterCanceled.cutoffAt()).isEqualTo(cancellation);
    assertThat(laterCanceled.gameplayAvailable()).isFalse();
    assertThat(laterCanceled.validUntil()).isEqualTo(f.clock().instant().plusSeconds(15));
    assertThat(laterCanceled.tenantBillingSequence()).isEqualTo(3);
    assertThat(count(c, "subscription_lifecycle_receipts")).isEqualTo(3);
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNull();
    // Effective hosting cutoff is not proof that the provider can no longer charge. Neither the
    // typed replacement owner nor structural SQL may strand that still-held original operation.
    assertThatThrownBy(
            () ->
                in(
                    c,
                    () ->
                        c.intents()
                            .retainPending(
                                new AccountSubscriptionCreateRequest(
                                    UUID.randomUUID(),
                                    f.creation().tenantId(),
                                    f.creation().owner(),
                                    f.creation().planCode(),
                                    f.instrument().instrumentUuid(),
                                    1,
                                    new ProducerEvidence(
                                        f.creation().owner().accountUuid(),
                                        UUID.randomUUID(),
                                        DIGEST),
                                    new AccountSubscriptionCreateRequest.ReplacementFence(
                                        f.creation().subscriptionId(),
                                        canceled.current().rowVersion())))))
        .isInstanceOf(AccountSubscriptionIntentRepository.ConflictException.class);
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET superseded_at = clock_timestamp(), "
                        + "superseded_by_request_id = ?, row_version = row_version + 1 WHERE subscription_id = ?",
                    UUID.randomUUID(),
                    f.creation().subscriptionId()));
    assertThat(count(c, "subscription_create_operations")).isOne();
    f.transport().json =
        response(f, "canceled")
            .replace("\"ended_at\":null", "\"ended_at\":" + cancellation.getEpochSecond());
    refresh(f);
    assertThat(f.service().readCurrent(f.creation().tenantId()).tenantBillingSequence())
        .isEqualTo(4);
    assertThat(
            one(
                    c,
                    "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                    f.creation().requestId())
                .get("released_at"))
        .isNotNull();
    assertThat(c.intents().lookupProviderId(f.providerId()).disposition())
        .isEqualTo(AccountSubscriptionIntentRepository.MappingDisposition.TERMINAL_HISTORY);
    assertThat(
            one(
                    c,
                    "SELECT row_version FROM payment_instrument WHERE instrument_uuid = ?",
                    f.instrument().instrumentUuid())
                .get("row_version", Long.class))
        .isOne();
    var beforeReplacement = c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow();
    in(
        c,
        () ->
            c.intents()
                .retainPending(
                    new AccountSubscriptionCreateRequest(
                        UUID.randomUUID(),
                        f.creation().tenantId(),
                        f.creation().owner(),
                        f.creation().planCode(),
                        f.instrument().instrumentUuid(),
                        1,
                        new ProducerEvidence(
                            f.creation().owner().accountUuid(), UUID.randomUUID(), DIGEST),
                        new AccountSubscriptionCreateRequest.ReplacementFence(
                            f.creation().subscriptionId(), beforeReplacement.rowVersion()))));
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThat(count(c, "subscription_lifecycle_receipts")).isEqualTo(4);
    assertThat(count(c, "subscription_provider_history")).isOne();
  }

  @Test
  void populatedV1029UpgradePreservesIntentQuoteRowAndFlywayHistoryWithoutSynthesizingReceipts() {
    Context c = context("1029");
    Fixture f = fixture(c);
    var before =
        one(
            c,
            "SELECT row_to_json(s)::text AS value, xmin::text AS xmin FROM subscription s WHERE subscription_id = ?",
            f.creation().subscriptionId());
    var intent =
        one(
            c,
            "SELECT row_to_json(o)::text AS value, xmin::text AS xmin FROM subscription_create_operations o WHERE request_id = ?",
            f.creation().requestId());
    var history =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
    configuration(c).target("1030").load().migrate();
    var after =
        one(
            c,
            "SELECT (to_jsonb(s) - 'lifecycle_inflight_request_id' - 'current_receipt_request_id')::text AS value, xmin::text AS xmin "
                + "FROM subscription s WHERE subscription_id = ?",
            f.creation().subscriptionId());
    // jsonb comparison avoids treating key order as row mutation.
    assertThat(
            one(
                    c,
                    "SELECT ?::jsonb = ?::jsonb AS same",
                    before.get("value", String.class),
                    after.get("value", String.class))
                .get("same", Boolean.class))
        .isTrue();
    assertThat(after.get("xmin", String.class)).isEqualTo(before.get("xmin", String.class));
    assertThat(
            one(
                c,
                "SELECT row_to_json(o)::text AS value, xmin::text AS xmin FROM subscription_create_operations o WHERE request_id = ?",
                f.creation().requestId()))
        .isEqualTo(intent);
    var afterHistory =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
    assertThat(afterHistory).hasSize(history.size() + 1);
    assertThat(afterHistory.subList(0, history.size())).isEqualTo(history);
    var v1030Subscription =
        one(
            c,
            "SELECT row_to_json(s)::text AS value, xmin::text AS xmin FROM subscription s WHERE subscription_id = ?",
            f.creation().subscriptionId());
    configuration(c).target("1041").load().migrate();
    assertThat(
            one(
                c,
                "SELECT row_to_json(s)::text AS value, xmin::text AS xmin FROM subscription s WHERE subscription_id = ?",
                f.creation().subscriptionId()))
        .isEqualTo(v1030Subscription);
    assertThat(
            one(
                c,
                "SELECT row_to_json(o)::text AS value, xmin::text AS xmin FROM subscription_create_operations o WHERE request_id = ?",
                f.creation().requestId()))
        .isEqualTo(intent);
    var currentHistory =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
    assertThat(currentHistory.subList(0, afterHistory.size())).isEqualTo(afterHistory);
    assertThat(count(c, "subscription_first_invoices")).isZero();
    assertThat(count(c, "subscription_first_invoice_completions")).isZero();
    assertThat(count(c, "subscription_lifecycle_receipts")).isZero();
    assertThatThrownBy(() -> f.service().readCurrent(f.creation().tenantId()))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
  }

  private static AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch paidClaim(
      Fixture f) {
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            f.context().source(),
            f.context().dsl(),
            f.context().tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var authorized =
        checkout.authorizeAndRetainSuccess(
            CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    assertThat(checkout.confirmations()).isOne();
    assertThat(count(f.context(), "subscription_first_invoice_completions")).isZero();
    return authorized;
  }

  private static AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch paidClaimAt1046(
      Fixture f) throws StripeException {
    var checkout =
        CanonicalHostingSubscriptionTestFixture.prepareFirstInvoice(
            f.context().source(),
            f.context().dsl(),
            f.context().tx(),
            f.creation(),
            f.instrument(),
            f.providerId(),
            f.clock(),
            f.owner());
    var authorized = checkout.authorize(CanonicalHostingSubscriptionTestFixture.BILLING_PASSWORD);
    assertThat(checkout.confirmations()).isOne();
    var signed = checkout.signedSuccess();
    var verified = checkout.verifier().verify(signed.payload(), signed.signature());
    var observation = checkout.provider().observe(checkout.invoice());
    // The pre-V1046.3 frontier has no late-payment conflict table. Retain its verified success
    // evidence directly so this historical proof does not invoke the later conflict lookup.
    in(
        f.context(),
        () -> {
          new StripeEventInboxRepository(f.context().dsl()).retainOrReplay(verified);
          f.context()
              .dsl()
              .execute(
                  "INSERT INTO subscription_first_invoice_success_evidence "
                      + "(request_id, subscription_id, provider_event_id, event_payload_digest, tuple_digest, observed_at) "
                      + "VALUES (?, ?, ?, ?, ?, ?::timestamptz)",
                  authorized.requestId(),
                  checkout.invoice().subscriptionId(),
                  verified.providerEventId(),
                  verified.payloadSha256(),
                  checkout.invoice().digest(),
                  java.time.OffsetDateTime.ofInstant(
                      observation.observedAt(), java.time.ZoneOffset.UTC));
          return Boolean.TRUE;
        });
    assertThat(count(f.context(), "subscription_first_invoice_completions")).isZero();
    return authorized;
  }

  private static AccountHostingSubscriptionLifecycleRepository.ProvisioningClaim claim(Fixture f) {
    return Objects.requireNonNull(
        f.service().claimProvisioning(f.creation().requestId(), 1, UUID.randomUUID()).claim());
  }

  @Test
  void committedTerminalCancellationReplaysAfterCanceledSubscriptionIsReplaced() {
    FailedResolution f = failedResolution();
    Context c = f.fixture.context();
    var owner = f.fixture.owner();
    var prepared = in(c, () -> owner.prepareTerminalResolution(f.request));
    f.fixture.transport().json = response(f.fixture, "canceled");
    var sdk = f.fixture.observer().observe(prepared.binding());
    var committed =
        in(c, () -> owner.applyTerminalResolution(prepared, sdk, target -> authorized(f)));
    var predecessor = c.intents().findCurrentByTenant(f.request.tenantId()).orElseThrow();
    var successor =
        in(
            c,
            () ->
                c.intents()
                    .retainPending(
                        new AccountSubscriptionCreateRequest(
                            UUID.randomUUID(),
                            f.request.tenantId(),
                            f.fixture.creation().owner(),
                            f.fixture.creation().planCode(),
                            f.fixture.instrument().instrumentUuid(),
                            1,
                            new ProducerEvidence(
                                f.request.actorAccountId(), UUID.randomUUID(), DIGEST),
                            new AccountSubscriptionCreateRequest.ReplacementFence(
                                f.request.subscriptionId(), predecessor.rowVersion()))));
    List<String> retained = resolutionImage(c);
    var replay = in(c, () -> owner.prepareTerminalResolution(f.request));
    assertThat(replay.replayResult()).isEqualTo(committed);
    assertThat(
            in(
                c,
                () ->
                    owner.applyTerminalResolution(
                        replay,
                        null,
                        target -> {
                          assertThat(target.currentSubscriptionVersion())
                              .isEqualTo(committed.committedRowVersion());
                          return authorized(f);
                        })))
        .isEqualTo(committed);
    assertThat(resolutionImage(c)).isEqualTo(retained);
    assertThat(c.intents().findCurrentByTenant(f.request.tenantId()).orElseThrow().subscriptionId())
        .isEqualTo(successor.subscriptionId());
    assertThatThrownBy(
            () ->
                in(
                    c,
                    () -> owner.prepareTerminalResolution(withReason(f.request, "changed reason"))))
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThat(failedImage(c, f.request.failedRequestId())).isEqualTo(f.failureImage);
  }

  @Test
  void
      terminalCancellationResolutionCommitsOneCanonicalReceiptAndReplaysWithoutChangingFailureHistory() {
    FailedResolution f = failedResolution();
    Context c = f.fixture.context();
    var owner = f.fixture.owner();
    List<String> before = resolutionImage(c);
    var prepared = in(c, () -> owner.prepareTerminalResolution(f.request));
    assertThat(resolutionImage(c)).isEqualTo(before);
    f.fixture.transport().json = response(f.fixture, "canceled");
    var sdk = f.fixture.observer().observe(prepared.binding());
    AtomicInteger callbacks = new AtomicInteger();
    var result =
        in(
            c,
            () ->
                owner.applyTerminalResolution(
                    prepared,
                    sdk,
                    target -> {
                      assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                          .isTrue();
                      assertThat(target.billingOwnerAccountId())
                          .isEqualTo(
                              c.intents()
                                  .findCreation(f.request.creationRequestId())
                                  .orElseThrow()
                                  .owner()
                                  .accountUuid());
                      assertThat(target.currentSubscriptionVersion())
                          .isEqualTo(f.request.expectedSubscriptionVersion());
                      callbacks.incrementAndGet();
                      return authorized(f);
                    }));
    assertThat(result.outcome()).isEqualTo(Outcome.COMMITTED);
    assertThat(result.committedRowVersion()).isEqualTo(f.request.expectedSubscriptionVersion() + 2);
    assertThat(count(c, "subscription_lifecycle_terminal_resolutions")).isOne();
    assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isOne();
    assertThat(count(c, "account_audit_outbox")).isEqualTo(f.auditRowsBefore + 1);
    assertThat(failedImage(c, f.request.failedRequestId())).isEqualTo(f.failureImage);
    assertThat(f.fixture.service().readCurrent(f.request.tenantId()).subscriptionStatus())
        .isEqualTo(Status.CANCELED);
    List<String> committed = resolutionImage(c);
    var replay = in(c, () -> owner.prepareTerminalResolution(f.request));
    assertThat(replay.replayResult()).isEqualTo(result);
    assertThat(
            in(
                c,
                () ->
                    owner.applyTerminalResolution(
                        replay,
                        null,
                        target -> {
                          callbacks.incrementAndGet();
                          return authorized(f);
                        })))
        .isEqualTo(result);
    assertThat(callbacks).hasValue(2);
    assertThat(resolutionImage(c)).isEqualTo(committed);
    assertThatThrownBy(
            () ->
                in(
                    c,
                    () -> owner.prepareTerminalResolution(withReason(f.request, "changed reason"))))
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription_lifecycle_operations SET state = 'COMMITTED', row_version = row_version + 1 WHERE request_id = ?",
                    f.request.failedRequestId()));
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "DELETE FROM subscription_lifecycle_operator_outbox WHERE request_id = ?",
                    f.request.failedRequestId()));
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription_lifecycle_terminal_resolutions SET audit_reason = 'changed' WHERE resolution_request_id = ?",
                    f.request.resolutionRequestId()));
    assertThat(resolutionImage(c)).isEqualTo(committed);
  }

  @Test
  void activeUnresolvedStaleAndWrongBindingResolutionEvidenceNeverInvokesAuthorizationOrWrites() {
    for (String failure : List.of("active", "unresolved", "stale", "wrong-binding")) {
      FailedResolution f = failedResolution();
      Context c = f.fixture.context();
      var prepared = in(c, () -> f.fixture.owner().prepareTerminalResolution(f.request));
      List<String> before = resolutionImage(c);
      f.fixture.transport().json =
          response(f.fixture, failure.equals("active") ? "active" : "canceled");
      if (failure.equals("unresolved")) f.fixture.transport().failure = mock(StripeException.class);
      if (failure.equals("wrong-binding"))
        f.fixture.transport().json =
            f.fixture.transport().json.replace("price_controlled", "price_other");
      var sdk = f.fixture.observer().observe(prepared.binding());
      if (failure.equals("stale")) f.fixture.clock().advance(Duration.ofSeconds(11));
      AtomicInteger callbacks = new AtomicInteger();
      assertThatThrownBy(
              () ->
                  in(
                      c,
                      () ->
                          f.fixture
                              .owner()
                              .applyTerminalResolution(
                                  prepared,
                                  sdk,
                                  target -> {
                                    callbacks.incrementAndGet();
                                    return authorized(f);
                                  })))
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
      assertThat(callbacks).hasValue(0);
      assertThat(resolutionImage(c)).isEqualTo(before);
    }
  }

  @Test
  void deniedAuthorizationAndActualDownstreamSqlFailureRetainFailedFenceAndAllOwnerRows() {
    FailedResolution f = failedResolution();
    Context c = f.fixture.context();
    var prepared = in(c, () -> f.fixture.owner().prepareTerminalResolution(f.request));
    f.fixture.transport().json = response(f.fixture, "canceled");
    var sdk = f.fixture.observer().observe(prepared.binding());
    List<String> before = resolutionImage(c);
    assertThatThrownBy(
            () ->
                in(
                    c,
                    () ->
                        f.fixture
                            .owner()
                            .applyTerminalResolution(
                                prepared,
                                sdk,
                                target -> {
                                  assertThat(
                                          count(c, "subscription_lifecycle_terminal_resolutions"))
                                      .isZero();
                                  throw new IllegalStateException(
                                      "controlled primary authentication denial");
                                })))
        .hasMessage("controlled primary authentication denial");
    assertThat(resolutionImage(c)).isEqualTo(before);
    c.setup()
        .execute(
            "CREATE FUNCTION reject_resolution_billing() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled resolution billing failure' USING ERRCODE = '23514'; END; $$");
    c.setup()
        .execute(
            "CREATE TRIGGER reject_resolution_billing BEFORE INSERT ON account_tenant_entitlement_outbox_events FOR EACH ROW EXECUTE FUNCTION reject_resolution_billing()");
    assertSqlState(
        "23514",
        () ->
            in(
                c,
                () ->
                    f.fixture
                        .owner()
                        .applyTerminalResolution(prepared, sdk, target -> authorized(f))));
    assertThat(resolutionImage(c)).isEqualTo(before);
    assertThat(failedImage(c, f.request.failedRequestId())).isEqualTo(f.failureImage);
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET lifecycle_inflight_request_id = NULL, row_version = row_version + 1 WHERE subscription_id = ?",
                    f.request.subscriptionId()));
    assertSqlState(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET lifecycle_inflight_request_id = ?, row_version = row_version + 1 WHERE subscription_id = ?",
                    UUID.randomUUID(),
                    f.request.subscriptionId()));
    assertThat(resolutionImage(c)).isEqualTo(before);
  }

  @Test
  void resolutionRequiresExactCapturedVersionsSameOwnerAndMatchingFreshAuthorizationSource() {
    FailedResolution f = failedResolution();
    Context c = f.fixture.context();
    List<String> before = resolutionImage(c);
    for (String changed : List.of("owner", "failed-version", "subscription-version")) {
      Request wrong =
          new Request(
              f.request.resolutionRequestId(),
              f.request.failedRequestId(),
              f.request.creationRequestId(),
              f.request.subscriptionId(),
              f.request.tenantId(),
              f.request.expectedFailedOperationVersion()
                  + (changed.equals("failed-version") ? 1 : 0),
              f.request.expectedSubscriptionVersion()
                  + (changed.equals("subscription-version") ? 1 : 0),
              changed.equals("owner") ? UUID.randomUUID() : f.request.actorAccountId(),
              f.request.controlUiIssuanceOperationId(),
              f.request.controlUiTokenHash(),
              f.request.auditEventId(),
              f.request.auditReason());
      assertThatThrownBy(() -> in(c, () -> f.fixture.owner().prepareTerminalResolution(wrong)))
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    }
    var prepared = in(c, () -> f.fixture.owner().prepareTerminalResolution(f.request));
    f.fixture.transport().json = response(f.fixture, "canceled");
    var sdk = f.fixture.observer().observe(prepared.binding());
    assertThatThrownBy(
            () -> in(c, () -> f.fixture.owner().applyTerminalResolution(prepared, sdk, null)))
        .isInstanceOf(NullPointerException.class);
    for (String changed : List.of("actor", "issuance", "hash", "stale-auth")) {
      assertThatThrownBy(
              () ->
                  in(
                      c,
                      () ->
                          f.fixture
                              .owner()
                              .applyTerminalResolution(
                                  prepared,
                                  sdk,
                                  target ->
                                      new AuthorizedActor(
                                          changed.equals("actor")
                                              ? UUID.randomUUID()
                                              : f.request.actorAccountId(),
                                          changed.equals("issuance")
                                              ? UUID.randomUUID()
                                              : f.request.controlUiIssuanceOperationId(),
                                          changed.equals("hash")
                                              ? "b".repeat(64)
                                              : f.request.controlUiTokenHash(),
                                          changed.equals("stale-auth")
                                              ? f.fixture.clock().instant().minusSeconds(1)
                                              : f.fixture.clock().instant()))))
          .isInstanceOf(AccountSubscriptionIntentRepository.FenceRejectedException.class);
    }
    assertThat(resolutionImage(c)).isEqualTo(before);
  }

  @Test
  void competingResolutionRequestsCommitExactlyOneAndRejectStaleCapturedTarget() throws Exception {
    FailedResolution f = failedResolution();
    Context c = f.fixture.context();
    Request other =
        new Request(
            UUID.randomUUID(),
            f.request.failedRequestId(),
            f.request.creationRequestId(),
            f.request.subscriptionId(),
            f.request.tenantId(),
            f.request.expectedFailedOperationVersion(),
            f.request.expectedSubscriptionVersion(),
            f.request.actorAccountId(),
            f.request.controlUiIssuanceOperationId(),
            f.request.controlUiTokenHash(),
            UUID.randomUUID(),
            "second controlled resolution");
    var first = in(c, () -> f.fixture.owner().prepareTerminalResolution(f.request));
    var second = in(c, () -> f.fixture.owner().prepareTerminalResolution(other));
    f.fixture.transport().json = response(f.fixture, "canceled");
    var firstSdk = f.fixture.observer().observe(first.binding());
    var secondSdk = f.fixture.observer().observe(second.binding());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a =
          executor.submit(
              () -> {
                ready.countDown();
                await(go);
                return attemptResolution(f, first, firstSdk);
              });
      var b =
          executor.submit(
              () -> {
                ready.countDown();
                await(go);
                return attemptResolution(f, second, secondSdk);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    } finally {
      go.countDown();
      executor.shutdownNow();
    }
    assertThat(count(c, "subscription_lifecycle_terminal_resolutions")).isOne();
    assertThat(count(c, "subscription_lifecycle_receipts")).isOne();
    assertThat(failedImage(c, f.request.failedRequestId())).isEqualTo(f.failureImage);
  }

  private static boolean attemptResolution(
      FailedResolution f,
      AccountHostingSubscriptionLifecycleRepository.ResolutionPreparation prepared,
      StripeHostingSubscriptionObserver.Result sdk) {
    try {
      in(
          f.fixture.context(),
          () ->
              f.fixture
                  .owner()
                  .applyTerminalResolution(
                      prepared,
                      sdk,
                      target ->
                          new AuthorizedActor(
                              prepared.request().actorAccountId(),
                              prepared.request().controlUiIssuanceOperationId(),
                              prepared.request().controlUiTokenHash(),
                              f.fixture.clock().instant())));
      return true;
    } catch (AccountSubscriptionIntentRepository.FenceRejectedException lostRace) {
      return false;
    }
  }

  private static FailedResolution failedResolution() {
    Context c = context("latest");
    Fixture f = fixture(c);
    var claim = claim(f);
    UUID failed = UUID.randomUUID();
    f.transport().json = response(f, "active").replace("price_controlled", "price_wrong");
    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThat(
              f.service()
                  .reconcile(failed, f.creation().requestId(), 2, f.providerId(), claim.attemptId())
                  .outcome())
          .isEqualTo(attempt == 3 ? Outcome.TERMINAL_FAILURE : Outcome.RECONCILING);
      f.clock().advance(Duration.ofSeconds(2));
    }
    long version =
        one(
                c,
                "SELECT row_version FROM subscription_lifecycle_operations WHERE request_id = ?",
                failed)
            .get(0, Long.class);
    long current =
        c.intents().findCurrentByTenant(f.creation().tenantId()).orElseThrow().rowVersion();
    Request request =
        new Request(
            UUID.randomUUID(),
            failed,
            f.creation().requestId(),
            f.creation().subscriptionId(),
            f.creation().tenantId(),
            version,
            current,
            f.creation().owner().accountUuid(),
            UUID.randomUUID(),
            "a".repeat(64),
            UUID.randomUUID(),
            "Controlled canceled provider recovery");
    return new FailedResolution(
        f, request, failedImage(c, failed), count(c, "account_audit_outbox"));
  }

  private static AuthorizedActor authorized(FailedResolution f) {
    return new AuthorizedActor(
        f.request.actorAccountId(),
        f.request.controlUiIssuanceOperationId(),
        f.request.controlUiTokenHash(),
        f.fixture.clock().instant());
  }

  private static Request withReason(Request original, String reason) {
    return new Request(
        original.resolutionRequestId(),
        original.failedRequestId(),
        original.creationRequestId(),
        original.subscriptionId(),
        original.tenantId(),
        original.expectedFailedOperationVersion(),
        original.expectedSubscriptionVersion(),
        original.actorAccountId(),
        original.controlUiIssuanceOperationId(),
        original.controlUiTokenHash(),
        original.auditEventId(),
        reason);
  }

  private static String failedImage(Context c, UUID failed) {
    return one(
            c,
            "SELECT to_jsonb(operation)::text || to_jsonb(notice)::text FROM subscription_lifecycle_operations operation JOIN subscription_lifecycle_operator_outbox notice USING (request_id) WHERE request_id = ?",
            failed)
        .get(0, String.class);
  }

  private static List<String> resolutionImage(Context c) {
    List<String> image = new ArrayList<>();
    for (String table :
        List.of(
            "subscription",
            "subscription_current_observations",
            "subscription_lifecycle_operations",
            "subscription_lifecycle_attempts",
            "subscription_lifecycle_observations",
            "subscription_lifecycle_receipts",
            "subscription_lifecycle_terminal_resolutions",
            "subscription_lifecycle_operator_outbox",
            "subscription_provider_history",
            "subscription_provider_terminal_resolutions",
            "subscription_instrument_reservations",
            "account_audit_outbox",
            "account_tenant_entitlement_outbox_events",
            "account_tenant_authority_source_records",
            "account_authority_generations")) {
      image.add(
          one(
                  c,
                  "SELECT coalesce(jsonb_agg(to_jsonb(value) ORDER BY to_jsonb(value)::text)::text, '[]') FROM "
                      + table
                      + " value")
              .get(0, String.class));
    }
    return List.copyOf(image);
  }

  private static List<String> firstInvoiceImage(Context c) {
    List<String> image = new ArrayList<>(resolutionImage(c));
    for (String table :
        List.of(
            "subscription_provisioning_work",
            "subscription_provisioning_attempts",
            "subscription_first_invoices",
            "subscription_first_invoice_confirmations",
            "subscription_first_invoice_dispatches",
            "subscription_first_invoice_authorizations",
            "subscription_first_invoice_command_claims",
            "subscription_first_invoice_success_evidence",
            "subscription_first_invoice_completions",
            "account_tenant_entitlement_outbox_streams",
            "account_authority_outbox_streams",
            "account_authority_outbox_events")) {
      image.add(
          one(
                  c,
                  "SELECT coalesce(jsonb_agg(to_jsonb(value) ORDER BY to_jsonb(value)::text)::text, '[]') FROM "
                      + table
                      + " value")
              .get(0, String.class));
    }
    return List.copyOf(image);
  }

  /** Controlled authorization callback only; no genuine actor/primary-authentication claim. */
  private record FailedResolution(
      Fixture fixture, Request request, String failureImage, long auditRowsBefore) {}

  private static void activate(Fixture f) {
    // Terminal initial provider evidence may release a hold without authorizing a payment.
    boolean terminal = f.transport().json.contains("\"status\":\"canceled\"");
    if (terminal) {
      var claim = claim(f);
      assertThat(
              f.service()
                  .reconcile(
                      UUID.randomUUID(),
                      f.creation().requestId(),
                      claim.subscriptionRowVersion(),
                      f.providerId(),
                      claim.attemptId())
                  .outcome())
          .isEqualTo(Outcome.COMMITTED);
      return;
    }
    var payment = paidClaim(f);
    assertThat(
            f.service()
                .reconcile(
                    payment.lifecycleRequestId(),
                    f.creation().requestId(),
                    payment.expectedRowVersion(),
                    f.providerId(),
                    null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
  }

  private static void refresh(Fixture f) {
    long version =
        f.context()
            .intents()
            .findCurrentByTenant(f.creation().tenantId())
            .orElseThrow()
            .rowVersion();
    assertThat(
            f.service()
                .reconcile(
                    UUID.randomUUID(), f.creation().requestId(), version, f.providerId(), null)
                .outcome())
        .isEqualTo(Outcome.COMMITTED);
  }

  private static Fixture fixture(Context c) {
    long row = ROWS.incrementAndGet();
    UUID account =
        CanonicalHostingSubscriptionTestFixture.createBillingAccount(c.dsl(), c.tx())
            .getAccountUuid();
    UUID tenant = UUID.randomUUID();
    UUID createRequest = UUID.randomUUID();
    UUID createOperation = UUID.randomUUID();
    String key = "game-" + row;
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            "prod", createRequest, key, "Controlled hosting", "Storage only");
    var source =
        new FreshTenantCreationEvidence(
            1,
            "prod",
            createRequest,
            createOperation,
            requestDigest,
            tenant,
            row,
            key,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "prod",
                createRequest,
                createOperation,
                requestDigest,
                tenant,
                row,
                key,
                "NEW_GAME_ROW"));
    in(
        c,
        () -> {
          new FreshTenantIdentityAssociationRepository(c.dsl(), "prod").importVerified(source);
          return new AccountAuthorityGenerationRepository(c.dsl()).initializeTenantIfAbsent(tenant);
        });
    UUID authorization = UUID.randomUUID();
    var creator =
        new FreshTenantCreatorEvidence(
            1,
            source,
            account,
            authorization,
            DIGEST,
            FreshTenantCreatorDigest.evidenceDigest(1, source, account, authorization, DIGEST));
    var owner =
        in(
            c,
            () ->
                new AccountInitialTenantBillingOwnerRepository(c.dsl())
                    .establishInitial(
                        new AccountInitialTenantBillingOwnerRepository.InitialRequest(
                            UUID.randomUUID(), creator, UUID.randomUUID(), 1, true)));
    var customers = new AccountBillingCustomerRepository(c.dsl());
    var pendingCustomer =
        in(c, () -> customers.retainIntent(account, UUID.randomUUID(), UUID.randomUUID()))
            .customer();
    var customer =
        in(
            c,
            () ->
                customers.recordObservation(
                    account,
                    pendingCustomer.customerProvisioningRequestId(),
                    1,
                    UUID.randomUUID(),
                    AccountBillingCustomerRecord.Observation.CONFIRMED,
                    "cus_" + row,
                    DIGEST));
    var instrument =
        in(
            c,
            () ->
                new AccountPaymentInstrumentRepository(c.dsl())
                    .retainAttachment(
                        new AccountPaymentInstrumentRepository.Attachment(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            account,
                            customer.customerProvisioningRequestId(),
                            customer.providerCustomerId(),
                            "pm_" + row,
                            AccountPaymentInstrumentRecord.State.ATTACHABLE,
                            new AccountPaymentInstrumentRecord.DisplayMetadata(
                                "visa", "4242", 12, 2030),
                            DIGEST)));
    var creation =
        in(
            c,
            () ->
                c.intents()
                    .retainPending(
                        new AccountSubscriptionCreateRequest(
                            UUID.randomUUID(),
                            tenant,
                            new OwnerFence(account, owner.ownerVersion(), owner.ownerGeneration()),
                            "hosting",
                            instrument.instrumentUuid(),
                            1,
                            new ProducerEvidence(account, UUID.randomUUID(), DIGEST),
                            null)));
    var clock = new MutableClock(creation.createdAt().plusSeconds(1));
    var transport = new ControlledTransport();
    var repository = owner(c, clock, c.intents());
    var observer =
        new StripeHostingSubscriptionObserver(
            new com.stripe.StripeClient(transport),
            clock,
            Duration.ofMillis(250),
            Duration.ofSeconds(1));
    var terminalInvoiceTransport =
        new ControlledFirstInvoiceTransport(creation, instrument, "sub_" + row, true);
    var terminalInvoiceClient =
        new net.firedevops.firemud.accountservice.client.StripeHostingFirstInvoicePaymentClient(
            new com.stripe.StripeClient(terminalInvoiceTransport),
            clock,
            Duration.ofMillis(250),
            Duration.ofSeconds(1));
    var service =
        new AccountHostingSubscriptionLifecycleService(
            repository,
            observer,
            new DataSourceTransactionManager(c.source()),
            terminalInvoiceClient);
    Fixture fixture =
        new Fixture(
            c,
            creation,
            instrument,
            "sub_" + row,
            clock,
            transport,
            terminalInvoiceTransport,
            repository,
            observer,
            service);
    transport.json = response(fixture, "active");
    return fixture;
  }

  private static AccountHostingSubscriptionLifecycleRepository owner(
      Context c, Clock clock, AccountSubscriptionIntentRepository intents) {
    return new AccountHostingSubscriptionLifecycleRepository(
        c.dsl(),
        intents,
        clock,
        new ReconciliationLimits(
            4, 3, Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofHours(1)),
        new AccountHostingSubscriptionLifecyclePolicy(
            Duration.ofSeconds(15),
            new RecoveryTiming(Duration.ofSeconds(60), Duration.ofSeconds(120))),
        "prod",
        false);
  }

  private static AccountHostingSubscriptionLifecycleService service(
      Context c,
      AccountHostingSubscriptionLifecycleRepository owner,
      ControlledTransport transport,
      Clock clock) {
    return new AccountHostingSubscriptionLifecycleService(
        owner,
        new StripeHostingSubscriptionObserver(
            new com.stripe.StripeClient(transport),
            clock,
            Duration.ofMillis(250),
            Duration.ofSeconds(1)),
        new DataSourceTransactionManager(c.source()));
  }

  @SuppressFBWarnings(
      value = "VA_FORMAT_STRING_USES_NEWLINE",
      justification =
          "This is a JSON test fixture whose text block intentionally retains LF characters independent of the host console line separator.")
  private static String response(Fixture f, String status) {
    return """
        {"id":"%s","object":"subscription","customer":"%s","default_payment_method":"%s",
         "livemode":false,"status":"%s","cancel_at_period_end":false,"cancel_at":null,"canceled_at":null,"ended_at":null,
         "metadata":{"firemud_metadata_schema_version":"1","firemud_operation_type":"subscription_provisioning",
          "firemud_operation_id":"%s","firemud_subscription_id":"%s"},
         "items":{"object":"list","data":[{"id":"si_controlled","object":"subscription_item","subscription":"%s",
          "quantity":1,"current_period_start":%d,"current_period_end":%d,"price":{"id":"%s","object":"price"}}],"has_more":false}}
        """
        .formatted(
            f.providerId(),
            f.creation().providerCustomerId(),
            f.instrument().providerPaymentMethodId(),
            status,
            f.creation().subscriptionProvisioningRequestId(),
            f.creation().subscriptionId(),
            f.providerId(),
            f.creation().createdAt().getEpochSecond(),
            f.creation().createdAt().getEpochSecond() + 3600,
            f.creation().planQuote().providerPriceId());
  }

  // Runtime fixtures use the latest schema; upgrade witnesses pin their historical targets.
  private static Context context(String target) {
    String schema = "hosting_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    var setup = DSL.using(source, SQLDialect.POSTGRES);
    var dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var catalog =
        new AccountHostingPlanCatalog(
            List.of(
                new AccountHostingPlanCatalog.ConfiguredPlan(
                    "hosting", "price_controlled", "1", "100", "2", "1048576")));
    var c =
        new Context(
            source, schema, setup, dsl, tx, new AccountSubscriptionIntentRepository(dsl, catalog));
    configuration(c).target(target).load().migrate();
    return c;
  }

  private static FluentConfiguration configuration(Context c) {
    return Flyway.configure()
        .dataSource(c.source())
        .schemas(c.schema())
        .defaultSchema(c.schema())
        .placeholders(Map.of("serviceSchema", c.schema()))
        .locations("classpath:db/migration", "classpath:db/migration/saga");
  }

  private static <T> T in(Context c, Supplier<T> body) {
    return Objects.requireNonNull(c.tx().execute(s -> body.get()));
  }

  private static org.jooq.Record one(Context c, String sql, Object... bindings) {
    return Objects.requireNonNull(c.setup().fetchOne(sql, bindings));
  }

  private static long count(Context c, String table) {
    return one(c, "SELECT count(*) FROM " + table).get(0, Long.class);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Controlled SDK read barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Controlled SDK read interrupted", interrupted);
    }
  }

  private static void assertSqlState(String state, Runnable body) {
    assertSqlState(state, null, body);
  }

  private static void assertSqlState(String state, String diagnostic, Runnable body) {
    assertThatThrownBy(body::run)
        .satisfies(
            failure -> {
              for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                  assertThat(sql.getSQLState()).isEqualTo(state);
                  if (diagnostic != null) {
                    assertThat(sql.getMessage()).contains(diagnostic);
                  }
                  return;
                }
              }
              throw new AssertionError("Expected actual PostgreSQL constraint refusal", failure);
            });
  }

  private record Context(
      DriverManagerDataSource source,
      String schema,
      DSLContext setup,
      DSLContext dsl,
      TransactionTemplate tx,
      AccountSubscriptionIntentRepository intents) {}

  private record Fixture(
      Context context,
      AccountSubscriptionCreationRecord creation,
      AccountPaymentInstrumentRecord instrument,
      String providerId,
      MutableClock clock,
      ControlledTransport transport,
      ControlledFirstInvoiceTransport terminalInvoiceTransport,
      AccountHostingSubscriptionLifecycleRepository owner,
      StripeHostingSubscriptionObserver observer,
      AccountHostingSubscriptionLifecycleService service) {}

  private static final class ControlledTransport implements StripeResponseGetter {
    private String json;
    private StripeException failure;
    private Runnable duringRead;
    private int reads;

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(method).isEqualTo(RequestMethod.GET);
      assertThat(path).startsWith("/v1/subscriptions/");
      reads++;
      Runnable action = duringRead;
      duringRead = null;
      if (action != null) {
        action.run();
      }
      if (failure != null) {
        throw failure;
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(json, type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode mode)
        throws StripeException {
      throw new AssertionError("No streaming or provider mutations are allowed");
    }
  }
}
