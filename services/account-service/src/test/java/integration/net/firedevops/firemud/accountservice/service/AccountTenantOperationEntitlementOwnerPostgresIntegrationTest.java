package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.Outcome;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountTenantOperationEntitlementOwner.Request;
import net.firedevops.firemud.common.account.authority.RuntimeTenantOperationEntitlementEvidence;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real Account SQL/current-source readers and real Stripe SDK over controlled read-only transport.
 * Enrollment inputs and provider transport are controlled; no mTLS, registration, live Stripe,
 * player producer/consumer, durable evaluation replay, or capacity-commit proof is claimed.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountTenantOperationEntitlementOwnerPostgresIntegrationTest {
  private static final String WORLD = "spiffe://firemud/ns/prod/sa/world-management-service";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void reusedAccountFixtureKeepsConfirmedCustomerAndObservationHistoryUnchanged() {
    var first = context().fixture();
    Account existing = new Account();
    existing.setId(first.accountId());
    existing.setAccountUuid(first.accountUuid());
    var customerBefore =
        first
            .dsl()
            .fetch("SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM billing_customer t")
            .intoMaps();
    var observationsBefore =
        first
            .dsl()
            .fetch(
                "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM account_billing_customer_observations t")
            .intoMaps();
    assertThat(
            java.util.Objects.requireNonNull(
                    first.dsl().fetchOne("SELECT row_version FROM billing_customer"))
                .get(0, Long.class))
        .isGreaterThan(1L);

    var second =
        CanonicalHostingSubscriptionTestFixture.create(
            java.util.Objects.requireNonNull(first.jdbc().getDataSource()), existing);

    assertThat(second.accountUuid()).isEqualTo(first.accountUuid());
    assertThat(second.tenantId()).isNotEqualTo(first.tenantId());
    assertThat(second.creation().providerCustomerId())
        .isEqualTo(first.creation().providerCustomerId());
    assertThat(
            first
                .dsl()
                .fetch(
                    "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM billing_customer t")
                .intoMaps())
        .isEqualTo(customerBefore);
    assertThat(
            first
                .dsl()
                .fetch(
                    "SELECT row_to_json(t)::text AS row, xmin::text AS xmin FROM account_billing_customer_observations t")
                .intoMaps())
        .isEqualTo(observationsBefore);
  }

  @Test
  void paidUsesActualReceiptSdkAndAuthorityCheckpointWithFreshEvaluationOfSameSnapshot() {
    Context c = context();
    var f = c.fixture();
    var claim =
        f.service().claimProvisioning(f.creation().requestId(), 1, UUID.randomUUID()).claim();
    var committed =
        f.service()
            .reconcile(
                UUID.randomUUID(),
                f.creation().requestId(),
                claim.subscriptionRowVersion(),
                f.providerId(),
                claim.attemptId());
    assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
    int beforeReads = f.transport().reads();
    UUID requestId = UUID.randomUUID();
    var first = c.owner().evaluatePaid(new Request(f.tenantId(), requestId, WORLD));
    f.clock().advance(java.time.Duration.ofSeconds(30));
    var second = c.owner().evaluatePaid(new Request(f.tenantId(), requestId, WORLD));
    assertThat(f.transport().reads()).isEqualTo(beforeReads + 2);
    assertThat(first.fields())
        .containsEntry("sourceKind", "PAID_HOSTING")
        .containsEntry("subscriptionStatus", "active")
        .containsEntry("provisioningStatus", null)
        .containsEntry("allowNewInstanceStarts", true)
        .containsEntry("callerWorkload", WORLD)
        .containsEntry("requestId", requestId.toString());
    assertThat(first.fields().get("snapshotIdentity"))
        .isEqualTo(second.fields().get("snapshotIdentity"));
    assertThat(first.fields().get("evaluationIdentity"))
        .isNotEqualTo(second.fields().get("evaluationIdentity"));
    assertThat(java.time.Instant.parse((String) second.fields().get("evaluatedAt")))
        .isAfter(java.time.Instant.parse((String) first.fields().get("evaluatedAt")));
    assertThat(first.fields())
        .doesNotContainKeys("callerAccountId", "issuer", "accountId", "verifiedConnectScopeId");
    assertThat(first.fields().get("quotas"))
        .isEqualTo(
            Map.of(
                "maxActiveSessions",
                "100",
                "maxConcurrentGameInstances",
                "2",
                "maxStorageBytes",
                "1048576"));
    assertActualTenantCheckpoint(c, first);
    assertThat(RuntimeTenantOperationEntitlementEvidence.parseCanonical(first.canonicalBytes()))
        .isEqualTo(first);

    f.providerStatus("canceled");
    assertThatThrownBy(
            () -> c.owner().evaluatePaid(new Request(f.tenantId(), UUID.randomUUID(), WORLD)))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
  }

  @Test
  void captureRunsInsideFinalWritableTransactionAndFailureRollsBackCapturedSql() {
    Context c = context();
    var f = c.fixture();
    var claim =
        f.service().claimProvisioning(f.creation().requestId(), 1, UUID.randomUUID()).claim();
    var committed =
        f.service()
            .reconcile(
                UUID.randomUUID(),
                f.creation().requestId(),
                claim.subscriptionRowVersion(),
                f.providerId(),
                claim.attemptId());
    assertThat(committed.outcome()).isEqualTo(Outcome.COMMITTED);
    String marker = "capture_marker_" + UUID.randomUUID().toString().replace("-", "");
    AtomicInteger captures = new AtomicInteger();
    assertThatThrownBy(
            () ->
                f.service()
                    .readCurrentAndCapture(
                        f.tenantId(),
                        receipt -> {
                          captures.incrementAndGet();
                          assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                              .isTrue();
                          assertThat(
                                  TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                              .isFalse();
                          f.dsl().execute("CREATE TABLE " + marker + " (id bigint)");
                          throw new IllegalStateException("controlled capture rollback");
                        }))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE")
        .hasCauseInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage("controlled capture rollback");
    assertThat(captures).hasValue(1);
    assertThat(f.dsl().fetchSingle("SELECT to_regclass(?)::text", marker).get(0, String.class))
        .isNull();
  }

  @Test
  void pendingDoesNotBecomePositiveAndExplicitDemoUsesOnlyItsActualCurrentOwner() {
    Context c = context();
    var f = c.fixture();
    assertThatThrownBy(
            () -> c.owner().evaluatePaid(new Request(f.tenantId(), UUID.randomUUID(), WORLD)))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
    assertThatThrownBy(
            () -> c.owner().evaluateDemo(new Request(f.tenantId(), UUID.randomUUID(), WORLD)))
        .isInstanceOf(RuntimeException.class);
    var tenantSources = new FreshTenantIdentityAssociationRepository(f.dsl(), "prod");
    var committed =
        f.transaction()
            .execute(
                status -> {
                  var source = tenantSources.read(f.tenantId()).orElseThrow();
                  return c.demos()
                      .provision(
                          new DemoTenantEntitlementRequest(
                              UUID.randomUUID(),
                              f.tenantId(),
                              source.creationRequestId(),
                              source.requestDigest(),
                              null,
                              null,
                              null,
                              true,
                              false,
                              true,
                              false,
                              new DemoTenantEntitlementRequest.Quotas(3, 1, 500)),
                          source);
                });
    assertThat(committed).isNotNull();
    int beforeReads = f.transport().reads();
    var evaluated = c.owner().evaluateDemo(new Request(f.tenantId(), UUID.randomUUID(), WORLD));
    assertThat(evaluated.fields())
        .containsEntry("sourceKind", "NON_PAID_DEMO")
        .containsEntry("subscriptionStatus", null)
        .containsEntry("provisioningStatus", null)
        .containsEntry("gameplayAvailable", true)
        .containsEntry("allowPublicJoin", false)
        .containsEntry("allowNewGameplayBindings", true)
        .containsEntry("allowNewInstanceStarts", false);
    assertActualTenantCheckpoint(c, evaluated);
    assertThat(f.transport().reads()).isEqualTo(beforeReads);
    assertThatThrownBy(
            () -> c.owner().evaluatePaid(new Request(f.tenantId(), UUID.randomUUID(), WORLD)))
        .hasMessageContaining("ENTITLEMENT_UNAVAILABLE");
  }

  @Test
  void wrongWorkloadNamespaceAndPlayerRequestsFailBeforeSourceReads() {
    Context c = context();
    var f = c.fixture();
    assertThatThrownBy(
            () ->
                c.owner()
                    .evaluatePaid(
                        new Request(
                            f.tenantId(),
                            UUID.randomUUID(),
                            "spiffe://firemud/ns/another/sa/world-management-service")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new Request(
                    f.tenantId(),
                    UUID.randomUUID(),
                    "spiffe://firemud/ns/prod/sa/game-session-service"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Request(new UUID(0L, 0L), UUID.randomUUID(), WORLD))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(f.transport().reads()).isZero();
  }

  @SuppressWarnings("unchecked")
  private static void assertActualTenantCheckpoint(
      Context c, RuntimeTenantOperationEntitlementEvidence value) {
    var expected =
        c.fixture()
            .transaction()
            .execute(
                status ->
                    new AccountAuthorityOutboxRepository(c.fixture().dsl())
                        .readCheckpoint(
                            TenantAuthorityEventV1Codec.streamKey(c.fixture().tenantId()))
                        .orElseThrow());
    assertThat(expected).isNotNull();
    var actual = (List<Map<String, Object>>) value.fields().get("outboxCheckpoints");
    assertThat(actual)
        .containsExactly(
            Map.of(
                "outboxStreamKey",
                expected.outboxStreamKey(),
                "outboxSequence",
                Long.toString(expected.outboxSequence()),
                "sourceEventId",
                expected.sourceEventId(),
                "sourceEventDigest",
                expected.sourceEventDigest()));
  }

  private static Context context() {
    String schema = "tenant_operation_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .load()
        .migrate();
    var f = CanonicalHostingSubscriptionTestFixture.create(source);
    var identities = new FreshTenantIdentityAssociationRepository(f.dsl(), "prod");
    var generations = new AccountAuthorityGenerationRepository(f.dsl());
    var billing = new AccountTenantEntitlementOutboxRepository(f.dsl());
    var tenantEvents =
        new AccountTenantAuthorityEventRepository(
            f.dsl(),
            new AccountAuthorityOutboxRepository(f.dsl()),
            billing,
            identities,
            generations);
    var demos =
        new AccountDemoTenantEntitlementRepository(
            f.dsl(), identities, generations, billing, tenantEvents);
    var owner =
        new AccountTenantOperationEntitlementOwner(
            f.service(), demos, new DataSourceTransactionManager(source), f.clock(), "prod");
    return new Context(f, demos, owner);
  }

  private record Context(
      CanonicalHostingSubscriptionTestFixture.Fixture fixture,
      AccountDemoTenantEntitlementRepository demos,
      AccountTenantOperationEntitlementOwner owner) {}
}
