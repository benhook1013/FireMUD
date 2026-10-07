package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier.Signed;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository.AssociationReceipt;
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
class GameSessionTenantAssociationRepositoryIntegrationTest {
  private static final String FLYWAY_TABLE = "flyway_schema_history_game_design_service";
  private static final String NAMESPACE = "game-session-association-test";
  private static final String SIGNER_KEY_ID = "gd-owner-approval-test";
  private static final String GAME_SESSION_EVIDENCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final Table<?> GAME = DSL.table(DSL.name("game"));
  private static final Table<?> ASSOCIATIONS =
      DSL.table(DSL.name("game_design_game_session_tenant_association_operations"));
  private static final org.jooq.Field<Long> GAME_ID = DSL.field(DSL.name("id"), Long.class);
  private static final org.jooq.Field<String> TENANT_ID =
      DSL.field(DSL.name("tenant_id"), String.class);
  private static final org.jooq.Field<String> GAME_NAME = DSL.field(DSL.name("name"), String.class);
  private static final org.jooq.Field<String> GAME_DESCRIPTION =
      DSL.field(DSL.name("description"), String.class);
  private static final org.jooq.Field<UUID> OPERATION_ID =
      DSL.field(DSL.name("operation_id"), UUID.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void appliesAuditedAssociationForFreshAndRetainedGameSources() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed retainedSigned = fixture.signed(fixture.retainedSource(), 901L, uuid(1));
    Signed freshSigned = fixture.signed(fixture.freshSource(), 902L, uuid(2));

    AssociationReceipt retained = fixture.apply(retainedSigned, NAMESPACE);
    AssociationReceipt fresh = fixture.apply(freshSigned, NAMESPACE);

    assertThat(retained.manifest().provenanceKind()).isEqualTo("RETAINED_GAME_V29");
    assertThat(retained.manifest().sourceGameRowIdValue())
        .isEqualTo(fixture.retainedSource().sourceGameId());
    assertThat(retained.manifest().legacyGameSessionTenantIdValue()).isEqualTo(901L);
    assertThat(fresh.manifest().provenanceKind()).isEqualTo("NEW_GAME_ROW");
    assertThat(fresh.manifest().sourceGameRowIdValue())
        .isEqualTo(fixture.freshSource().sourceGameId());
    assertThat(fresh.manifest().legacyGameSessionTenantIdValue()).isEqualTo(902L);
    assertThat(fixture.read(retained)).contains(retained);
    assertThat(fixture.read(fresh)).contains(fresh);
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(2);
  }

  @Test
  void exactRetryReturnsSameSignedEvidenceWithoutMutatingSourceOrAssociation() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed signed = fixture.signed(fixture.freshSource(), 910L, uuid(10));
    AssociationReceipt first = fixture.apply(signed, NAMESPACE);
    String originalGameXmin = fixture.gameXmin(fixture.freshSource().sourceGameId());

    AssociationReceipt retry = fixture.apply(signed, NAMESPACE);

