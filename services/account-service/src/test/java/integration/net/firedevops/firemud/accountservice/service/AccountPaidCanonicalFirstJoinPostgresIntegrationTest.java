package integration.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import integration.net.firedevops.firemud.accountservice.repository.CanonicalHostingSubscriptionTestFixture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.accountservice.client.StripeHostingSubscriptionObserver;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReadFence;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.flywaydb.core.Flyway;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Physical Account transaction and real SDK decoding against a controlled local Stripe transport.
 * Tenant/creator/billing setup and the caller/scope are controlled; no authenticated public caller,
 * real Stripe, World hold, protected admission transport, custody or runtime activation is proved.
 */
class AccountPaidCanonicalFirstJoinPostgresIntegrationTest {
  private static final String PREFIX = "paid_first_join_";
  private static final String CALLER = "controlled-paid-join-caller";
  private static final AccountPostgresIntegrationFixture POSTGRES =
      new AccountPostgresIntegrationFixture();
  private final Set<String> schemas = ConcurrentHashMap.newKeySet();

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @AfterEach
  void dropOwnedSchemas() {
    var jdbc = new JdbcTemplate(POSTGRES.dataSource());
    for (String schema : schemas) {
      if (!schema.startsWith(PREFIX) || !schema.matches("[a-z][a-z0-9_]{0,62}"))
        throw new IllegalStateException("Refusing unowned schema cleanup");
      jdbc.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    }
    schemas.clear();
  }

  @Test
  void paidJoinCommitsOnePlayerEventAuditAndReceiptThenExactlyReplaysWithoutProviderIo() {
    Context c = context(false);
    int reads = c.hosting.transport().reads();
    var first = c.commit(c.observed);
    var committedAuditRows = c.auditRows();
    var replay = c.commit(c.observed);
    assertThat(replay).isEqualTo(first);
    assertThat(c.auditRows()).isEqualTo(committedAuditRows);
    assertThat(c.hosting.transport().reads()).isEqualTo(reads);
    assertThat(c.scope.accountId()).isNotEqualTo(c.hosting.accountUuid());
    var retained =
        c.hosting.transaction().execute(status -> c.outbox.findEvent(c.stream(), 1L).orElseThrow());
    var event =
        MembershipAuthorityEventV1Codec.verify(
            new String(Objects.requireNonNull(retained).payload(), StandardCharsets.UTF_8));
    assertThat(event.accountId()).isEqualTo(c.scope.accountId().toString());
    assertThat(event.authorityTuple().tenantBillingCutoff()).isPresent();
    assertThat(event.authorityTuple().tenantAuthorityGeneration())
        .containsKey(c.hosting.tenantId().toString());
    assertThat(c.count("account_tenant_membership")).isEqualTo(1);
    assertThat(c.count("account_tenant_membership_role_snapshots")).isEqualTo(1);
    c.assertCommittedAudit(first);
    assertThat(c.operation().status()).isEqualTo("COMMITTED");
    assertThat(c.operation().terminalProof()).isEqualTo(first);
  }

  @Test
  void providerFactsChangedBeforeCommitRollbackAllStagedWritesThenSameOriginalCanRecover() {
    Context c = context(false);
    var original = c.operation();
    c.hosting.providerStatus("canceled");
    var contradictory = c.hosting.observer().observe(c.fence.binding());
    assertThatThrownBy(() -> c.commit(contradictory))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ENTITLEMENT_UNAVAILABLE: provider facts differ from the committed receipt");
    verifyNoInteractions(c.demos);
    c.assertRolledBack(original);
    c.hosting.providerStatus("active");
    var recovered = c.hosting.observer().observe(c.fence.binding());
    var committed = c.commit(recovered);
    assertThat(committed.entitlementVersion()).isEqualTo(original.entitlementVersion());
    assertThat(c.count("account_tenant_membership")).isEqualTo(1);
    c.assertCommittedAudit(committed);
  }

  @Test
  void changedOperationPolicyCannotCommitAgainstAnOlderPaidReceipt() {
    Context c = context(true);
    var original = c.operation();
    assertThatThrownBy(() -> c.commit(c.observed))
        .hasMessageContaining("exact current public-join entitlement");
    c.assertRolledBack(original);
  }

  @Test
  void expiredPaidReceiptIsUnavailableAndNeverSelectsDemo() {
    Context c = context(false);
    var original = c.operation();
    c.hosting.clock().advance(Duration.ofHours(2));
    var freshSdkResult = c.hosting.observer().observe(c.fence.binding());
    assertThatThrownBy(() -> c.commit(freshSdkResult))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("ENTITLEMENT_UNAVAILABLE: current receipt is expired or inconsistent");
    verifyNoInteractions(c.demos);
    c.assertRolledBack(original);
  }

