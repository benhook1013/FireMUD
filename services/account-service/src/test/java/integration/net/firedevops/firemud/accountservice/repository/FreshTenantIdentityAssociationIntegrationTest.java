package integration.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.client.GameDesignFreshTenantIdentityClient;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.service.FreshTenantIdentityEnrollmentService;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
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
 * PostgreSQL-only Account persistence proof. The Game Design client is mocked here: these cases
 * prove local association, claim, and transaction behavior, not authenticated mTLS owner evidence.
 */
@Testcontainers(disabledWithoutDocker = true)
class FreshTenantIdentityAssociationIntegrationTest {
  private static final String TEST_NAMESPACE = "account-service";
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final UUID RETAINED_OPERATION_ID =
      UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID RETAINED_UUID = UUID.fromString("88888888-8888-4888-8888-888888888888");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void exactRetryReadbackGenerationInitializationAndImmutabilityStayAccountLocal() {
    Fixture fixture = fixture(false);
    FreshTenantCreationEvidence evidence =
        evidence(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            REQUEST_DIGEST,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            117L,
            "new-game-tenant-117");
    FreshTenantIdentityEnrollmentService service = service(fixture, evidence);

    assertThatThrownBy(() -> fixture.repository().importVerified(evidence))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThatThrownBy(() -> fixture.repository().read(evidence.canonicalTenantId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    assertThat(
            inTransaction(
                fixture.transaction(), () -> fixture.repository().read(UUID.randomUUID())))
        .isEmpty();

    assertThat(service.enroll(evidence.creationRequestId(), evidence.requestDigest()))
        .isEqualTo(evidence);
    assertThat(service.enroll(evidence.creationRequestId(), evidence.requestDigest()))
        .isEqualTo(evidence);
    assertThat(count(fixture.setupDsl(), "account_fresh_tenant_identity_associations"))
        .isEqualTo(1L);
    assertThat(count(fixture.setupDsl(), "account_canonical_tenant_identity_claims")).isEqualTo(1L);

    ScopeState tenantState =
        inTransaction(
            fixture.transaction(),
            () -> fixture.generations().read(AuthorityScope.tenant(evidence.canonicalTenantId())));
    assertThat(tenantState.generation()).isEqualTo(1L);
    assertThat(tenantState.sourceVersion()).isEqualTo(1L);
    ScopeState advanced =
        inTransaction(
            fixture.transaction(), () -> fixture.generations().advance(tenantState, null));
    assertThat(advanced.generation()).isEqualTo(2L);
    assertThat(service.enroll(evidence.creationRequestId(), evidence.requestDigest()))
        .isEqualTo(evidence);
    assertThat(
            inTransaction(
                fixture.transaction(),
                () ->
                    fixture
                        .generations()
                        .read(AuthorityScope.tenant(evidence.canonicalTenantId()))))
        .isEqualTo(advanced);

    assertNoMembershipOrAdmissionSideEffects(fixture.setupDsl());
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "UPDATE account_fresh_tenant_identity_associations "
                            + "SET request_digest = ? WHERE canonical_tenant_id = ?",
                        "sha256:" + "f".repeat(64),
                        evidence.canonicalTenantId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "DELETE FROM account_canonical_tenant_identity_claims "
                            + "WHERE canonical_tenant_id = ?",
                        evidence.canonicalTenantId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("immutable");
    assertThatThrownBy(
            () -> fixture.setupDsl().execute("TRUNCATE account_fresh_tenant_identity_associations"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("immutable");
  }

  @Test
  void changedRequestOperationOrUuidCannotMutateAnExistingAssociation() {
    Fixture fixture = fixture(false);
    FreshTenantCreationEvidence original =
        evidence(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            REQUEST_DIGEST,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            117L,
            "new-game-tenant-117");
    inTransaction(fixture.transaction(), () -> fixture.repository().importVerified(original));

    FreshTenantCreationEvidence changedRequest =
        evidence(
            original.creationRequestId(),
            "sha256:" + "b".repeat(64),
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            UUID.fromString("55555555-5555-4555-8555-555555555555"),
            118L,
            "different-game-tenant");
    FreshTenantCreationEvidence changedOperation =
        evidence(
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            REQUEST_DIGEST,
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            original.canonicalTenantId(),
            119L,
            "another-game-tenant");
    FreshTenantCreationEvidence changedUuid =
        evidence(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            REQUEST_DIGEST,
            original.operationId(),
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            120L,
            "third-game-tenant");
    FreshTenantCreationEvidence changedSource =
        evidence(
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
            REQUEST_DIGEST,
            UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
            original.sourceGameRowId(),
            original.sourceGameTenantKey());

    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(),
                    () -> fixture.repository().importVerified(changedRequest)))
        .isInstanceOf(
            FreshTenantIdentityAssociationRepository.IdentityAssociationConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(),
                    () -> fixture.repository().importVerified(changedOperation)))
        .isInstanceOf(
            FreshTenantIdentityAssociationRepository.IdentityAssociationConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(), () -> fixture.repository().importVerified(changedUuid)))
        .isInstanceOf(
            FreshTenantIdentityAssociationRepository.IdentityAssociationConflictException.class);
    assertThatThrownBy(
            () ->
                inTransaction(
                    fixture.transaction(),
                    () -> fixture.repository().importVerified(changedSource)))
        .isInstanceOf(
            FreshTenantIdentityAssociationRepository.IdentityAssociationConflictException.class);

    assertThat(
            inTransaction(
                fixture.transaction(),
                () -> fixture.repository().read(original.canonicalTenantId())))
        .contains(original);
    assertThat(count(fixture.setupDsl(), "account_fresh_tenant_identity_associations"))
        .isEqualTo(1L);
    assertThat(count(fixture.setupDsl(), "account_canonical_tenant_identity_claims")).isEqualTo(1L);
  }

