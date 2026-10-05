package integration.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import integration.net.firedevops.firemud.accountservice.repository.AccountPostgresIntegrationFixture;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.CanonicalJoinScopeV2;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountAuditOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountConnectScopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.AccountCanonicalFirstJoinTerminalCoordinator;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL proof fixture for the unwired canonical first-JOIN event/first-pair composition. */
class AccountCanonicalFirstJoinEventIntegrationTest {
  private static final String TEST_NAMESPACE = "canonical-join-event-proof";
  private static final String ACCOUNT_ISSUER = "firemud-account-service";

  private static final AccountPostgresIntegrationFixture postgres =
      new AccountPostgresIntegrationFixture();

  @BeforeAll
  static void startPostgres() {
    postgres.start();
  }

  @AfterAll
  static void stopPostgres() {
    postgres.stop();
  }

  @Test
  void commitsExactEventAndPairAndReplaysOnlyTheSameCallerOperation() {
    Fixture fixture = newFixture();
    AccountAuthorityOutboxRepository.Checkpoint first = fixture.writeAndPublish();
    AccountAuthorityOutboxRepository.Checkpoint replay =
        fixture.inTransaction(
            () ->
                fixture.producer.publishCanonicalFirstJoinMembershipChange(
                    fixture.scope, fixture.requestId, fixture.callerBinding));

    String stream = fixture.membershipStream();
    AccountAuthorityOutboxRepository.Event event =
        fixture.inTransaction(
            () -> fixture.outbox.findEvent(stream, fixture.requestId).orElseThrow());
    var decoded =
        MembershipAuthorityEventV1Codec.verify(new String(event.payload(), StandardCharsets.UTF_8));
    PairAuthority pair =
        fixture.inTransaction(
            () ->
                fixture
                    .pairs
                    .readForUpdate(fixture.account.getAccountUuid(), fixture.tenantUuid)
                    .orElseThrow());

    assertThat(first.outboxSequence()).isEqualTo(1L);
    assertThat(replay).isEqualTo(first);
    assertThat(decoded.requestId()).isEqualTo(fixture.requestId);
    assertThat(decoded.accountId()).isEqualTo(fixture.account.getAccountUuid().toString());
    assertThat(decoded.tenantId()).isEqualTo(fixture.tenantUuid.toString());
    assertThat(decoded.membershipVersion()).isEqualTo(Map.of(fixture.tenantUuid.toString(), "2"));
    assertThat(decoded.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(decoded.authorityTuple().privateRealmGrantVersions()).isEmpty();
    assertThat(decoded.authorityTuple().accountSecurityCutoff()).isEmpty();
    assertThat(decoded.authorityTuple().tenantBillingCutoff()).isEmpty();
    assertThat(decoded.roles()).containsExactly("player");
    assertThat(pair.membershipExists()).isTrue();
    assertThat(pair.membershipVersion()).isEqualTo(2L);
    assertThat(pair.membershipAuthorityGeneration()).isEqualTo(1L);
    assertThat(pair.eventSequence()).isEqualTo(1L);
    assertThat(pair.eventId()).isEqualTo(decoded.eventId());
    assertThat(pair.eventDigest()).isEqualTo(decoded.eventDigest());
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.producer.publishCanonicalFirstJoinMembershipChange(
                            fixture.scope, fixture.requestId, "another-caller")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("request, caller, scope, or available policy");
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isEqualTo(1L);
  }

  @Test
  void pairCasFailureRollsBackMembershipAndTheActualOutboxAppend() {
    Fixture fixture = newFixture();
    fixture.dsl.execute(
        "CREATE FUNCTION fail_first_join_pair_advance() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN RAISE EXCEPTION 'first-join pair CAS failure'; END $$");
    fixture.dsl.execute(
        "CREATE TRIGGER fail_first_join_pair_advance BEFORE UPDATE ON "
            + "account_membership_pair_authority FOR EACH ROW "
            + "EXECUTE FUNCTION fail_first_join_pair_advance()");

    assertThatThrownBy(fixture::writeAndPublish)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("first-join pair CAS failure");

    String stream = fixture.membershipStream();
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(
            fixture.count(
                "account_tenant_membership_role_snapshots",
                "membership_id",
                fixture.membershipId()))
        .isZero();
    assertThat(
            fixture.count("account_membership_pair_authority", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_streams", "outbox_stream_key", stream))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isZero();
  }

  @Test
  void terminalCoordinatorCommitsAuditAndOperationWithTheFirstEventAndReplaysExactly() {
    Fixture fixture = newFixture();
    var proof = fixture.commitFirstJoin();
    var replay = fixture.commitFirstJoin();
    String stream = fixture.membershipStream();
    var operation =
        fixture.inTransaction(
            () ->
                fixture
                    .operations
                    .findCanonicalEvidenceForUpdateByRequestId(fixture.requestId)
                    .orElseThrow());

    assertThat(replay).isEqualTo(proof);
    assertThat(operation.status()).isEqualTo("COMMITTED");
    assertThat(operation.outcome()).isEqualTo("JOINED");
    assertThat(operation.terminalProof()).isEqualTo(proof);
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isEqualTo(1L);
    assertThat(
            fixture.count(
                "account_tenant_membership_role_snapshots", "membership_id", proof.membershipId()))
        .isEqualTo(1L);
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", proof.auditEventId()))
        .isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.terminalCoordinator.commitCanonicalFirstJoin(
                            fixture.scope, fixture.requestId, "another-caller")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isEqualTo(1L);
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", proof.auditEventId()))
        .isEqualTo(1L);
  }

  @Test
  void terminalJournalFailureRollsBackMembershipEventPairAndAuditTogether() {
    Fixture fixture = newFixture();
    fixture.dsl.execute(
        "CREATE FUNCTION fail_first_join_terminal_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.status = 'COMMITTED' THEN "
            + "RAISE EXCEPTION 'first-join terminal receipt failure'; END IF; RETURN NEW; END $$");
    fixture.dsl.execute(
        "CREATE TRIGGER fail_first_join_terminal_receipt BEFORE UPDATE ON "
            + "account_join_operations FOR EACH ROW "
            + "EXECUTE FUNCTION fail_first_join_terminal_receipt()");

    assertThatThrownBy(fixture::commitFirstJoin)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("first-join terminal receipt failure");

    String stream = fixture.membershipStream();
    UUID auditEventId =
        UUID.nameUUIDFromBytes(
            ("account-join-audit/v1:" + fixture.requestId).getBytes(StandardCharsets.UTF_8));
    assertThat(fixture.count("account_tenant_membership", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.countAll("account_tenant_membership_role_snapshots")).isZero();
    assertThat(fixture.countAll("account_tenant_membership_role_snapshot_roles")).isZero();
    assertThat(
            fixture.count("account_membership_pair_authority", "tenant_uuid", fixture.tenantUuid))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_streams", "outbox_stream_key", stream))
        .isZero();
    assertThat(fixture.count("account_authority_outbox_events", "outbox_stream_key", stream))
        .isZero();
    assertThat(fixture.count("account_audit_outbox", "audit_event_id", auditEventId)).isZero();
    var operation =
        fixture.inTransaction(
            () ->
                fixture
                    .operations
                    .findCanonicalEvidenceForUpdateByRequestId(fixture.requestId)
                    .orElseThrow());
    assertThat(operation.status()).isEqualTo("PENDING");
    assertThat(operation.terminalProof()).isNull();
  }

  private Fixture newFixture() {
    return new Fixture(newTestContext());
  }

  private static TestContext newTestContext() {
    String schema = "canonical_join_event_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = postgres.dataSource(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new TestContext(
        dsl, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }

  private static final class Fixture {
    private final DSLContext dsl;
    private final TransactionTemplate transaction;
    private final AccountRepository accounts;
    private final FreshTenantIdentityAssociationRepository freshTenants;
    private final AccountMembershipPairAuthorityRepository pairs;
    private final AccountTenantMembershipRepository memberships;
    private final AccountTenantMembershipRoleSnapshotRepository roles;
    private final AccountAuthorityGenerationRepository generations;
    private final AccountAuthorityOutboxRepository outbox;
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
    private final AccountAuditOutboxRepository auditOutbox;
    private final AccountJoinOperationRepository operations;
    private final AccountMembershipAuthorityEventProducer producer;
    private final AccountCanonicalFirstJoinTerminalCoordinator terminalCoordinator;
    private final AccountConnectScopeRepository connectScopes;
    private final Account account;
    private final UUID tenantUuid = UUID.randomUUID();
    private final FreshTenantCreationEvidence tenantEvidence;
    private final VerifiedTenantProvenance provenance;
    private final CanonicalJoinScopeV2 scope;
    private final String requestId = UUID.randomUUID().toString();
    private final String callerBinding = "owner-bound-caller-" + UUID.randomUUID();
    private Long membershipId;

    private Fixture(TestContext context) {
      this.dsl = context.dsl();
      this.transaction = context.transaction();
      this.accounts = new AccountRepository(dsl);
      this.freshTenants = new FreshTenantIdentityAssociationRepository(dsl, TEST_NAMESPACE);
      this.pairs = new AccountMembershipPairAuthorityRepository(dsl);
      this.memberships = new AccountTenantMembershipRepository(dsl, accounts, freshTenants, pairs);
      this.roles = new AccountTenantMembershipRoleSnapshotRepository(dsl);
      this.generations = new AccountAuthorityGenerationRepository(dsl);
      this.outbox = new AccountAuthorityOutboxRepository(dsl);
      this.sourceEvidence = new AccountAuthoritySourceEvidenceRepository(dsl, generations, outbox);
      this.auditOutbox = new AccountAuditOutboxRepository(dsl);
      var legacyAssociationRepository =
          org.mockito.Mockito.mock(ApprovedLegacyTenantAssociationRepository.class);
      var retainedIdentityResolver =
          new AccountTenantIdentityResolver(legacyAssociationRepository, TEST_NAMESPACE);
      this.connectScopes =
          new AccountConnectScopeRepository(dsl, retainedIdentityResolver, freshTenants);
      this.operations = new AccountJoinOperationRepository(dsl, connectScopes);
      this.producer =
          new AccountMembershipAuthorityEventProducer(
              operations, accounts, pairs, memberships, roles, generations, outbox, sourceEvidence);
      this.terminalCoordinator =
          new AccountCanonicalFirstJoinTerminalCoordinator(
              accounts, operations, memberships, roles, outbox, pairs, auditOutbox, producer);
      this.account = createAccount();
      this.tenantEvidence = freshTenantEvidence(tenantUuid);
      this.provenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvidence.operationId(),
              tenantEvidence.evidenceDigest());
      this.scope = canonicalScope(account.getAccountUuid(), tenantUuid);
      seedOwnerRowsAndOperation();
    }

    private Account createAccount() {
      Account input = new Account();
      String suffix = UUID.randomUUID().toString().replace("-", "");
      input.setUsername("canonical-join-" + suffix);
      input.setEmail("canonical-join-" + suffix + "@example.test");
      input.setPasswordHash("integration-fixture-hash");
      input.setRole("player");
      return inTransaction(() -> accounts.save(input));
    }

    private void seedOwnerRowsAndOperation() {
      inTransaction(
          () -> {
            freshTenants.importVerified(tenantEvidence);
            sourceEvidence.initializeIssuerIfAbsent(ACCOUNT_ISSUER);
            generations.initializeTenantIfAbsent(tenantUuid);
            generations.initialize(
                AccountAuthorityGenerationRepository.AuthorityScope.membership(
                    account.getAccountUuid(), tenantUuid));
            connectScopes.insertCanonical(account.getId(), scope, provenance);
            operations.insertCanonicalIntent(requestId, scope, callerBinding);
            operations.bindCanonicalPolicyEvidence(requestId, scope, callerBinding, true, 5L);
            return null;
          });
    }

    private AccountAuthorityOutboxRepository.Checkpoint writeAndPublish() {
      return inTransaction(
          () -> {
            var membership =
                memberships.createFreshMembershipForJoin(account.getAccountUuid(), tenantUuid);
            membershipId = membership.getId();
            roles.replaceCanonical(
                membership,
                account.getAccountUuid(),
                tenantUuid,
                provenance,
                2L,
                java.util.List.of("player"));
            return producer.publishCanonicalFirstJoinMembershipChange(
                scope, requestId, callerBinding);
          });
    }

    private AccountJoinOperationRepository.CanonicalJoinTerminalProof commitFirstJoin() {
      return inTransaction(
          () -> terminalCoordinator.commitCanonicalFirstJoin(scope, requestId, callerBinding));
    }

    private long membershipId() {
      return membershipId == null ? -1L : membershipId;
    }

    private String membershipStream() {
      return MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + account.getAccountUuid()
          + "/"
          + tenantUuid;
    }

    private long count(String table, String keyColumn, Object value) {
      return Objects.requireNonNull(
              dsl.fetchOne("SELECT count(*) FROM " + table + " WHERE " + keyColumn + " = ?", value))
          .get(0, Long.class);
    }

    private long countAll(String table) {
      return Objects.requireNonNull(dsl.fetchOne("SELECT count(*) FROM " + table))
          .get(0, Long.class);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> callback) {
      return transaction.execute(status -> callback.get());
    }
  }

  private static FreshTenantCreationEvidence freshTenantEvidence(UUID tenantUuid) {
    UUID requestId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String sourceTenantKey =
        "fresh-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    String requestDigest =
        GameTenantCreationDigest.requestDigest(
            TEST_NAMESPACE, requestId, sourceTenantKey, "Canonical JOIN event test", null);
    long sourceGameRowId = positiveRandomLong();
    String provenanceKind = "NEW_GAME_ROW";
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        tenantUuid,
        sourceGameRowId,
        sourceTenantKey,
        provenanceKind,
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            tenantUuid,
            sourceGameRowId,
            sourceTenantKey,
            provenanceKind));
  }

  private static CanonicalJoinScopeV2 canonicalScope(UUID accountUuid, UUID tenantUuid) {
    return new CanonicalJoinScopeV2(
        "canonical-scope-" + UUID.randomUUID(),
        accountUuid,
        tenantUuid,
        UUID.randomUUID(),
        "tenant",
        "world",
        "production",
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        7L,
        3L,
        "2026-10-04T00:00:00Z",
        "2026-10-04T00:02:00Z");
  }

  private static long positiveRandomLong() {
    long value = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    return value == 0L ? 1L : value;
  }

  private record TestContext(DSLContext dsl, TransactionTemplate transaction) {}
}