  @Test
  void finalCommitFailureRollsBackMembershipRolesPairEventAuditAndTerminalReceipt() {
    Context c = context(false);
    var original = c.operation();
    assertThatThrownBy(
            () ->
                c.hosting
                    .transaction()
                    .execute(
                        status -> {
                          TransactionSynchronizationManager.registerSynchronization(
                              new TransactionSynchronization() {
                                @Override
                                public void beforeCommit(boolean readOnly) {
                                  throw new IllegalStateException(
                                      "controlled final paid JOIN commit refusal");
                                }
                              });
                          return c.coordinator.commitPaidCanonicalFirstJoin(
                              c.scope,
                              c.requestId,
                              CALLER,
                              c.hosting.lifecycleRepository(),
                              c.fence,
                              c.observed);
                        }))
        .hasMessageContaining("controlled final paid JOIN commit refusal");
    c.assertRolledBack(original);
  }

  private Context context(boolean wrongPolicy) {
    String schema = PREFIX + UUID.randomUUID().toString().replace("-", "");
    schemas.add(schema);
    var source = POSTGRES.dataSource(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return new Context(CanonicalHostingSubscriptionTestFixture.create(source), wrongPolicy);
  }

  private static final class Context {
    private final CanonicalHostingSubscriptionTestFixture.Fixture hosting;
    private final AccountJoinOperationRepository operations;
    private final AccountAuthorityOutboxRepository outbox;
    private final AccountDemoTenantEntitlementRepository demos;
    private final AccountMembershipPairAuthorityRepository pairs;
    private final AccountCanonicalFirstJoinTerminalCoordinator coordinator;
    private final CanonicalJoinScopeV2 scope;
    private final String requestId = UUID.randomUUID().toString();
    private final ReadFence fence;
    private final StripeHostingSubscriptionObserver.Result observed;
    private final List<Map<String, Object>> paidAuditRows;

    private Context(CanonicalHostingSubscriptionTestFixture.Fixture hosting, boolean wrongPolicy) {
      this.hosting = hosting;
      var committed = hosting.completeFirstInvoice();
      assertThat(committed.outcome())
          .isEqualTo(
              net.firedevops.firemud.accountservice.repository
                  .AccountHostingSubscriptionLifecycleRepository.Outcome.COMMITTED);
      hosting.transaction().setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
      var dsl = hosting.dsl();
      var accounts = new AccountRepository(dsl);
      var fresh = new FreshTenantIdentityAssociationRepository(dsl, "prod");
      pairs = new AccountMembershipPairAuthorityRepository(dsl);
      var members = new AccountTenantMembershipRepository(dsl, accounts, fresh, pairs);
      var roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      var generations = new AccountAuthorityGenerationRepository(dsl);
      outbox = new AccountAuthorityOutboxRepository(dsl);
      var sources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      var billing = new AccountTenantEntitlementOutboxRepository(dsl);
      var tenantEvents =
          new AccountTenantAuthorityEventRepository(dsl, outbox, billing, fresh, generations);
      demos =
          spy(
              new AccountDemoTenantEntitlementRepository(
                  dsl, fresh, generations, billing, tenantEvents));
      var scopes =
          new AccountConnectScopeRepository(
              dsl,
              new AccountTenantIdentityResolver(
                  mock(ApprovedLegacyTenantAssociationRepository.class), "prod"),
              fresh);
      operations = new AccountJoinOperationRepository(dsl, scopes);
      var producer =
          new AccountMembershipAuthorityEventProducer(
              operations,
              accounts,
              pairs,
              members,
              roles,
              generations,
              outbox,
              sources,
              tenantEvents,
              demos);
      coordinator =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts,
              operations,
              members,
              roles,
              outbox,
              pairs,
              new AccountAuditOutboxRepository(dsl),
              producer);
      Account input = new Account();
      input.setUsername("paid-player-" + UUID.randomUUID());
      input.setEmail("paid-player-" + UUID.randomUUID() + "@example.test");
      input.setPasswordHash("controlled-hash");
      // Player role belongs to the canonical tenant membership, not global Account authority.
      Account player =
          Objects.requireNonNull(hosting.transaction().execute(status -> accounts.save(input)));
      var now = hosting.clock().instant();
      scope =
          new CanonicalJoinScopeV2(
              "controlled-paid-scope-" + UUID.randomUUID(),
              player.getAccountUuid(),
              hosting.tenantId(),
              UUID.randomUUID(),
              "tenant",
              "world",
              "production",
              UUID.randomUUID(),
              "SHARED",
              UUID.randomUUID(),
              7,
              3,
              now.toString(),
              now.plusSeconds(120).toString());
      hosting
          .transaction()
          .executeWithoutResult(
              status -> {
                var source = fresh.read(hosting.tenantId()).orElseThrow();
                sources.initializeIssuerIfAbsent("firemud-account-service");
                generations.initialize(
                    AccountAuthorityGenerationRepository.AuthorityScope.membership(
                        player.getAccountUuid(), hosting.tenantId()));
                scopes.insertCanonical(
                    player.getId(),
                    scope,
                    new VerifiedTenantProvenance(
                        null,
                        TenantProvenanceKind.FRESH_GAME_DESIGN,
                        source.operationId(),
                        source.evidenceDigest()));
                operations.insertCanonicalIntent(requestId, scope, CALLER);
              });
      fence =
          Objects.requireNonNull(
              hosting
                  .transaction()
                  .execute(
                      status ->
                          hosting.lifecycleRepository().prepareCurrentRead(hosting.tenantId())));
      observed = hosting.observer().observe(fence.binding());
      hosting
          .transaction()
          .executeWithoutResult(
              status -> {
                var receipt = hosting.lifecycleRepository().readCurrent(fence, observed);
                operations.bindCanonicalPolicyEvidence(
                    requestId,
                    scope,
                    CALLER,
                    receipt.allowPublicJoin(),
                    receipt.entitlementVersion() + (wrongPolicy ? 1 : 0));
              });
      paidAuditRows = auditRows();
      assertThat(paidAuditRows).isNotEmpty();
    }

    private AccountJoinOperationRepository.CanonicalJoinTerminalProof commit(
        StripeHostingSubscriptionObserver.Result result) {
      return Objects.requireNonNull(
          hosting
              .transaction()
              .execute(
                  status ->
                      coordinator.commitPaidCanonicalFirstJoin(
                          scope, requestId, CALLER, hosting.lifecycleRepository(), fence, result)));
    }

    private AccountJoinOperationRepository.CanonicalJoinOperationEvidence operation() {
      return Objects.requireNonNull(
          hosting
              .transaction()
              .execute(
                  status -> {
                    operations.lockAccount(
                        Objects.requireNonNull(
                                hosting
                                    .dsl()
                                    .fetchOne(
                                        "SELECT id FROM accounts WHERE account_uuid = ?",
                                        scope.accountId()))
                            .get(0, Long.class));
                    return operations
                        .findCanonicalEvidenceForUpdateByRequestId(requestId)
                        .orElseThrow();
                  }));
    }

    private String stream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + scope.accountId()
          + "/"
          + scope.tenantId();
    }

