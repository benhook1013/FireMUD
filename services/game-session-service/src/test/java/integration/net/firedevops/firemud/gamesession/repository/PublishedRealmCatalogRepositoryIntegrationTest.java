package integration.net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogEntry.NamespaceResolution;
import net.firedevops.firemud.gamesession.entity.PublishedRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.repository.InitialAdmissionBindCatalogRepository;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.Table;
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
class PublishedRealmCatalogRepositoryIntegrationTest {
  private static final String NAMESPACE = "published-catalog-test";
  private static final long TENANT_ID = 70123L;
  private static final long OTHER_TENANT_ID = 70124L;
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID OTHER_CANONICAL_TENANT_ID =
      UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String PROVENANCE_KIND = "NEW_GAME_ROW";
  private static final String WORKFLOW = "published-catalog-integration";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final String SIGNATURE = "A".repeat(86) + "==";
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final Table<?> ADMISSION_POINTER =
      DSL.table(DSL.name("gameplay_admission_pointer"));

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Test
  void snapshotsRetryExactlyKeepStableRealmAndSharedIdentityAndGateLifecycleTransitions() {
    Fixture fixture = fixture();
    int originalPointerCount = fixture.dsl().fetchCount(ADMISSION_POINTER);
    fixture.seedAssociation(TENANT_ID, CANONICAL_TENANT_ID, 501L, "source-game-501");

    PublishedRealmEntryPolicySetEvidence firstSet =
        evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 100L, 1, "Main", "SHARED");
    PublishedRealmCatalogSnapshot first = fixture.materialize(TENANT_ID, firstSet);
    PublishedRealmCatalogSnapshot retry = fixture.materialize(TENANT_ID, firstSet);
    assertThat(retry).isEqualTo(first);
    assertThat(first.tenantId()).isEqualTo(TENANT_ID);
    assertThat(first.sourceGameRowId()).isEqualTo(501L);
    assertThat(first.catalogRevision()).isEqualTo(1L);
    assertThat(first.entries())
        .allSatisfy(
            entry ->
                assertThat(entry.namespaceResolution()).isEqualTo(NamespaceResolution.RESOLVED));
    UUID mainRealmId = first.entries().getFirst().realmId();
    UUID sharedNamespaceId = first.entries().getFirst().playableStateNamespaceId();
    assertThat(first.entries().get(1).playableStateNamespaceId()).isEqualTo(sharedNamespaceId);
    assertThat(first.requireVisibleEntryForAdmission("firemud", "main").realmId())
        .isEqualTo(mainRealmId);
    assertThatThrownBy(() -> first.requireVisibleEntryForAdmission("firemud", "side"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_ENTRY_HIDDEN");
    assertThat(first.entries().getFirst().policyEvidence().policy().realmDisplayName())
        .isEqualTo("Main");

    PublishedRealmEntryPolicySetEvidence changedReuse =
        evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 100L, 1, "Changed", "SHARED");
    assertThatThrownBy(() -> fixture.materialize(TENANT_ID, changedReuse))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_VERSION_CONFLICT");

    PublishedRealmEntryPolicySetEvidence secondSet =
        evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 101L, 2, "Renamed", "SHARED");
    PublishedRealmCatalogSnapshot second = fixture.materialize(TENANT_ID, secondSet);
    assertThat(second.catalogRevision()).isEqualTo(2L);
    assertThat(second.entries().getFirst().realmId()).isEqualTo(mainRealmId);
    assertThat(second.entries().getFirst().playableStateNamespaceId()).isEqualTo(sharedNamespaceId);
    PublishedRealmCatalogSnapshot originalRead =
        fixture.repository.findPublishedSnapshot(NAMESPACE, TENANT_ID, 1L).orElseThrow();
    assertThat(originalRead.entries().getFirst().policyEvidence().policy().realmDisplayName())
        .isEqualTo("Main");

