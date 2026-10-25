package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalRealmCatalogRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.CatalogConflictException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.InvalidCatalogEvidenceException;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalRealmCatalogRepository.PreparedInitialPublicProduction;
import net.firedevops.firemud.gamesession.repository.GameSessionFreshTenantAssociationRepository;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GameSessionCanonicalRealmCatalogRepositoryIntegrationTest {
  private static final String NAMESPACE = "canonical-realm-catalog-it";
  private static final long RETAINED_TENANT_ID = 917L;
  private static final long ODD_RETAINED_TENANT_ID = 918L;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final UUID RETAINED_REALM_ID = uuid(801);
  private static final UUID RETAINED_PLAYABLE_NAMESPACE_ID = uuid(802);
  private static final UUID RETAINED_SHARED_NAMESPACE_ID = uuid(803);
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void awaitPostgresJdbcReady() {
    PostgresBackedServiceTestSupport.awaitPostgresJdbcReady(postgres);
  }

  @Test
  void persistsExactFreshSelectorsAndSharedNamespaceWithoutChangingRetainedNumericEvidence() {
    Fixture fixture = fixture(true);
    IntakeReceipt source = fixture.register(tenant(1), "new-world", "Fresh World", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest request =
        request(source, uuid(20), "public-realm-east", "Public Realm", "PRESEEDED_ONLY", "SHARED");
    String retainedNamespaceXmin =
        requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin FROM gameplay_tenant_shared_playable_state_namespace "
                    + "WHERE tenant_id = ?",
                RETAINED_TENANT_ID)
            .get("xmin", String.class);
    String retainedPointerXmin =
        requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin FROM gameplay_admission_pointer WHERE tenant_id = ?",
                RETAINED_TENANT_ID)
            .get("xmin", String.class);
    String oddRetainedNamespaceXmin =
        requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin FROM gameplay_tenant_shared_playable_state_namespace "
                    + "WHERE tenant_id = ?",
                ODD_RETAINED_TENANT_ID)
            .get("xmin", String.class);

    CanonicalRealmCatalogSnapshot created = fixture.create(request);
    CanonicalRealmCatalogSnapshot replay = fixture.create(request);

    assertThat(created).isEqualTo(replay);
    assertThat(created.tenantId()).isEqualTo(tenant(1));
    assertThat(created.tenantSlug()).isEqualTo(source.source().tenantSlug());
    assertThat(created.worldSlug()).isEqualTo("new-world");
    assertThat(created.realmSlug()).isEqualTo("public-realm-east");
    assertThat(created.stateScope()).isEqualTo("SHARED");
    assertThat(created.catalogRevision()).isEqualTo(1L);
    assertThat(created.playableStateNamespaceId()).isNotEqualTo(RETAINED_SHARED_NAMESPACE_ID);
    assertThat(created.sourceIntakeReceipt()).isEqualTo(source);
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, request.requestId()))
        .contains(created);
    assertThat(fixture.catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, tenant(1)))
        .contains(created);

    assertThat(
            requiredRecord(
                fixture.dsl,
                "SELECT tenant_id, canonical_tenant_id, playable_state_namespace_id "
                    + "FROM gameplay_tenant_shared_playable_state_namespace "
                    + "WHERE canonical_tenant_id = ?",
                tenant(1)))
        .satisfies(
            row -> {
              assertThat(row.get("tenant_id", Long.class)).isNull();
              assertThat(row.get("canonical_tenant_id", UUID.class)).isEqualTo(tenant(1));
              assertThat(row.get("playable_state_namespace_id", UUID.class))
                  .isEqualTo(created.playableStateNamespaceId());
            });
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT playable_state_namespace_id FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("playable_state_namespace_id", UUID.class))
        .isEqualTo(RETAINED_SHARED_NAMESPACE_ID);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT realm_id, playable_state_namespace_id FROM gameplay_admission_pointer "
                        + "WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("realm_id", UUID.class))
        .isEqualTo(RETAINED_REALM_ID);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT playable_state_namespace_id FROM gameplay_admission_pointer WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("playable_state_namespace_id", UUID.class))
        .isEqualTo(RETAINED_PLAYABLE_NAMESPACE_ID);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT xmin::text AS xmin FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("xmin", String.class))
        .isEqualTo(retainedNamespaceXmin);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT xmin::text AS xmin FROM gameplay_admission_pointer WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("xmin", String.class))
        .isEqualTo(retainedPointerXmin);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT playable_state_namespace_id FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE tenant_id = ?",
                    ODD_RETAINED_TENANT_ID)
                .get("playable_state_namespace_id", UUID.class))
        .isEqualTo(NIL_UUID);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT xmin::text AS xmin FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE tenant_id = ?",
                    ODD_RETAINED_TENANT_ID)
                .get("xmin", String.class))
        .isEqualTo(oddRetainedNamespaceXmin);

    // The existing runtime selector remains supported by the new nullable-tenant identity layout.
    fixture.dsl.execute(
        "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
            + "(tenant_id, playable_state_namespace_id) VALUES (?, ?) "
            + "ON CONFLICT (tenant_id) DO NOTHING",
        RETAINED_TENANT_ID,
        uuid(804));
    assertThat(
            fixture.dsl.fetchCount(
                DSL.table(DSL.name("gameplay_tenant_shared_playable_state_namespace"))))
        .isEqualTo(3);
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT xmin::text AS xmin FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("xmin", String.class))
        .isEqualTo(retainedNamespaceXmin);

    assertThatThrownBy(
            () ->
                fixture.create(
                    request(
                        source,
                        uuid(21),
                        "another-realm",
                        "Another Realm",
                        "PRESEEDED_ONLY",
                        "SHARED")))
        .isInstanceOf(CatalogConflictException.class)
        .hasMessageContaining("already has a public-production");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE game_session_canonical_realm_catalog SET realm_slug = ? "
                        + "WHERE creation_request_id = ?",
                    "rewritten-realm",
                    request.requestId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM game_session_canonical_realm_catalog WHERE creation_request_id = ?",
                    request.requestId()))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(() -> fixture.dsl.execute("TRUNCATE game_session_canonical_realm_catalog"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot truncate a table referenced in a foreign key constraint");
    // The disposable schema's CASCADE form reaches the statement immutability guards after
    // PostgreSQL's separate catalog/pointer foreign-key precondition has been satisfied.
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE game_session_canonical_realm_catalog CASCADE"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("evidence is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE gameplay_tenant_shared_playable_state_namespace "
                        + "SET playable_state_namespace_id = ? WHERE canonical_tenant_id = ?",
                    uuid(805),
                    tenant(1)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("fresh shared namespace identity is immutable");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "DELETE FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE canonical_tenant_id = ?",
                    tenant(1)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("fresh shared namespace identity is immutable");
    assertThatThrownBy(
            () -> fixture.dsl.execute("TRUNCATE gameplay_tenant_shared_playable_state_namespace"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("fresh shared namespace identity is immutable");
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, request.requestId()))
        .contains(created);
  }

  @Test
  void historicalCatalogAndAssociationRemainUnchangedWithoutPoisoningFreshDiscovery() {
    Fixture fixture = fixture(false, MigrationVersion.fromVersion("40"));
    IntakeReceipt historicalSource =
        fixture.register(tenant(40), "historical-world", "Historical World", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest historicalRequest =
        request(
            historicalSource,
            uuid(1040),
            "historical-realm",
            "Historical Realm",
            "ALLOW_NEW",
            "ISOLATED");
    insertHistoricalFreshAssociation(fixture, historicalSource);
    insertCatalogRow(fixture, historicalSource, historicalRequest, java.util.Map.of());
    Record catalogBefore = retainedCatalogState(fixture, historicalRequest.requestId());
    Record associationBefore = retainedAssociationState(fixture, tenant(40));

    fixture.migrateToLatest();

    assertThat(retainedCatalogState(fixture, historicalRequest.requestId()))
        .isEqualTo(catalogBefore);
    assertThat(retainedAssociationState(fixture, tenant(40))).isEqualTo(associationBefore);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT complete_policy_set_evidence IS NULL AND fixture_policy_request_bytes IS NULL "
                        + "AND fixture_policy_seal_operation_id IS NULL AND fixture_policy_seal_request_digest IS NULL "
                        + "AND fixture_policy_seal_evidence_digest IS NULL AS historical "
                        + "FROM game_session_canonical_realm_catalog WHERE creation_request_id = ?",
                    historicalRequest.requestId())
                .get("historical", Boolean.class))
        .isTrue();
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT complete_source_evidence FROM game_session_fresh_tenant_association WHERE canonical_tenant_id = ?",
                    tenant(40))
                .get("complete_source_evidence"))
        .isNull();
    assertThat(fixture.catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE)).isEmpty();
    assertThatThrownBy(
            () -> fixture.catalogRepository.readByRequest(NAMESPACE, historicalRequest.requestId()))
        .isInstanceOf(InvalidCatalogEvidenceException.class)
        .hasMessageContaining("Exactly one complete catalog policy provenance required");
    assertThatThrownBy(
            () ->
                fixture.catalogRepository.readUniqueVisiblePublicProduction(NAMESPACE, tenant(40)))
        .isInstanceOf(InvalidCatalogEvidenceException.class);

    IntakeReceipt freshSource =
        fixture.register(tenant(41), "fresh-world", "Fresh World", "NEW_GAME_ROW");
    CanonicalRealmCatalogSnapshot fresh =
        fixture.create(
            request(
                freshSource, uuid(1041), "fresh-realm", "Fresh Realm", "PRESEEDED_ONLY", "SHARED"));
    assertThat(fixture.catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .containsExactly(fresh);
    assertThat(retainedCatalogState(fixture, historicalRequest.requestId()))
        .isEqualTo(catalogBefore);
    assertThat(retainedAssociationState(fixture, tenant(40))).isEqualTo(associationBefore);
  }

  @Test
  void discoveryStillRejectsMalformedEvidenceAndDatabaseRejectsPartialOrBothProvenances() {
    Fixture fixture = fixture(false);
    IntakeReceipt source =
        fixture.register(tenant(42), "invalid-world", "Invalid World", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest request =
        request(source, uuid(1042), "invalid-realm", "Invalid Realm", "ALLOW_NEW", "ISOLATED");
    for (String column :
        java.util.List.of(
            "fixture_policy_request_bytes", "fixture_policy_seal_operation_id",
            "fixture_policy_seal_request_digest", "fixture_policy_seal_evidence_digest")) {
      Object value =
          column.endsWith("bytes")
              ? new byte[] {1}
              : column.endsWith("operation_id") ? uuid(1043) : "sha256:" + "a".repeat(64);
      assertThatThrownBy(
              () -> insertCatalogRow(fixture, source, request, java.util.Map.of(column, value)))
          .isInstanceOf(DataAccessException.class);
    }
    assertThatThrownBy(
            () ->
                insertCatalogRow(
                    fixture,
                    source,
                    request,
                    java.util.Map.of(
                        "complete_policy_set_evidence",
                        new byte[] {1},
                        "fixture_policy_request_bytes",
                        new byte[] {1},
                        "fixture_policy_seal_operation_id",
                        uuid(1043),
                        "fixture_policy_seal_request_digest",
                        "sha256:" + "a".repeat(64),
                        "fixture_policy_seal_evidence_digest",
                        "sha256:" + "b".repeat(64))))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> insertCatalogRow(fixture, source, request, java.util.Map.of()))
        .isInstanceOf(DataAccessException.class);

    // The insert guard checks presence; authoritative readback validates the actual policy bytes.
    insertCatalogRow(
        fixture, source, request, java.util.Map.of("complete_policy_set_evidence", new byte[] {1}));
    assertThatThrownBy(
            () -> fixture.catalogRepository.readVisiblePublicProductionSnapshots(NAMESPACE))
        .isInstanceOf(InvalidCatalogEvidenceException.class);
  }

  @Test
  void requiresOwnerWriteTransactionRejectsChangedRequestAndDeniesRetainedProvenance() {
    Fixture fixture = fixture(false);
    IntakeReceipt source = fixture.register(tenant(2), "world-two", "World Two", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest request =
        request(source, uuid(30), "realm", "Realm", "PRESEEDED_ONLY", "SHARED");

    PreparedInitialPublicProduction prepared = fixture.prepare(request);
    assertThatThrownBy(() -> fixture.catalogRepository.createInitialPublicProduction(prepared))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active owner transaction");
    TransactionTemplate readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThatThrownBy(
            () ->
                readOnly.execute(
                    status -> fixture.catalogRepository.createInitialPublicProduction(prepared)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("read-write");

    CanonicalRealmCatalogSnapshot created = fixture.create(request);
    String beforeXmin =
        requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin FROM game_session_canonical_realm_catalog "
                    + "WHERE creation_request_id = ?",
                request.requestId())
            .get("xmin", String.class);
    CreateCanonicalRealmCatalogRequest changed =
        request(source, request.requestId(), "realm", "Changed Realm", "PRESEEDED_ONLY", "SHARED");
    assertThatThrownBy(() -> fixture.create(changed)).isInstanceOf(CatalogConflictException.class);
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, request.requestId()))
        .contains(created);
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT xmin::text AS xmin FROM game_session_canonical_realm_catalog "
                        + "WHERE creation_request_id = ?",
                    request.requestId())
                .get("xmin", String.class))
        .isEqualTo(beforeXmin);

    CreateCanonicalRealmCatalogRequest wrongSource =
        new CreateCanonicalRealmCatalogRequest(
            uuid(32),
            NAMESPACE,
            source.source().canonicalTenantId(),
            uuid(33),
            "other-realm",
            "Other Realm",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    assertThatThrownBy(() -> fixture.prepare(wrongSource))
        .isInstanceOf(InvalidCatalogEvidenceException.class)
        .hasMessageContaining("source intake is missing");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);

    IntakeReceipt otherTenantSource =
        fixture.register(tenant(9), "world-nine", "World Nine", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest mismatchedSource =
        new CreateCanonicalRealmCatalogRequest(
            uuid(34),
            NAMESPACE,
            source.source().canonicalTenantId(),
            otherTenantSource.operationId(),
            "mismatched-realm",
            "Mismatched Realm",
            true,
            true,
            "SHARED",
            "PRESEEDED_ONLY",
            null,
            null);
    assertThatThrownBy(() -> fixture.prepare(mismatchedSource))
        .isInstanceOf(InvalidCatalogEvidenceException.class)
        .hasMessageContaining("conflicts with the immutable canonical tenant source binding");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);

    IntakeReceipt retained =
        fixture.register(tenant(3), "retained-world", "Retained World", "RETAINED_GAME_V29");
    String retainedBindingXmin =
        requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin FROM game_session_authored_world_tenant_source_binding "
                    + "WHERE canonical_tenant_id = ?",
                retained.source().canonicalTenantId())
            .get("xmin", String.class);
    String retainedIntakeXmin =
        requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin FROM game_session_authored_world_source_intake "
                    + "WHERE operation_id = ?",
                retained.operationId())
            .get("xmin", String.class);
    assertThatThrownBy(
            () ->
                fixture.create(
                    request(
                        retained, uuid(31), "retained-realm", "Realm", "PRESEEDED_ONLY", "SHARED")))
        .isInstanceOf(InvalidCatalogEvidenceException.class)
        .hasMessageContaining("fresh Game Design");
    assertThat(
            requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin, provenance_kind FROM "
                    + "game_session_authored_world_tenant_source_binding WHERE canonical_tenant_id = ?",
                retained.source().canonicalTenantId()))
        .satisfies(
            row -> {
              assertThat(row.get("xmin", String.class)).isEqualTo(retainedBindingXmin);
              assertThat(row.get("provenance_kind", String.class)).isEqualTo("RETAINED_GAME_V29");
            });
    assertThat(
            requiredRecord(
                fixture.dsl,
                "SELECT xmin::text AS xmin, source_provenance_kind FROM "
                    + "game_session_authored_world_source_intake WHERE operation_id = ?",
                retained.operationId()))
        .satisfies(
            row -> {
              assertThat(row.get("xmin", String.class)).isEqualTo(retainedIntakeXmin);
              assertThat(row.get("source_provenance_kind", String.class))
                  .isEqualTo("RETAINED_GAME_V29");
            });
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
  }

  @Test
  void rawSqlRejectsV30AndUnknownProvenanceForNewSourceRows() {
    Fixture fixture = fixture(false);

    for (String provenance : new String[] {"RETAINED_GAME_V30", "UNKNOWN_KIND"}) {
      int suffix = provenance.equals("RETAINED_GAME_V30") ? 1 : 2;
      assertThatThrownBy(
              () ->
                  fixture.dsl.execute(
                      "INSERT INTO game_session_authored_world_tenant_source_binding "
                          + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                          + "source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, ?)",
                      NAMESPACE,
                      uuid(900 + suffix),
                      "raw-binding-" + suffix,
                      990L + suffix,
                      "legacy-key-" + suffix,
                      provenance))
          .isInstanceOf(DataAccessException.class)
          .hasStackTraceContaining("chk_gs_authored_world_tenant_source_provenance_v29");

      assertThatThrownBy(
              () ->
                  fixture.dsl.execute(
                      "INSERT INTO game_session_authored_world_source_intake ("
                          + "operation_id, schema_version, target_namespace, intake_request_id, "
                          + "request_digest, source_schema_version, source_registration_request_id, "
                          + "source_operation_id, source_request_digest, canonical_tenant_id, "
                          + "tenant_slug, world_slug, world_display_name, source_game_row_id, "
                          + "source_game_tenant_key, source_provenance_kind, source_evidence_digest, "
                          + "receipt_digest) VALUES (?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                      uuid(910 + suffix),
                      NAMESPACE,
                      uuid(920 + suffix),
                      "sha256:" + "a".repeat(64),
                      uuid(930 + suffix),
                      uuid(940 + suffix),
                      "sha256:" + "b".repeat(64),
                      uuid(900 + suffix),
                      "raw-binding-" + suffix,
                      "raw-world-" + suffix,
                      "Raw World",
                      990L + suffix,
                      "legacy-key-" + suffix,
                      provenance,
                      "sha256:" + "c".repeat(64),
                      "sha256:" + "d".repeat(64)))
          .isInstanceOf(DataAccessException.class)
          .hasStackTraceContaining("chk_gs_authored_world_source_intake_provenance_v29");
    }
  }

  @Test
  void v40PreservesExistingV30SourceRowsAndRejectsNewV30Rows() {
    Fixture fixture = fixture(false, MigrationVersion.fromVersion("39"));
    UUID canonicalTenantId = uuid(950);
    UUID intakeOperationId = uuid(951);
    String tenantSlug = "retained-v30-tenant";
    String worldSlug = "retained-v30-world";
    long sourceGameRowId = 9751L;
    String sourceGameTenantKey = "gds-retained-v30";
    String requestDigest = "sha256:" + "1".repeat(64);
    String sourceRequestDigest = "sha256:" + "2".repeat(64);
    String sourceEvidenceDigest = "sha256:" + "3".repeat(64);
    String receiptDigest = "sha256:" + "4".repeat(64);

    fixture.transactions.execute(
        status -> {
          fixture.dsl.execute(
              "INSERT INTO game_session_authored_world_tenant_source_binding "
                  + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                  + "source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, 'RETAINED_GAME_V30')",
              NAMESPACE,
              canonicalTenantId,
              tenantSlug,
              sourceGameRowId,
              sourceGameTenantKey);
          fixture.dsl.execute(
              "INSERT INTO game_session_authored_world_source_intake ("
                  + "operation_id, schema_version, target_namespace, intake_request_id, "
                  + "request_digest, source_schema_version, source_registration_request_id, "
                  + "source_operation_id, source_request_digest, canonical_tenant_id, tenant_slug, "
                  + "world_slug, world_display_name, source_game_row_id, source_game_tenant_key, "
                  + "source_provenance_kind, source_evidence_digest, receipt_digest) "
                  + "VALUES (?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RETAINED_GAME_V30', ?, ?)",
              intakeOperationId,
              NAMESPACE,
              uuid(952),
              requestDigest,
              uuid(953),
              uuid(954),
              sourceRequestDigest,
              canonicalTenantId,
              tenantSlug,
              worldSlug,
              "Retained V30 World",
              sourceGameRowId,
              sourceGameTenantKey,
              sourceEvidenceDigest,
              receiptDigest);
          return null;
        });

    Record retainedBindingBefore =
        requiredRecord(
            fixture.dsl,
            "SELECT *, xmin::text AS retained_xmin "
                + "FROM game_session_authored_world_tenant_source_binding "
                + "WHERE canonical_tenant_id = ?",
            canonicalTenantId);
    Record retainedIntakeBefore =
        requiredRecord(
            fixture.dsl,
            "SELECT *, xmin::text AS retained_xmin FROM game_session_authored_world_source_intake "
                + "WHERE operation_id = ?",
            intakeOperationId);

    fixture.migrateToLatest();

    Record retainedBindingAfter =
        requiredRecord(
            fixture.dsl,
            "SELECT *, xmin::text AS retained_xmin "
                + "FROM game_session_authored_world_tenant_source_binding "
                + "WHERE canonical_tenant_id = ?",
            canonicalTenantId);
    Record retainedIntakeAfter =
        requiredRecord(
            fixture.dsl,
            "SELECT *, xmin::text AS retained_xmin FROM game_session_authored_world_source_intake "
                + "WHERE operation_id = ?",
            intakeOperationId);
    assertThat(retainedBindingAfter).isEqualTo(retainedBindingBefore);
    assertThat(retainedIntakeAfter).isEqualTo(retainedIntakeBefore);
    assertThat(retainedIntakeAfter.get("request_digest", String.class)).isEqualTo(requestDigest);
    assertThat(retainedIntakeAfter.get("source_request_digest", String.class))
        .isEqualTo(sourceRequestDigest);
    assertThat(retainedIntakeAfter.get("source_evidence_digest", String.class))
        .isEqualTo(sourceEvidenceDigest);
    assertThat(retainedIntakeAfter.get("receipt_digest", String.class)).isEqualTo(receiptDigest);

    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "INSERT INTO game_session_authored_world_tenant_source_binding "
                        + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                        + "source_game_tenant_key, provenance_kind) "
                        + "VALUES (?, ?, ?, ?, ?, 'RETAINED_GAME_V30')",
                    NAMESPACE,
                    uuid(960),
                    "new-v30-tenant",
                    9760L,
                    "gds-new-v30"))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("chk_gs_authored_world_tenant_source_provenance_v29");
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "INSERT INTO game_session_authored_world_source_intake ("
                        + "operation_id, schema_version, target_namespace, intake_request_id, "
                        + "request_digest, source_schema_version, source_registration_request_id, "
                        + "source_operation_id, source_request_digest, canonical_tenant_id, tenant_slug, "
                        + "world_slug, world_display_name, source_game_row_id, source_game_tenant_key, "
                        + "source_provenance_kind, source_evidence_digest, receipt_digest) "
                        + "VALUES (?, 1, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "'RETAINED_GAME_V30', ?, ?)",
                    uuid(961),
                    NAMESPACE,
                    uuid(962),
                    requestDigest,
                    uuid(963),
                    uuid(964),
                    sourceRequestDigest,
                    uuid(965),
                    "new-v30-tenant",
                    "new-v30-world",
                    "New V30 World",
                    9761L,
                    "gds-new-v30-intake",
                    sourceEvidenceDigest,
                    receiptDigest))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("chk_gs_authored_world_source_intake_provenance_v29");
  }

  @Test
  void isolatedCreationAllocatesDistinctRealmLocalNamespacesOutsideSharedRegistry() {
    Fixture fixture = fixture(true);
    IntakeReceipt firstSource =
        fixture.register(tenant(4), "world-four", "World Four", "NEW_GAME_ROW");
    IntakeReceipt secondSource =
        fixture.register(tenant(5), "world-five", "World Five", "NEW_GAME_ROW");

    CanonicalRealmCatalogSnapshot first =
        fixture.create(
            request(
                firstSource, uuid(40), "realm-four", "Realm Four", "PRESEEDED_ONLY", "ISOLATED"));
    CanonicalRealmCatalogSnapshot second =
        fixture.create(
            request(
                secondSource, uuid(50), "realm-five", "Realm Five", "PRESEEDED_ONLY", "ISOLATED"));

    assertThat(first.playableStateNamespaceId()).isNotEqualTo(second.playableStateNamespaceId());
    assertThat(first.tenantSlug()).isNotEqualTo(first.worldSlug());
    assertThat(second.tenantSlug()).isNotEqualTo(second.worldSlug());
    assertThat(
            fixture.dsl.fetch(
                "SELECT canonical_tenant_id FROM gameplay_tenant_shared_playable_state_namespace "
                    + "WHERE canonical_tenant_id IS NOT NULL"))
        .isEmpty();
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, uuid(40))).contains(first);
    assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, uuid(50))).contains(second);
    assertThatThrownBy(
            () ->
                fixture.dsl.execute(
                    "UPDATE gameplay_tenant_shared_playable_state_namespace "
                        + "SET playable_state_namespace_id = ? WHERE tenant_id = ?",
                    first.playableStateNamespaceId(),
                    RETAINED_TENANT_ID))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Legacy tenant SHARED namespace conflicts with a canonical ISOLATED realm namespace");
    assertThat(
            requiredRecord(
                    fixture.dsl,
                    "SELECT playable_state_namespace_id FROM gameplay_tenant_shared_playable_state_namespace "
                        + "WHERE tenant_id = ?",
                    RETAINED_TENANT_ID)
                .get("playable_state_namespace_id", UUID.class))
        .isEqualTo(RETAINED_SHARED_NAMESPACE_ID);
  }

  @Test
  void concurrentExactCreationRecoversOneDurableRealmAndNamespace() throws Exception {
    Fixture fixture = fixture(false);
    IntakeReceipt source =
        fixture.register(tenant(6), "concurrent-world", "Concurrent World", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest request =
        request(
            source, uuid(60), "concurrent-realm", "Concurrent Realm", "PRESEEDED_ONLY", "SHARED");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<CanonicalRealmCatalogSnapshot> first =
          executor.submit(() -> raceCreate(fixture, ready, start, request));
      Future<CanonicalRealmCatalogSnapshot> second =
          executor.submit(() -> raceCreate(fixture, ready, start, request));
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      CanonicalRealmCatalogSnapshot firstResult = first.get(20, TimeUnit.SECONDS);
      CanonicalRealmCatalogSnapshot secondResult = second.get(20, TimeUnit.SECONDS);

      assertThat(firstResult).isEqualTo(secondResult);
      assertThat(fixture.catalogRepository.readByRequest(NAMESPACE, request.requestId()))
          .contains(firstResult);
      assertThat(
              fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
          .isEqualTo(1);
      assertThat(
              fixture.dsl.fetchCount(
                  DSL.table(DSL.name("gameplay_tenant_shared_playable_state_namespace"))))
          .isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void freshCatalogAndRetainedAssociationCannotBothAcquireTenantAuthorityInEitherOrder() {
    Fixture associationFirst = fixture(false);
    IntakeReceipt associationFirstSource =
        associationFirst.register(
            tenant(7), "association-first-world", "Association First", "NEW_GAME_ROW");
    associationFirst.insertRetainedAssociation(associationFirstSource, uuid(70));
    CreateCanonicalRealmCatalogRequest afterAssociation =
        request(
            associationFirstSource,
            uuid(71),
            "association-first-realm",
            "Association First Realm",
            "PRESEEDED_ONLY",
            "SHARED");
    assertThatThrownBy(() -> associationFirst.prepare(afterAssociation))
        .isInstanceOf(CatalogConflictException.class)
        .hasMessageContaining("retained numeric Game Session identity");
    assertThat(
            associationFirst.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isZero();
    assertThat(
            associationFirst.dsl.fetchCount(
                DSL.table(DSL.name("game_session_retained_tenant_association"))))
        .isEqualTo(1);

    Fixture catalogFirst = fixture(false);
    IntakeReceipt catalogFirstSource =
        catalogFirst.register(tenant(8), "catalog-first-world", "Catalog First", "NEW_GAME_ROW");
    CanonicalRealmCatalogSnapshot catalog =
        catalogFirst.create(
            request(
                catalogFirstSource,
                uuid(80),
                "catalog-first-realm",
                "Catalog First Realm",
                "PRESEEDED_ONLY",
                "SHARED"));
    assertThatThrownBy(() -> catalogFirst.insertRetainedAssociation(catalogFirstSource, uuid(81)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining(
            "Retained tenant mapping conflicts with a Game Session owner claim");
    assertThat(
            catalogFirst.dsl.fetchCount(
                DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
    assertThat(catalogFirst.catalogRepository.readByRequest(NAMESPACE, uuid(80))).contains(catalog);
    assertThat(
            catalogFirst.dsl.fetchCount(
                DSL.table(DSL.name("game_session_retained_tenant_association"))))
        .isZero();
  }

  private CanonicalRealmCatalogSnapshot raceCreate(
      Fixture fixture,
      CountDownLatch ready,
      CountDownLatch start,
      CreateCanonicalRealmCatalogRequest request)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Timed out waiting for canonical realm creation race");
    }
    return fixture.create(request);
  }

  private static CreateCanonicalRealmCatalogRequest request(
      IntakeReceipt source,
      UUID requestId,
      String realmSlug,
      String displayName,
      String policy,
      String scope) {
    return new CreateCanonicalRealmCatalogRequest(
        requestId,
        NAMESPACE,
        source.source().canonicalTenantId(),
        source.operationId(),
        realmSlug,
        displayName,
        true,
        true,
        scope,
        policy,
        null,
        null);
  }

  private static AuthoredWorldSourceEvidence source(
      UUID tenantId, String worldSlug, String displayName, String provenance) {
    UUID registrationRequestId =
        UUID.nameUUIDFromBytes(
            ("registration-" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    UUID sourceOperationId =
        UUID.nameUUIDFromBytes(
            ("operation-" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String tenantSlug = "tenant-" + tenantId.toString().substring(0, 8);
    long sourceGameRowId = Math.abs(tenantId.getMostSignificantBits()) + 10L;
    String sourceGameTenantKey = "gds-tenant-" + tenantId.toString().substring(0, 8);
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequestId, tenantId, tenantSlug, worldSlug, displayName);
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequestId,
            sourceOperationId,
            requestDigest,
            tenantId,
            tenantSlug,
            worldSlug,
            displayName,
            sourceGameRowId,
            sourceGameTenantKey,
            provenance);
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequestId,
        sourceOperationId,
        requestDigest,
        tenantId,
        tenantSlug,
        worldSlug,
        displayName,
        sourceGameRowId,
        sourceGameTenantKey,
        provenance,
        evidenceDigest);
  }

  private Fixture fixture(boolean seedRetainedNumericRows) {
    return fixture(seedRetainedNumericRows, null);
  }

  private Fixture fixture(boolean seedRetainedNumericRows, MigrationVersion targetVersion) {
    String schema = "gs_canonical_realm_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    dataSource.setSchema(schema);
    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .target(MigrationVersion.fromVersion("11"))
        .load()
        .migrate();

    DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
    if (seedRetainedNumericRows) {
      dsl.execute(
          "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
              + "(tenant_id, playable_state_namespace_id) VALUES (?, ?)",
          RETAINED_TENANT_ID,
          RETAINED_SHARED_NAMESPACE_ID);
      dsl.execute(
          "INSERT INTO gameplay_tenant_shared_playable_state_namespace "
              + "(tenant_id, playable_state_namespace_id) VALUES (?, ?)",
          ODD_RETAINED_TENANT_ID,
          NIL_UUID);
      dsl.execute(
          "INSERT INTO gameplay_admission_pointer "
              + "(world_slug, world_display_name, realm_slug, realm_display_name, tenant_id, "
              + "game_instance_id, pointer_version, visible, requires_character_selection, "
              + "state_scope, character_creation_policy, last_updated_by, last_update_reason, "
              + "public_production_realm, realm_id, playable_state_namespace_id) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          "legacy-world",
          "Legacy World",
          "legacy-realm",
          "Legacy Realm",
          RETAINED_TENANT_ID,
          501L,
          1L,
          true,
          false,
          "SHARED",
          "ALLOW_NEW",
          "fixture",
          "retained evidence",
          true,
          RETAINED_REALM_ID,
          RETAINED_PLAYABLE_NAMESPACE_ID);
    }

    FluentConfiguration migrationConfiguration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(MIGRATION_LOCATION);
    if (targetVersion != null) {
      migrationConfiguration.target(targetVersion);
    }
    migrationConfiguration.load().migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext transactionalDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(
        transactionalDsl,
        new GameSessionAuthoredWorldSourceRepository(transactionalDsl),
        new GameSessionCanonicalRealmCatalogRepository(transactionalDsl),
        transactionManager,
        transactions,
        dataSource,
        schema,
        new java.util.concurrent.ConcurrentHashMap<>());
  }

  private static Record retainedCatalogState(Fixture fixture, UUID requestId) {
    return requiredRecord(
        fixture.dsl,
        "SELECT xmin::text AS xmin, (to_jsonb(catalog) - ARRAY['complete_policy_set_evidence', "
            + "'fixture_policy_request_bytes', 'fixture_policy_seal_operation_id', "
            + "'fixture_policy_seal_request_digest', 'fixture_policy_seal_evidence_digest'])::text AS bytes "
            + "FROM game_session_canonical_realm_catalog catalog WHERE creation_request_id = ?",
        requestId);
  }

  private static Record retainedAssociationState(Fixture fixture, UUID tenantId) {
    return requiredRecord(
        fixture.dsl,
        "SELECT xmin::text AS xmin, (to_jsonb(association) - 'complete_source_evidence')::text AS bytes "
            + "FROM game_session_fresh_tenant_association association WHERE canonical_tenant_id = ?",
        tenantId);
  }

  private static void insertHistoricalFreshAssociation(Fixture fixture, IntakeReceipt receipt) {
    AuthoredWorldSourceEvidence source = receipt.source();
    UUID operationId = uuid(1044);
    long privateTenantId = 1044L;
    fixture.transactions.execute(
        status -> {
          fixture.dsl.execute(
              "INSERT INTO game_session_tenant_scope_reservation "
                  + "(game_session_tenant_id, reservation_kind) VALUES (?, 'FRESH_SOURCE_BOUND')",
              privateTenantId);
          fixture.dsl.execute(
              "INSERT INTO game_session_tenant_canonical_claim "
                  + "(target_namespace, canonical_tenant_id, legacy_game_session_tenant_id, association_kind, "
                  + "reservation_kind, association_operation_id, association_request_id) "
                  + "VALUES (?, ?, ?, 'FRESH_SOURCE_BOUND', 'FRESH_SOURCE_BOUND', ?, ?)",
              NAMESPACE,
              source.canonicalTenantId(),
              privateTenantId,
              operationId,
              source.registrationRequestId());
          fixture.dsl.execute(
              "INSERT INTO game_session_fresh_tenant_association "
                  + "(association_operation_id, target_namespace, association_request_id, source_schema_version, "
                  + "source_target_namespace, source_request_id, source_canonical_tenant_id, canonical_tenant_id, "
                  + "legacy_game_session_tenant_id, source_game_row_id, source_game_tenant_key, provenance_kind) "
                  + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'NEW_GAME_ROW')",
              operationId,
              NAMESPACE,
              source.registrationRequestId(),
              NAMESPACE,
              source.registrationRequestId(),
              source.canonicalTenantId(),
              source.canonicalTenantId(),
              privateTenantId,
              source.sourceGameRowId(),
              source.sourceGameTenantKey());
          return null;
        });
  }

  private static void insertCatalogRow(
      Fixture fixture,
      IntakeReceipt receipt,
      CreateCanonicalRealmCatalogRequest request,
      java.util.Map<String, Object> provenance) {
    Record intake =
        requiredRecord(
            fixture.dsl,
            "SELECT * FROM game_session_authored_world_source_intake WHERE operation_id = ?",
            receipt.operationId());
    java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
    for (String field :
        java.util.List.of("target_namespace", "canonical_tenant_id", "tenant_slug", "world_slug")) {
      values.put(field, intake.get(field));
    }
    UUID realmId = request.requestId();
    UUID namespaceId =
        UUID.nameUUIDFromBytes(
            ("historical-namespace/" + realmId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String requestDigest = historicalRequestDigest(request, receipt);
    values.put("realm_id", realmId);
    values.put("realm_slug", request.realmSlug());
    values.put("realm_display_name", request.realmDisplayName());
    values.put("visible", true);
    values.put("public_production", true);
    values.put("state_scope", request.stateScope());
    values.put("playable_state_namespace_id", namespaceId);
    values.put("character_creation_policy", request.characterCreationPolicy());
    values.put("catalog_revision", 1L);
    values.put("creation_request_id", request.requestId());
    values.put("request_digest", requestDigest);
    values.put(
        "receipt_digest",
        historicalReceiptDigest(request, receipt, realmId, namespaceId, requestDigest));
    values.put("source_intake_operation_id", receipt.operationId());
    values.put("source_intake_schema_version", 1);
    values.put("source_intake_request_id", receipt.intakeRequestId());
    values.put("source_intake_request_digest", receipt.requestDigest());
    values.put("source_intake_receipt_digest", receipt.receiptDigest());
    for (String field :
        java.util.List.of(
            "source_schema_version",
            "source_registration_request_id",
            "source_operation_id",
            "source_request_digest",
            "source_game_row_id",
            "source_game_tenant_key",
            "source_provenance_kind",
            "source_evidence_digest")) {
      values.put(field, intake.get(field));
    }
    values.put("source_world_display_name", intake.get("world_display_name"));
    values.putAll(provenance);
    fixture
        .dsl
        .insertInto(DSL.table(DSL.name("game_session_canonical_realm_catalog")))
        .columns(values.keySet().stream().map(name -> DSL.field(DSL.name(name))).toList())
        .values(values.values())
        .execute();
  }

  private static String historicalRequestDigest(
      CreateCanonicalRealmCatalogRequest request, IntakeReceipt receipt) {
    AuthoredWorldSourceEvidence source = receipt.source();
    // Independent V23 framing: decimal UTF-8 byte length, colon, then exact bytes per field.
    return historicalDigest(
        java.util.List.of(
            "game-session-canonical-realm-catalog-request/v1",
            "1",
            request.requestId().toString(),
            request.targetNamespace(),
            request.canonicalTenantId().toString(),
            request.sourceIntakeOperationId().toString(),
            receipt.intakeRequestId().toString(),
            receipt.requestDigest(),
            receipt.receiptDigest(),
            Integer.toString(source.schemaVersion()),
            source.registrationRequestId().toString(),
            source.operationId().toString(),
            source.requestDigest(),
            source.canonicalTenantId().toString(),
            source.tenantSlug(),
            source.worldSlug(),
            source.worldDisplayName(),
            Long.toString(source.sourceGameRowId()),
            source.sourceGameTenantKey(),
            source.provenanceKind(),
            source.evidenceDigest(),
            request.realmSlug(),
            request.realmDisplayName(),
            Boolean.toString(request.visible()),
            Boolean.toString(request.publicProduction()),
            request.stateScope(),
            request.characterCreationPolicy(),
            "ABSENT",
            "ABSENT"));
  }

  private static String historicalReceiptDigest(
      CreateCanonicalRealmCatalogRequest request,
      IntakeReceipt receipt,
      UUID realmId,
      UUID namespaceId,
      String requestDigest) {
    return historicalDigest(
        java.util.List.of(
            "game-session-canonical-realm-catalog-receipt/v1",
            "1",
            request.targetNamespace(),
            request.canonicalTenantId().toString(),
            request.requestId().toString(),
            requestDigest,
            realmId.toString(),
            namespaceId.toString(),
            "1",
            receipt.operationId().toString(),
            receipt.receiptDigest()));
  }

  private static String historicalDigest(java.util.List<String> fields) {
    try {
      java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
      for (String value : fields) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        hash.update(
            Integer.toString(bytes.length).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        hash.update((byte) ':');
        hash.update(bytes);
      }
      return "sha256:" + java.util.HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static UUID tenant(int value) {
    return uuid(100 + value);
  }

  private static Record requiredRecord(DSLContext dsl, String sql, Object... bindings) {
    return java.util.Objects.requireNonNull(
        dsl.fetchOne(sql, bindings), "Expected owner evidence row is missing");
  }

  private static UUID uuid(int value) {
    return UUID.fromString(String.format("%08d-1111-4111-8111-111111111111", value));
  }

  private record Fixture(
      DSLContext dsl,
      GameSessionAuthoredWorldSourceRepository sourceRepository,
      GameSessionCanonicalRealmCatalogRepository catalogRepository,
      DataSourceTransactionManager transactionManager,
      TransactionTemplate transactions,
      DriverManagerDataSource dataSource,
      String schema,
      java.util.Map<
              CreateCanonicalRealmCatalogRequest,
              net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidence.PublishedSet>
          publishedSets) {
    void migrateToLatest() {
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
    }

    IntakeReceipt register(UUID tenantId, String worldSlug, String displayName, String provenance) {
      AuthoredWorldSourceEvidence source =
          GameSessionCanonicalRealmCatalogRepositoryIntegrationTest.source(
              tenantId, worldSlug, displayName, provenance);
      UUID intakeRequestId =
          UUID.nameUUIDFromBytes(
              ("intake-" + tenantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      IntakeReceipt receipt =
          transactions.execute(status -> sourceRepository.register(intakeRequestId, source));
      return java.util.Objects.requireNonNull(receipt);
    }

    PreparedInitialPublicProduction prepare(CreateCanonicalRealmCatalogRequest request) {
      Record sourceRow =
          dsl.fetchOne(
              "SELECT world_slug FROM game_session_authored_world_source_intake "
                  + "WHERE target_namespace = ? AND operation_id = ?",
              request.targetNamespace(),
              request.sourceIntakeOperationId());
      if (sourceRow == null) return catalogRepository.prepareInitialPublicProduction(request, null);
      IntakeReceipt source =
          sourceRepository
              .read(
                  request.sourceIntakeOperationId(),
                  request.canonicalTenantId(),
                  sourceRow.get("world_slug", String.class),
                  request.targetNamespace())
              .orElse(null);
      if (source == null) return catalogRepository.prepareInitialPublicProduction(request, null);
      boolean retained =
          Boolean.TRUE.equals(
              dsl.fetchSingle(
                      "SELECT EXISTS (SELECT 1 FROM game_session_retained_tenant_association "
                          + "WHERE target_namespace = ? AND canonical_tenant_id = ?) AS retained",
                      request.targetNamespace(),
                      request.canonicalTenantId())
                  .get("retained", Boolean.class));
      if (retained || !"NEW_GAME_ROW".equals(source.source().provenanceKind())) {
        return catalogRepository.prepareInitialPublicProduction(request, null);
      }
      transactions.execute(
          status ->
              new GameSessionFreshTenantAssociationRepository(dsl)
                  .associateFresh(
                      UUID.nameUUIDFromBytes(
                          ("gs-association/" + source.source().canonicalTenantId())
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                      source.source().targetNamespace(),
                      source,
                      CanonicalHumanPublicationOwnerTestFixtures.creationEvidence(
                          source.source())));
      UUID versionId =
          UUID.nameUUIDFromBytes(
              ("controlled-version/" + source.source().canonicalTenantId())
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return catalogRepository.prepareInitialPublicProduction(
          request,
          publishedSets.computeIfAbsent(
              request,
              selected ->
                  CanonicalHumanPublicationOwnerTestFixtures.publishedSet(
                      selected,
                      source.source(),
                      versionId,
                      72L,
                      "publish-policy-" + selected.requestId(),
                      "release-bundle:" + versionId,
                      "generation-1",
                      3L)));
    }

    CanonicalRealmCatalogSnapshot create(CreateCanonicalRealmCatalogRequest request) {
      PreparedInitialPublicProduction prepared = prepare(request);
      CanonicalRealmCatalogSnapshot snapshot =
          transactions.execute(status -> catalogRepository.createInitialPublicProduction(prepared));
      return java.util.Objects.requireNonNull(snapshot);
    }

    void insertRetainedAssociation(IntakeReceipt source, UUID requestId) {
      AuthoredWorldSourceEvidence evidence = source.source();
      String digest = "sha256:" + "a".repeat(64);
      UUID operationId =
          UUID.nameUUIDFromBytes(
              ("retained-operation-" + requestId)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      dsl.execute(
          "INSERT INTO game_session_retained_tenant_association ("
              + "operation_id, target_namespace, association_request_id, "
              + "legacy_game_session_tenant_id, canonical_tenant_id, source_game_row_id, "
              + "source_game_tenant_key, provenance_kind) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          operationId,
          NAMESPACE,
          requestId,
          RETAINED_TENANT_ID,
          evidence.canonicalTenantId(),
          evidence.sourceGameRowId(),
          evidence.sourceGameTenantKey(),
          evidence.provenanceKind());
      dsl.execute(
          "INSERT INTO game_session_retained_tenant_association_payload ("
              + "operation_id, request_digest, approval_operation_id, approval_schema_version, "
              + "signer_key_id, approved_by, approval_reference, signed_at, "
              + "game_session_evidence_digest, approval_manifest_digest, approval_signature, "
              + "snapshot_canonical_json, snapshot_evidence_digest, receipt_digest) "
              + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          operationId,
          digest,
          UUID.nameUUIDFromBytes(
              ("approval-operation-" + requestId)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          "fixture-key",
          "fixture-operator",
          "fixture-reference",
          "2026-10-03T00:00:00Z",
          evidence.evidenceDigest(),
          digest,
          "A".repeat(86) + "==",
          "{\"canonicalTenantId\":\""
              + evidence.canonicalTenantId()
              + "\",\"sourceGameRowId\":"
              + evidence.sourceGameRowId()
              + ",\"sourceGameTenantKey\":\""
              + evidence.sourceGameTenantKey()
              + "\",\"provenanceKind\":\""
              + evidence.provenanceKind()
              + "\"}",
          digest,
          digest);
    }
  }
}
