package integration.net.firedevops.firemud.accountservice.creatorparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.accountservice.creatorparty.CreatorPartyEncoding;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartyRepository.AssociationConflictException;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource;
import net.firedevops.firemud.accountservice.creatorparty.IndividualCreatorPartySource.VerificationStatus;
import net.firedevops.firemud.accountservice.repository.AccountTenantCreationBootstrapOperationRepository;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical proof for exact fresh creator-party receipts and canonical tenant serialization. */
@Testcontainers(disabledWithoutDocker = true)
class IndividualCreatorPartyPostgresIntegrationTest {
  private static final String NAMESPACE = "creator-party-proof";
  private static final String DIGEST = "sha256:" + "1".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void committedAssociationHasExactIndependentReadbackAndReplay() throws Exception {
    Fixture fixture = fixture();
    UUID request = UUID.randomUUID();

    var committed =
        tx(
            fixture.db(),
            () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party()));
    byte[] originalReceipt =
        Objects.requireNonNull(
                fixture
                    .db()
                    .dsl()
                    .fetchOne(
                        "SELECT result_payload FROM account_fresh_creator_party_association_operations "
                            + "WHERE request_id = ?",
                        request))
            .get("result_payload", byte[].class);
    assertThat(
            tx(
                fixture.db(),
                () ->
                    fixture
                        .owner()
                        .readInitialAssociation(request, fixture.creator(), fixture.party())))
        .isEqualTo(committed);
    assertThat(
            tx(
                fixture.db(),
                () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party())))
        .isEqualTo(committed);
    assertThat(
            Objects.requireNonNull(
                    fixture
                        .db()
                        .dsl()
                        .fetchOne(
                            "SELECT result_payload FROM account_fresh_creator_party_association_operations "
                                + "WHERE request_id = ?",
                            request))
                .get("result_payload", byte[].class))
        .containsExactly(originalReceipt);
    assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
    assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations"))
        .isEqualTo(1);
  }

  @Test
  void retainedHistoryCommitsBeforeWaitingFreshAssociationAndExcludesIt() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch retainedLocked = new CountDownLatch(1);
    CountDownLatch releaseRetained = new CountDownLatch(1);
    CountDownLatch freshStarted = new CountDownLatch(1);
    AtomicInteger retainedPid = new AtomicInteger();
    AtomicInteger freshPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> retained =
          executor.submit(
              () ->
                  tx(
                      fixture.db(),
                      () -> {
                        insertRetainedHistory(fixture);
                        retainedPid.set(backendPid(fixture.db()));
                        retainedLocked.countDown();
                        await(releaseRetained);
                        return null;
                      }));
      assertThat(retainedLocked.await(10, TimeUnit.SECONDS)).isTrue();

      Future<?> fresh =
          executor.submit(
              () ->
                  tx(
                      fixture.db(),
                      () -> {
                        freshPid.set(backendPid(fixture.db()));
                        freshStarted.countDown();
                        return fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), fixture.party());
                      }));
      assertThat(freshStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertDatabaseLockWait(fixture.db(), freshPid.get(), retainedPid.get());

      releaseRetained.countDown();
      retained.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> fresh.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AssociationConflictException.class);
      assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
      assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations"))
          .isZero();
    } finally {
      releaseRetained.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static Fixture fixture() throws Exception {
    Database db = database();
    UUID account = insertAccount(db);
    UUID request = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    String tenantKey = "party-game";
    String creationDigest =
        GameTenantCreationDigest.requestDigest(NAMESPACE, request, tenantKey, "fixture", null);
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            NAMESPACE,
            request,
            operation,
            creationDigest,
            tenant,
            1,
            tenantKey,
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                NAMESPACE,
                request,
                operation,
                creationDigest,
                tenant,
                1,
                tenantKey,
                "NEW_GAME_ROW"));
    FreshTenantCreatorEvidence creator = qualification(creation, account, UUID.randomUUID());
    FreshTenantIdentityAssociationRepository freshTenants =
        new FreshTenantIdentityAssociationRepository(db.dsl(), NAMESPACE);
    tx(
        db,
        () -> {
          freshTenants.importVerified(creation);
          insertCommittedBootstrapFixture(db, creator);
          return null;
        });

    IndividualCreatorPartySource party = party(account);
    byte[] partyPayload = CreatorPartyEncoding.party(party);
    db.dsl()
        .execute(
            "INSERT INTO account_individual_creator_party_sources "
                + "(creator_party_id, account_uuid, verification_status, identity_version, "
                + "policy_reference, policy_version, verification_evidence_reference, "
                + "verification_evidence_version, source_version, source_payload, source_digest) "
                + "VALUES (?, ?, 'VERIFIED', 1, ?, 1, ?, 1, 1, ?, ?)",
            party.creatorPartyId(),
            party.accountId(),
            party.policyReference(),
            party.verificationEvidenceReference(),
            partyPayload,
            CreatorPartyEncoding.digest(partyPayload));
    return new Fixture(
        db,
        creator,
        party,
        new IndividualCreatorPartyRepository(
            db.dsl(),
            freshTenants,
            new AccountTenantCreationBootstrapOperationRepository(db.dsl())));
  }

  private static FreshTenantCreatorEvidence qualification(
      FreshTenantCreationEvidence creation, UUID account, UUID authorization) {
    return new FreshTenantCreatorEvidence(
        1,
        creation,
        account,
        authorization,
        DIGEST,
        FreshTenantCreatorDigest.evidenceDigest(1, creation, account, authorization, DIGEST));
  }

  private static IndividualCreatorPartySource party(UUID account) {
    return new IndividualCreatorPartySource(
        UUID.randomUUID(),
        account,
        VerificationStatus.VERIFIED,
        1,
        "fixture-approved-policy",
        1L,
        "fixture-verification-evidence",
        1L,
        1);
  }

  private static void insertCommittedBootstrapFixture(
      Database db, FreshTenantCreatorEvidence creator) {
    var creation = creator.creationEvidence();
    UUID authorization = creator.accountAuthorizationOperationId();
    db.dsl()
        .execute(
            "INSERT INTO account_tenant_creation_bootstrap_operations "
                + "(request_id, schema_version, initiating_account_uuid, tenant_uuid, "
                + "creation_request_id, creation_operation_id, account_authorization_operation_id, "
                + "account_authorization_digest, creator_evidence_digest, creator_evidence_payload, "
                + "source_snapshot_payload, source_snapshot_digest, request_payload, request_digest, "
                + "baseline_membership_version, baseline_membership_authority_generation, "
                + "baseline_event_sequence, status) "
                + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 'IN_PROGRESS')",
            authorization,
            creator.initiatingAccountId(),
            creation.canonicalTenantId(),
            creation.creationRequestId(),
            creation.operationId(),
            authorization,
            DIGEST,
            creator.evidenceDigest(),
            CreatorPartyEncoding.creation(creator),
            new byte[] {1},
            DIGEST,
            new byte[] {1},
            DIGEST);
    db.dsl()
        .execute(
            "UPDATE account_tenant_creation_bootstrap_operations SET membership_id = 1, "
                + "membership_version = 2, membership_authority_generation = 1, "
                + "membership_lifecycle_state = 'ACTIVE', gameplay_admission_allowed = FALSE, "
                + "membership_roles_payload = convert_to('[\"tenantAdmin\"]', 'UTF8'), "
                + "event_stream_key = ?, event_request_id = ?, event_sequence = 1, "
                + "event_id = 'fixture-event', event_digest = ?, event_payload = ?, "
                + "caller_bound_authority_invalidated = FALSE, audit_event_id = ?, "
                + "audit_event_type = 'ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED', "
                + "audit_occurred_at = CURRENT_TIMESTAMP, audit_payload_digest = ?, "
                + "audit_payload = ?, result_payload = ?, result_digest = ?, status = 'COMMITTED' "
                + "WHERE request_id = ?",
            "account:auth-authority:v1:membership/"
                + creator.initiatingAccountId()
                + "/"
                + creation.canonicalTenantId(),
            authorization.toString(),
            DIGEST,
            new byte[] {1},
            UUID.randomUUID(),
            DIGEST,
            new byte[] {1},
            new byte[] {1},
            DIGEST,
            authorization);
  }

  private static void insertRetainedHistory(Fixture fixture) {
    fixture
        .db()
        .dsl()
        .execute(
            "INSERT INTO account_tenant_creator_party_history "
                + "(history_id, tenant_uuid, origin, source_version, evidence_payload, evidence_digest) "
                + "VALUES (?, ?, 'RETAINED', 1, ?, ?)",
            UUID.randomUUID(),
            fixture.creator().creationEvidence().canonicalTenantId(),
            new byte[] {1},
            DIGEST);
  }

  private static UUID insertAccount(Database db) {
    UUID account = UUID.randomUUID();
    db.dsl()
        .execute(
            "INSERT INTO accounts (account_uuid, username, email, password_hash) "
                + "VALUES (?, ?, ?, 'fixture-hash')",
            account,
            "party-" + account.toString().substring(0, 8),
            account + "@example.com");
    return account;
  }

  private static Database database() {
    String schema = "creator_party_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    source.setSchema(schema);
    Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return new Database(
        DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES),
        new TransactionTemplate(new DataSourceTransactionManager(source)));
  }

  private static int backendPid(Database db) {
    return Objects.requireNonNull(
            Objects.requireNonNull(db.dsl().fetchOne("SELECT pg_backend_pid()"))
                .get(0, Integer.class))
        .intValue();
  }

  private static long count(Database db, String table) {
    return Objects.requireNonNull(
        Objects.requireNonNull(db.dsl().fetchOne("SELECT count(*) AS count FROM " + table))
            .get("count", Long.class));
  }

  private static <T> T tx(Database db, Supplier<T> work) {
    return db.transactions().execute(status -> work.get());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Database interleaving was not released");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Database interleaving was interrupted", exception);
    }
  }

  private static void assertDatabaseLockWait(Database db, int pid, int blocker)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var waiting = db.dsl().fetchSingle("SELECT ? = ANY(pg_blocking_pids(?))", blocker, pid);
      if (Boolean.TRUE.equals(waiting.get(0, Boolean.class))) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("Second connection did not wait on canonical tenant owner row");
  }

  private record Database(DSLContext dsl, TransactionTemplate transactions) {}

  private record Fixture(
      Database db,
      FreshTenantCreatorEvidence creator,
      IndividualCreatorPartySource party,
      IndividualCreatorPartyRepository owner) {}
}