    assertThatThrownBy(
            () ->
                fixture.materialize(
                    TENANT_ID,
                    evidenceSet(
                        CANONICAL_TENANT_ID, 501L, "source-game-501", 99L, 1, "Older", "SHARED")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_STALE_VERSION");

    fixture.seedAssociation(OTHER_TENANT_ID, OTHER_CANONICAL_TENANT_ID, 502L, "source-game-502");
    PublishedRealmCatalogSnapshot otherTenant =
        fixture.materialize(
            OTHER_TENANT_ID,
            evidenceSet(
                OTHER_CANONICAL_TENANT_ID, 502L, "source-game-502", 200L, 1, "Main", "SHARED"));
    assertThat(otherTenant.entries().getFirst().realmId()).isNotEqualTo(mainRealmId);
    assertThat(otherTenant.entries().getFirst().playableStateNamespaceId())
        .isNotEqualTo(sharedNamespaceId);

    PublishedRealmEntryPolicySetEvidence isolatedSet =
        evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 102L, 3, "Renamed", "ISOLATED");
    PublishedRealmCatalogSnapshot awaitingLifecycle = fixture.materialize(TENANT_ID, isolatedSet);
    assertThat(awaitingLifecycle.entries().getFirst().namespaceResolution())
        .isEqualTo(NamespaceResolution.RESOLVED);
    assertThat(awaitingLifecycle.entries().getFirst().requirePlayableStateNamespaceId())
        .isEqualTo(sharedNamespaceId);
    assertThat(awaitingLifecycle.requireVisibleEntryForAdmission("firemud", "main").realmId())
        .isEqualTo(mainRealmId);
    assertThat(awaitingLifecycle.entries().get(1).namespaceResolution())
        .isEqualTo(NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
    assertThat(awaitingLifecycle.entries().get(1).playableStateNamespaceId()).isNull();
    assertThatThrownBy(() -> awaitingLifecycle.requireVisibleEntryForAdmission("firemud", "side"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_LIFECYCLE_UNRESOLVED");
    assertThatThrownBy(awaitingLifecycle::requireNamespaceResolved)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_LIFECYCLE_UNRESOLVED");

    assertThatThrownBy(
            () ->
                fixture.materialize(
                    TENANT_ID,
                    evidenceSet(
                        CANONICAL_TENANT_ID,
                        999L,
                        "wrong-source-row",
                        103L,
                        4,
                        "Renamed",
                        "SHARED")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_TENANT_IDENTITY_MISMATCH");

    assertThatThrownBy(
            () ->
                fixture.materialize(
                    TENANT_ID,
                    evidenceSet(
                        CANONICAL_TENANT_ID,
                        501L,
                        "wrong-source-key",
                        104L,
                        5,
                        "Renamed",
                        "SHARED")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_TENANT_IDENTITY_MISMATCH");

    assertThatThrownBy(
            () ->
                fixture.materialize(
                    TENANT_ID,
                    evidenceSet(
                        CANONICAL_TENANT_ID,
                        501L,
                        "source-game-501",
                        105L,
                        6,
                        "Renamed",
                        "SHARED",
                        "otherworld")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PUBLISHED_REALM_CATALOG_SELECTOR_CHANGED");
    assertThat(fixture.dsl().fetchCount(ADMISSION_POINTER)).isEqualTo(originalPointerCount);
  }

  @Test
  void scopeTransitionsResolveOnlyTheCurrentTenantSharedNamespaceAndKeepPriorSnapshotsImmutable() {
    Fixture fixture = fixture();
    fixture.seedAssociation(TENANT_ID, CANONICAL_TENANT_ID, 501L, "source-game-501");

    PublishedRealmCatalogSnapshot sharedBeforeIsolation =
        fixture.materialize(
            TENANT_ID,
            evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 110L, 1, "Main", "SHARED"));
    UUID tenantSharedNamespace =
        sharedBeforeIsolation.entries().stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow()
            .requirePlayableStateNamespaceId();

    PublishedRealmCatalogSnapshot isolatedTransition =
        fixture.materialize(
            TENANT_ID,
            evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 111L, 2, "Main", "ISOLATED"));
    var isolatedSide =
        isolatedTransition.entries().stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    assertThat(isolatedSide.namespaceResolution())
        .isEqualTo(NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
    assertThat(isolatedSide.playableStateNamespaceId()).isNull();

    PublishedRealmCatalogSnapshot sharedAfterIsolation =
        fixture.materialize(
            TENANT_ID,
            evidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501", 112L, 3, "Main", "SHARED"));
    var sharedSideAfterIsolation =
        sharedAfterIsolation.entries().stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    assertThat(sharedSideAfterIsolation.namespaceResolution())
        .isEqualTo(NamespaceResolution.RESOLVED);
    assertThat(sharedSideAfterIsolation.requirePlayableStateNamespaceId())
        .isEqualTo(tenantSharedNamespace);

    fixture.seedAssociation(OTHER_TENANT_ID, OTHER_CANONICAL_TENANT_ID, 502L, "source-game-502");
    PublishedRealmCatalogSnapshot initiallyIsolated =
        fixture.materialize(
            OTHER_TENANT_ID,
            evidenceSet(
                OTHER_CANONICAL_TENANT_ID, 502L, "source-game-502", 210L, 1, "Main", "ISOLATED"));
    var initiallyIsolatedSide =
        initiallyIsolated.entries().stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    assertThat(initiallyIsolatedSide.namespaceResolution())
        .isEqualTo(NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
    assertThat(initiallyIsolatedSide.playableStateNamespaceId()).isNull();

    PublishedRealmCatalogSnapshot isolatedThenShared =
        fixture.materialize(
            OTHER_TENANT_ID,
            evidenceSet(
                OTHER_CANONICAL_TENANT_ID, 502L, "source-game-502", 211L, 2, "Main", "SHARED"));
    UUID otherTenantSharedNamespace =
        isolatedThenShared.entries().stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("main"))
            .findFirst()
            .orElseThrow()
            .requirePlayableStateNamespaceId();
    var isolatedThenSharedSide =
        isolatedThenShared.entries().stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    assertThat(isolatedThenSharedSide.namespaceResolution())
        .isEqualTo(NamespaceResolution.RESOLVED);
    assertThat(isolatedThenSharedSide.requirePlayableStateNamespaceId())
        .isEqualTo(otherTenantSharedNamespace)
        .isNotEqualTo(tenantSharedNamespace);

    var storedSharedBeforeIsolation =
        fixture
            .repository
            .findPublishedSnapshot(NAMESPACE, TENANT_ID, 1L)
            .orElseThrow()
            .entries()
            .stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    var storedIsolatedTransition =
        fixture
            .repository
            .findPublishedSnapshot(NAMESPACE, TENANT_ID, 2L)
            .orElseThrow()
            .entries()
            .stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    var storedInitiallyIsolated =
        fixture
            .repository
            .findPublishedSnapshot(NAMESPACE, OTHER_TENANT_ID, 1L)
            .orElseThrow()
            .entries()
            .stream()
            .filter(entry -> entry.policyEvidence().policy().realmSlug().equals("side"))
            .findFirst()
            .orElseThrow();
    assertThat(storedSharedBeforeIsolation.namespaceResolution())
        .isEqualTo(NamespaceResolution.RESOLVED);
    assertThat(storedSharedBeforeIsolation.requirePlayableStateNamespaceId())
        .isEqualTo(tenantSharedNamespace);
    assertThat(storedIsolatedTransition.namespaceResolution())
        .isEqualTo(NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
    assertThat(storedIsolatedTransition.playableStateNamespaceId()).isNull();
    assertThat(storedInitiallyIsolated.namespaceResolution())
        .isEqualTo(NamespaceResolution.AWAITING_LIFECYCLE_PROOF);
    assertThat(storedInitiallyIsolated.playableStateNamespaceId()).isNull();
  }

  @Test
  void persistedSnapshotOrdersAsciiPunctuationByWorldThenRealmSlug() {
    Fixture fixture = fixture();
    fixture.seedAssociation(TENANT_ID, CANONICAL_TENANT_ID, 501L, "source-game-501");

    PublishedRealmEntryPolicySetEvidence policySet =
        punctuationEvidenceSet(CANONICAL_TENANT_ID, 501L, "source-game-501");
    PublishedRealmCatalogSnapshot snapshot = fixture.materialize(TENANT_ID, policySet);
    PublishedRealmCatalogSnapshot readback =
        fixture
            .repository
            .findPublishedSnapshot(NAMESPACE, TENANT_ID, snapshot.catalogRevision())
            .orElseThrow();

    assertThat(readback.entries())
        .extracting(
            entry ->
                entry.policyEvidence().policy().worldSlug()
                    + "/"
                    + entry.policyEvidence().policy().realmSlug())
        .containsExactly("a-b/x-z", "a0/x-a", "a0/x0");
  }

  private static PublishedRealmEntryPolicySetEvidence punctuationEvidenceSet(
      UUID canonicalTenantId, long sourceGameRowId, String sourceGameTenantKey) {
    long versionId = 300L;
    int versionNumber = 1;
    String workflow = WORKFLOW + ":" + versionId;
    String manifest = "manifest-" + versionId;
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            canonicalTenantId, versionId, workflow, manifest, JSON);
    List<PublishedRealmEntryPolicyEvidence> policies =
        List.of(
            evidence(
                canonicalTenantId,
                sourceGameRowId,
                sourceGameTenantKey,
                versionId,
                versionNumber,
                workflow,
                manifest,
                releaseIdentity,
                "x-a",
                "Punctuation",
                true,
                false,
                "SHARED",
                "a0"),
            evidence(
                canonicalTenantId,
                sourceGameRowId,
                sourceGameTenantKey,
                versionId,
                versionNumber,
                workflow,
                manifest,
                releaseIdentity,
                "x0",
                "Punctuation",
                true,
                false,
                "SHARED",
                "a0"),
            evidence(
                canonicalTenantId,
                sourceGameRowId,
                sourceGameTenantKey,
                versionId,
                versionNumber,
                workflow,
                manifest,
                releaseIdentity,
                "x-z",
                "Punctuation",
                true,
                true,
                "SHARED",
                "a-b"));
    return PublishedRealmEntryPolicySetEvidence.create(
        canonicalTenantId,
        versionId,
        versionNumber,
        releaseIdentity,
        workflow,
        manifest,
        policies,
        JSON);
  }

  private static PublishedRealmEntryPolicySetEvidence evidenceSet(
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      long versionId,
      int versionNumber,
      String mainDisplayName,
      String sideScope) {
    return evidenceSet(
        canonicalTenantId,
        sourceGameRowId,
        sourceGameTenantKey,
        versionId,
        versionNumber,
        mainDisplayName,
        sideScope,
        "firemud");
  }

  private static PublishedRealmEntryPolicySetEvidence evidenceSet(
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      long versionId,
      int versionNumber,
      String mainDisplayName,
      String sideScope,
      String worldSlug) {
    String workflow = WORKFLOW + ":" + versionId;
    String manifest = "manifest-" + versionId;
    String releaseIdentity =
        PublishedRealmEntryPolicyEvidence.releaseBundleIdentity(
            canonicalTenantId, versionId, workflow, manifest, JSON);
    List<PublishedRealmEntryPolicyEvidence> policies =
        List.of(
            evidence(
                canonicalTenantId,
                sourceGameRowId,
                sourceGameTenantKey,
                versionId,
                versionNumber,
                workflow,
                manifest,
                releaseIdentity,
                "main",
                mainDisplayName,
                true,
                true,
                "SHARED",
                worldSlug),
            evidence(
                canonicalTenantId,
                sourceGameRowId,
                sourceGameTenantKey,
                versionId,
                versionNumber,
                workflow,
                manifest,
                releaseIdentity,
                "side",
                "Side",
                "ISOLATED".equals(sideScope),
                false,
                sideScope,
                worldSlug));
    return PublishedRealmEntryPolicySetEvidence.create(
        canonicalTenantId,
        versionId,
        versionNumber,
        releaseIdentity,
        workflow,
        manifest,
        policies,
        JSON);
  }

  private static PublishedRealmEntryPolicyEvidence evidence(
      UUID canonicalTenantId,
      long sourceGameRowId,
      String sourceGameTenantKey,
      long versionId,
      int versionNumber,
      String workflow,
      String manifest,
      String releaseIdentity,
      String realmSlug,
      String realmDisplayName,
      boolean visible,
      boolean publicProduction,
      String stateScope,
      String worldSlug) {
    String policyJson =
        "{\"schemaVersion\":1,\"worldSlug\":\""
            + worldSlug
            + "\",\"worldDisplayName\":\"FireMUD\","
            + "\"realmSlug\":\""
            + realmSlug
            + "\",\"realmDisplayName\":\""
            + realmDisplayName
            + "\",\"visible\":"
            + visible
            + ",\"publicProduction\":"
            + publicProduction
            + ",\"stateScope\":\""
            + stateScope
            + "\",\"entryPolicy\":\"PRESEEDED_ONLY\"}";
    return PublishedRealmEntryPolicyEvidence.create(
        UUID.nameUUIDFromBytes(
            (canonicalTenantId + ":" + versionId + ":" + realmSlug)
                .getBytes(StandardCharsets.UTF_8)),
        canonicalTenantId,
        PROVENANCE_KIND,
        sourceGameRowId,
        sourceGameTenantKey,
        versionId,
        versionNumber,
        versionId * 10 + (realmSlug.equals("main") ? 1 : 2),
        releaseIdentity,
        workflow,
        manifest,
        RealmEntryPolicy.parse(policyJson, JSON),
        JSON);
  }

  private static Fixture fixture() {
    String schema = "gs_published_catalog_" + UUID.randomUUID().toString().replace("-", "");
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
        .load()
        .migrate();
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    DSLContext dsl =
        DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
    return new Fixture(dsl, new InitialAdmissionBindCatalogRepository(dsl), transactions);
  }

  private record Fixture(
      DSLContext dsl,
      InitialAdmissionBindCatalogRepository repository,
      TransactionTemplate transactions) {
    PublishedRealmCatalogSnapshot materialize(
        long tenantId, PublishedRealmEntryPolicySetEvidence policySet) {
      return transactions.execute(
          status ->
              repository.materializePublishedSnapshot(
                  NAMESPACE,
                  tenantId,
                  policySet.canonicalTenantId(),
                  policySet.policies().getFirst().sourceGameRowId(),
                  policySet.policies().getFirst().sourceGameTenantKey(),
                  PROVENANCE_KIND,
                  policySet));
    }

    void seedAssociation(
        long tenantId, UUID canonicalTenantId, long sourceGameRowId, String sourceGameTenantKey) {
      dsl.execute(
          "INSERT INTO game_session_retained_tenant_association "
              + "(operation_id, target_namespace, association_request_id, "
              + "legacy_game_session_tenant_id, canonical_tenant_id, source_game_row_id, "
              + "source_game_tenant_key, provenance_kind, captured_at, terminal_outcome) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 'ASSOCIATED')",
          UUID.randomUUID(),
          NAMESPACE,
          UUID.randomUUID(),
          tenantId,
          canonicalTenantId,
          sourceGameRowId,
          sourceGameTenantKey,
          PROVENANCE_KIND);
    }
  }
}
