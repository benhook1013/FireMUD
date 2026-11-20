package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

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
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionLifecycleRepository.ReadFence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;

/**
 * Physical consumer SQL regression after a genuine canonical LEAVE and restore.
 * Tenant/creator/billing setup and the caller/scope are controlled; no authenticated public caller,
 * real Stripe, World hold, protected admission transport, custody or runtime activation is proved.
 */
class AccountLeaveConsumerPostgresIntegrationTest {
  private static final String PREFIX = "leave_consumer_";
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
  void paidCheckpointUsesCompositeFenceAfterLeaveRestore() {
    Context c = context(false);
    c.commit(c.observed);
    var before = c.currentSources();
    var securityBefore = c.securityRows();
    c.leaveAndRestore();
    var after = c.currentSources();
    assertThat(after.issuanceFence().value()).isEqualTo(before.issuanceFence().value() + 1L);
    assertThat(after.account().generation()).isEqualTo(before.account().generation());
    assertThat(c.securityRows()).isEqualTo(securityBefore);
    assertThat(
            c.hosting
                .dsl()
                .fetchSingle(
                    "SELECT current_issuance_fence FROM account_authority_source_records WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    c.scope.accountId())
                .get(0, Long.class))
        .isEqualTo(1L);

    long previousSequence =
        c.in(status -> c.outbox.readCheckpoint(c.stream()).orElseThrow().outboxSequence());
    long version =
        c.hosting
            .dsl()
            .fetchSingle(
                "SELECT row_version FROM subscription WHERE subscription_id = ?",
                c.hosting.creation().subscriptionId())
            .get(0, Long.class);
    c.hosting.clock().advance(Duration.ofSeconds(2));
    assertThat(
            c.hosting
                .service()
                .reconcile(
                    UUID.randomUUID(),
                    c.hosting.creation().requestId(),
                    version,
                    c.hosting.providerId(),
                    null)
                .outcome())
        .isEqualTo(AccountHostingSubscriptionLifecycleRepository.Outcome.COMMITTED);
    var freshFence =
        c.in(status -> c.hosting.lifecycleRepository().prepareCurrentRead(c.scope.tenantId()));
    var freshObserved = c.hosting.observer().observe(freshFence.binding());
    var snapshot =
        c.in(
            status ->
                c.snapshots.requalifyPaidAcquisition(
                    c.scope.accountId(),
                    c.scope.tenantId(),
                    c.hosting.lifecycleRepository().readCurrent(freshFence, freshObserved)));
    assertThat(snapshot.issuanceFence()).isEqualTo("2");
    var retained =
        c.in(status -> c.outbox.findEvent(c.stream(), previousSequence + 1L).orElseThrow());
    var event =
        MembershipAuthorityEventV1Codec.verify(
            new String(retained.payload(), StandardCharsets.UTF_8));
    assertThat(event.eventType()).isEqualTo("MEMBERSHIP_AUTHORITY_CHECKPOINT");
    assertThat(event.issuanceFence()).isEqualTo("2");
    assertThat(event.membershipVersion()).isEqualTo(snapshot.membershipVersion());
    assertThat(event.membershipAuthorityGeneration())
        .isEqualTo(snapshot.membershipAuthorityGeneration());
    assertThat(c.securityRows()).isEqualTo(securityBefore);
    var replay =
        c.in(
            status ->
                c.snapshots.requalifyPaidAcquisition(
                    c.scope.accountId(),
                    c.scope.tenantId(),
                    c.hosting.lifecycleRepository().readCurrent(freshFence, freshObserved)));
    assertThat(replay).isEqualTo(snapshot);
    Long replaySequence =
        c.in(status -> c.outbox.readCheckpoint(c.stream()).orElseThrow().outboxSequence());
    assertThat(replaySequence).isEqualTo(previousSequence + 1L);
  }

  @Test
  void delegationGuardAcceptsCurrentCompositeFenceAndRejectsStaleFenceAfterLeaveRestore() {
    Context c = context(false);
    c.commit(c.observed);
    var before = c.currentSources();
    var securityBefore = c.securityRows();
    c.leaveAndRestore();
    var after = c.currentSources();
    assertThat(after.issuanceFence().value()).isEqualTo(before.issuanceFence().value() + 1L);
    assertThat(after.account().generation()).isEqualTo(before.account().generation());
    assertThat(c.securityRows()).isEqualTo(securityBefore);
    assertThat(
            c.hosting
                .dsl()
                .fetchSingle(
                    "SELECT current_issuance_fence FROM account_authority_source_records WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
                    c.scope.accountId())
                .get(0, Long.class))
        .isEqualTo(1L);

    // Actual persisted pending intent invokes the current gameplay authority SQL trigger.
    var issuance =
        new AccountGameplayDelegationIssuanceRepository(
            c.hosting.dsl(), c.sources, c.hosting.clock());
    var intent = c.intent(after);
    c.hosting.transaction().executeWithoutResult(status -> issuance.beginPending(intent));
    assertThat(
            c.hosting
                .dsl()
                .fetchSingle(
                    "SELECT issuance_fence FROM account_gameplay_delegation_issuance_operations WHERE request_id = ?",
                    intent.requestId())
                .get(0, Long.class))
        .isEqualTo(2L);
    assertThatThrownBy(
            () ->
                c.hosting
                    .transaction()
                    .executeWithoutResult(status -> issuance.beginPending(c.intent(before))))
        .isInstanceOf(AccountGameplayDelegationIssuanceRepository.StaleAuthorityException.class);

    var pendingBefore =
        c.hosting
            .dsl()
            .fetchSingle(
                "SELECT row_to_json(p)::text AS image, xmin::text AS row_xmin FROM account_gameplay_delegation_issuance_operations p WHERE request_id = ?",
                intent.requestId())
            .intoMap();
    assertThatThrownBy(
            () ->
                c.hosting
                    .transaction()
                    .executeWithoutResult(
                        status ->
                            c.hosting
                                .dsl()
                                .execute(
                                    "UPDATE account_gameplay_delegation_issuance_operations SET issuance_fence = 1, issuance_fence_source_version = 1 WHERE request_id = ?",
                                    intent.requestId())))
        .rootCause()
        .isInstanceOf(org.postgresql.util.PSQLException.class)
        .hasMessageContaining("Account gameplay delegation authority snapshot is stale or missing");
    assertThat(
            c.hosting
                .dsl()
                .fetchSingle(
                    "SELECT row_to_json(p)::text AS image, xmin::text AS row_xmin FROM account_gameplay_delegation_issuance_operations p WHERE request_id = ?",
                    intent.requestId())
                .intoMap())
        .isEqualTo(pendingBefore);
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
    private final AccountAuthoritySourceEvidenceRepository sources;
    private final AccountCanonicalRuntimeMembershipSnapshotRepository snapshots;
    private final net.firedevops.firemud.accountservice.service
            .AccountCanonicalLeaveMembershipCoordinator
        leave;

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
      sources = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
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
      leave =
          new net.firedevops.firemud.accountservice.service
              .AccountCanonicalLeaveMembershipCoordinator(
              accounts,
              operations,
              new AccountLeaveOperationRepository(dsl),
              members,
              sources,
              producer,
              new AccountAuditOutboxRepository(dsl));
      snapshots =
          new AccountCanonicalRuntimeMembershipSnapshotRepository(
              accounts, generations, sources, tenantEvents, fresh, members, pairs, roles, outbox);
      Account input = new Account();
      input.setUsername("paid-player-" + UUID.randomUUID());
      input.setEmail("paid-player-" + UUID.randomUUID() + "@example.test");
      input.setPasswordHash("controlled-hash");
      // Player role belongs to the canonical tenant membership, not global Account authority.
      Account player = Objects.requireNonNull(in(status -> accounts.save(input)));
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
              in(status -> hosting.lifecycleRepository().prepareCurrentRead(hosting.tenantId())));
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
    }

