package integration.net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.sql.Connection;
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
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.tenant.GameSessionTenantAssociationEvidence;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.maintenance.GameSessionTenantAssociationManifestVerifier.Signed;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository;
import net.firedevops.firemud.gamedesign.repository.GameSessionTenantAssociationRepository.AssociationReceipt;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
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
  private static final org.jooq.Field<String> APPROVED_BY =
      DSL.field(DSL.name("approved_by"), String.class);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void appliesAuditedAssociationForFreshAndRetainedGameSources() throws Exception {
    Fixture fixture = fixtureWithFreshAndRetainedSources();
    Signed retainedSigned = fixture.signed(fixture.retainedSource(), 901L, uuid(1));
    Signed freshSigned = fixture.signed(fixture.freshSource(), 902L, uuid(2));

    AssociationReceipt retained = fixture.apply(retainedSigned, NAMESPACE);
    AssociationReceipt fresh = fixture.apply(freshSigned, NAMESPACE);

    assertThat(retained.manifest().provenanceKind()).isEqualTo("RETAINED_GAME_V30");
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
  void versionLockRepositoryUsesConfiguredIsolatedOwnerSchema() throws Exception {
    String schema = "game_design_version_lock_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema, null);
    PostgresProperties postgresProperties = new PostgresProperties();
    postgresProperties.setSchema(schema);

    try (Connection connection = dataSource.getConnection()) {
      DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
      Table<?> versions = DSL.table(DSL.name(schema, "version"));
      var idField = DSL.field(DSL.name("id"), Long.class);
      Long versionId =
          dsl.insertInto(versions)
              .set(DSL.field(DSL.name("tenant_id"), String.class), "configured-schema-tenant")
              .set(DSL.field(DSL.name("version_number"), Integer.class), 7)
              .set(DSL.field(DSL.name("version_state"), String.class), "PUBLISHED")
              .set(DSL.field(DSL.name("version_state_epoch"), Long.class), 9L)
              .set(DSL.field(DSL.name("is_script_only"), Boolean.class), false)
              .set(DSL.field(DSL.name("notes"), String.class), "configured owner schema lock proof")
              .returning(idField)
              .fetchOne(idField);
      assertThat(versionId).isNotNull();

      VersionRepository repository = new VersionRepository(dsl, postgresProperties);

      Version locked =
          repository
              .findByTenantIdAndIdForEntityDigestBaselineMigration(
                  "configured-schema-tenant", versionId)
              .orElseThrow();
      assertThat(locked.getId()).isEqualTo(versionId);
      assertThat(locked.getTenantId()).isEqualTo("configured-schema-tenant");
      assertThat(locked.getVersionNumber()).isEqualTo(7);
      assertThat(locked.getVersionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
      assertThat(locked.getVersionStateEpoch()).isEqualTo(9L);
      assertThat(
              repository.findByTenantIdAndIdForEntityDigestBaselineMigration(
                  "wrong-tenant", versionId))
          .isEmpty();
      assertThat(
              repository.findByTenantIdAndIdForEntityDigestBaselineMigration(
                  "configured-schema-tenant", versionId + 1000L))
          .isEmpty();
    }
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
    Signed malformedSignature =
        new Signed(signed.manifest(), signed.ed25519Signature().substring(0, 84) + "AA==");
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
                    .set(APPROVED_BY, "changed-owner")
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
        .hasStackTraceContaining("evidence is immutable");

    assertThatThrownBy(
            () ->
                fixture
                    .dsl()
                    .insertInto(ASSOCIATIONS)
                    .set(OPERATION_ID, uuid(81))
                    .set(DSL.field(DSL.name("schema_version"), Integer.class), 1)
                    .set(DSL.field(DSL.name("target_namespace"), String.class), NAMESPACE)
                    .set(DSL.field(DSL.name("signer_key_id"), String.class), SIGNER_KEY_ID)
                    .set(DSL.field(DSL.name("approved_by"), String.class), "owner@example.test")
                    .set(
                        DSL.field(DSL.name("approval_reference"), String.class),
                        "source-contradiction-test")
                    .set(DSL.field(DSL.name("signed_at"), String.class), "2026-10-01T00:00:00Z")
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
                    .set(
                        DSL.field(DSL.name("game_session_evidence_digest"), String.class),
                        GAME_SESSION_EVIDENCE_DIGEST)
                    .set(
                        DSL.field(DSL.name("manifest_digest"), String.class),
                        receipt.manifestDigest())
                    .set(DSL.field(DSL.name("signature"), String.class), receipt.ed25519Signature())
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
    migrate(dataSource, schema, MigrationVersion.fromVersion("29"));
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
        original.legacyGameSessionTenantId(),
        original.canonicalTenantId(),
        original.sourceGameRowId(),
        original.sourceGameTenantKey(),
        original.provenanceKind(),
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
      return new GameSessionTenantAssociationEvidence(
          1,
          operationId,
          NAMESPACE,
          SIGNER_KEY_ID,
          "approved-owner@example.test",
          "retained-session-audit-" + operationId,
          "2026-10-01T00:00:00Z",
          Long.toString(retainedGameSessionTenantId),
          canonicalTenantId,
          Long.toString(sourceGameRowId),
          sourceGameTenantKey,
          provenanceKind,
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
