package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.repository.GameplayAdmissionPointerEventRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindAttemptRepository;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import net.firedevops.firemud.gamesession.service.impl.DatabaseInitialAdmissionBindOwnerService;
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
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class InitialAdmissionBindPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String PUBLISHED_NAMESPACE = "published-bind-test";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void initialAdmissionLedgerCommitsPointerAuditAndAttemptTogetherAndReadbackFailsClosed() {
    String schema = schemaName();
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    GameInstanceRepository gameInstanceRepository = new GameInstanceRepository(dsl);
    GameInstance gameInstance = gameInstanceRepository.save(gameInstance());
    DatabaseInitialAdmissionBindOwnerService service =
        new DatabaseInitialAdmissionBindOwnerService(
            new InitialAdmissionBindCatalogRepository(dsl),
            new InitialAdmissionBindAttemptRepository(dsl),
            new GameplayAdmissionPointerEventRepository(dsl),
            gameInstanceRepository);

    InitialAdmissionBindCatalogSnapshot catalog =
        transactionTemplate.execute(
            status ->
                toSnapshot(
                    service.registerPublicSharedFixtureCatalog(
                        new InitialAdmissionBindCatalogDescriptor(
                            41L, 810L, "smoke", "Smoke World", "production", "Live Realm", true))));
    var request =
        new InitialAdmissionBindRequest(
            41L,
            "smoke",
            "production",
            "pg-initial-bind-" + gameInstance.getId(),
            "c".repeat(64),
            gameInstance.getId(),
            902L,
            13L);
    var attempt = transactionTemplate.execute(status -> service.beginIntent(request));
    var binding =
        new InitialAdmissionBindHoldBinding(
            "291787b2-ed31-4ae0-9d72-1f872931e7ed",
            "ea29d3c4-bb4c-4ce5-8f81-8fd60d21b472",
            41L,
            catalog.realmId(),
            catalog.namespaceId(),
            "SHARED",
            gameInstance.getId(),
            902L,
            13L,
            request.initialAdmissionRequestId(),
            request.requestDigest(),
            true,
            1L);
    transactionTemplate.execute(status -> service.attachHold(binding));

    dsl.execute(
        "CREATE FUNCTION reject_initial_admission_audit() RETURNS trigger LANGUAGE plpgsql AS $$ "
            + "BEGIN IF NEW.reason = 'initial admission pointer bind' THEN "
            + "RAISE EXCEPTION 'forced initial admission audit failure'; END IF; RETURN NEW; END; $$");
    dsl.execute(
        "CREATE TRIGGER reject_initial_admission_audit BEFORE INSERT ON "
            + "gameplay_admission_pointer_event FOR EACH ROW EXECUTE FUNCTION "
            + "reject_initial_admission_audit()");
    assertThatThrownBy(() -> transactionTemplate.execute(status -> service.commit(binding)))
        .isInstanceOf(RuntimeException.class);
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isZero();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT status FROM gameplay_initial_admission_bind_attempt WHERE attempt_id = ?",
                        attempt.attemptId()),
                    "expected retained initial-admission bind attempt after audit rollback")
                .get("status", String.class))
        .isEqualTo("PENDING");
    dsl.execute("DROP TRIGGER reject_initial_admission_audit ON gameplay_admission_pointer_event");
    dsl.execute("DROP FUNCTION reject_initial_admission_audit()");

    var committed = transactionTemplate.execute(status -> service.commit(binding));
    assertThat(committed.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.COMMITTED);
    assertThat(committed.ownerProofId()).isEqualTo(attempt.attemptId().toString());
    assertThat(committed.pointerAuditRequestDigest()).isEqualTo(request.requestDigest());
    assertThat(committed.pointerVersion()).isEqualTo(1L);
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT pointer.catalog_revision AS pointer_revision, "
                            + "event.catalog_revision AS event_revision, pointer.realm_id, "
                            + "event.realm_id AS event_realm_id, pointer.playable_state_namespace_id, "
                            + "event.playable_state_namespace_id AS event_namespace_id "
                            + "FROM gameplay_initial_admission_bind_attempt attempt "
                            + "JOIN gameplay_admission_pointer pointer ON pointer.id = attempt.pointer_id "
                            + "JOIN gameplay_admission_pointer_event event ON event.id = attempt.audit_event_id "
                            + "WHERE attempt.attempt_id = ?",
                        attempt.attemptId()),
                    "expected committed initial-admission pointer/audit row")
                .get("pointer_revision", Long.class))
        .isEqualTo(1L);
    var evidence =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT event.catalog_revision AS event_revision, event.realm_id AS event_realm_id, "
                    + "event.playable_state_namespace_id AS event_namespace_id "
                    + "FROM gameplay_initial_admission_bind_attempt attempt "
                    + "JOIN gameplay_admission_pointer_event event ON event.id = attempt.audit_event_id "
                    + "WHERE attempt.attempt_id = ?",
                attempt.attemptId()),
            "expected committed initial-admission audit evidence row");
    assertThat(evidence.get("event_revision", Long.class)).isEqualTo(1L);
    assertThat(evidence.get("event_realm_id", java.util.UUID.class).toString())
        .isEqualTo(catalog.realmId());
    assertThat(evidence.get("event_namespace_id", java.util.UUID.class).toString())
        .isEqualTo(catalog.namespaceId());
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isEqualTo(1);
  }

  @Test
  void publishedSnapshotBindsWithoutCreatingOrReadingAV9FixtureCatalog() {
    String schema = schemaName();
    DriverManagerDataSource dataSource = dataSource(schema);
    migrate(dataSource, schema);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    GameInstanceRepository gameInstanceRepository = new GameInstanceRepository(dsl);
    GameInstance runtime = gameInstance();
    runtime.setLaunchDescriptorId("published-launch-v1");
    runtime.setReleaseBundleId(77L);
    runtime.setVersionStateEpoch(4L);
    runtime.setRunOwnedStartRequestId("4c31b7b9-e9e8-41a4-a5b1-334db8bc49ae");
    runtime.setRunOwnedStartPublishedReleaseBundleRef("prb:41:902:77");
    GameInstance savedRuntime = gameInstanceRepository.save(runtime);
    seedAssociation(dsl, 41L, CANONICAL_TENANT_ID, 731L, "source-game-key-not-local-id");
    InitialAdmissionBindCatalogRepository catalogRepository =
        new InitialAdmissionBindCatalogRepository(dsl);
    PublishedRealmEntryPolicySetEvidence policySet = publishedPolicySet();
    var snapshot =
        transactionTemplate.execute(
            status ->
                catalogRepository.materializePublishedSnapshot(
                    PUBLISHED_NAMESPACE,
                    41L,
                    CANONICAL_TENANT_ID,
                    731L,
                    "source-game-key-not-local-id",
                    "NEW_GAME_ROW",
                    policySet));
    var entry = Objects.requireNonNull(snapshot).entries().getFirst();
    DatabaseInitialAdmissionBindOwnerService service =
        new DatabaseInitialAdmissionBindOwnerService(
            catalogRepository,
            new InitialAdmissionBindAttemptRepository(dsl),
            new GameplayAdmissionPointerEventRepository(dsl),
            gameInstanceRepository);
    String requestId = "4c31b7b9-e9e8-41a4-a5b1-334db8bc49ae";
    String digest = "b".repeat(64);
    var request =
        new InitialAdmissionBindRequest(
            41L,
            "firemud",
            "main",
            requestId,
            digest,
            savedRuntime.getId(),
            902L,
            13L,
            new InitialAdmissionBindRequest.PublishedCatalogBinding(
                PUBLISHED_NAMESPACE, CANONICAL_TENANT_ID, snapshot.catalogRevision()),
            new InitialAdmissionBindRequest.LaunchEvidence(
                810L, "published-launch-v1", 77L, "prb:41:902:77", 4L));

    assertThat(dsl.fetchCount(DSL.table("gameplay_initial_admission_bind_catalog"))).isZero();
    var attempt = transactionTemplate.execute(status -> service.beginIntent(request));
    assertThatThrownBy(
            () ->
                transactionTemplate.execute(
                    status ->
                        service.beginIntent(
                            new InitialAdmissionBindRequest(
                                41L,
                                "firemud",
                                "main",
                                requestId,
                                "c".repeat(64),
                                savedRuntime.getId(),
                                902L,
                                13L,
                                request.publishedCatalog(),
                                request.launchEvidence()))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isZero();

    assertThat(attempt.catalogSourceKind()).isEqualTo("V14_PUBLISHED");
    assertThat(attempt.tenantId()).isEqualTo(41L);
    assertThat(attempt.canonicalTenantId()).isEqualTo(CANONICAL_TENANT_ID);
    assertThat(attempt.publishedTargetNamespace()).isEqualTo(PUBLISHED_NAMESPACE);
    assertThat(attempt.realmId()).isEqualTo(entry.realmId());
    assertThat(attempt.playableStateNamespaceId()).isEqualTo(entry.playableStateNamespaceId());
    assertThat(attempt.gameTemplateId()).isEqualTo(810L);
    assertThat(attempt.versionId()).isEqualTo(policySet.versionId());
    var binding =
        new InitialAdmissionBindHoldBinding(
            "291787b2-ed31-4ae0-9d72-1f872931e7ed",
            "ea29d3c4-bb4c-4ce5-8f81-8fd60d21b472",
            41L,
            entry.realmId().toString(),
            entry.playableStateNamespaceId().toString(),
            "SHARED",
            savedRuntime.getId(),
            902L,
            13L,
            requestId,
            digest,
            true,
            snapshot.catalogRevision());
    transactionTemplate.execute(status -> service.attachHold(binding));
    var proof = transactionTemplate.execute(status -> service.commit(binding));
    assertThat(proof.outcome()).isEqualTo(InitialAdmissionBindOwnerProof.Outcome.COMMITTED);
    assertThat(proof.pointerAuditRequestDigest()).isEqualTo(digest);
    assertThat(dsl.fetchCount(DSL.table("gameplay_initial_admission_bind_catalog"))).isZero();
    assertThat(
            dsl.fetchOne(
                    "SELECT character_creation_policy, requires_character_selection, state_scope "
                        + "FROM gameplay_admission_pointer WHERE tenant_id = 41")
                .get("character_creation_policy", String.class))
        .isEqualTo("PRESEEDED_ONLY");
    assertThat(
            dsl.fetchOne(
                    "SELECT requires_character_selection FROM gameplay_admission_pointer "
                        + "WHERE tenant_id = 41")
                .get("requires_character_selection", Boolean.class))
        .isTrue();
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer"))).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("gameplay_admission_pointer_event"))).isEqualTo(1);
  }

  private static String schemaName() {
    return "gs_initial_admission_bind_" + UUID.randomUUID().toString().replace("-", "");
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private static void migrate(DriverManagerDataSource dataSource, String schema) {
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
  }

  private static GameInstance gameInstance() {
    GameInstance gameInstance = new GameInstance();
    gameInstance.setTenantId(41L);
    gameInstance.setRuntimeVersion("smoke-1");
    gameInstance.setOwnerAccountId(1001L);
    gameInstance.setStatus("RUNNING");
    gameInstance.setGameTemplateId(810L);
    gameInstance.setVersionId(902L);
    gameInstance.setGameTemplateId(810L);
    return gameInstance;
  }

  private static void seedAssociation(
      DSLContext dsl,
      long tenantId,
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey) {
    dsl.execute(
        "INSERT INTO game_session_retained_tenant_association "
            + "(operation_id, target_namespace, association_request_id, "
            + "legacy_game_session_tenant_id, canonical_tenant_id, source_game_row_id, "
            + "source_game_tenant_key, provenance_kind, terminal_outcome) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW', 'ASSOCIATED')",
        UUID.fromString("a2b30fc4-c2d9-40f9-81a8-e559f6ab672e"),
        PUBLISHED_NAMESPACE,
        UUID.fromString("34d29e68-15d7-4f8a-9bc9-2289aa91563a"),
        tenantId,
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey);
  }

  private static PublishedRealmEntryPolicySetEvidence publishedPolicySet() {
    long versionId = 902L;
    String workflow = "published-initial-bind";
    String manifest = "manifest-v1";
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            CANONICAL_TENANT_ID, versionId, workflow, manifest, JSON);
    String policyJson =
        "{\"schemaVersion\":1,\"worldSlug\":\"firemud\","
            + "\"worldDisplayName\":\"FireMUD\",\"realmSlug\":\"main\","
            + "\"realmDisplayName\":\"Main\",\"visible\":true,"
            + "\"publicProduction\":true,\"stateScope\":\"SHARED\","
            + "\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    var policy = RealmEntryPolicy.parse(policyJson, JSON);
    var evidence =
        PublishedRealmEntryPolicyEvidence.create(
            UUID.nameUUIDFromBytes("published-policy".getBytes(StandardCharsets.UTF_8)),
            CANONICAL_TENANT_ID,
            "NEW_GAME_ROW",
            731L,
            "source-game-key-not-local-id",
            versionId,
            1,
            901L,
            releaseIdentity,
            workflow,
            manifest,
            policy,
            JSON);
    return PublishedRealmEntryPolicySetEvidence.create(
        CANONICAL_TENANT_ID,
        versionId,
        1,
        releaseIdentity,
        workflow,
        manifest,
        List.of(evidence),
        JSON);
  }

  private static InitialAdmissionBindCatalogSnapshot toSnapshot(
      net.firedevops.firemud.gamesession.entity.InitialAdmissionBindCatalog catalog) {
    return new InitialAdmissionBindCatalogSnapshot(
        catalog.realmId().toString(), catalog.playableStateNamespaceId().toString());
  }

  private record InitialAdmissionBindCatalogSnapshot(String realmId, String namespaceId) {}
}