    assertThat(retry).isEqualTo(first);
    assertThat(fixture.gameXmin(fixture.freshSource().sourceGameId())).isEqualTo(originalGameXmin);
    assertThat(fixture.dsl().fetchCount(GAME)).isEqualTo(2);
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
    assertThat(fixture.read(first)).contains(first);
  }

  @Test
  void v2PayloadRequiresProjectionDigestWhileHistoricalV1MayOmitIt() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    UUID v1OperationId = uuid(910);
    fixture.insertExpiredAssociation(v1OperationId, 910L, fixture.retainedSource());
    fixture.insertRawPayload(v1OperationId, 1, null);

    UUID v2OperationId = uuid(911);
    fixture.insertExpiredAssociation(v2OperationId, 911L, fixture.freshSource());
    assertThatThrownBy(() -> fixture.insertRawPayload(v2OperationId, 2, null))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("chk_gd_game_session_association_payload_capture");
  }

  @Test
  void unverifiedLegalHoldWritesFailClosedAndCleanupPreservesImmutableAssociationClaim()
      throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    UUID operationId = uuid(915);
    long legacyTenantId = 915L;
    GameTenantIdentity source = fixture.freshSource();
    fixture.insertExpiredAssociation(operationId, legacyTenantId, source);
    fixture.insertRawPayload(operationId);

    assertThatThrownBy(() -> fixture.insertLegalHold(operationId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("authenticated owner authorization boundary");

    assertThatThrownBy(() -> fixture.insertInvalidScopeLegalHold(operationId))
        .isInstanceOf(DataAccessException.class);
    UUID holdId = fixture.insertActiveLegalHold(operationId);
    assertThat(fixture.repository().purgeExpiredRawPayloads()).isZero();
    assertThat(
            fixture
                    .dsl()
                    .fetchOne(
                        "SELECT 1 FROM game_design_game_session_tenant_association_payload "
                            + "WHERE operation_id = ?",
                        operationId)
                != null)
        .isTrue();
    assertThatThrownBy(() -> fixture.releaseLegalHold(holdId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("authenticated owner authorization boundary");
    fixture.releaseLegalHoldForRetentionTest(holdId);
    assertThatThrownBy(() -> fixture.deleteLegalHold(holdId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("finite policy-controlled expiry");
    assertThat(fixture.repository().purgeExpiredRawPayloads()).isEqualTo(1);
    assertThat(
            fixture
                    .dsl()
                    .fetchOne(
                        "SELECT 1 FROM game_design_game_session_tenant_association_payload "
                            + "WHERE operation_id = ?",
                        operationId)
                != null)
        .isFalse();
    assertThat(
            fixture
                    .dsl()
                    .fetchOne(
                        "SELECT 1 FROM game_design_game_session_tenant_association_operations "
                            + "WHERE operation_id = ? AND captured_at IS NOT NULL",
                        operationId)
                != null)
        .isTrue();
    assertThat(
            fixture
                    .dsl()
                    .fetchOne(
                        "SELECT 1 FROM game_design_game_session_tenant_association_legal_hold "
                            + "WHERE hold_id = ? AND operation_id = ? AND hold_scope = 'RAW_PAYLOAD' "
                            + "AND reason_code = 'REGULATORY' AND released_at IS NOT NULL "
                            + "AND released_by = 'fixture-authenticated-owner' "
                            + "AND release_reference = 'release-case-915'",
                        holdId,
                        operationId)
                != null)
        .isTrue();
    assertThatThrownBy(
            () ->
                fixture
                    .repository()
                    .read(operationId, source.canonicalTenantId(), legacyTenantId, NAMESPACE))
        .isInstanceOf(
            GameSessionTenantAssociationRepository.InvalidAssociationEvidenceException.class);
    fixture.insertRawPayload(operationId);
    assertThatThrownBy(
            () ->
                fixture
                    .repository()
                    .read(operationId, source.canonicalTenantId(), legacyTenantId, NAMESPACE))
        .isInstanceOf(
            GameSessionTenantAssociationRepository.InvalidAssociationEvidenceException.class)
        .hasMessageContaining("expired");
    assertThat(fixture.repository().purgeExpiredRawPayloads()).isEqualTo(1);
    assertThatThrownBy(() -> fixture.insertLegalHold(operationId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("authenticated owner authorization boundary");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "DELETE FROM game_design_game_session_tenant_association_operations "
                            + "WHERE operation_id = ?",
                        operationId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void changedSignedFieldUnderSameOperationConflictsWithoutReplacingReceipt() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed original = fixture.signed(fixture.freshSource(), 920L, uuid(20));
    AssociationReceipt committed = fixture.apply(original, NAMESPACE);
    GameSessionTenantAssociationEvidence changedManifest =
        withApprovalReference(original.manifest(), "changed-approved-reference");
    Signed changed = fixture.sign(changedManifest);

    assertThatThrownBy(() -> fixture.apply(changed, NAMESPACE))
        .isInstanceOf(GameSessionTenantAssociationRepository.AssociationConflictException.class)
        .hasMessageContaining("reused with changed signed evidence");

    assertThat(fixture.read(committed)).contains(committed);
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  @Test
  void canonicalAndRetainedKeyCollisionsFailWithoutPartialAssociationClaims() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed first = fixture.signed(fixture.freshSource(), 930L, uuid(30));
    AssociationReceipt committed = fixture.apply(first, NAMESPACE);
    Signed sameCanonicalDifferentRetainedKey =
        fixture.signed(fixture.freshSource(), 931L, uuid(31));
    Signed sameRetainedKeyDifferentCanonical =
        fixture.signed(fixture.retainedSource(), 930L, uuid(32));

    assertThatThrownBy(() -> fixture.apply(sameCanonicalDifferentRetainedKey, NAMESPACE))
        .isInstanceOf(GameSessionTenantAssociationRepository.AssociationConflictException.class)
        .hasMessageContaining("already associated");
    assertThatThrownBy(() -> fixture.apply(sameRetainedKeyDifferentCanonical, NAMESPACE))
        .isInstanceOf(GameSessionTenantAssociationRepository.AssociationConflictException.class)
        .hasMessageContaining("already associated");

    assertThat(fixture.read(committed)).contains(committed);
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
    assertThat(fixture.dsl().fetchCount(GAME)).isEqualTo(2);
  }

  @Test
  void absentOrContradictoryGameDesignSourcesFailWithoutAssociationMutation() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    GameSessionTenantAssociationEvidence absentSource =
        fixture.evidence(
            uuid(40),
            940L,
            UUID.randomUUID(),
            fixture.freshSource().sourceGameId(),
            "unrelated-game-key",
            "NEW_GAME_ROW");
    GameSessionTenantAssociationEvidence contradictorySource =
        fixture.evidence(
            uuid(41),
            941L,
            fixture.freshSource().canonicalTenantId(),
            fixture.retainedSource().sourceGameId(),
            fixture.freshSource().sourceLegacyTenantId(),
            "NEW_GAME_ROW");

    assertThatThrownBy(() -> fixture.apply(fixture.sign(absentSource), NAMESPACE))
        .isInstanceOf(GameSessionTenantAssociationRepository.AssociationConflictException.class)
        .hasMessageContaining("source game row is missing");
    assertThatThrownBy(() -> fixture.apply(fixture.sign(contradictorySource), NAMESPACE))
        .isInstanceOf(
            GameSessionTenantAssociationRepository.InvalidAssociationEvidenceException.class)
        .hasMessageContaining("source no longer matches");

    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isZero();
    assertThat(fixture.dsl().fetchCount(GAME)).isEqualTo(2);
  }

  @Test
  void concurrentExactFirstWritersReturnOneImmutableReceipt() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed signed = fixture.signed(fixture.freshSource(), 950L, uuid(50));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AssociationReceipt> first =
          executor.submit(() -> concurrentApply(fixture, signed, ready, start));
      Future<AssociationReceipt> second =
          executor.submit(() -> concurrentApply(fixture, signed, ready, start));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      AssociationReceipt firstReceipt = first.get(10, TimeUnit.SECONDS);
      AssociationReceipt secondReceipt = second.get(10, TimeUnit.SECONDS);
      assertThat(firstReceipt).isEqualTo(secondReceipt);
      assertThat(fixture.read(firstReceipt)).contains(firstReceipt);
      assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
      assertThat(fixture.dsl().fetchCount(GAME)).isEqualTo(2);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void ownerRollbackLeavesNoClaimAndLostAcknowledgementRetryReadsExactReceipt() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed signed = fixture.signed(fixture.freshSource(), 960L, uuid(60));

    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status -> {
                          fixture.repository().apply(signed, fixture.trustedKeys(), NAMESPACE);
                          throw new IllegalStateException("simulate owner commit rollback");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulate owner commit rollback");
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isZero();

    fixture.apply(signed, NAMESPACE); // The commit response is intentionally discarded.
    AssociationReceipt exactReadback = fixture.apply(signed, NAMESPACE);

    assertThat(exactReadback.manifest()).isEqualTo(signed.manifest());
    assertThat(exactReadback.ed25519Signature()).isEqualTo(signed.ed25519Signature());
    assertThat(exactReadback.manifestDigest()).isEqualTo(signed.manifest().manifestDigest());
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  @Test
  void applyRequiresOwnerTransactionAndReadRequiresExactCommittedScope() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed signed = fixture.signed(fixture.freshSource(), 970L, uuid(70));

    assertThatThrownBy(() -> fixture.repository().apply(signed, fixture.trustedKeys(), NAMESPACE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active Game Design owner transaction");
    assertThatThrownBy(
            () ->
                fixture
                    .transactionTemplate()
                    .execute(
                        status ->
                            fixture
                                .repository()
                                .read(
                                    signed.manifest().operationId(),
                                    signed.manifest().canonicalTenantId(),
                                    signed.manifest().legacyGameSessionTenantIdValue(),
                                    NAMESPACE)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("committed-outcome owner read");
    assertThatThrownBy(() -> fixture.apply(signed, "another-namespace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("different namespace");
    byte[] alteredSignatureBytes = Base64.getDecoder().decode(signed.ed25519Signature());
    alteredSignatureBytes[0] ^= 1;
    String alteredSignature = Base64.getEncoder().encodeToString(alteredSignatureBytes);
    assertThat(alteredSignature).isNotEqualTo(signed.ed25519Signature());
    Signed malformedSignature = new Signed(signed.manifest(), alteredSignature);
    assertThatThrownBy(() -> fixture.apply(malformedSignature, NAMESPACE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("signature");
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isZero();

    AssociationReceipt committed = fixture.apply(signed, NAMESPACE);
    assertThat(fixture.read(committed)).contains(committed);
    assertThat(
            fixture
                .repository()
                .read(
                    uuid(71),
                    committed.manifest().canonicalTenantId(),
                    committed.manifest().legacyGameSessionTenantIdValue(),
                    NAMESPACE))
        .isEmpty();
    assertThat(
            fixture
                .repository()
                .read(
                    committed.manifest().operationId(),
                    fixture.retainedSource().canonicalTenantId(),
                    committed.manifest().legacyGameSessionTenantIdValue(),
                    NAMESPACE))
        .isEmpty();
    assertThat(
            fixture
                .repository()
                .read(
                    committed.manifest().operationId(),
                    committed.manifest().canonicalTenantId(),
                    committed.manifest().legacyGameSessionTenantIdValue() + 1,
                    NAMESPACE))
        .isEmpty();
    assertThat(
            fixture
                .repository()
                .read(
                    committed.manifest().operationId(),
                    committed.manifest().canonicalTenantId(),
                    committed.manifest().legacyGameSessionTenantIdValue(),
                    "another-namespace"))
        .isEmpty();
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  @Test
  void databaseRejectsAssociationUpdatesDeletesTruncatesAndContradictorySourceInsert()
      throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    AssociationReceipt receipt =
        fixture.apply(fixture.signed(fixture.freshSource(), 980L, uuid(80)), NAMESPACE);

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .update(ASSOCIATIONS)
                    .set(
                        DSL.field(DSL.name("source_game_tenant_key"), String.class),
                        "changed-owner")
                    .where(OPERATION_ID.eq(receipt.manifest().operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .deleteFrom(ASSOCIATIONS)
                    .where(OPERATION_ID.eq(receipt.manifest().operationId()))
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute("TRUNCATE game_design_game_session_tenant_association_operations"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot truncate a table referenced in a foreign key constraint");
    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .execute(
                        "TRUNCATE game_design_game_session_tenant_association_operations, "
                            + "game_design_game_session_tenant_association_payload, "
                            + "game_design_game_session_tenant_association_legal_hold"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .insertInto(ASSOCIATIONS)
                    .set(OPERATION_ID, uuid(81))
                    .set(DSL.field(DSL.name("target_namespace"), String.class), NAMESPACE)
                    .set(DSL.field(DSL.name("legacy_game_session_tenant_id"), Long.class), 981L)
                    .set(
                        DSL.field(DSL.name("canonical_tenant_id"), UUID.class),
                        receipt.manifest().canonicalTenantId())
                    .set(
                        DSL.field(DSL.name("source_game_row_id"), Long.class),
                        fixture.freshSource().sourceGameId())
                    .set(
                        DSL.field(DSL.name("source_game_tenant_key"), String.class),
                        "contradictory-source-key")
                    .set(DSL.field(DSL.name("provenance_kind"), String.class), "NEW_GAME_ROW")
                    .execute())
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("does not match its Game Design row");

    assertThat(fixture.read(receipt)).contains(receipt);
    assertThat(fixture.dsl().fetchCount(ASSOCIATIONS)).isEqualTo(1);
  }

  private AssociationReceipt concurrentApply(
      Fixture fixture, Signed signed, CountDownLatch ready, CountDownLatch start) {
    ready.countDown();
    await(start);
    return fixture.apply(signed, NAMESPACE);
  }

  private Fixture fixtureWithFreshAndRetainedSources() throws Exception {
    String schema = "game_design_gs_assoc_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, MigrationVersion.fromVersion("28"));
    DSLContext legacyDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    Long retainedGameId =
        legacyDsl
            .insertInto(GAME)
            .set(TENANT_ID, "retained-game-design-owner-source")
            .set(GAME_NAME, "Retained Source Game")
            .set(GAME_DESCRIPTION, "Persisted before canonical tenant identities")
            .returning(GAME_ID)
            .fetchOne(GAME_ID);
    assertThat(retainedGameId).isNotNull();

    migrate(dataSource, schema, null);
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    GameRepository gameRepository = new GameRepository(dsl);
    GameSessionTenantAssociationRepository repository =
        new GameSessionTenantAssociationRepository(dsl, gameRepository);
    UUID retainedCanonicalTenantId =
        dsl.select(DSL.field(DSL.name("canonical_tenant_id"), UUID.class))
            .from(GAME)
            .where(GAME_ID.eq(retainedGameId))
            .fetchOne(DSL.field(DSL.name("canonical_tenant_id"), UUID.class));
    GameTenantIdentity retainedSource =
        gameRepository
            .findRuntimeTenantIdentityByCanonicalTenantId(retainedCanonicalTenantId)
            .orElseThrow();

    Game freshGame = new Game();
    freshGame.setTenantId("fresh-game-design-owner-source");
    freshGame.setName("Fresh Source Game");
    freshGame.setDescription("Persisted after canonical tenant identities");
    Game persistedFreshGame = transactionTemplate.execute(status -> gameRepository.save(freshGame));
    GameTenantIdentity freshSource =
        gameRepository
            .findRuntimeTenantIdentityByCanonicalTenantId(
                Objects.requireNonNull(persistedFreshGame).getCanonicalTenantId())
            .orElseThrow();
    KeyPair ownerKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    Map<String, String> trustedKeys =
        Map.of(
            SIGNER_KEY_ID, Base64.getEncoder().encodeToString(ownerKey.getPublic().getEncoded()));
    return new Fixture(
        dsl, repository, transactionTemplate, ownerKey, trustedKeys, retainedSource, freshSource);
  }

  private DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private void migrate(DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table(FLYWAY_TABLE)
            .placeholders(Map.of("serviceSchema", schema))
            .locations(
                "filesystem:"
                    + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize());
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private GameSessionTenantAssociationEvidence withApprovalReference(
      GameSessionTenantAssociationEvidence original, String approvalReference) {
    return new GameSessionTenantAssociationEvidence(
        original.schemaVersion(),
        original.operationId(),
        original.targetNamespace(),
        original.signerKeyId(),
        original.approvedBy(),
        approvalReference,
        original.signedAt(),
        original.sourceCapturedAt(),
        original.legacyGameSessionTenantId(),
        original.canonicalTenantId(),
        original.sourceGameRowId(),
        original.sourceGameTenantKey(),
        original.provenanceKind(),
        original.gameSessionProjectionDigest(),
        original.gameSessionEvidenceDigest());
  }

  private UUID uuid(int suffix) {
    return UUID.fromString(String.format("%08d-2222-4222-8222-222222222222", suffix));
  }

  private void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for concurrent association apply");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for concurrent association apply", exception);
    }
  }

  private record Fixture(
      DSLContext dsl,
      GameSessionTenantAssociationRepository repository,
      TransactionTemplate transactionTemplate,
      KeyPair ownerKey,
      Map<String, String> trustedKeys,
      GameTenantIdentity retainedSource,
      GameTenantIdentity freshSource) {
    void insertExpiredAssociation(
        UUID operationId, long legacyTenantId, GameTenantIdentity source) {
      dsl()
          .execute(
              "INSERT INTO game_design_game_session_tenant_association_operations ("
                  + "operation_id, target_namespace, legacy_game_session_tenant_id, "
                  + "canonical_tenant_id, source_game_row_id, source_game_tenant_key, "
                  + "provenance_kind, captured_at, terminal_outcome) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp() - INTERVAL '31 days', 'ASSOCIATED')",
              operationId,
              NAMESPACE,
              legacyTenantId,
              source.canonicalTenantId(),
              source.sourceGameId(),
              source.sourceLegacyTenantId(),
              source.provenanceKind().name());
    }

    void insertRawPayload(UUID operationId) {
      insertRawPayload(operationId, 2, "sha256:" + "a".repeat(64));
    }

    void insertRawPayload(UUID operationId, int schemaVersion, String projectionDigest) {
      dsl()
          .execute(
              "INSERT INTO game_design_game_session_tenant_association_payload ("
                  + "operation_id, schema_version, signer_key_id, approved_by, approval_reference, "
                  + "signed_at, game_session_projection_digest, game_session_evidence_digest, "
                  + "manifest_digest, signature) VALUES (?, ?, 'owner-key', 'owner', 'case', ?, ?, ?, ?, ?)",
              operationId,
              schemaVersion,
              java.time.Instant.now().truncatedTo(ChronoUnit.MICROS).toString(),
              projectionDigest,
              "sha256:" + "b".repeat(64),
              "sha256:" + "c".repeat(64),
              Base64.getEncoder().encodeToString(new byte[64]));
    }

    void insertLegalHold(UUID operationId) {
      dsl()
          .execute(
              "INSERT INTO game_design_game_session_tenant_association_legal_hold ("
                  + "hold_id, operation_id, hold_scope, reason_code, case_reference, "
                  + "authorization_id, authorized_principal, authorized_at, review_at) "
                  + "VALUES (?, ?, 'RAW_PAYLOAD', 'REGULATORY', 'case-2026-002', ?, "
                  + "'owner-admin', clock_timestamp(), clock_timestamp() + INTERVAL '30 days')",
              UUID.randomUUID(),
              operationId,
              UUID.randomUUID());
    }

    UUID insertActiveLegalHold(UUID operationId) {
      UUID holdId = UUID.randomUUID();
      // Seed governed state only to prove expiry blockers; application writes remain denied.
      withHoldInsertTriggerDisabled(
          () ->
              dsl()
                  .execute(
                      "INSERT INTO game_design_game_session_tenant_association_legal_hold ("
                          + "hold_id, operation_id, hold_scope, reason_code, case_reference, "
                          + "authorization_id, authorized_principal, authorized_at, review_at) "
                          + "VALUES (?, ?, 'RAW_PAYLOAD', 'REGULATORY', 'test-case-915', ?, "
                          + "'fixture-authenticated-owner', clock_timestamp() - INTERVAL '2 days', "
                          + "clock_timestamp() + INTERVAL '30 days')",
                      holdId,
                      operationId,
                      UUID.randomUUID()));
      return holdId;
    }

    void insertInvalidScopeLegalHold(UUID operationId) {
      withHoldInsertTriggerDisabled(
          () ->
              dsl()
                  .execute(
                      "INSERT INTO game_design_game_session_tenant_association_legal_hold ("
                          + "hold_id, operation_id, hold_scope, reason_code, case_reference, "
                          + "authorization_id, authorized_principal, authorized_at, review_at) "
                          + "VALUES (?, ?, 'ACCOUNT_MAPPING', 'REGULATORY', 'bad-scope', ?, "
                          + "'fixture-authenticated-owner', clock_timestamp(), "
                          + "clock_timestamp() + INTERVAL '30 days')",
                      UUID.randomUUID(),
                      operationId,
                      UUID.randomUUID()));
    }

    void releaseLegalHold(UUID holdId) {
      dsl()
          .execute(
              "UPDATE game_design_game_session_tenant_association_legal_hold "
                  + "SET released_at = clock_timestamp(), released_by = 'fixture-authenticated-owner', "
                  + "release_reference = 'release-case-915' WHERE hold_id = ?",
              holdId);
    }

    void deleteLegalHold(UUID holdId) {
      dsl()
          .execute(
              "DELETE FROM game_design_game_session_tenant_association_legal_hold WHERE hold_id = ?",
              holdId);
    }

    void releaseLegalHoldForRetentionTest(UUID holdId) {
      // This fixture bypasses auth solely to prove release-triggered raw erasure.
      dsl()
          .execute(
              "ALTER TABLE game_design_game_session_tenant_association_legal_hold "
                  + "DISABLE TRIGGER gd_game_session_tenant_hold_release_only");
      try {
        releaseLegalHold(holdId);
      } finally {
        dsl()
            .execute(
                "ALTER TABLE game_design_game_session_tenant_association_legal_hold "
                    + "ENABLE TRIGGER gd_game_session_tenant_hold_release_only");
      }
    }

    private void withHoldInsertTriggerDisabled(Runnable insert) {
      dsl()
          .execute(
              "ALTER TABLE game_design_game_session_tenant_association_legal_hold "
                  + "DISABLE TRIGGER gd_game_session_tenant_hold_authentication_required");
      try {
        insert.run();
      } finally {
        dsl()
            .execute(
                "ALTER TABLE game_design_game_session_tenant_association_legal_hold "
                    + "ENABLE TRIGGER gd_game_session_tenant_hold_authentication_required");
      }
    }

    Signed signed(GameTenantIdentity source, long retainedGameSessionTenantId, UUID operationId)
        throws Exception {
      return sign(
          evidence(
              operationId,
              retainedGameSessionTenantId,
              source.canonicalTenantId(),
              source.sourceGameId(),
              source.sourceLegacyTenantId(),
              source.provenanceKind().name()));
    }

    GameSessionTenantAssociationEvidence evidence(
        UUID operationId,
        long retainedGameSessionTenantId,
        UUID canonicalTenantId,
        long sourceGameRowId,
        String sourceGameTenantKey,
        String provenanceKind) {
      java.time.Instant sourceCapturedAt =
          java.time.Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
      return new GameSessionTenantAssociationEvidence(
          2,
          operationId,
          NAMESPACE,
          SIGNER_KEY_ID,
          "approved-owner@example.test",
          "retained-session-audit-" + operationId,
          sourceCapturedAt.plusSeconds(1).toString(),
          sourceCapturedAt.toString(),
          Long.toString(retainedGameSessionTenantId),
          canonicalTenantId,
          Long.toString(sourceGameRowId),
          sourceGameTenantKey,
          provenanceKind,
          "sha256:" + "b".repeat(64),
          GAME_SESSION_EVIDENCE_DIGEST);
    }

    Signed sign(GameSessionTenantAssociationEvidence manifest) throws Exception {
      Signature signer = Signature.getInstance("Ed25519");
      signer.initSign(ownerKey.getPrivate());
      signer.update(manifest.preimage());
      return new Signed(manifest, Base64.getEncoder().encodeToString(signer.sign()));
    }

    AssociationReceipt apply(Signed signed, String exactNamespace) {
      AssociationReceipt receipt =
          transactionTemplate.execute(
              status -> repository.apply(signed, trustedKeys, exactNamespace));
      return Objects.requireNonNull(receipt);
    }

    Optional<AssociationReceipt> read(AssociationReceipt receipt) {
      return repository.read(
          receipt.manifest().operationId(),
          receipt.manifest().canonicalTenantId(),
          receipt.manifest().legacyGameSessionTenantIdValue(),
          receipt.manifest().targetNamespace());
    }

    String gameXmin(long gameId) {
      return dsl.fetch("SELECT xmin::text AS xmin FROM game WHERE id = ?", gameId)
          .getFirst()
          .get("xmin", String.class);
    }
  }
}