    private int count(String table) {
      return hosting.dsl().fetchCount(DSL.table(table));
    }

    private List<Map<String, Object>> auditRows() {
      return hosting
          .dsl()
          .fetch(
              "SELECT audit_event_id, row_to_json(audit)::text AS row_image, xmin::text AS row_xmin "
                  + "FROM account_audit_outbox audit ORDER BY audit_event_id")
          .intoMaps();
    }

    private void assertCommittedAudit(
        AccountJoinOperationRepository.CanonicalJoinTerminalProof proof) {
      assertThat(
              hosting
                  .dsl()
                  .fetch(
                      "SELECT audit_event_id, row_to_json(audit)::text AS row_image, xmin::text AS row_xmin "
                          + "FROM account_audit_outbox audit WHERE audit_event_id <> ? ORDER BY audit_event_id",
                      proof.auditEventId())
                  .intoMaps())
          .isEqualTo(paidAuditRows);
      var joined =
          Objects.requireNonNull(
              hosting
                  .transaction()
                  .execute(
                      status ->
                          new AccountAuditOutboxRepository(hosting.dsl())
                              .findCanonicalTenantEnvelopeForUpdate(proof.auditEventId())
                              .orElseThrow()));
      assertThat(joined.auditEventId()).isEqualTo(proof.auditEventId());
      assertThat(joined.eventType()).isEqualTo("ACCOUNT_JOINED_PUBLIC_PRODUCTION");
      assertThat(joined.tenantUuid()).isEqualTo(scope.tenantId());
      assertThat(joined.payloadDigest()).isEqualTo(proof.auditPayloadDigest());
      assertThat(joined.occurredAt()).isEqualTo(proof.auditOccurredAt());
    }

    private void assertRolledBack(
        AccountJoinOperationRepository.CanonicalJoinOperationEvidence original) {
      assertThat(operation()).isEqualTo(original);
      assertThat(count("account_tenant_membership")).isZero();
      assertThat(count("account_tenant_membership_role_snapshots")).isZero();
      assertThat(auditRows()).isEqualTo(paidAuditRows);
      var checkpoint = hosting.transaction().execute(status -> outbox.readCheckpoint(stream()));
      assertThat(checkpoint).isEmpty();
      var pair =
          hosting
              .transaction()
              .execute(status -> pairs.readForUpdate(scope.accountId(), scope.tenantId()));
      assertThat(pair).isEmpty();
    }
  }
}
