package integration.net.firedevops.firemud.accountservice.creatorparty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
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

/**
 * Real owner-row and PostgreSQL guard definitions. Verification/policy and original V64 creation
 * qualification are explicit SQL fixtures, not authentication, legal provisioning or launch proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class IndividualCreatorPartyPostgresIntegrationTest {
  private static final String NAMESPACE = "creator-party-proof";
  private static final String DIGEST = "sha256:" + "1".repeat(64);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void migrationPreservesExistingAccountAndMembershipAndEnrollsNoParty() throws Exception {
    // V74 is this source tree's last existing migration before V78; V77 is reserved elsewhere
    // and is not a historical schema that this candidate can execute or prove.
    Database db = database("74");
    UUID account = insertAccount(db);
    long accountKey =
        Objects.requireNonNull(
            Objects.requireNonNull(
                    db.dsl().fetchOne("SELECT id FROM accounts WHERE account_uuid = ?", account),
                    "Persisted baseline Account row is missing")
                .get("id", Long.class),
            "Persisted baseline Account key is missing");
    Map<String, Object> accountBefore =
        Objects.requireNonNull(
                db.dsl().fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", account),
                "Baseline Account row is missing")
            .intoMap();
    db.dsl()
        .execute(
            "INSERT INTO account_tenant_membership "
                + "(account_id, tenant_id, gameplay_admission_allowed, lifecycle_state, "
                + "membership_version, membership_authority_generation, authority_provenance) "
                + "VALUES (?, 42, FALSE, 'LEGACY_UNVERIFIED', 1, 1, 'LEGACY_UNVERIFIED')",
            accountKey);
    Map<String, Object> before =
        Objects.requireNonNull(
                db.dsl()
                    .fetchOne(
                        "SELECT * FROM account_tenant_membership WHERE account_id = ?", accountKey),
                "Baseline membership row is missing")
            .intoMap();
    migrate(db, "78");
    assertThat(
            Objects.requireNonNull(
                    db.dsl().fetchOne("SELECT * FROM accounts WHERE account_uuid = ?", account),
                    "Preserved Account row is missing after migration")
                .intoMap())
        .isEqualTo(accountBefore);
    assertThat(
            Objects.requireNonNull(
                    db.dsl()
                        .fetchOne(
                            "SELECT * FROM account_tenant_membership WHERE account_id = ?",
                            accountKey),
                    "Preserved membership row is missing after migration")
                .intoMap())
        .isEqualTo(before);
    assertThat(count(db, "account_individual_creator_party_sources")).isZero();
    assertThat(count(db, "account_tenant_creator_party_history")).isZero();
    assertThat(count(db, "account_fresh_creator_party_association_operations")).isZero();
  }

  @Test
  void committedAssociationReadsExactSourcesAndRetriesWithoutAnotherResult() throws Exception {
    Fixture fixture = fixture();
    UUID request = UUID.randomUUID();
    var original =
        tx(
            fixture.db(),
            () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party()));
    assertThat(
            tx(
                fixture.db(),
                () ->
                    fixture
                        .owner()
                        .readInitialAssociation(request, fixture.creator(), fixture.party())))
        .isEqualTo(original);
    assertThat(
            tx(
                fixture.db(),
                () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party())))
        .isEqualTo(original);
    assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
    assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations"))
        .isEqualTo(1);
    assertThatThrownBy(() -> fixture.owner().requireHostedAuthoringCurrentness())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("hosted terms");
  }

  @Test
  void changedPartyCreationAndOperationCannotRewriteAnOriginal() throws Exception {
    Fixture fixture = fixture();
    UUID request = UUID.randomUUID();
    tx(
        fixture.db(),
        () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party()));
    IndividualCreatorPartySource other =
        party(fixture.creator().initiatingAccountId(), VerificationStatus.VERIFIED);
    insertParty(fixture.db(), other);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () -> fixture.owner().associateFresh(request, fixture.creator(), other)))
        .isInstanceOf(AssociationConflictException.class);
    FreshTenantCreatorEvidence changed =
        qualification(
            fixture.creator().creationEvidence(),
            fixture.creator().initiatingAccountId(),
            UUID.randomUUID());
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () -> fixture.owner().associateFresh(request, changed, fixture.party())))
        .isInstanceOf(AssociationConflictException.class);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), fixture.party())))
        .isInstanceOf(AssociationConflictException.class);
    assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
  }

  @Test
  void unverifiedMissingPolicyForeignPartyAndMissingCreatorProofWriteNothing() throws Exception {
    Fixture fixture = fixture();
    IndividualCreatorPartySource unverified =
        party(fixture.creator().initiatingAccountId(), VerificationStatus.UNVERIFIED);
    insertParty(fixture.db(), unverified);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), unverified)))
        .isInstanceOf(IllegalStateException.class);
    IndividualCreatorPartySource unsupported =
        party(fixture.creator().initiatingAccountId(), VerificationStatus.UNSUPPORTED);
    insertParty(fixture.db(), unsupported);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), unsupported)))
        .isInstanceOf(IllegalStateException.class);
    IndividualCreatorPartySource foreign =
        party(insertAccount(fixture.db()), VerificationStatus.VERIFIED);
    insertParty(fixture.db(), foreign);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), foreign)))
        .isInstanceOf(IllegalArgumentException.class);
    FreshTenantCreatorEvidence absent =
        qualification(
            fixture.creator().creationEvidence(),
            fixture.creator().initiatingAccountId(),
            UUID.randomUUID());
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture.owner().associateFresh(UUID.randomUUID(), absent, fixture.party())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("qualification is absent");
    IndividualCreatorPartySource missing =
        party(fixture.creator().initiatingAccountId(), VerificationStatus.VERIFIED);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), missing)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source is absent");
    IndividualCreatorPartySource changedProof =
        new IndividualCreatorPartySource(
            fixture.party().creatorPartyId(),
            fixture.party().accountId(),
            VerificationStatus.VERIFIED,
            1,
            "fixture-approved-policy",
            2L,
            "fixture-verified-evidence",
            1L,
            1);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), changedProof)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source is absent");
    assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isZero();
    assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations")).isZero();
  }

  @Test
  void priorRetainedAndTombstonedHistoryCannotBeTreatedAsAbsent() throws Exception {
    for (String origin : new String[] {"RETAINED", "TOMBSTONE"}) {
      Fixture fixture = fixture();
      insertHistory(fixture, origin, 1);
      assertThatThrownBy(
              () ->
                  tx(
                      fixture.db(),
                      () ->
                          fixture
                              .owner()
                              .associateFresh(
                                  UUID.randomUUID(), fixture.creator(), fixture.party())))
          .isInstanceOf(AssociationConflictException.class);
      assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
      assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations"))
          .isZero();
    }
  }

  @Test
  void historicalReplaySurvivesLaterTombstoneButCurrentInitialReadDenies() throws Exception {
    Fixture fixture = fixture();
    UUID request = UUID.randomUUID();
    var original =
        tx(
            fixture.db(),
            () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party()));
    insertHistory(fixture, "TOMBSTONE", 2);
    assertThat(
            tx(
                fixture.db(),
                () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party())))
        .isEqualTo(original);
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () ->
                        fixture
                            .owner()
                            .readInitialAssociation(request, fixture.creator(), fixture.party())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("later party history");
  }

  @Test
  void concurrentFirstAssociationsHaveOneCommittedWinner() throws Exception {
    Fixture fixture = fixture();
    IndividualCreatorPartySource secondParty =
        party(fixture.creator().initiatingAccountId(), VerificationStatus.VERIFIED);
    insertParty(fixture.db(), secondParty);
    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    AtomicInteger secondPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> winner =
          executor.submit(
              () ->
                  tx(
                      fixture.db(),
                      () -> {
                        fixture
                            .db()
                            .dsl()
                            .fetchOne(
                                "SELECT canonical_tenant_id FROM account_canonical_tenant_identity_claims WHERE canonical_tenant_id = ? FOR UPDATE",
                                fixture.creator().creationEvidence().canonicalTenantId());
                        locked.countDown();
                        await(release);
                        return fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), fixture.party());
                      }));
      assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> loser =
          executor.submit(
              () -> {
                return tx(
                    fixture.db(),
                    () -> {
                      secondPid.set(
                          Objects.requireNonNull(
                              Objects.requireNonNull(
                                      fixture.db().dsl().fetchOne("SELECT pg_backend_pid()"),
                                      "Competing fresh backend identity readback is missing")
                                  .get(0, Integer.class),
                              "Competing fresh backend PID is missing"));
                      secondStarted.countDown();
                      return fixture
                          .owner()
                          .associateFresh(UUID.randomUUID(), fixture.creator(), secondParty);
                    });
              });
      assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertDatabaseLockWait(fixture.db(), secondPid.get());
      release.countDown();
      winner.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AssociationConflictException.class);
      assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations"))
          .isEqualTo(1);
      assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void retainedHistoryCommitWinsAgainstWaitingFreshAssociation() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch retainedInserted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch freshStarted = new CountDownLatch(1);
    AtomicInteger freshPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> retained =
          executor.submit(
              () ->
                  tx(
                      fixture.db(),
                      () -> {
                        insertHistory(fixture, "RETAINED", 1);
                        retainedInserted.countDown();
                        await(release);
                        return null;
                      }));
      assertThat(retainedInserted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<?> fresh =
          executor.submit(
              () ->
                  tx(
                      fixture.db(),
                      () -> {
                        freshPid.set(
                            Objects.requireNonNull(
                                Objects.requireNonNull(
                                        fixture.db().dsl().fetchOne("SELECT pg_backend_pid()"),
                                        "Waiting fresh backend identity readback is missing")
                                    .get(0, Integer.class),
                                "Waiting fresh backend PID is missing"));
                        freshStarted.countDown();
                        return fixture
                            .owner()
                            .associateFresh(UUID.randomUUID(), fixture.creator(), fixture.party());
                      }));
      assertThat(freshStarted.await(10, TimeUnit.SECONDS)).isTrue();
      assertDatabaseLockWait(fixture.db(), freshPid.get());
      release.countDown();
      retained.get(10, TimeUnit.SECONDS);
      assertThatThrownBy(() -> fresh.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(AssociationConflictException.class);
      assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isEqualTo(1);
      assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations"))
          .isZero();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void commitBoundaryRejectsHistoryAddedAfterInitialAssociationInSameTransaction()
      throws Exception {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                tx(
                    fixture.db(),
                    () -> {
                      fixture
                          .owner()
                          .associateFresh(UUID.randomUUID(), fixture.creator(), fixture.party());
                      insertHistory(fixture, "RETAINED", 2);
                      return null;
                    }))
        .hasStackTraceContaining("exact immutable owner receipt");
    assertThat(count(fixture.db(), "account_tenant_creator_party_history")).isZero();
    assertThat(count(fixture.db(), "account_fresh_creator_party_association_operations")).isZero();
  }

  @Test
  void databaseRejectsSourceAndReceiptRewritesAndOrphanInitialHistory() throws Exception {
    Fixture fixture = fixture();
    UUID request = UUID.randomUUID();
    tx(
        fixture.db(),
        () -> fixture.owner().associateFresh(request, fixture.creator(), fixture.party()));
    assertThatThrownBy(
            () ->
                fixture
                    .db()
                    .dsl()
                    .execute(
                        "UPDATE account_individual_creator_party_sources SET verification_status = 'UNVERIFIED' WHERE creator_party_id = ?",
                        fixture.party().creatorPartyId()))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .db()
                    .dsl()
                    .execute(
                        "DELETE FROM account_fresh_creator_party_association_operations WHERE request_id = ?",
                        request))
        .hasMessageContaining("immutable");
    Fixture empty = fixture();
    assertThatThrownBy(
            () ->
                tx(
                    empty.db(),
                    () -> {
                      empty
                          .db()
                          .dsl()
                          .execute(
                              "INSERT INTO account_tenant_creator_party_history (history_id, tenant_uuid, origin, creator_party_id, source_version, evidence_payload, evidence_digest) VALUES (?, ?, 'FRESH_INITIAL', ?, 1, ?, ?)",
                              UUID.randomUUID(),
                              empty.creator().creationEvidence().canonicalTenantId(),
                              empty.party().creatorPartyId(),
                              new byte[] {1},
                              DIGEST);
                      return null;
                    }))
        .hasStackTraceContaining("exact immutable owner receipt");
    assertThat(count(empty.db(), "account_tenant_creator_party_history")).isZero();
  }

  private static Fixture fixture() throws Exception {
    Database db = database("78");
    UUID account = insertAccount(db);
    UUID request = UUID.randomUUID();
    UUID operation = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    FreshTenantCreationEvidence creation =
        new FreshTenantCreationEvidence(
            1,
            NAMESPACE,
            request,
            operation,
            DIGEST,
            tenant,
            1,
            "party-game",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                NAMESPACE, request, operation, DIGEST, tenant, 1, "party-game", "NEW_GAME_ROW"));
    FreshTenantCreatorEvidence creator = qualification(creation, account, UUID.randomUUID());
    FreshTenantIdentityAssociationRepository fresh =
        new FreshTenantIdentityAssociationRepository(db.dsl(), NAMESPACE);
    tx(db, () -> fresh.importVerified(creation));
    insertBootstrapFixture(db, creator);
    IndividualCreatorPartySource party = party(account, VerificationStatus.VERIFIED);
    insertParty(db, party);
    return new Fixture(
        db,
        creator,
        party,
        new IndividualCreatorPartyRepository(
            db.dsl(), fresh, new AccountTenantCreationBootstrapOperationRepository(db.dsl())));
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

  private static IndividualCreatorPartySource party(UUID account, VerificationStatus status) {
    return new IndividualCreatorPartySource(
        UUID.randomUUID(),
        account,
        status,
        1,
        status == VerificationStatus.VERIFIED ? "fixture-approved-policy" : null,
        status == VerificationStatus.VERIFIED ? 1L : null,
        status == VerificationStatus.VERIFIED ? "fixture-verified-evidence" : null,
        status == VerificationStatus.VERIFIED ? 1L : null,
        1);
  }

  private static void insertParty(Database db, IndividualCreatorPartySource party) {
    byte[] payload = CreatorPartyEncoding.party(party);
    db.dsl()
        .execute(
            "INSERT INTO account_individual_creator_party_sources (creator_party_id, account_uuid, verification_status, identity_version, policy_reference, policy_version, verification_evidence_reference, verification_evidence_version, source_version, source_payload, source_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            party.creatorPartyId(),
            party.accountId(),
            party.verificationStatus().name(),
            party.identityVersion(),
            party.policyReference(),
            party.policyVersion(),
            party.verificationEvidenceReference(),
            party.verificationEvidenceVersion(),
            party.sourceVersion(),
            payload,
            CreatorPartyEncoding.digest(payload));
  }

  private static void insertHistory(Fixture fixture, String origin, long version) {
    fixture
        .db()
        .dsl()
        .execute(
            "INSERT INTO account_tenant_creator_party_history (history_id, tenant_uuid, origin, source_version, evidence_payload, evidence_digest) VALUES (?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(),
            fixture.creator().creationEvidence().canonicalTenantId(),
            origin,
            version,
            new byte[] {1},
            DIGEST);
  }

  private static void insertBootstrapFixture(Database db, FreshTenantCreatorEvidence creator) {
    // Stipulated original qualification, using the real guarded V64 lifecycle; no guard is
    // disabled.
    var creation = creator.creationEvidence();
    UUID authorization = creator.accountAuthorizationOperationId();
    db.dsl()
        .execute(
            "INSERT INTO account_tenant_creation_bootstrap_operations (request_id, schema_version, initiating_account_uuid, tenant_uuid, creation_request_id, creation_operation_id, account_authorization_operation_id, account_authorization_digest, creator_evidence_digest, creator_evidence_payload, source_snapshot_payload, source_snapshot_digest, request_payload, request_digest, baseline_membership_version, baseline_membership_authority_generation, baseline_event_sequence, status) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, 0, 'IN_PROGRESS')",
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
            "UPDATE account_tenant_creation_bootstrap_operations SET membership_id = 1, membership_version = 2, membership_authority_generation = 1, membership_lifecycle_state = 'ACTIVE', gameplay_admission_allowed = FALSE, membership_roles_payload = convert_to('[\"tenantAdmin\"]', 'UTF8'), event_stream_key = ?, event_request_id = ?, event_sequence = 1, event_id = 'fixture-event', event_digest = ?, event_payload = ?, caller_bound_authority_invalidated = FALSE, audit_event_id = ?, audit_event_type = 'ACCOUNT_TENANT_CREATOR_BOOTSTRAPPED', audit_occurred_at = CURRENT_TIMESTAMP, audit_payload_digest = ?, audit_payload = ?, result_payload = ?, result_digest = ?, status = 'COMMITTED' WHERE request_id = ?",
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

  private static UUID insertAccount(Database db) {
    UUID account = UUID.randomUUID();
    db.dsl()
        .execute(
            "INSERT INTO accounts (account_uuid, username, email, password_hash) VALUES (?, ?, ?, 'fixture-hash')",
            account,
            "party-" + account.toString().substring(0, 8),
            account + "@example.com");
    return account;
  }

  private static Database database(String target) throws Exception {
    String schema = "creator_party_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            postgres.getJdbcUrl()
                + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema="
                + schema,
            postgres.getUsername(),
            postgres.getPassword());
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
    Database db =
        new Database(
            schema,
            source,
            DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES),
            new TransactionTemplate(new DataSourceTransactionManager(source)));
    migrate(db, target);
    return db;
  }

  private static void migrate(Database db, String target) {
    Flyway.configure()
        .dataSource(db.source())
        .schemas(db.schema())
        .defaultSchema(db.schema())
        .placeholders(Map.of("serviceSchema", db.schema()))
        .locations("classpath:db/migration")
        .target(target)
        .load()
        .migrate();
  }

  private static long count(Database db, String table) {
    return Objects.requireNonNull(
        Objects.requireNonNull(
                db.dsl().fetchOne("SELECT count(*) AS count FROM " + table),
                "Owner table count readback is missing")
            .get("count", Long.class),
        "Owner table count is missing");
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

  private static void assertDatabaseLockWait(Database db, int pid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      var activity =
          db.dsl().fetchOne("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", pid);
      if (activity != null && "Lock".equals(activity.get("wait_event_type", String.class))) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("Second connection did not wait on the canonical tenant owner row");
  }

  private record Database(
      String schema,
      DriverManagerDataSource source,
      DSLContext dsl,
      TransactionTemplate transactions) {}

  private record Fixture(
      Database db,
      FreshTenantCreatorEvidence creator,
      IndividualCreatorPartySource party,
      IndividualCreatorPartyRepository owner) {}
}