    private AccountJoinOperationRepository.CanonicalJoinTerminalProof commit(
        StripeHostingSubscriptionObserver.Result result) {
      return Objects.requireNonNull(
          in(
              status ->
                  coordinator.commitPaidCanonicalFirstJoin(
                      scope, requestId, CALLER, hosting.lifecycleRepository(), fence, result)));
    }

    private AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot currentSources() {
      return in(
          status ->
              sources.readCurrentIssuerAccountSources(
                  "firemud-account-service", scope.accountId()));
    }

    private List<Map<String, Object>> securityRows() {
      return hosting
          .dsl()
          .fetch(
              "SELECT row_to_json(s)::text AS row_image, xmin::text AS row_xmin FROM account_authority_source_records s WHERE scope_kind = 'ACCOUNT' AND account_uuid = ?",
              scope.accountId())
          .intoMaps();
    }

    private void leaveAndRestore() {
      var binding =
          new net.firedevops.firemud.accountservice.dto.AccountLeaveMembershipBinding(
              UUID.randomUUID().toString(), scope.accountId(), scope.tenantId(), CALLER);
      var result = in(status -> leave.leave(binding));
      assertThat(result.terminal().outcome()).isEqualTo("LEFT");
      String next = UUID.randomUUID().toString();
      hosting
          .transaction()
          .executeWithoutResult(
              status -> {
                operations.lockAccount(
                    hosting
                        .dsl()
                        .fetchSingle(
                            "SELECT id FROM accounts WHERE account_uuid = ?", scope.accountId())
                        .get(0, Long.class));
                operations.insertCanonicalIntent(next, scope, CALLER);
                var receipt = hosting.lifecycleRepository().readCurrent(fence, observed);
                operations.bindCanonicalPolicyEvidence(
                    next, scope, CALLER, receipt.allowPublicJoin(), receipt.entitlementVersion());
              });
      var restored =
          in(
              status ->
                  coordinator.commitCanonicalJoin(
                      scope, next, CALLER, hosting.lifecycleRepository(), fence, observed));
      assertThat(restored.membershipVersion()).isEqualTo(4L);
    }

    private AccountGameplayDelegationIssuanceRepository.PendingIntent intent(
        AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot source) {
      long now = hosting.clock().instant().getEpochSecond();
      return new AccountGameplayDelegationIssuanceRepository.PendingIntent(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "spiffe://firemud/ns/test/sa/game-session-service",
          UUID.randomUUID(),
          UUID.randomUUID(),
          now,
          now,
          now + 120L,
          new net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord
              .AccountAuthoritySnapshot(
              scope.accountId(),
              source.issuer().generation(),
              source.issuer().sourceVersion(),
              source.account().generation(),
              source.account().sourceVersion(),
              source.issuanceFence().value(),
              source.issuanceFence().sourceVersion(),
              java.util.Optional.empty()),
          AccountGameplayCredentialRequestBindingFixture.binding());
    }

    private <T> T in(org.springframework.transaction.support.TransactionCallback<T> action) {
      return Objects.requireNonNull(hosting.transaction().execute(action));
    }

    private String stream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + scope.accountId()
          + "/"
          + scope.tenantId();
    }
  }
}
