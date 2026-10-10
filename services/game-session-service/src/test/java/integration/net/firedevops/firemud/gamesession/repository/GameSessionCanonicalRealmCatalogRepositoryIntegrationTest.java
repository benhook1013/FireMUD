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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void persistsExactFreshSelectorsAndSharedNamespaceWithoutChangingRetainedNumericEvidence() {
    Fixture fixture = fixture(true);
    IntakeReceipt source = fixture.register(tenant(1), "new-world", "Fresh World", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest request =
        request(source, uuid(20), "public-realm-east", "Public Realm", "owner-policy-v2", "SHARED");
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
                        "owner-mode",
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
  void requiresOwnerWriteTransactionRejectsChangedRequestAndDeniesRetainedProvenance() {
    Fixture fixture = fixture(false);
    IntakeReceipt source = fixture.register(tenant(2), "world-two", "World Two", "NEW_GAME_ROW");
    CreateCanonicalRealmCatalogRequest request =
        request(source, uuid(30), "realm", "Realm", "owner-mode", "SHARED");

    PreparedInitialPublicProduction prepared =
        fixture.catalogRepository.prepareInitialPublicProduction(request);
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
        request(source, request.requestId(), "realm", "Changed Realm", "owner-mode", "SHARED");
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
            "owner-mode",
            null,
            null);
    assertThatThrownBy(() -> fixture.catalogRepository.prepareInitialPublicProduction(wrongSource))
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
            "owner-mode",
            null,
            null);
    assertThatThrownBy(
            () -> fixture.catalogRepository.prepareInitialPublicProduction(mismatchedSource))
        .isInstanceOf(InvalidCatalogEvidenceException.class)
        .hasMessageContaining("conflicts with the immutable canonical tenant source binding");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);

    IntakeReceipt retained =
        fixture.register(tenant(3), "retained-world", "Retained World", "RETAINED_GAME_V29");
    assertThatThrownBy(
            () ->
                fixture.create(
                    request(retained, uuid(31), "retained-realm", "Realm", "owner-mode", "SHARED")))
        .isInstanceOf(InvalidCatalogEvidenceException.class)
        .hasMessageContaining("fresh Game Design");
    assertThat(fixture.dsl.fetchCount(DSL.table(DSL.name("game_session_canonical_realm_catalog"))))
        .isEqualTo(1);
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
            request(firstSource, uuid(40), "realm-four", "Realm Four", "owner-mode", "ISOLATED"));
    CanonicalRealmCatalogSnapshot second =
        fixture.create(
            request(secondSource, uuid(50), "realm-five", "Realm Five", "owner-mode", "ISOLATED"));

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
        request(source, uuid(60), "concurrent-realm", "Concurrent Realm", "owner-mode", "SHARED");
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
            "owner-mode",
            "SHARED");
    assertThatThrownBy(
            () ->
                associationFirst.catalogRepository.prepareInitialPublicProduction(afterAssociation))
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
                "owner-mode",
                "SHARED"));
    assertThatThrownBy(() -> catalogFirst.insertRetainedAssociation(catalogFirstSource, uuid(81)))
        .isInstanceOf(DataAccessException.class)
        .hasStackTraceContaining("cannot replace fresh canonical tenant namespace authority");
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

    Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history")
        .locations(MIGRATION_LOCATION)
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext transactionalDsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(
        transactionalDsl,
        new GameSessionAuthoredWorldSourceRepository(transactionalDsl),
        new GameSessionCanonicalRealmCatalogRepository(transactionalDsl),
        transactionManager,
        transactions);
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
      TransactionTemplate transactions) {
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

    CanonicalRealmCatalogSnapshot create(CreateCanonicalRealmCatalogRequest request) {
      PreparedInitialPublicProduction prepared =
          catalogRepository.prepareInitialPublicProduction(request);
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
