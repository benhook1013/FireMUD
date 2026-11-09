package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
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
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.Attachment;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.OwnerFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ProducerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCreateRequest.ReplacementFence;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.ConflictException;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.FenceRejectedException;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionIntentRepository.MappingDisposition;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorDigest;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.test.TestContainerImages;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL persistence/concurrency sources using CONTROLLED owner/provider evidence. These
 * fixtures do not qualify genuine authenticated producers, Stripe, lifecycle/outbox dispatch,
 * transfers, downstream readers or production admission. No trigger is disabled to construct them.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountSubscriptionIntentPostgresIntegrationTest {
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final OffsetDateTime BOUNDARY = OffsetDateTime.parse("2026-11-01T00:00:00Z");
  private static final AtomicLong GAME_ROWS = new AtomicLong(100);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void durableProvisioningClaimReplaysAfterRecreationAndRecoversOnlyTheOriginalBinding() {
    Context c = context();
    configuration(c).target("1030").load().migrate();
    Seed seed = seed(c);
    var created = create(c, request(seed, UUID.randomUUID(), 1, null));
    UUID attemptId = UUID.randomUUID();
    var first =
        Objects.requireNonNull(
                c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 1, attemptId)))
            .claim();
    assertThat(first.creation()).isEqualTo(created);
    assertThat(first.subscriptionRowVersion()).isEqualTo(2);
    assertThat(first.attemptNumber()).isEqualTo(1);
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().status())
        .isEqualTo("provisioning");
    assertThat(
            c.repository()
                .findCurrentByTenant(seed.tenant())
                .orElseThrow()
                .providerSubscriptionId())
        .isNull();
    var replay =
        Objects.requireNonNull(
                c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 1, attemptId)))
            .claim();
    assertThat(replay.attemptId()).isEqualTo(first.attemptId());
    assertThat(replay.subscriptionRowVersion()).isEqualTo(first.subscriptionRowVersion());
    assertThat(replay.expiresAt()).isEqualTo(first.expiresAt());
    assertThat(count(c, "subscription_provisioning_attempts")).isOne();
    assertThat(databaseNow(c)).isBefore(first.expiresAt());
    var waiting =
        Objects.requireNonNull(
            c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 2, UUID.randomUUID())));
    assertThat(waiting.disposition())
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.WAITING);
    sleepUntil(c, first.expiresAt().plusMillis(6));
    var recovered =
        Objects.requireNonNull(
                c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 2, UUID.randomUUID())))
            .claim();
    // Attempt history does not grant provider IO; the independent dispatch receipt owns it.
    assertThat(recovered.attemptNumber()).isEqualTo(2);
    assertThat(recovered.creation()).isEqualTo(created);
    assertThat(recovered.subscriptionRowVersion()).isEqualTo(3);
    assertThat(recovered.attemptId()).isNotEqualTo(first.attemptId());
    assertThat(count(c, "subscription_provisioning_attempts")).isEqualTo(2);
    assertThatThrownBy(() -> c.tx().execute(s -> lifecycle(c).deferUnresolved(first)))
        .isInstanceOf(FenceRejectedException.class);
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                            created.requestId()),
                    "Expected retained reservation")
                .get("released_at"))
        .isNull();
    assertThat(count(c, "subscription_provider_history")).isZero();
    assertThat(count(c, "account_tenant_entitlement_outbox_events")).isZero();
  }

  @Test
  void provisioningExhaustionCommitsOneOperatorItemWithoutCancellationOrWalletRewrite() {
    Context c = context();
    configuration(c).target("1030").load().migrate();
    Seed seed = seed(c);
    var created = create(c, request(seed, UUID.randomUUID(), 1, null));
    var first =
        Objects.requireNonNull(
                c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 1, UUID.randomUUID())))
            .claim();
    var firstDeferred = c.tx().execute(s -> lifecycle(c).deferUnresolved(first));
    assertThat(firstDeferred)
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.WAITING);
    Instant retryAt =
        Objects.requireNonNull(
                c.setup()
                    .fetchOne(
                        "SELECT retry_at FROM subscription_provisioning_work WHERE request_id = ?",
                        created.requestId()))
            .get("retry_at", OffsetDateTime.class)
            .toInstant();
    sleepUntil(c, retryAt.plusMillis(5));
    var second =
        Objects.requireNonNull(
                c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 2, UUID.randomUUID())))
            .claim();
    var exhausted = c.tx().execute(s -> lifecycle(c).deferUnresolved(second));
    assertThat(exhausted)
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.TERMINAL_FAILURE);
    var exhaustedReplay = c.tx().execute(s -> lifecycle(c).deferUnresolved(second));
    assertThat(exhaustedReplay)
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.TERMINAL_FAILURE);
    assertThat(count(c, "subscription_provisioning_operator_outbox")).isOne();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    var current = c.repository().findCurrentByTenant(seed.tenant()).orElseThrow();
    assertThat(current.status()).isEqualTo("provisioning");
    assertThat(current.terminalResolutionId()).isNull();
    assertThat(current.rowVersion()).isEqualTo(3);
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT row_version FROM payment_instrument WHERE instrument_uuid = ?",
                            seed.instrument().instrumentUuid()),
                    "Expected retained instrument")
                .get("row_version", Long.class))
        .isOne();
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                            created.requestId()),
                    "Expected retained reservation")
                .get("released_at"))
        .isNull();
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute("UPDATE subscription_provisioning_operator_outbox SET reason = reason"));
    rejectSql("23514", () -> c.setup().execute("DELETE FROM subscription_provisioning_attempts"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = AccountPaymentInstrumentRecord.State.class,
      names = {"ATTACHABLE", "UNAVAILABLE"})
  void claimRollbackAndStaleReservedWalletRetainOneTerminalOperatorItem(
      AccountPaymentInstrumentRecord.State observedState) {
    Context c = context();
    configuration(c).target("1030").load().migrate();
    Seed seed = seed(c);
    var created = create(c, request(seed, UUID.randomUUID(), 1, null));
    c.tx()
        .executeWithoutResult(
            s -> {
              lifecycle(c).claim(created.requestId(), 1, UUID.randomUUID());
              s.setRollbackOnly();
            });
    assertThat(count(c, "subscription_provisioning_work")).isZero();
    assertThat(count(c, "subscription_provisioning_attempts")).isZero();
    var pending = c.repository().findCurrentByTenant(seed.tenant()).orElseThrow();
    assertThat(pending.status()).isEqualTo("pending");
    var observed =
        c.tx()
            .execute(
                s ->
                    new AccountPaymentInstrumentRepository(c.dsl())
                        .recordAvailability(
                            new AccountPaymentInstrumentRepository.AvailabilityObservation(
                                UUID.randomUUID(),
                                seed.instrument().instrumentUuid(),
                                seed.owner().accountUuid(),
                                1L,
                                observedState,
                                seed.instrument().display(),
                                DIGEST)));
    // Upgrade the original pending intent and its changed wallet without recreating either row.
    configuration(c).load().migrate();
    assertThatThrownBy(
            () ->
                c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 2, UUID.randomUUID())))
        .isInstanceOf(FenceRejectedException.class);
    // Provisioning boundaries use PostgreSQL clock_timestamp(); an injected Java clock cannot
    // move the database clock before this durable creation time. The wrong-version fence above
    // and rollback proof below exercise the supported database-owned claim boundaries.
    c.tx()
        .executeWithoutResult(
            s -> {
              assertThat(
                      lifecycle(c).claim(created.requestId(), 1, UUID.randomUUID()).disposition())
                  .isEqualTo(
                      AccountHostingSubscriptionLifecycleRepository.Disposition.TERMINAL_FAILURE);
              s.setRollbackOnly();
            });
    assertThat(count(c, "subscription_provisioning_work")).isZero();
    assertThat(count(c, "subscription_provisioning_operator_outbox")).isZero();
    UUID firstAttempt = UUID.randomUUID();
    for (UUID attempt : List.of(firstAttempt, firstAttempt, UUID.randomUUID())) {
      var result =
          Objects.requireNonNull(
              c.tx().execute(s -> lifecycle(c).claim(created.requestId(), 1, attempt)));
      assertThat(result.disposition())
          .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.TERMINAL_FAILURE);
      assertThat(result.claim()).isNull();
    }
    assertThat(
            Objects.requireNonNull(
                    c.tx()
                        .execute(
                            s -> lifecycle(c).claim(created.requestId(), 1, UUID.randomUUID())))
                .disposition())
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.TERMINAL_FAILURE);
    assertThat(count(c, "subscription_provisioning_work")).isOne();
    assertThat(count(c, "subscription_provisioning_attempts")).isZero();
    assertThat(count(c, "subscription_provider_dispatch_claim")).isZero();
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    assertThat(count(c, "subscription_provisioning_operator_outbox")).isOne();
    var work =
        Objects.requireNonNull(
            c.setup()
                .fetchOne(
                    "SELECT * FROM subscription_provisioning_work WHERE request_id = ?",
                    created.requestId()));
    assertThat(work.get("phase", String.class))
        .isEqualTo("BILLING_RECONCILIATION_TERMINAL_FAILURE");
    assertThat(work.get("attempt_count", Integer.class)).isZero();
    assertThat(work.get("row_version", Long.class)).isEqualTo(2L);
    assertThat(work.get("last_attempt_id")).isNull();
    var notice =
        Objects.requireNonNull(
            c.setup()
                .fetchOne(
                    "SELECT * FROM subscription_provisioning_operator_outbox WHERE request_id = ?",
                    created.requestId()));
    assertThat(notice.get("reason", String.class)).isEqualTo("RESERVED_INSTRUMENT_CHANGED");
    assertThat(notice.get("recorded_at")).isEqualTo(work.get("terminal_at"));
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow()).isEqualTo(pending);
    assertThat(
            new AccountPaymentInstrumentRepository(c.dsl())
                .findByOwner(seed.owner().accountUuid(), seed.instrument().instrumentUuid())
                .orElseThrow())
        .isEqualTo(observed);
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                            created.requestId()))
                .get("released_at"))
        .isNull();
  }

  @Test
  void concurrentProvisioningClaimsHaveOneCurrentVersionWinner() throws Exception {
    Context c = context();
    configuration(c).target("1030").load().migrate();
    Seed seed = seed(c);
    var created = create(c, request(seed, UUID.randomUUID(), 1, null));
    Supplier<String> attempt =
        () -> {
          try {
            return Objects.requireNonNull(
                    c.tx()
                        .execute(
                            s -> lifecycle(c).claim(created.requestId(), 1, UUID.randomUUID())))
                .disposition()
                .name();
          } catch (FenceRejectedException stale) {
            return "FENCED";
          }
        };
    assertThat(race(attempt, attempt)).containsExactlyInAnyOrder("CLAIMED", "FENCED");
    assertThat(count(c, "subscription_provisioning_attempts")).isOne();
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().rowVersion())
        .isEqualTo(2);
  }

  @Test
  void expiredIntentCreatesUnresolvedFailureWithoutClaimingProviderWork() {
    Context c = context();
    configuration(c).target("1030").load().migrate();
    Seed seed = seed(c);
    var created = create(c, request(seed, UUID.randomUUID(), 1, null));
    Duration provisioningTimeout = Duration.ofSeconds(3);
    sleepUntil(c, created.createdAt().plus(provisioningTimeout).plusMillis(5));
    var expired =
        Objects.requireNonNull(
            c.tx()
                .execute(
                    s ->
                        lifecycle(c, provisioningTimeout)
                            .claim(created.requestId(), 1, UUID.randomUUID())));
    assertThat(expired.disposition())
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Disposition.TERMINAL_FAILURE);
    assertThat(expired.claim()).isNull();
    assertThat(count(c, "subscription_provisioning_attempts")).isZero();
    assertThat(count(c, "subscription_provisioning_operator_outbox")).isOne();
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().status())
        .isEqualTo("pending");
  }

  private static AccountHostingSubscriptionLifecycleRepository lifecycle(Context c) {
    return lifecycle(c, Duration.ofHours(1));
  }

  private static AccountHostingSubscriptionLifecycleRepository lifecycle(
      Context c, Duration absoluteTimeout) {
    return new AccountHostingSubscriptionLifecycleRepository(
        c.dsl(),
        c.repository(),
        Clock.systemUTC(),
        new AccountHostingSubscriptionLifecycleRepository.ReconciliationLimits(
            2, 3, Duration.ofSeconds(3), Duration.ofMillis(1), absoluteTimeout),
        new AccountHostingSubscriptionLifecyclePolicy(
            Duration.ofSeconds(15),
            new AccountHostingSubscriptionLifecyclePolicy.RecoveryTiming(
                Duration.ofSeconds(60), Duration.ofSeconds(120))),
        "prod",
        false);
  }

  private static Instant databaseNow(Context c) {
    return Objects.requireNonNull(c.setup().fetchOne("SELECT clock_timestamp() AS now"))
        .get("now", OffsetDateTime.class)
        .toInstant();
  }

  private static void sleepUntil(Context c, Instant deadline) {
    c.setup().execute("SELECT pg_sleep_until(?::timestamptz)", deadline.atOffset(ZoneOffset.UTC));
  }

  @Test
  void
      combinedForwardUpgradePreservesOriginalNumericRowsAndPublishedHistoryWithoutInferringWinners() {
    Context c = predecessor();
    var rows =
        c.setup()
            .fetch(
                "SELECT row_to_json(s)::text AS value, xmin::text AS xmin, ctid::text AS ctid FROM subscription s ORDER BY id")
            .intoMaps();
    var accounts =
        c.setup()
            .fetch(
                "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM accounts a ORDER BY id")
            .intoMaps();
    var history =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();

    configuration(c).target("1029").load().migrate();

    assertThat(
            c.setup()
                .fetch(
                    "SELECT row_to_json(s)::text AS value, xmin::text AS xmin, ctid::text AS ctid FROM account_retained_subscription_evidence s ORDER BY id")
                .intoMaps())
        .isEqualTo(rows);
    assertThat(
            c.setup()
                .fetch(
                    "SELECT row_to_json(a)::text AS value, xmin::text AS xmin FROM accounts a ORDER BY id")
                .intoMaps())
        .isEqualTo(accounts);
    var afterHistory =
        c.setup().fetch("SELECT * FROM flyway_schema_history ORDER BY installed_rank").intoMaps();
    assertThat(afterHistory).hasSize(history.size() + 5);
    assertThat(afterHistory.subList(history.size(), afterHistory.size()))
        .extracting(row -> row.get("version"))
        .containsExactly("1027", "1027.1", "1027.2", "1028", "1029");
    assertThat(afterHistory.subList(0, history.size())).containsExactlyElementsOf(history);
    assertThat(
            c.setup()
                .fetch(
                    "SELECT version FROM flyway_schema_history WHERE version IN ('1000','1001','1002')"))
        .hasSize(3);
    assertThat(count(c, "subscription")).isZero();
    assertThat(count(c, "subscription_create_operations")).isZero();
    assertThat(count(c, "subscription_provider_history")).isZero();
    assertThat(
            c.setup()
                .fetch(
                    "SELECT tenant_id FROM subscription WHERE superseded_at IS NULL GROUP BY tenant_id HAVING count(*) > 1"))
        .isEmpty();
    assertThat(
            c.setup()
                .fetch(
                    "SELECT provider_subscription_id FROM subscription WHERE superseded_at IS NULL AND provider_subscription_id IS NOT NULL GROUP BY provider_subscription_id HAVING count(*) <> 1"))
        .isEmpty();
    assertThat(indexDefinition(c, "uq_subscription_active_tenant"))
        .contains("UNIQUE", "tenant_id", "superseded_at IS NULL");
    assertThat(indexDefinition(c, "uq_subscription_active_provider"))
        .contains("UNIQUE", "provider_subscription_id IS NOT NULL", "superseded_at IS NULL");
    rejectSql(
        "23514",
        () ->
            c.setup().execute("UPDATE account_retained_subscription_evidence SET status = status"));
    configuration(c).target("1029").load().validate();
  }

  @Test
  void
      pendingRetainsExactOriginalResultSeparateIdentitiesReservationAndOutboxAndRejectsChangedRequest() {
    Context c = context();
    Seed seed = seed(c);
    var request = request(seed, UUID.randomUUID(), 1, null);
    var created = create(c, request);
    assertThat(created.requestDigest())
        .isEqualTo(
            AccountSubscriptionCreateDigest.encode(
                    seed.tenant(), c.account(), "basic-hosting", seed.instrument().instrumentUuid())
                .digest());
    assertThat(created.initialStatus()).isEqualTo("pending");
    assertThat(created.initialRowVersion()).isOne();
    assertThat(
            List.of(
                created.requestId(),
                created.subscriptionProvisioningRequestId(),
                created.cleanupProviderIdentity(),
                created.customerProvisioningRequestId()))
        .doesNotHaveDuplicates();
    var customer =
        new AccountBillingCustomerRepository(c.dsl()).findByAccount(c.account()).orElseThrow();
    assertThat(created.subscriptionProvisioningRequestId())
        .isNotEqualTo(customer.providerIdempotencyId());
    assertThat(created.cleanupProviderIdentity()).isNotEqualTo(customer.providerIdempotencyId());
    assertThat(
            c.repository()
                .findCurrentByTenant(seed.tenant())
                .orElseThrow()
                .providerSubscriptionId())
        .isNull();
    assertThat(
            Objects.requireNonNull(
                    c.tx().execute(s -> c.repository().lockPendingForClaim(request.requestId(), 1)))
                .status())
        .isEqualTo("pending");
    assertThat(create(c, request)).isEqualTo(created);
    assertThat(count(c, "subscription_create_outbox")).isOne();
    assertThat(count(c, "subscription_instrument_reservations")).isOne();
    assertThat(count(c, "subscription_create_provider_identities")).isEqualTo(2);
    var changed =
        new AccountSubscriptionCreateRequest(
            request.requestId(),
            seed.tenant(),
            seed.owner(),
            "pro-hosting",
            seed.instrument().instrumentUuid(),
            1,
            producer(c.account()),
            null);
    assertThatThrownBy(() -> create(c, changed))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThat(count(c, "subscription")).isOne();
    controlledTerminalCleanup(c, created);
    assertThat(create(c, request))
        .isEqualTo(created); // Never rebuild the result from canceled current state.
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().status())
        .isEqualTo("canceled");
  }

  @Test
  void
      frozenServerQuoteReplaysAfterCatalogChangeOrRemovalWithoutChangingCallerDigestOrReservation() {
    Context c = context();
    Seed first = seed(c);
    var request = request(first, UUID.randomUUID(), 1, null);
    var created = create(c, request);
    var quote = configuredPlans().resolve("basic-hosting");
    assertThat(created.planQuote()).isEqualTo(quote);
    var retained =
        Objects.requireNonNull(
            c.setup()
                .fetchOne(
                    "SELECT * FROM subscription_create_operations WHERE request_id = ?",
                    created.requestId()));
    assertThat(retained.get("plan_quote_payload", byte[].class)).containsExactly(quote.payload());
    assertThat(retained.get("plan_quote_digest", String.class)).isEqualTo(quote.digest());
    assertThat(retained.get("provider_price_id", String.class)).isEqualTo(quote.providerPriceId());
    assertThat(retained.get("provider_quantity", Long.class)).isEqualTo(quote.providerQuantity());
    assertThat(retained.get("max_active_sessions", Long.class))
        .isEqualTo(quote.maxActiveSessions());
    assertThat(retained.get("max_concurrent_game_instances", Long.class))
        .isEqualTo(quote.maxConcurrentGameInstances());
    assertThat(retained.get("max_storage_bytes", Long.class)).isEqualTo(quote.maxStorageBytes());
    var changedCatalog =
        new AccountHostingPlanCatalog(
            List.of(
                new AccountHostingPlanCatalog.ConfiguredPlan(
                    "basic-hosting", "price_controlledLater", "9", "200", "4", "2097152")));
    var changedRepository = new AccountSubscriptionIntentRepository(c.dsl(), changedCatalog);
    assertThat(
            c.tx()
                .<AccountSubscriptionCreationRecord>execute(
                    s -> changedRepository.retainPending(request)))
        .isEqualTo(created);
    var removedCatalogRepository =
        new AccountSubscriptionIntentRepository(c.dsl(), new AccountHostingPlanCatalog(List.of()));
    assertThat(
            c.tx()
                .<AccountSubscriptionCreationRecord>execute(
                    s -> removedCatalogRepository.retainPending(request)))
        .isEqualTo(created);
    assertThat(changedRepository.findCurrentByTenant(first.tenant()).orElseThrow().planQuote())
        .isEqualTo(quote);
    assertThat(
            Objects.requireNonNull(
                    c.tx()
                        .execute(
                            s -> changedRepository.lockPendingForClaim(request.requestId(), 1)))
                .planQuote())
        .isEqualTo(quote);
    Seed second = seed(c);
    var later =
        Objects.requireNonNull(
            c.tx()
                .execute(
                    s ->
                        changedRepository.retainPending(
                            request(second, UUID.randomUUID(), 1, null))));
    assertThat(later.planQuote()).isEqualTo(changedCatalog.resolve("basic-hosting"));
    assertThat(later.planQuote().digest()).isNotEqualTo(created.planQuote().digest());
    assertThat(created.requestDigest())
        .isEqualTo(
            AccountSubscriptionCreateDigest.encode(
                    first.tenant(),
                    first.owner().accountUuid(),
                    request.planCode(),
                    request.paymentInstrumentId())
                .digest());
    var conflict =
        new AccountSubscriptionCreateRequest(
            request.requestId(),
            request.tenantId(),
            request.owner(),
            "pro-hosting",
            request.paymentInstrumentId(),
            request.instrumentVersion(),
            request.producer(),
            null);
    assertThatThrownBy(() -> c.tx().execute(s -> changedRepository.retainPending(conflict)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThat(count(c, "subscription_create_operations")).isEqualTo(2);
    assertThat(count(c, "subscription_instrument_reservations")).isEqualTo(2);
    assertThat(count(c, "subscription_create_outbox")).isEqualTo(2);
    assertThat(count(c, "subscription_provider_history")).isZero();
    assertThat(changedRepository.findCreation(request.requestId())).contains(created);
  }

  @Test
  void missingServerPlanConfigurationCreatesNoIntentReservationOrWorkItem() {
    Context c = context();
    Seed seed = seed(c);
    var unconfigured =
        new AccountSubscriptionIntentRepository(c.dsl(), new AccountHostingPlanCatalog(List.of()));
    assertThatThrownBy(
            () ->
                c.tx()
                    .execute(
                        s -> unconfigured.retainPending(request(seed, UUID.randomUUID(), 1, null))))
        .isInstanceOf(AccountHostingPlanCatalog.PlanUnavailableException.class);
    assertThat(count(c, "subscription_create_operations")).isZero();
    assertThat(count(c, "subscription")).isZero();
    assertThat(count(c, "subscription_instrument_reservations")).isZero();
    assertThat(count(c, "subscription_create_outbox")).isZero();
  }

  @Test
  void directSqlQuoteBytesDigestAndProjectedFieldsMustAgreeAndRetainedQuoteCannotBeRewritten() {
    Context c = context();
    Seed seed = seed(c);
    var request = request(seed, UUID.randomUUID(), 1, null);
    var quote = configuredPlans().resolve(request.planCode());
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        insertBareOperationWithQuote(
                            c,
                            request,
                            seed,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            quote,
                            quote.payload(),
                            "sha256:" + "0".repeat(64))));
    for (var changedFields :
        List.of(
            new AccountHostingPlanQuote(
                quote.planCode(), "price_substituted", quote.providerQuantity(), 100, 2, 1048576),
            new AccountHostingPlanQuote(
                quote.planCode(), quote.providerPriceId(), 2, 100, 2, 1048576),
            new AccountHostingPlanQuote(
                quote.planCode(),
                quote.providerPriceId(),
                quote.providerQuantity(),
                101,
                2,
                1048576),
            new AccountHostingPlanQuote(
                quote.planCode(),
                quote.providerPriceId(),
                quote.providerQuantity(),
                100,
                3,
                1048576),
            new AccountHostingPlanQuote(
                quote.planCode(),
                quote.providerPriceId(),
                quote.providerQuantity(),
                100,
                2,
                1048577))) {
      rejectSql(
          "23514",
          () ->
              c.tx()
                  .executeWithoutResult(
                      s ->
                          insertBareOperationWithQuote(
                              c,
                              request,
                              seed,
                              UUID.randomUUID(),
                              UUID.randomUUID(),
                              changedFields,
                              quote.payload(),
                              quote.digest())));
    }
    byte[] alteredBytes = Arrays.copyOf(quote.payload(), quote.payload().length + 1);
    alteredBytes[alteredBytes.length - 1] = ' ';
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        insertBareOperationWithQuote(
                            c,
                            request,
                            seed,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            quote,
                            alteredBytes,
                            quote.digest())));
    assertThat(count(c, "subscription_create_operations")).isZero();
    var created = create(c, request);
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription_create_operations SET provider_quantity = 2 WHERE request_id = ?",
                    request.requestId()));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET plan_quote_digest = ?, row_version = row_version + 1 WHERE subscription_id = ?",
                    "sha256:" + "f".repeat(64),
                    created.subscriptionId()));
    assertThat(c.repository().findCreation(request.requestId())).contains(created);
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().planQuote())
        .isEqualTo(quote);
  }

  @Test
  void concurrentEmptyTenantCreatesHaveOneWinnerAndConcurrentExactRetriesReplayOneResult()
      throws Exception {
    Context c = context();
    Seed seed = seed(c);
    var request = request(seed, UUID.randomUUID(), 1, null);
    List<AccountSubscriptionCreationRecord> retries =
        race(() -> create(c, request), () -> create(c, request));
    assertThat(retries.getLast()).isEqualTo(retries.getFirst());
    assertThat(count(c, "subscription")).isOne();
    Seed empty = seed(c);
    var left = request(empty, UUID.randomUUID(), 1, null);
    var right = request(empty, UUID.randomUUID(), 1, null);
    assertThat(race(() -> attemptCreate(c, left), () -> attemptCreate(c, right)))
        .containsExactlyInAnyOrder("APPLIED", "CONFLICT");
    assertThat(
            c.setup()
                .fetch(
                    "SELECT * FROM subscription WHERE tenant_id = ? AND superseded_at IS NULL",
                    empty.tenant()))
        .hasSize(1);
    assertThat(count(c, "subscription_create_outbox")).isEqualTo(2);
  }

  @Test
  void unauditedOwnerAdvanceIsRefusedAndStaleOwnerCapturesCannotCreateIntent() {
    Context c = context();
    Seed seed = seed(c);
    var before =
        c.setup()
            .fetch(
                "SELECT row_to_json(o)::text AS value, xmin::text AS xmin FROM account_tenant_billing_owners o WHERE tenant_uuid = ?",
                seed.tenant())
            .intoMaps();
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    status ->
                        c.dsl()
                            .execute(
                                "UPDATE account_tenant_billing_owners SET owner_version = owner_version + 1, owner_generation = ? WHERE tenant_uuid = ?",
                                UUID.randomUUID(),
                                seed.tenant())));
    assertThat(
            c.setup()
                .fetch(
                    "SELECT row_to_json(o)::text AS value, xmin::text AS xmin FROM account_tenant_billing_owners o WHERE tenant_uuid = ?",
                    seed.tenant())
                .intoMaps())
        .isEqualTo(before);
    // These are stale comparison inputs, not a fabricated committed transfer or authority grant.
    for (var stale :
        List.of(
            new OwnerFence(
                seed.owner().accountUuid(), seed.owner().version() + 1, seed.owner().generation()),
            new OwnerFence(
                seed.owner().accountUuid(), seed.owner().version(), UUID.randomUUID()))) {
      var captured = new Seed(seed.tenant(), stale, seed.instrument());
      assertThat(attemptCreate(c, request(captured, UUID.randomUUID(), 1, null)))
          .isEqualTo("FENCED");
    }
    assertThat(count(c, "subscription_create_operations")).isZero();
    assertThat(count(c, "subscription_create_outbox")).isZero();
    assertThat(count(c, "subscription_instrument_reservations")).isZero();
  }

  @Test
  void instrumentAdvanceThatCommitsFirstFencesQueuedCreateWithoutPartialIntent() throws Exception {
    Context walletContext = context();
    Seed walletSeed = seed(walletContext);
    var walletRequest = request(walletSeed, UUID.randomUUID(), 1, null);
    advanceBeforeCreate(
        walletContext,
        walletRequest,
        () ->
            new AccountPaymentInstrumentRepository(walletContext.dsl())
                .recordAvailability(
                    new AccountPaymentInstrumentRepository.AvailabilityObservation(
                        UUID.randomUUID(),
                        walletSeed.instrument().instrumentUuid(),
                        walletContext.account(),
                        1,
                        AccountPaymentInstrumentRecord.State.UNAVAILABLE,
                        walletSeed.instrument().display(),
                        DIGEST)));
    assertThat(count(walletContext, "subscription_create_operations")).isZero();
    assertThat(count(walletContext, "subscription_create_outbox")).isZero();
    assertThat(count(walletContext, "subscription_instrument_reservations")).isZero();
  }

  @Test
  void commitRechecksSameTransactionOwnerAndWalletChangesAndClaimReadRejectsStaleReservation() {
    Context c = context();
    Seed seed = seed(c);
    var request = request(seed, UUID.randomUUID(), 1, null);
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s -> {
                      c.repository().retainPending(request);
                      c.dsl()
                          .execute(
                              "UPDATE account_tenant_billing_owners SET owner_version = owner_version + 1, owner_generation = ? WHERE tenant_uuid = ?",
                              UUID.randomUUID(),
                              seed.tenant());
                    }));
    assertThat(count(c, "subscription")).isZero();
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s -> {
                      c.repository().retainPending(request);
                      advanceWallet(c, seed.instrument().instrumentUuid(), UUID.randomUUID());
                    }));
    assertThat(count(c, "subscription_create_operations")).isZero();
    create(c, request);
    c.tx()
        .executeWithoutResult(
            s -> advanceWallet(c, seed.instrument().instrumentUuid(), UUID.randomUUID()));
    assertThatThrownBy(
            () -> c.tx().execute(s -> c.repository().lockPendingForClaim(request.requestId(), 1)))
        .isInstanceOf(FenceRejectedException.class);
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().status())
        .isEqualTo("pending");
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT released_at FROM subscription_instrument_reservations WHERE request_id = ?",
                            request.requestId()),
                    "Expected retained reservation")
                .get(0))
        .isNull();
  }

  @Test
  void canceledCurrentReplacementIsAtomicVersionedDistinctAndOneConcurrentReplacementWins()
      throws Exception {
    Context c = context();
    Seed seed = seed(c);
    var originalRequest = request(seed, UUID.randomUUID(), 1, null);
    var original = create(c, originalRequest);
    controlledTerminalCleanup(c, original);
    var canceled = c.repository().findCurrentByTenant(seed.tenant()).orElseThrow();
    var replacement =
        request(
            seed,
            UUID.randomUUID(),
            1,
            new ReplacementFence(original.subscriptionId(), canceled.rowVersion()));
    c.tx()
        .executeWithoutResult(
            s -> {
              c.repository().retainPending(replacement);
              s.setRollbackOnly();
            });
    assertThat(c.repository().findCurrentByTenant(seed.tenant())).contains(canceled);
    assertThat(count(c, "subscription")).isOne();
    assertThat(count(c, "subscription_current_observations")).isEqualTo(2);
    var stale =
        request(seed, UUID.randomUUID(), 1, new ReplacementFence(original.subscriptionId(), 1));
    assertThatThrownBy(() -> create(c, stale)).isInstanceOf(FenceRejectedException.class);
    var competing =
        request(
            seed,
            UUID.randomUUID(),
            1,
            new ReplacementFence(original.subscriptionId(), canceled.rowVersion()));
    assertThat(race(() -> attemptCreate(c, replacement), () -> attemptCreate(c, competing)))
        .containsExactlyInAnyOrder("APPLIED", "FENCED");
    var current = c.repository().findCurrentByTenant(seed.tenant()).orElseThrow();
    assertThat(current.subscriptionId()).isNotEqualTo(original.subscriptionId());
    var successor = c.repository().findCreation(current.creationRequestId()).orElseThrow();
    assertThat(successor.subscriptionProvisioningRequestId())
        .isNotEqualTo(original.subscriptionProvisioningRequestId());
    assertThat(successor.cleanupProviderIdentity())
        .isNotIn(original.subscriptionProvisioningRequestId(), original.cleanupProviderIdentity());
    var retained =
        Objects.requireNonNull(
            c.setup()
                .fetchOne(
                    "SELECT * FROM subscription WHERE subscription_id = ?",
                    original.subscriptionId()));
    assertThat(retained.get("superseded_at")).isNotNull();
    assertThat(retained.get("superseded_by_request_id", UUID.class))
        .isEqualTo(current.creationRequestId());
    assertThat(retained.get("row_version", Long.class)).isEqualTo(canceled.rowVersion() + 1);
    assertThat(create(c, originalRequest)).isEqualTo(original);
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET row_version = row_version + 1 WHERE subscription_id = ?",
                    original.subscriptionId()));
  }

  @Test
  void directSqlRejectsIdentityMutationDuplicateCurrentProviderAndUnconfirmedActiveState() {
    Context c = context();
    Seed first = seed(c);
    Seed second = seed(c);
    var one = create(c, request(first, UUID.randomUUID(), 1, null));
    var two = create(c, request(second, UUID.randomUUID(), 1, null));
    for (String assignment :
        List.of(
            "subscription_id = '11111111-1111-4111-8111-111111111111'::uuid",
            "tenant_id = '22222222-2222-4222-8222-222222222222'::uuid",
            "plan_code = 'other-plan'",
            "account_uuid = '33333333-3333-4333-8333-333333333333'::uuid",
            "payment_instrument_id = '44444444-4444-4444-8444-444444444444'::uuid")) {
      rejectSql(
          "23514",
          () ->
              c.setup()
                  .execute(
                      "UPDATE subscription SET "
                          + assignment
                          + ", row_version = row_version + 1 WHERE subscription_id = ?",
                      one.subscriptionId()));
    }
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET status = 'active', row_version = row_version + 1 WHERE subscription_id = ?",
                    one.subscriptionId()));
    rejectSql(
        "23505",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        c.dsl()
                            .execute(
                                "UPDATE subscription SET provider_subscription_id = 'sub_duplicate', row_version = row_version + 1 WHERE subscription_id IN (?, ?)",
                                one.subscriptionId(),
                                two.subscriptionId())));
    rejectSql(
        "23503",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        c.dsl()
                            .execute(
                                "UPDATE subscription SET provider_subscription_id = 'sub_unmapped', row_version = row_version + 1 WHERE subscription_id = ?",
                                one.subscriptionId())));
    var duplicateTenant = request(first, UUID.randomUUID(), 1, null);
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        insertBareOperation(
                            c, duplicateTenant, first, UUID.randomUUID(), UUID.randomUUID())));
    assertThat(indexDefinition(c, "uq_subscription_active_tenant"))
        .contains("UNIQUE", "superseded_at IS NULL");
    assertThat(
            c.repository()
                .findCurrentByTenant(first.tenant())
                .orElseThrow()
                .providerSubscriptionId())
        .isNull();
    assertThat(
            c.repository()
                .findCurrentByTenant(second.tenant())
                .orElseThrow()
                .providerSubscriptionId())
        .isNull();
  }

  @Test
  void
      operationSubscriptionReservationAndOutboxCannotCommitPartiallyAndCrossRoleKeysCannotBeReused() {
    Context c = context();
    Seed first = seed(c);
    var created = create(c, request(first, UUID.randomUUID(), 1, null));
    Seed empty = seed(c);
    var missingPair = request(empty, UUID.randomUUID(), 1, null);
    rejectSql(
        "23503",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        insertBareOperation(
                            c, missingPair, empty, UUID.randomUUID(), UUID.randomUUID())));
    assertThat(c.repository().findCreation(missingPair.requestId())).isEmpty();
    rejectSql(
        "23505",
        () ->
            c.tx()
                .executeWithoutResult(
                    s ->
                        insertBareOperation(
                            c,
                            request(empty, UUID.randomUUID(), 1, null),
                            empty,
                            created.cleanupProviderIdentity(),
                            UUID.randomUUID())));
    for (String table :
        List.of(
            "subscription_create_operations",
            "subscription_create_outbox",
            "subscription_create_provider_identities")) {
      rejectSql("23514", () -> c.setup().execute("DELETE FROM " + table));
      rejectSql("23514", () -> c.setup().execute("TRUNCATE " + table + " CASCADE"));
    }
    rejectSql("23514", () -> c.setup().execute("DELETE FROM subscription"));
    rejectSql("23514", () -> c.setup().execute("DELETE FROM subscription_instrument_reservations"));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription_create_operations SET request_digest = request_digest"));
    assertThat(count(c, "subscription_create_operations")).isOne();
    assertThat(count(c, "subscription_create_outbox")).isOne();
  }

  @Test
  void
      canceledCommitRequiresSeparateTerminalEvidenceAndExactInstrumentReleaseInTheSameTransaction() {
    Context c = context();
    Seed seed = seed(c);
    var creation = create(c, request(seed, UUID.randomUUID(), 1, null));
    rejectSql(
        "23514",
        () ->
            c.tx()
                .executeWithoutResult(
                    s -> {
                      c.repository().lockPendingForClaim(creation.requestId(), 1);
                      UUID resolution = UUID.randomUUID();
                      c.dsl()
                          .execute(
                              "INSERT INTO subscription_provider_terminal_resolutions "
                                  + "(resolution_id,request_id,subscription_id,cleanup_provider_identity,outcome,effective_at,evidence_digest) "
                                  + "VALUES (?, ?, ?, ?, 'PROVIDER_ABSENT', ?::timestamptz, ?)",
                              resolution,
                              creation.requestId(),
                              creation.subscriptionId(),
                              creation.cleanupProviderIdentity(),
                              BOUNDARY,
                              DIGEST);
                      c.dsl()
                          .execute(
                              "UPDATE subscription SET status = 'canceled', terminal_resolution_id = ?, row_version = row_version + 1 WHERE subscription_id = ?",
                              resolution,
                              creation.subscriptionId());
                    }));
    assertThat(c.repository().findCurrentByTenant(seed.tenant()).orElseThrow().status())
        .isEqualTo("pending");
    assertThat(count(c, "subscription_provider_terminal_resolutions")).isZero();
    controlledTerminalCleanup(c, creation);
    rejectSql("23514", () -> controlledProviderHistory(c, creation, "sub_afterTerminalAbsence"));
    assertThat(c.repository().lookupProviderId("sub_afterTerminalAbsence").disposition())
        .isEqualTo(MappingDisposition.UNRESOLVED);
  }

  @Test
  void terminalCleanupAfterWalletAdvanceReleasesOnlyItsOriginalReservationWithoutRewritingWallet() {
    Context c = context();
    Seed first = seed(c);
    var originalRequest = request(first, UUID.randomUUID(), 1, null);
    var original = create(c, originalRequest);
    var instruments = new AccountPaymentInstrumentRepository(c.dsl());
    var advancedWallet =
        Objects.requireNonNull(
            c.tx()
                .execute(
                    s ->
                        instruments.recordAvailability(
                            new AccountPaymentInstrumentRepository.AvailabilityObservation(
                                UUID.randomUUID(),
                                first.instrument().instrumentUuid(),
                                c.account(),
                                1,
                                AccountPaymentInstrumentRecord.State.ATTACHABLE,
                                first.instrument().display(),
                                "sha256:" + "b".repeat(64)))));
    assertThat(advancedWallet.rowVersion()).isEqualTo(2);
    Seed otherTenant = seed(c);
    Seed sharedWallet = new Seed(otherTenant.tenant(), otherTenant.owner(), first.instrument());
    var independent = create(c, request(sharedWallet, UUID.randomUUID(), 2, null));
    assertThat(independent.paymentInstrumentId()).isEqualTo(original.paymentInstrumentId());
    var independentHold =
        Objects.requireNonNull(
                c.setup()
                    .fetchOne(
                        "SELECT * FROM subscription_instrument_reservations WHERE request_id = ?",
                        independent.requestId()))
            .intoMap();
    var walletHistory =
        c.setup()
            .fetch(
                "SELECT * FROM account_payment_instrument_observations WHERE instrument_uuid = ? ORDER BY row_version",
                original.paymentInstrumentId())
            .intoMaps();

    // Controlled provider absence is the cleanup evidence, not a fresh provisioning claim or a
    // rewrite of wallet currentness. The original reservation still records observed version 1.
    UUID terminalResolution = controlledTerminalCleanup(c, original);
    var releasedHold =
        Objects.requireNonNull(
                c.setup()
                    .fetchOne(
                        "SELECT * FROM subscription_instrument_reservations WHERE request_id = ?",
                        original.requestId()))
            .intoMap();
    assertThat(releasedHold.get("instrument_version"))
        .isEqualTo(original.reservedInstrumentVersion());
    assertThat(releasedHold.get("terminal_resolution_id")).isEqualTo(terminalResolution);
    assertThat(releasedHold.get("released_at")).isNotNull();
    var terminalReceipt =
        Objects.requireNonNull(
                c.setup()
                    .fetchOne(
                        "SELECT * FROM subscription_provider_terminal_resolutions WHERE resolution_id = ?",
                        terminalResolution))
            .intoMap();
    assertThat(controlledTerminalCleanup(c, original)).isEqualTo(terminalResolution);
    assertThat(create(c, originalRequest)).isEqualTo(original);

    // An old request/subscription tuple or the originally observed version cannot select the
    // independent version-2 reservation. Nor may a stale caller rewrite its immutable identity.
    c.tx()
        .executeWithoutResult(
            s -> {
              assertThat(
                      c.dsl()
                          .execute(
                              "UPDATE subscription_instrument_reservations SET terminal_resolution_id = ?, released_at = clock_timestamp() "
                                  + "WHERE request_id = ? AND subscription_id = ? AND payment_instrument_id = ? AND instrument_version = ? AND released_at IS NULL",
                              terminalResolution,
                              independent.requestId(),
                              independent.subscriptionId(),
                              original.paymentInstrumentId(),
                              original.reservedInstrumentVersion()))
                  .isZero();
              assertThat(
                      c.dsl()
                          .execute(
                              "UPDATE subscription_instrument_reservations SET terminal_resolution_id = ?, released_at = clock_timestamp() "
                                  + "WHERE request_id = ? AND subscription_id = ? AND payment_instrument_id = ? AND instrument_version = ? AND released_at IS NULL",
                              terminalResolution,
                              original.requestId(),
                              independent.subscriptionId(),
                              original.paymentInstrumentId(),
                              independent.reservedInstrumentVersion()))
                  .isZero();
            });
    for (String assignment :
        List.of(
            "request_id = '" + original.requestId() + "'::uuid",
            "subscription_id = '" + original.subscriptionId() + "'::uuid",
            "instrument_version = " + original.reservedInstrumentVersion())) {
      rejectSql(
          "23514",
          () ->
              c.setup()
                  .execute(
                      "UPDATE subscription_instrument_reservations SET "
                          + assignment
                          + ", terminal_resolution_id = ?, released_at = clock_timestamp() WHERE request_id = ?",
                      terminalResolution,
                      independent.requestId()));
    }
    rejectSql(
        "23505",
        () ->
            c.tx()
                .executeWithoutResult(
                    s -> {
                      c.dsl()
                          .execute(
                              "UPDATE subscription_instrument_reservations SET terminal_resolution_id = ?, released_at = clock_timestamp() WHERE request_id = ?",
                              terminalResolution,
                              independent.requestId());
                    }));
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT * FROM subscription_instrument_reservations WHERE request_id = ?",
                            independent.requestId()))
                .intoMap())
        .isEqualTo(independentHold);
    assertThat(c.repository().findCurrentByTenant(otherTenant.tenant()).orElseThrow().status())
        .isEqualTo("pending");
    assertThat(
            Objects.requireNonNull(
                    c.tx()
                        .execute(
                            s -> c.repository().lockPendingForClaim(independent.requestId(), 1)))
                .subscriptionId())
        .isEqualTo(independent.subscriptionId());
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT * FROM subscription_instrument_reservations WHERE request_id = ?",
                            original.requestId()))
                .intoMap())
        .isEqualTo(releasedHold);
    assertThat(
            Objects.requireNonNull(
                    c.setup()
                        .fetchOne(
                            "SELECT * FROM subscription_provider_terminal_resolutions WHERE resolution_id = ?",
                            terminalResolution))
                .intoMap())
        .isEqualTo(terminalReceipt);
    assertThat(instruments.findByOwner(c.account(), original.paymentInstrumentId()))
        .contains(advancedWallet);
    assertThat(
            c.setup()
                .fetch(
                    "SELECT * FROM account_payment_instrument_observations WHERE instrument_uuid = ? ORDER BY row_version",
                    original.paymentInstrumentId())
                .intoMaps())
        .isEqualTo(walletHistory);
  }

  @Test
  void
      providerHistoryAndTerminalEvidenceAreSeparateImmutableAndUnknownIdsNeverChooseCurrentAuthority() {
    Context c = context();
    Seed seed = seed(c);
    var creation = create(c, request(seed, UUID.randomUUID(), 1, null));
    assertThat(c.repository().lookupProviderId("sub_unknown").disposition())
        .isEqualTo(MappingDisposition.UNRESOLVED);
    assertThat(count(c, "subscription_provider_history")).isZero();
    controlledProviderHistory(c, creation, "sub_original");
    assertThat(c.repository().lookupProviderId("sub_original").disposition())
        .isEqualTo(MappingDisposition.HISTORY_ONLY);
    assertThat(
            c.repository()
                .findCurrentByTenant(seed.tenant())
                .orElseThrow()
                .providerSubscriptionId())
        .isNull();
    c.tx()
        .executeWithoutResult(
            s ->
                c.dsl()
                    .execute(
                        "UPDATE subscription SET provider_subscription_id = 'sub_original', row_version = row_version + 1 WHERE subscription_id = ?",
                        creation.subscriptionId()));
    var mapped = c.repository().lookupProviderId("sub_original");
    assertThat(mapped.disposition()).isEqualTo(MappingDisposition.CURRENT_MAPPING);
    assertThat(mapped.history().orElseThrow().providerOperationId())
        .isEqualTo(creation.subscriptionProvisioningRequestId());
    assertThat(mapped.terminalResolution()).isEmpty();
    assertThat(c.repository().lookupProviderId("sub_predecessorNotRecorded").disposition())
        .isEqualTo(MappingDisposition.UNRESOLVED);
    rejectSql("23505", () -> controlledProviderHistory(c, creation, "sub_conflicting"));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription SET provider_subscription_id = 'sub_guessed', row_version = row_version + 1 WHERE subscription_id = ?",
                    creation.subscriptionId()));
    controlledTerminalCleanup(c, creation);
    var terminal = c.repository().lookupProviderId("sub_original");
    assertThat(terminal.disposition()).isEqualTo(MappingDisposition.TERMINAL_HISTORY);
    assertThat(terminal.terminalResolution().orElseThrow().cleanupProviderIdentity())
        .isEqualTo(creation.cleanupProviderIdentity());
    var canceled = c.repository().findCurrentByTenant(seed.tenant()).orElseThrow();
    create(
        c,
        request(
            seed,
            UUID.randomUUID(),
            1,
            new ReplacementFence(creation.subscriptionId(), canceled.rowVersion())));
    var current = c.repository().findCurrentByTenant(seed.tenant()).orElseThrow();
    assertThat(current.providerSubscriptionId()).isNull();
    assertThat(
            c.repository()
                .lookupProviderId("sub_original")
                .history()
                .orElseThrow()
                .subscriptionId())
        .isEqualTo(creation.subscriptionId());
    assertThat(c.repository().findCurrentByTenant(seed.tenant())).contains(current);
    for (String table :
        List.of(
            "subscription_provider_history",
            "subscription_provider_terminal_resolutions",
            "subscription_current_observations")) {
      rejectSql("23514", () -> c.setup().execute("DELETE FROM " + table));
      rejectSql("23514", () -> c.setup().execute("TRUNCATE " + table + " CASCADE"));
    }
    rejectSql(
        "23514", () -> c.setup().execute("UPDATE subscription_provider_history SET role = role"));
    rejectSql(
        "23514",
        () ->
            c.setup()
                .execute(
                    "UPDATE subscription_provider_terminal_resolutions SET outcome = outcome"));
    rejectSql(
        "23503",
        () -> c.setup().execute("DELETE FROM accounts WHERE account_uuid = ?", c.account()));
  }

  private static void advanceBeforeCreate(
      Context c, AccountSubscriptionCreateRequest request, Runnable controlledAdvance)
      throws Exception {
    CountDownLatch advanced = new CountDownLatch(1);
    CountDownLatch contenderStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var holderPid = new AtomicInteger();
    var contenderPid = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    try {
      var holder =
          executor.submit(
              () ->
                  c.tx()
                      .executeWithoutResult(
                          s -> {
                            holderPid.set(
                                Objects.requireNonNull(c.dsl().fetchOne("SELECT pg_backend_pid()"))
                                    .get(0, Integer.class));
                            c.dsl()
                                .fetchOne(
                                    "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE",
                                    c.account());
                            controlledAdvance.run();
                            advanced.countDown();
                            await(release);
                          }));
      if (!advanced.await(5, TimeUnit.SECONDS)) {
        // Expose a failed owner transaction instead of masking it as a coordination timeout.
        holder.get(1, TimeUnit.SECONDS);
        throw new AssertionError("Controlled owner advance did not reach its lock boundary");
      }
      var contender =
          executor.submit(
              () -> {
                // Classify rejections only after the original create transaction has rolled back.
                try {
                  c.tx()
                      .executeWithoutResult(
                          s -> {
                            contenderPid.set(
                                Objects.requireNonNull(c.dsl().fetchOne("SELECT pg_backend_pid()"))
                                    .get(0, Integer.class));
                            contenderStarted.countDown();
                            c.repository().retainPending(request);
                          });
                  return "APPLIED";
                } catch (ConflictException conflict) {
                  return "CONFLICT";
                } catch (FenceRejectedException stale) {
                  return "FENCED";
                }
              });
      assertThat(contenderStarted.await(5, TimeUnit.SECONDS)).isTrue();
      awaitBlocked(c, contenderPid.get(), holderPid.get());
      release.countDown();
      holder.get(15, TimeUnit.SECONDS);
      assertThat(contender.get(15, TimeUnit.SECONDS)).isEqualTo("FENCED");
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private static void awaitBlocked(Context c, int waitingPid, int blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      var observed =
          Objects.requireNonNull(
              c.setup()
                  .fetchOne(
                      "SELECT ? = ANY(pg_blocking_pids(?)) AS blocked", blockerPid, waitingPid),
              "Exact PostgreSQL blocking identity readback required");
      if (Boolean.TRUE.equals(observed.get("blocked", Boolean.class))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException(
        "Subscription create did not block on the controlled Account owner transaction");
  }

  /** Controlled qualified-result shape only; no Stripe or lifecycle producer is claimed. */
  private static void controlledProviderHistory(
      Context c, AccountSubscriptionCreationRecord creation, String providerId) {
    c.tx()
        .executeWithoutResult(
            s ->
                c.dsl()
                    .execute(
                        "INSERT INTO subscription_provider_history "
                            + "(provider_subscription_id,subscription_id,operation_kind,request_id,provider_operation_id,role,effective_at,evidence_digest) "
                            + "VALUES (?, ?, 'SUBSCRIPTION_PROVISIONING', ?, ?, 'canonical', ?::timestamptz, ?)",
                        providerId,
                        creation.subscriptionId(),
                        creation.requestId(),
                        creation.subscriptionProvisioningRequestId(),
                        BOUNDARY,
                        DIGEST));
  }

  /**
   * Controlled SQL cleanup tuple; does not prove provider verification or a lifecycle outbox
   * bridge.
   */
  private static UUID controlledTerminalCleanup(
      Context c, AccountSubscriptionCreationRecord creation) {
    return Objects.requireNonNull(
        c.tx()
            .execute(
                s -> {
                  // Cleanup is not a new provisioning claim. Lock the same sources without
                  // requiring
                  // the globally shared wallet to retain the original observed
                  // version/availability.
                  new AccountPaymentInstrumentRepository(c.dsl())
                      .findByOwnerForUpdate(
                          creation.owner().accountUuid(), creation.paymentInstrumentId())
                      .orElseThrow();
                  c.dsl()
                      .fetchOne(
                          "SELECT * FROM account_tenant_billing_owners WHERE tenant_uuid = ? FOR UPDATE",
                          creation.tenantId());
                  var current =
                      Objects.requireNonNull(
                          c.dsl()
                              .fetchOne(
                                  "SELECT * FROM subscription WHERE subscription_id = ? AND tenant_id = ? AND creation_request_id = ? FOR UPDATE",
                                  creation.subscriptionId(),
                                  creation.tenantId(),
                                  creation.requestId()));
                  var hold =
                      Objects.requireNonNull(
                          c.dsl()
                              .fetchOne(
                                  "SELECT * FROM subscription_instrument_reservations WHERE request_id = ? AND subscription_id = ? AND payment_instrument_id = ? AND instrument_version = ? FOR UPDATE",
                                  creation.requestId(),
                                  creation.subscriptionId(),
                                  creation.paymentInstrumentId(),
                                  creation.reservedInstrumentVersion()));
                  if (hold.get("released_at") != null) {
                    UUID retainedResolution = hold.get("terminal_resolution_id", UUID.class);
                    assertThat(current.get("status", String.class)).isEqualTo("canceled");
                    assertThat(current.get("terminal_resolution_id", UUID.class))
                        .isEqualTo(retainedResolution);
                    var receipt =
                        Objects.requireNonNull(
                            c.dsl()
                                .fetchOne(
                                    "SELECT * FROM subscription_provider_terminal_resolutions WHERE resolution_id = ? AND request_id = ? AND subscription_id = ? AND cleanup_provider_identity = ?",
                                    retainedResolution,
                                    creation.requestId(),
                                    creation.subscriptionId(),
                                    creation.cleanupProviderIdentity()));
                    assertThat(receipt.get("provider_subscription_id", String.class))
                        .isEqualTo(current.get("provider_subscription_id", String.class));
                    return retainedResolution;
                  }
                  UUID resolution = UUID.randomUUID();
                  c.dsl()
                      .execute(
                          "INSERT INTO subscription_provider_terminal_resolutions "
                              + "(resolution_id,request_id,subscription_id,cleanup_provider_identity,provider_subscription_id,outcome,effective_at,evidence_digest) "
                              + "VALUES (?, ?, ?, ?, ?, ?, ?::timestamptz, ?)",
                          resolution,
                          creation.requestId(),
                          creation.subscriptionId(),
                          creation.cleanupProviderIdentity(),
                          current.get("provider_subscription_id", String.class),
                          current.get("provider_subscription_id") == null
                              ? "PROVIDER_ABSENT"
                              : "CANCELED",
                          BOUNDARY,
                          DIGEST);
                  assertThat(
                          c.dsl()
                              .execute(
                                  "UPDATE subscription SET status = 'canceled', terminal_resolution_id = ?, row_version = row_version + 1 WHERE subscription_id = ? AND creation_request_id = ? AND row_version = ? AND status = 'pending'",
                                  resolution,
                                  creation.subscriptionId(),
                                  creation.requestId(),
                                  current.get("row_version", Long.class)))
                      .isOne();
                  assertThat(
                          c.dsl()
                              .execute(
                                  "UPDATE subscription_instrument_reservations SET terminal_resolution_id = ?, released_at = clock_timestamp() WHERE request_id = ? AND subscription_id = ? AND payment_instrument_id = ? AND instrument_version = ? AND released_at IS NULL",
                                  resolution,
                                  creation.requestId(),
                                  creation.subscriptionId(),
                                  creation.paymentInstrumentId(),
                                  creation.reservedInstrumentVersion()))
                      .isOne();
                  return resolution;
                }));
  }

  private static void advanceWallet(Context c, UUID instrument, UUID changeRequest) {
    c.dsl()
        .execute(
            "UPDATE payment_instrument SET row_version = row_version + 1, change_request_id = ?, evidence_digest = ? WHERE instrument_uuid = ?",
            changeRequest,
            DIGEST,
            instrument);
  }

  private static void insertBareOperation(
      Context c,
      AccountSubscriptionCreateRequest request,
      Seed seed,
      UUID createKey,
      UUID cleanupKey) {
    var quote = configuredPlans().resolve(request.planCode());
    insertBareOperationWithQuote(
        c, request, seed, createKey, cleanupKey, quote, quote.payload(), quote.digest());
  }

  private static void insertBareOperationWithQuote(
      Context c,
      AccountSubscriptionCreateRequest request,
      Seed seed,
      UUID createKey,
      UUID cleanupKey,
      AccountHostingPlanQuote quote,
      byte[] quotePayload,
      String quoteDigest) {
    var encoded =
        AccountSubscriptionCreateDigest.encode(
            request.tenantId(),
            request.owner().accountUuid(),
            request.planCode(),
            request.paymentInstrumentId());
    c.dsl()
        .execute(
            "INSERT INTO subscription_create_operations (request_id,subscription_id,tenant_id,account_uuid,owner_version,owner_generation,actor_account_uuid,authorization_operation_id,authorization_digest,plan_code,payment_instrument_id,reserved_instrument_version,customer_provisioning_request_id,provider_customer_id,subscription_provisioning_request_id,cleanup_provider_identity,request_payload,request_digest,plan_quote_payload,plan_quote_digest,provider_price_id,provider_quantity,max_active_sessions,max_concurrent_game_instances,max_storage_bytes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            request.requestId(),
            UUID.randomUUID(),
            request.tenantId(),
            request.owner().accountUuid(),
            request.owner().version(),
            request.owner().generation(),
            c.account(),
            request.producer().authorizationOperationId(),
            request.producer().authorizationDigest(),
            request.planCode(),
            request.paymentInstrumentId(),
            request.instrumentVersion(),
            seed.instrument().customerProvisioningRequestId(),
            seed.instrument().providerCustomerId(),
            createKey,
            cleanupKey,
            encoded.payload(),
            encoded.digest(),
            quotePayload,
            quoteDigest,
            quote.providerPriceId(),
            quote.providerQuantity(),
            quote.maxActiveSessions(),
            quote.maxConcurrentGameInstances(),
            quote.maxStorageBytes());
  }

  private static AccountSubscriptionCreationRecord create(
      Context c, AccountSubscriptionCreateRequest request) {
    return Objects.requireNonNull(c.tx().execute(s -> c.repository().retainPending(request)));
  }

  private static String attemptCreate(Context c, AccountSubscriptionCreateRequest request) {
    try {
      create(c, request);
      return "APPLIED";
    } catch (ConflictException conflict) {
      return "CONFLICT";
    } catch (FenceRejectedException stale) {
      return "FENCED";
    }
  }

  private static AccountSubscriptionCreateRequest request(
      Seed seed, UUID request, long instrumentVersion, ReplacementFence replacement) {
    return new AccountSubscriptionCreateRequest(
        request,
        seed.tenant(),
        seed.owner(),
        "basic-hosting",
        seed.instrument().instrumentUuid(),
        instrumentVersion,
        producer(seed.owner().accountUuid()),
        replacement);
  }

  private static ProducerEvidence producer(UUID account) {
    return new ProducerEvidence(account, UUID.randomUUID(), DIGEST);
  }

  private static Seed seed(Context c) {
    UUID tenant = UUID.randomUUID();
    UUID creationRequest = UUID.randomUUID();
    UUID creationOperation = UUID.randomUUID();
    long gameRow = GAME_ROWS.incrementAndGet();
    String key = "game-" + gameRow;
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            "prod", creationRequest, key, "Controlled tenant", "Storage proof only");
    var creation =
        new FreshTenantCreationEvidence(
            1,
            "prod",
            creationRequest,
            creationOperation,
            requestDigest,
            tenant,
            gameRow,
            key,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "prod",
                creationRequest,
                creationOperation,
                requestDigest,
                tenant,
                gameRow,
                key,
                "NEW_GAME_ROW"));
    UUID authorityOperation = UUID.randomUUID();
    var creator =
        new FreshTenantCreatorEvidence(
            1,
            creation,
            c.account(),
            authorityOperation,
            DIGEST,
            FreshTenantCreatorDigest.evidenceDigest(
                1, creation, c.account(), authorityOperation, DIGEST));
    c.tx()
        .executeWithoutResult(
            s ->
                new FreshTenantIdentityAssociationRepository(c.dsl(), "prod")
                    .importVerified(creation));
    var ownerResult =
        Objects.requireNonNull(
            c.tx()
                .execute(
                    s ->
                        new AccountInitialTenantBillingOwnerRepository(c.dsl())
                            .establishInitial(
                                new AccountInitialTenantBillingOwnerRepository.InitialRequest(
                                    UUID.randomUUID(), creator, UUID.randomUUID(), 1, true))));
    var customers = new AccountBillingCustomerRepository(c.dsl());
    var customer =
        Objects.requireNonNull(
                c.tx()
                    .execute(
                        s ->
                            customers.retainIntent(
                                c.account(), UUID.randomUUID(), UUID.randomUUID())))
            .customer();
    if (customer.state() != AccountBillingCustomerRecord.State.CONFIRMED) {
      var pending = customer;
      customer =
          Objects.requireNonNull(
              c.tx()
                  .execute(
                      s ->
                          customers.recordObservation(
                              c.account(),
                              pending.customerProvisioningRequestId(),
                              pending.rowVersion(),
                              UUID.randomUUID(),
                              AccountBillingCustomerRecord.Observation.CONFIRMED,
                              "cus_" + c.account().toString().replace("-", ""),
                              DIGEST)));
    }
    var confirmed = customer;
    var instrument =
        Objects.requireNonNull(
            c.tx()
                .execute(
                    s ->
                        new AccountPaymentInstrumentRepository(c.dsl())
                            .retainAttachment(
                                new Attachment(
                                    UUID.randomUUID(),
                                    UUID.randomUUID(),
                                    c.account(),
                                    confirmed.customerProvisioningRequestId(),
                                    confirmed.providerCustomerId(),
                                    "pm_" + UUID.randomUUID().toString().replace("-", ""),
                                    AccountPaymentInstrumentRecord.State.ATTACHABLE,
                                    new DisplayMetadata("visa", "4242", 12, 2030),
                                    DIGEST))));
    return new Seed(
        tenant,
        new OwnerFence(c.account(), ownerResult.ownerVersion(), ownerResult.ownerGeneration()),
        instrument);
  }

  private static <T> List<T> race(Supplier<T> left, Supplier<T> right) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return left.get();
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                await(start);
                return right.get();
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Storage proof barrier timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Storage proof interrupted", interrupted);
    }
  }

  private static void rejectSql(String state, ThrowingCallable action) {
    assertThatThrownBy(action)
        .satisfies(
            failure -> {
              for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) {
                  assertThat(sql.getSQLState()).isEqualTo(state);
                  return;
                }
              }
              throw new AssertionError("Expected PostgreSQL invariant refusal", failure);
            });
  }

  private static long count(Context c, String table) {
    return Objects.requireNonNull(c.setup().fetchOne("SELECT count(*) FROM " + table))
        .get(0, Long.class);
  }

  private static String indexDefinition(Context c, String name) {
    return Objects.requireNonNull(
            c.setup()
                .fetchOne(
                    "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                    name))
        .get(0, String.class);
  }

  private static FluentConfiguration configuration(Context c) {
    return Flyway.configure()
        .dataSource(c.source())
        .schemas(c.schema())
        .defaultSchema(c.schema())
        .placeholders(Map.of("serviceSchema", c.schema()))
        .locations("classpath:db/migration", "classpath:db/migration/saga");
  }

  private static Context context() {
    Context c = predecessor();
    configuration(c).target("1029").load().migrate();
    return c;
  }

  private static Context predecessor() {
    String schema = "subscription_intent_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    DSLContext setup = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(10);
    var empty =
        new Context(
            source,
            schema,
            setup,
            dsl,
            tx,
            new AccountSubscriptionIntentRepository(dsl, configuredPlans()),
            null);
    configuration(empty).target("1026").load().migrate();
    var account =
        Objects.requireNonNull(
            setup.fetchOne(
                "INSERT INTO accounts(username,email,password_hash) VALUES ('subscription-owner','owner@example.test','hash') RETURNING id, account_uuid"));
    setup.execute(
        "INSERT INTO subscription(id,account_id,plan_id,status,started_at,ended_at,tenant_id,entitlement_version,tenant_authority_generation) VALUES "
            + "(91,?,'unqualified-active','active','2025-01-02 03:04:05.123456',NULL,7,19,?),"
            + "(92,?,'retained-canceled','canceled',NULL,'2025-02-03 04:05:06.654321',7,21,?)",
        account.get("id", Long.class),
        UUID.randomUUID(),
        account.get("id", Long.class),
        UUID.randomUUID());
    return new Context(
        source,
        schema,
        setup,
        dsl,
        tx,
        empty.repository(),
        account.get("account_uuid", UUID.class));
  }

  /** Controlled configuration only; not an installed live catalog or production price selection. */
  private static AccountHostingPlanCatalog configuredPlans() {
    return new AccountHostingPlanCatalog(
        List.of(
            new AccountHostingPlanCatalog.ConfiguredPlan(
                "basic-hosting", "price_controlledInitial", "1", "100", "2", "1048576")));
  }

  private record Seed(UUID tenant, OwnerFence owner, AccountPaymentInstrumentRecord instrument) {}

  private record Context(
      DriverManagerDataSource source,
      String schema,
      DSLContext setup,
      DSLContext dsl,
      TransactionTemplate tx,
      AccountSubscriptionIntentRepository repository,
      UUID account) {}
}
