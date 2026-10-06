package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreatorEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
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

@Testcontainers(disabledWithoutDocker = true)
class GameTenantCreationRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> OPERATIONS = DSL.table(DSL.name("game_tenant_creation_operations"));
  private static final Table<?> CREATOR_QUALIFICATIONS =
      DSL.table(DSL.name("game_tenant_creation_creator_qualifications"));
  private static final org.jooq.Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final org.jooq.Field<UUID> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final org.jooq.Field<Integer> SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final org.jooq.Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final org.jooq.Field<UUID> CREATION_REQUEST_ID =
      DSL.field(DSL.name("creation_request_id"), UUID.class);
  private static final org.jooq.Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final org.jooq.Field<String> OPERATION_EVIDENCE_DIGEST =
      DSL.field(DSL.name("evidence_digest"), String.class);
  private static final org.jooq.Field<String> SOURCE_GAME_TENANT_KEY =
      DSL.field(DSL.name("source_game_tenant_key"), String.class);
  private static final org.jooq.Field<String> NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final org.jooq.Field<String> STATUS = DSL.field(DSL.name("status"), String.class);
  private static final org.jooq.Field<Boolean> CREATOR_QUALIFICATION_REQUIRED =
      DSL.field(DSL.name("creator_qualification_required"), Boolean.class);
  private static final org.jooq.Field<UUID> CREATOR_QUALIFICATION_OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);
  private static final org.jooq.Field<String> ACCOUNT_AUTHORIZATION_DIGEST =
      DSL.field(DSL.name("account_authorization_digest"), String.class);
  private static final org.jooq.Field<Integer> CREATOR_QUALIFICATION_SCHEMA_VERSION =
      DSL.field(DSL.name("schema_version"), Integer.class);
  private static final org.jooq.Field<UUID> INITIATING_ACCOUNT_ID_FIELD =
      DSL.field(DSL.name("initiating_account_id"), UUID.class);
  private static final org.jooq.Field<UUID> ACCOUNT_AUTHORIZATION_OPERATION_ID =
      DSL.field(DSL.name("account_authorization_operation_id"), UUID.class);
  private static final org.jooq.Field<String> CREATOR_EVIDENCE_DIGEST =
      DSL.field(DSL.name("evidence_digest"), String.class);
  private static final UUID INITIATING_ACCOUNT_ID =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID AUTHORIZATION_OPERATION_ID =
      UUID.fromString("66666666-6666-4666-8666-666666666666");
  private static final String AUTHORIZATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String NAMESPACE = "fresh-tenant-test";
  private static final String SOURCE_KEY = "new-game-tenant-01";
  private static final UUID REQUEST_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void createCandidateExactRetryReturnsCommittedReceiptWithoutSecondGameWrite() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence first =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));
    String firstGameXmin = gameXmin(fixture.dsl, SOURCE_KEY);

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(first);
    FreshTenantCreationEvidence exactRetry =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    assertThat(exactRetry).isEqualTo(first);
    assertThat(gameXmin(fixture.dsl, SOURCE_KEY)).isEqualTo(firstGameXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void qualifiedCreationCommitsSourceAndCreatorEvidenceAtomicallyAndReadsBothBack()
      throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreatorEvidence created =
        fixture.inTransaction(
            () ->
                createQualified(
                    fixture,
                    INITIATING_ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST));

    FreshTenantCreationEvidence sourceEvidence =
        fixture.repository.read(REQUEST_ID, NAMESPACE).orElseThrow();
    FreshTenantCreatorEvidence committedReadback =
        fixture
            .repository
            .readCreatorQualification(
                REQUEST_ID,
                NAMESPACE,
                sourceEvidence.requestDigest(),
                sourceEvidence.evidenceDigest(),
                INITIATING_ACCOUNT_ID,
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST,
                created.evidenceDigest())
            .orElseThrow();

    assertThat(committedReadback).isEqualTo(created);
    assertThat(committedReadback.creationEvidence()).isEqualTo(sourceEvidence);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(CREATOR_QUALIFICATIONS)).isEqualTo(1);
    assertThat(
            fixture
                .dsl
                .select(REQUEST_DIGEST)
                .from(OPERATIONS)
                .where(CREATION_REQUEST_ID.eq(REQUEST_ID))
                .fetchOne(REQUEST_DIGEST))
        .isEqualTo(sourceEvidence.requestDigest());
    assertThat(
            fixture
                .dsl
                .select(OPERATION_EVIDENCE_DIGEST)
                .from(OPERATIONS)
                .where(CREATION_REQUEST_ID.eq(REQUEST_ID))
                .fetchOne(OPERATION_EVIDENCE_DIGEST))
        .isEqualTo(sourceEvidence.evidenceDigest());
  }

  @Test
  void qualifiedRetryWithChangedInitiatorOrAuthorizationConflictsBeforeMutation() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreatorEvidence first =
        fixture.inTransaction(
            () ->
                createQualified(
                    fixture,
                    INITIATING_ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST));
    String originalGameXmin = gameXmin(fixture.dsl, SOURCE_KEY);

    assertThat(
            fixture.inTransaction(
                () ->
                    createQualified(
                        fixture,
                        INITIATING_ACCOUNT_ID,
                        AUTHORIZATION_OPERATION_ID,
                        AUTHORIZATION_DIGEST)))
        .isEqualTo(first);
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        createQualified(
                            fixture,
                            UUID.fromString("77777777-7777-4777-8777-777777777777"),
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("changed Account authority");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        createQualified(
                            fixture,
                            INITIATING_ACCOUNT_ID,
                            UUID.fromString("88888888-8888-4888-8888-888888888888"),
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("changed Account authority");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        createQualified(
                            fixture,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            "sha256:" + "b".repeat(64))))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("changed Account authority");

    assertThat(gameXmin(fixture.dsl, SOURCE_KEY)).isEqualTo(originalGameXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(CREATOR_QUALIFICATIONS)).isEqualTo(1);
  }

  @Test
  void sourceOnlyOperationCannotReceiveCreatorQualificationRetroactively() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence sourceOnly =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));
    String originalGameXmin = gameXmin(fixture.dsl, SOURCE_KEY);

    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        createQualified(
                            fixture,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("cannot be attached to an existing source-only operation");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .insertInto(CREATOR_QUALIFICATIONS)
                    .set(CREATOR_QUALIFICATION_OPERATION_ID, sourceOnly.operationId())
                    .set(CREATOR_QUALIFICATION_SCHEMA_VERSION, 1)
                    .set(INITIATING_ACCOUNT_ID_FIELD, INITIATING_ACCOUNT_ID)
                    .set(ACCOUNT_AUTHORIZATION_OPERATION_ID, AUTHORIZATION_OPERATION_ID)
                    .set(ACCOUNT_AUTHORIZATION_DIGEST, AUTHORIZATION_DIGEST)
                    .set(CREATOR_EVIDENCE_DIGEST, "sha256:" + "b".repeat(64))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot attach to source-only");

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(sourceOnly);
    assertThat(gameXmin(fixture.dsl, SOURCE_KEY)).isEqualTo(originalGameXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(CREATOR_QUALIFICATIONS)).isZero();
  }

  @Test
  void creatorQualificationEvidenceCannotBeUpdatedDeletedOrTruncated() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreatorEvidence created =
        fixture.inTransaction(
            () ->
                createQualified(
                    fixture,
                    INITIATING_ACCOUNT_ID,
                    AUTHORIZATION_OPERATION_ID,
                    AUTHORIZATION_DIGEST));

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(CREATOR_QUALIFICATIONS)
                    .set(ACCOUNT_AUTHORIZATION_DIGEST, "sha256:" + "b".repeat(64))
                    .where(
                        CREATOR_QUALIFICATION_OPERATION_ID.eq(
                            created.creationEvidence().operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .deleteFrom(CREATOR_QUALIFICATIONS)
                    .where(
                        CREATOR_QUALIFICATION_OPERATION_ID.eq(
                            created.creationEvidence().operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE TABLE game_tenant_creation_creator_qualifications"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot be truncated");

    assertThat(
            fixture.repository.readCreatorQualification(
                REQUEST_ID,
                NAMESPACE,
                created.creationEvidence().requestDigest(),
                created.creationEvidence().evidenceDigest(),
                INITIATING_ACCOUNT_ID,
                AUTHORIZATION_OPERATION_ID,
                AUTHORIZATION_DIGEST,
                created.evidenceDigest()))
        .contains(created);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(CREATOR_QUALIFICATIONS)).isEqualTo(1);
  }

  @Test
  void creatorQualificationRequiredOperationCannotCommitWithoutItsQualifier() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence sourceOnly =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "The First World", "A description"));

    try (Connection connection = fixture.dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "ALTER TABLE game_tenant_creation_operations "
              + "DISABLE TRIGGER game_tenant_creation_operation_immutable");
      try {
        assertThatThrownBy(
                () ->
                    fixture.transactionTemplate.execute(
                        status ->
                            fixture
                                .dsl
                                .update(OPERATIONS)
                                .set(CREATOR_QUALIFICATION_REQUIRED, true)
                                .where(OPERATION_ID.eq(sourceOnly.operationId()))
                                .execute()))
            .isInstanceOf(RuntimeException.class)
            .hasStackTraceContaining("without creator evidence");
      } finally {
        statement.execute(
            "ALTER TABLE game_tenant_creation_operations "
                + "ENABLE TRIGGER game_tenant_creation_operation_immutable");
      }
    }
    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(sourceOnly);
    assertThat(fixture.dsl.fetchCount(CREATOR_QUALIFICATIONS)).isZero();
  }

  @Test
  void concurrentQualifiedRetriesWithSameBindingConvergeOnOneSourceAndQualifier() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch firstOperationCreated = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    CountDownLatch secondOperationStarted = new CountDownLatch(1);
    AtomicInteger firstBackendPid = new AtomicInteger();
    AtomicInteger secondBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FreshTenantCreatorEvidence> first =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        FreshTenantCreatorEvidence evidence =
                            createQualified(
                                fixture,
                                INITIATING_ACCOUNT_ID,
                                AUTHORIZATION_OPERATION_ID,
                                AUTHORIZATION_DIGEST);
                        firstBackendPid.set(currentBackendPid(fixture.dsl));
                        firstOperationCreated.countDown();
                        awaitLatch(allowFirstCommit);
                        return evidence;
                      }));
      assertThat(firstOperationCreated.await(10, TimeUnit.SECONDS)).isTrue();
      Future<FreshTenantCreatorEvidence> second =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        secondBackendPid.set(currentBackendPid(fixture.dsl));
                        secondOperationStarted.countDown();
                        return createQualified(
                            fixture,
                            INITIATING_ACCOUNT_ID,
                            AUTHORIZATION_OPERATION_ID,
                            AUTHORIZATION_DIGEST);
                      }));
      assertThat(secondOperationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseBlocking(fixture.dataSource, secondBackendPid.get(), firstBackendPid.get());
      allowFirstCommit.countDown();

      FreshTenantCreatorEvidence firstEvidence = first.get(10, TimeUnit.SECONDS);
      FreshTenantCreatorEvidence secondEvidence = second.get(10, TimeUnit.SECONDS);
      assertThat(secondEvidence).isEqualTo(firstEvidence);
      assertThat(secondEvidence.creationEvidence().canonicalTenantId())
          .isEqualTo(firstEvidence.creationEvidence().canonicalTenantId());
      assertThat(
              fixture.repository.readCreatorQualification(
                  REQUEST_ID,
                  NAMESPACE,
                  firstEvidence.creationEvidence().requestDigest(),
                  firstEvidence.creationEvidence().evidenceDigest(),
                  INITIATING_ACCOUNT_ID,
                  AUTHORIZATION_OPERATION_ID,
                  AUTHORIZATION_DIGEST,
                  firstEvidence.evidenceDigest()))
          .contains(firstEvidence);
      assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(CREATOR_QUALIFICATIONS)).isEqualTo(1);
    } finally {
      allowFirstCommit.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void runtimeIdentityLookupReadsOnlyExactNewAndRetainedGameDesignRows() throws Exception {
    RetainedFixture retainedFixture = fixtureWithRetainedGame();
    Fixture fixture = retainedFixture.fixture();
    String retainedTenantKey = "retained-game-tenant-9001";
    UUID retainedTenantId =
        fixture
            .dsl
            .select(CANONICAL_TENANT_ID)
            .from(GAME)
            .where(GAME_ID.eq(retainedFixture.retainedGameRowId()))
            .fetchOne(CANONICAL_TENANT_ID);
    assertThat(retainedTenantId).isNotNull();
    String retainedXmin = gameXmin(fixture.dsl, retainedTenantKey);

    FreshTenantCreationEvidence freshReceipt =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "Runtime Identity", null));
    String freshXmin = gameXmin(fixture.dsl, SOURCE_KEY);

    assertThat(
            fixture.gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(
                freshReceipt.canonicalTenantId()))
        .contains(
            new GameTenantIdentity(
                freshReceipt.canonicalTenantId(),
                GameTenantIdentity.ProvenanceKind.NEW_GAME_ROW,
                freshReceipt.sourceGameRowId(),
                SOURCE_KEY));
    assertThat(
            fixture.gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(retainedTenantId))
        .contains(
            new GameTenantIdentity(
                retainedTenantId,
                GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                retainedFixture.retainedGameRowId(),
                retainedTenantKey));
    assertThat(
            fixture.gameRepository.findRuntimeTenantIdentityByCanonicalTenantId(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")))
        .isEmpty();

    assertThat(gameXmin(fixture.dsl, SOURCE_KEY)).isEqualTo(freshXmin);
    assertThat(gameXmin(fixture.dsl, retainedTenantKey)).isEqualTo(retainedXmin);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(2);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void changedPayloadConflictsAndNonTransactionalConstructionCannotWrite() throws Exception {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active Game Design owner transaction");
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> fixture.repository.read(REQUEST_ID, NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed-outcome owner read");
    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).isEmpty();
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    fixture.inTransaction(
        () -> fixture.repository.createCandidate(NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null));
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "Changed World", null)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("reused with changed input");
    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).isPresent();
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void candidateEnforcesGameBoundsAndAcceptsMaximalMultibyteDescription() throws Exception {
    Fixture fixture = fixture();
    String malformedName = "bad" + (char) 0xD800;
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, "x".repeat(73), "World", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceGameTenantKey");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "x".repeat(201), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", "x".repeat(256))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", "😀".repeat(256))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("description");
    assertThatThrownBy(
            () ->
                fixture.inTransaction(
                    () ->
                        fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, malformedName, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("valid Unicode");

    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();
    String maximalMultibyteDescription = "😀".repeat(255);
    assertThat(
            fixture.inTransaction(
                () ->
                    fixture.repository.createCandidate(
                        NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", maximalMultibyteDescription)))
        .isNotNull();
    assertThat(
            fixture
                .dsl
                .select(DESCRIPTION)
                .from(GAME)
                .where(TENANT_ID.eq(SOURCE_KEY))
                .fetchOne(DESCRIPTION))
        .isEqualTo(maximalMultibyteDescription);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void rollbackAndClaimOnlyAttemptsLeaveNoPersistedOperation() throws Exception {
    Fixture fixture = fixture();
    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture.repository.createCandidate(
                          NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null);
                      throw new IllegalStateException("simulate lost owner transaction");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulate lost owner transaction");
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    assertThatThrownBy(
            () ->
                fixture.transactionTemplate.execute(
                    status -> {
                      fixture
                          .dsl
                          .insertInto(OPERATIONS)
                          .set(OPERATION_ID, UUID.randomUUID())
                          .set(SCHEMA_VERSION, 1)
                          .set(TARGET_NAMESPACE, NAMESPACE)
                          .set(
                              CREATION_REQUEST_ID,
                              UUID.fromString("22222222-2222-4222-8222-222222222222"))
                          .set(
                              REQUEST_DIGEST,
                              GameTenantCreationDigest.requestDigest(
                                  NAMESPACE,
                                  UUID.fromString("22222222-2222-4222-8222-222222222222"),
                                  "claim-only",
                                  "World",
                                  null))
                          .set(SOURCE_GAME_TENANT_KEY, "claim-only")
                          .set(NAME, "World")
                          .set(STATUS, "PENDING")
                          .execute();
                      return null;
                    }))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("cannot commit while pending");
    assertThat(fixture.dsl.fetchCount(GAME)).isZero();
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isZero();

    assertThat(
            fixture.inTransaction(
                () ->
                    fixture.repository.createCandidate(
                        NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null)))
        .isNotNull();
  }

  @Test
  void completedOperationCannotBeUpdatedOrDeleted() throws Exception {
    Fixture fixture = fixture();
    FreshTenantCreationEvidence receipt =
        fixture.inTransaction(
            () ->
                fixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "World", null));

    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .update(OPERATIONS)
                    .set(NAME, "Changed")
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl
                    .deleteFrom(OPERATIONS)
                    .where(OPERATION_ID.eq(receipt.operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");

    assertThat(fixture.repository.read(REQUEST_ID, NAMESPACE)).contains(receipt);
    assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
    assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
  }

  @Test
  void readFailsClosedWhenGameSourceTupleChangesOrSourceRowDisappears() throws Exception {
    Fixture changedFixture = fixture();
    FreshTenantCreationEvidence changedReceipt =
        changedFixture.inTransaction(
            () ->
                changedFixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "Changed Source", null));
    try (Connection connection = changedFixture.dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE game DISABLE TRIGGER trg_game_tenant_identity_immutable");
      changedFixture
          .dsl
          .update(GAME)
          .set(
              DSL.field(DSL.name("tenant_identity_provenance_kind"), String.class),
              "RETAINED_GAME_V30")
          .where(GAME_ID.eq(changedReceipt.sourceGameRowId()))
          .execute();
      statement.execute("ALTER TABLE game ENABLE TRIGGER trg_game_tenant_identity_immutable");
    }
    assertThatThrownBy(
            () ->
                changedFixture.inTransaction(
                    () ->
                        changedFixture.repository.createCandidate(
                            NAMESPACE,
                            REQUEST_ID,
                            SOURCE_KEY,
                            "Changed request against damaged source",
                            null)))
        .isInstanceOf(GameTenantCreationRepository.CreationRequestConflictException.class)
        .hasMessageContaining("reused with changed input");
    assertThatThrownBy(() -> changedFixture.repository.read(REQUEST_ID, NAMESPACE))
        .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
        .hasMessageContaining("source tuple no longer matches");

    Fixture missingFixture = fixture();
    FreshTenantCreationEvidence missingReceipt =
        missingFixture.inTransaction(
            () ->
                missingFixture.repository.createCandidate(
                    NAMESPACE, REQUEST_ID, SOURCE_KEY, "Missing Source", null));
    try (Connection connection = missingFixture.dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("ALTER TABLE game DISABLE TRIGGER ALL");
      missingFixture
          .dsl
          .deleteFrom(GAME)
          .where(GAME_ID.eq(missingReceipt.sourceGameRowId()))
          .execute();
      statement.execute("ALTER TABLE game ENABLE TRIGGER ALL");
    }
    assertThatThrownBy(() -> missingFixture.repository.read(REQUEST_ID, NAMESPACE))
        .isInstanceOf(GameTenantCreationRepository.InvalidCreationEvidenceException.class)
        .hasMessageContaining("source row is missing");
  }

  @Test
  void concurrentDuplicateCreationRequestsConvergeOnOneUuidAndReceipt() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch firstOperationCreated = new CountDownLatch(1);
    CountDownLatch allowFirstCommit = new CountDownLatch(1);
    CountDownLatch secondOperationStarted = new CountDownLatch(1);
    AtomicInteger firstBackendPid = new AtomicInteger();
    AtomicInteger secondBackendPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FreshTenantCreationEvidence> first =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        FreshTenantCreationEvidence receipt =
                            fixture.repository.createCandidate(
                                NAMESPACE,
                                REQUEST_ID,
                                SOURCE_KEY,
                                "Concurrent World",
                                "same request");
                        firstBackendPid.set(currentBackendPid(fixture.dsl));
                        firstOperationCreated.countDown();
                        awaitLatch(allowFirstCommit);
                        return receipt;
                      }));
      assertThat(firstOperationCreated.await(10, TimeUnit.SECONDS)).isTrue();

      Future<FreshTenantCreationEvidence> second =
          executor.submit(
              () ->
                  fixture.transactionTemplate.execute(
                      status -> {
                        secondBackendPid.set(currentBackendPid(fixture.dsl));
                        secondOperationStarted.countDown();
                        return fixture.repository.createCandidate(
                            NAMESPACE, REQUEST_ID, SOURCE_KEY, "Concurrent World", "same request");
                      }));
      assertThat(secondOperationStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitDatabaseBlocking(fixture.dataSource, secondBackendPid.get(), firstBackendPid.get());
      allowFirstCommit.countDown();

      FreshTenantCreationEvidence firstReceipt = first.get(10, TimeUnit.SECONDS);
      FreshTenantCreationEvidence secondReceipt = second.get(10, TimeUnit.SECONDS);
      assertThat(secondReceipt).isEqualTo(firstReceipt);
      assertThat(secondReceipt.canonicalTenantId()).isEqualTo(firstReceipt.canonicalTenantId());
      assertThat(fixture.dsl.fetchCount(GAME)).isEqualTo(1);
      assertThat(fixture.dsl.fetchCount(OPERATIONS)).isEqualTo(1);
    } finally {
      allowFirstCommit.countDown();
      executor.shutdownNow();
    }
  }

  private FreshTenantCreatorEvidence createQualified(
      Fixture fixture,
      UUID initiatingAccountId,
      UUID authorizationOperationId,
      String authorizationDigest) {
    return fixture.repository.createCandidateWithCreator(
        NAMESPACE,
        REQUEST_ID,
        SOURCE_KEY,
        "The First World",
        "A description",
        initiatingAccountId,
        authorizationOperationId,
        authorizationDigest);
  }

  private Fixture fixture() {
    String schema = "game_design_creation_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table(FLYWAY_TABLE)
        .placeholders(Map.of("serviceSchema", schema))
        .locations("classpath:db/migration")
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    assertThat(dsl.select(DSL.field("current_schema()", String.class)).fetchSingle().value1())
        .isEqualTo(schema);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    return new Fixture(dataSource, dsl, gameRepository, repository, transactionTemplate);
  }

  private RetainedFixture fixtureWithRetainedGame() {
    String schema =
        "game_design_retained_identity_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);

    migrate(dataSource, schema, MigrationVersion.fromVersion("29"));
    DSLContext legacyDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Long retainedGameRowId =
        legacyDsl
            .insertInto(GAME)
            .set(TENANT_ID, "retained-game-tenant-9001")
            .set(NAME, "Retained Game Design Row")
            .set(DESCRIPTION, "Retained source identity fixture")
            .returning(GAME_ID)
            .fetchOne(GAME_ID);
    assertThat(retainedGameRowId).isNotNull();

    migrate(dataSource, schema, null);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    assertThat(dsl.select(DSL.field("current_schema()", String.class)).fetchSingle().value1())
        .isEqualTo(schema);
    GameRepository gameRepository = new GameRepository(dsl);
    GameTenantCreationRepository repository = new GameTenantCreationRepository(dsl, gameRepository);
    Fixture fixture = new Fixture(dataSource, dsl, gameRepository, repository, transactionTemplate);
    return new RetainedFixture(fixture, retainedGameRowId);
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private String gameXmin(DSLContext dsl, String sourceGameTenantKey) {
    return dsl.fetch("SELECT xmin::text AS xmin FROM game WHERE tenant_id = ?", sourceGameTenantKey)
        .getFirst()
        .get("xmin", String.class);
  }

  private int currentBackendPid(DSLContext dsl) {
    var backendRecord = dsl.fetchOne("SELECT pg_backend_pid() AS pid");
    if (backendRecord == null) {
      throw new IllegalStateException("Could not read the PostgreSQL backend id");
    }
    Integer pid = backendRecord.get("pid", Integer.class);
    if (pid == null || pid <= 0) {
      throw new IllegalStateException("PostgreSQL returned an invalid backend id");
    }
    return pid;
  }

  private void awaitDatabaseBlocking(
      DriverManagerDataSource dataSource, int blockedBackendPid, int blockerBackendPid)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT ? = ANY(pg_blocking_pids(?))")) {
      statement.setInt(1, blockerBackendPid);
      statement.setInt(2, blockedBackendPid);
      while (System.nanoTime() < deadline) {
        try (ResultSet resultSet = statement.executeQuery()) {
          resultSet.next();
          if (resultSet.getBoolean(1)) {
            return;
          }
        }
        Thread.yield();
      }
    }
    throw new AssertionError(
        "PostgreSQL did not report the duplicate request blocked on the first writer");
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to commit the first tenant operation");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting to commit tenant operation", exception);
    }
  }

  private record Fixture(
      DriverManagerDataSource dataSource,
      DSLContext dsl,
      GameRepository gameRepository,
      GameTenantCreationRepository repository,
      TransactionTemplate transactionTemplate) {
    private <T> T inTransaction(java.util.concurrent.Callable<T> work) throws Exception {
      return transactionTemplate.execute(
          status -> {
            try {
              return work.call();
            } catch (RuntimeException exception) {
              throw exception;
            } catch (Exception exception) {
              throw new IllegalStateException(exception);
            }
          });
    }
  }

  private record RetainedFixture(Fixture fixture, long retainedGameRowId) {}
}