  @Test
  void generationFailureRollsBackFreshAssociationAndCanonicalClaim() {
    Fixture fixture = fixture(false);
    FreshTenantCreationEvidence evidence =
        evidence(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            REQUEST_DIGEST,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            117L,
            "new-game-tenant-117");
    GameDesignFreshTenantIdentityClient client = mock(GameDesignFreshTenantIdentityClient.class);
    when(client.resolveCreation(evidence.creationRequestId(), evidence.requestDigest()))
        .thenReturn(evidence);
    AccountAuthorityGenerationRepository failingGenerationRepository =
        mock(AccountAuthorityGenerationRepository.class);
    doThrow(new IllegalStateException("generation enrollment failure"))
        .when(failingGenerationRepository)
        .initializeTenantIfAbsent(evidence.canonicalTenantId());
    FreshTenantIdentityEnrollmentService service =
        new FreshTenantIdentityEnrollmentService(
            client, fixture.repository(), failingGenerationRepository, fixture.transaction());

    assertThatThrownBy(() -> service.enroll(evidence.creationRequestId(), evidence.requestDigest()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("generation enrollment failure");

    assertThat(
            inTransaction(
                fixture.transaction(),
                () -> fixture.repository().read(evidence.canonicalTenantId())))
        .isEmpty();
    assertThat(count(fixture.setupDsl(), "account_fresh_tenant_identity_associations")).isZero();
    assertThat(count(fixture.setupDsl(), "account_canonical_tenant_identity_claims")).isZero();
    assertThat(
            countWhere(
                fixture.setupDsl(),
                "account_authority_generations",
                "scope_kind = 'TENANT' AND tenant_uuid = ?",
                evidence.canonicalTenantId()))
        .isZero();
  }

  @Test
  void retainedAndFreshUuidClaimsConflictInEitherInsertionOrder() {
    Fixture retainedFirst = fixture(false);
    FreshTenantCreationEvidence fresh =
        evidence(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            REQUEST_DIGEST,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            RETAINED_UUID,
            117L,
            "fresh-collision-source");
    inTransaction(
        retainedFirst.transaction(),
        () -> {
          insertRetained(
              retainedFirst.transactionDsl(), 81L, RETAINED_UUID, 721L, "retained-source");
          return null;
        });
    assertThatThrownBy(
            () ->
                inTransaction(
                    retainedFirst.transaction(),
                    () -> retainedFirst.repository().importVerified(fresh)))
        .isInstanceOf(DataAccessException.class);
    assertThat(count(retainedFirst.setupDsl(), "account_fresh_tenant_identity_associations"))
        .isZero();
    assertThat(count(retainedFirst.setupDsl(), "account_approved_legacy_tenant_associations"))
        .isEqualTo(1L);

    Fixture freshFirst = fixture(false);
    FreshTenantCreationEvidence freshWinner =
        evidence(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            REQUEST_DIGEST,
            UUID.fromString("44444444-4444-4444-8444-444444444444"),
            RETAINED_UUID,
            118L,
            "fresh-winner-source");
    inTransaction(
        freshFirst.transaction(), () -> freshFirst.repository().importVerified(freshWinner));
    assertThatThrownBy(
            () ->
                inTransaction(
                    freshFirst.transaction(),
                    () -> {
                      insertRetained(
                          freshFirst.transactionDsl(), 82L, RETAINED_UUID, 722L, "retained-loser");
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);
    assertThat(count(freshFirst.setupDsl(), "account_fresh_tenant_identity_associations"))
        .isEqualTo(1L);
    assertThat(count(freshFirst.setupDsl(), "account_approved_legacy_tenant_associations"))
        .isZero();
  }

  @Test
  void concurrentRetainedAndFreshUuidClaimsSerializeOnTheSharedPrimaryKey() throws Exception {
    Fixture fixture = fixture(false);
    FreshTenantCreationEvidence fresh =
        evidence(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            REQUEST_DIGEST,
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            RETAINED_UUID,
            117L,
            "fresh-concurrent-source");
    CountDownLatch contenderHasPid = new CountDownLatch(1);
    AtomicInteger contenderPid = new AtomicInteger();
    ExecutorService executor = Executors.newSingleThreadExecutor();

    try (Connection holder = fixture.dataSource().getConnection()) {
      holder.setAutoCommit(false);
      DSLContext holderDsl = DSL.using(holder, SQLDialect.POSTGRES);
      Integer observedHolderPid =
          fetchOneValue(holderDsl, "SELECT pg_backend_pid()", Integer.class);
      if (observedHolderPid == null || observedHolderPid <= 0) {
        throw new IllegalStateException("Holder PostgreSQL backend PID is absent or invalid");
      }
      int holderPid = observedHolderPid;
      insertRetained(holderDsl, 83L, RETAINED_UUID, 723L, "retained-concurrent-source");

      Future<RuntimeException> contender =
          executor.submit(
              () -> {
                try {
                  inTransaction(
                      fixture.transaction(),
                      () -> {
                        Integer observedContenderPid =
                            fetchOneValue(
                                fixture.transactionDsl(), "SELECT pg_backend_pid()", Integer.class);
                        if (observedContenderPid == null || observedContenderPid <= 0) {
                          throw new IllegalStateException(
                              "Contender PostgreSQL backend PID is absent or invalid");
                        }
                        contenderPid.set(observedContenderPid);
                        contenderHasPid.countDown();
                        fixture.repository().importVerified(fresh);
                        return null;
                      });
                  return null;
                } catch (RuntimeException failure) {
                  return failure;
                }
              });

      assertThat(contenderHasPid.await(10, TimeUnit.SECONDS)).isTrue();
      awaitBlockedBy(fixture.setupDsl(), holderPid, contenderPid.get());
      holder.commit();

      RuntimeException failure = contender.get(10, TimeUnit.SECONDS);
      assertThat(failure).isInstanceOf(DataAccessException.class);
    } finally {
      executor.shutdownNow();
    }

    assertThat(count(fixture.setupDsl(), "account_approved_legacy_tenant_associations"))
        .isEqualTo(1L);
    assertThat(count(fixture.setupDsl(), "account_fresh_tenant_identity_associations")).isZero();
    assertThat(count(fixture.setupDsl(), "account_canonical_tenant_identity_claims")).isEqualTo(1L);
  }

  @Test
  void v38BackfillsOnlyExistingExactRetainedAssociationsAndRejectsOrphanClaims() {
    Fixture fixture = fixture(true);
    var retainedClaim =
        fixture
            .setupDsl()
            .fetchOne(
                "SELECT canonical_tenant_id, identity_kind, source_operation_id, "
                    + "source_account_legacy_tenant_id, source_target_namespace, "
                    + "source_game_row_id, source_game_tenant_key, source_evidence_digest, "
                    + "source_manifest_digest FROM account_canonical_tenant_identity_claims "
                    + "WHERE canonical_tenant_id = ?",
                RETAINED_UUID);
    assertThat(retainedClaim).isNotNull();
    assertThat(retainedClaim.get("canonical_tenant_id", UUID.class)).isEqualTo(RETAINED_UUID);
    assertThat(retainedClaim.get("identity_kind", String.class)).isEqualTo("APPROVED_RETAINED");
    assertThat(retainedClaim.get("source_operation_id", UUID.class))
        .isEqualTo(RETAINED_OPERATION_ID);
    assertThat(retainedClaim.get("source_account_legacy_tenant_id", Long.class)).isEqualTo(80L);
    assertThat(retainedClaim.get("source_target_namespace", String.class))
        .isEqualTo(TEST_NAMESPACE);
    assertThat(retainedClaim.get("source_game_row_id", Long.class)).isEqualTo(720L);
    assertThat(retainedClaim.get("source_game_tenant_key", String.class))
        .isEqualTo("retained-backfill-source");
    assertThat(retainedClaim.get("source_evidence_digest", String.class))
        .isEqualTo("sha256:" + "c".repeat(64));
    assertThat(retainedClaim.get("source_manifest_digest", String.class))
        .isEqualTo("sha256:" + "d".repeat(64));
    assertThat(
            fetchOneValue(
                fixture.setupDsl(),
                "SELECT source_legacy_game_tenant_id FROM "
                    + "account_approved_legacy_tenant_associations WHERE canonical_tenant_id = ?",
                String.class,
                RETAINED_UUID))
        .isEqualTo("retained-backfill-source");
    assertThat(count(fixture.setupDsl(), "account_canonical_tenant_identity_claims")).isEqualTo(1L);

    assertThatThrownBy(
            () ->
                fixture
                    .setupDsl()
                    .execute(
                        "INSERT INTO account_canonical_tenant_identity_claims "
                            + "(canonical_tenant_id, identity_kind, source_operation_id, "
                            + "source_target_namespace, source_creation_request_id, "
                            + "source_request_digest, source_game_row_id, source_game_tenant_key, "
                            + "source_provenance_kind, source_evidence_digest) "
                            + "VALUES (?, 'FRESH_GAME_DESIGN', ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', ?) ",
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                        UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                        TEST_NAMESPACE,
                        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        REQUEST_DIGEST,
                        990L,
                        "orphan-tenant-key",
                        "sha256:" + "d".repeat(64)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("no exact source association");
  }

  private static Fixture fixture(boolean preexistingRetainedAssociation) {
    String schema = "fresh_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);

    if (preexistingRetainedAssociation) {
      flyway(dataSource, schema, "37").migrate();
      DSLContext beforeV38 = DSL.using(dataSource, SQLDialect.POSTGRES);
      insertRetained(beforeV38, 80L, RETAINED_UUID, 720L, "retained-backfill-source");
    }
    flyway(dataSource, schema, null).migrate();

    DSLContext setupDsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    assertThat(fetchOneValue(setupDsl, "SELECT current_schema()", String.class)).isEqualTo(schema);
    DSLContext transactionDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    FreshTenantIdentityAssociationRepository repository =
        new FreshTenantIdentityAssociationRepository(transactionDsl, TEST_NAMESPACE);
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(transactionDsl);
    return new Fixture(dataSource, setupDsl, transactionDsl, transaction, repository, generations);
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (targetVersion != null) {
      configuration.target(targetVersion);
    }
    return configuration.load();
  }

  private static FreshTenantIdentityEnrollmentService service(
      Fixture fixture, FreshTenantCreationEvidence evidence) {
    GameDesignFreshTenantIdentityClient client = mock(GameDesignFreshTenantIdentityClient.class);
    when(client.resolveCreation(evidence.creationRequestId(), evidence.requestDigest()))
        .thenReturn(evidence);
    return new FreshTenantIdentityEnrollmentService(
        client, fixture.repository(), fixture.generations(), fixture.transaction());
  }

  private static FreshTenantCreationEvidence evidence(
      UUID requestId,
      String requestDigest,
      UUID operationId,
      UUID canonicalTenantId,
      long sourceRowId,
      String sourceKey) {
    String receiptDigest =
        GameTenantCreationDigest.evidenceDigest(
            TEST_NAMESPACE,
            requestId,
            operationId,
            requestDigest,
            canonicalTenantId,
            sourceRowId,
            sourceKey,
            "NEW_GAME_ROW");
    return new FreshTenantCreationEvidence(
        1,
        TEST_NAMESPACE,
        requestId,
        operationId,
        requestDigest,
        canonicalTenantId,
        sourceRowId,
        sourceKey,
        "NEW_GAME_ROW",
        receiptDigest);
  }

  private static void insertRetained(
      DSLContext dsl,
      long legacyTenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey) {
    dsl.execute(
        "INSERT INTO account_approved_legacy_tenant_associations "
            + "(legacy_tenant_id, canonical_tenant_id, source_legacy_game_tenant_id, "
            + "source_game_row_id, account_evidence_digest, operation_id, manifest_digest, "
            + "manifest_signature, target_namespace, signer_key_id, approved_by, "
            + "approval_reference, signed_at, operation_entry_count, manifest_schema_version) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)",
        legacyTenantId,
        canonicalTenantId,
        sourceGameTenantKey,
        sourceGameRowId,
        "sha256:" + "c".repeat(64),
        RETAINED_OPERATION_ID,
        "sha256:" + "d".repeat(64),
        Base64.getEncoder().encodeToString(new byte[64]),
        TEST_NAMESPACE,
        "retained-signing-key",
        "owner@example.test",
        "reviewed-retained-import",
        "2026-09-30T00:00:00Z");
  }

  private static void assertNoMembershipOrAdmissionSideEffects(DSLContext dsl) {
    assertThat(count(dsl, "account_tenant_membership")).isZero();
    assertThat(count(dsl, "profiles")).isZero();
    assertThat(count(dsl, "account_membership_pair_authority")).isZero();
    assertThat(count(dsl, "account_membership_transition_receipts")).isZero();
    assertThat(count(dsl, "account_authority_outbox_events")).isZero();
    assertThat(count(dsl, "account_join_operations")).isZero();
    assertThat(count(dsl, "account_connect_token_issuance_operations")).isZero();
    assertThat(count(dsl, "account_connect_token_response_envelopes")).isZero();
    assertThat(count(dsl, "account_bare_login_exchange_operations")).isZero();
    assertThat(count(dsl, "account_approved_legacy_tenant_associations")).isZero();
  }

  private static void awaitBlockedBy(DSLContext dsl, int blockerPid, int blockedPid)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      Boolean blocked =
          fetchOneValue(
              dsl,
              "SELECT ? = ANY(pg_blocking_pids(pid)) FROM pg_stat_activity WHERE pid = ?",
              Boolean.class,
              blockerPid,
              blockedPid);
      if (Boolean.TRUE.equals(blocked)) {
        return;
      }
      Thread.yield();
    }
    throw new AssertionError("Contender PostgreSQL backend was not observed blocked by the holder");
  }

  private static long count(DSLContext dsl, String table) {
    Long count = fetchOneValue(dsl, "SELECT count(*) FROM " + table, Long.class);
    if (count == null || count < 0L) {
      throw new IllegalStateException("PostgreSQL aggregate count is absent or negative");
    }
    return count;
  }

  private static long countWhere(DSLContext dsl, String table, String predicate, Object... values) {
    Long count =
        fetchOneValue(
            dsl, "SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class, values);
    if (count == null || count < 0L) {
      throw new IllegalStateException("PostgreSQL aggregate count is absent or negative");
    }
    return count;
  }

  private static <T> T fetchOneValue(
      DSLContext dsl, String query, Class<T> valueType, Object... bindings) {
    Record row = dsl.fetchOne(query, bindings);
    return row == null ? null : row.get(0, valueType);
  }

  private static <T> T inTransaction(
      TransactionTemplate transaction, java.util.function.Supplier<T> work) {
    return transaction.execute(status -> work.get());
  }

  private record Fixture(
      DriverManagerDataSource dataSource,
      DSLContext setupDsl,
      DSLContext transactionDsl,
      TransactionTemplate transaction,
      FreshTenantIdentityAssociationRepository repository,
      AccountAuthorityGenerationRepository generations) {}
}
