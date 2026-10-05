package net.firedevops.firemud.gamedesign.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

class PublishedReleaseBundleRepositoryTest {
  private static final UUID TENANT_UUID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID VERSION_UUID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID OTHER_UUID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Test
  void newBundleUsesExactPersistedVersionAndGameIdentityAndReadsItBack() throws Exception {
    try (Fixture fixture = fixture()) {
      insertGame(fixture.dsl(), 7L, "tenant-key", TENANT_UUID, 7L, "tenant-key", "NEW_GAME_ROW");
      insertVersion(
          fixture.dsl(),
          17L,
          "tenant-key",
          TENANT_UUID,
          VERSION_UUID,
          7L,
          "tenant-key",
          "NEW_GAME_ROW");

      PublishedReleaseBundle saved = fixture.repository().save(bundle("tenant-key", 17L));
      assertThat(saved.getId()).isNotNull();
      assertThat(saved.getCanonicalTenantId()).isEqualTo(TENANT_UUID);
      assertThat(saved.getCanonicalVersionId()).isEqualTo(VERSION_UUID);
      assertThat(fixture.repository().findByTenantIdAndVersionId("tenant-key", 17L).orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(saved);

      PublishedReleaseBundle stored =
          fixture.repository().findByTenantIdAndVersionId("tenant-key", 17L).orElseThrow();
      assertThatThrownBy(() -> fixture.repository().save(stored))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Published release bundle is immutable");
      assertThat(fixture.repository().findByTenantIdAndVersionId("tenant-key", 17L).orElseThrow())
          .usingRecursiveComparison()
          .isEqualTo(stored);

      stored.setManifestHash("sha256:updated-test-value");
      assertThatThrownBy(() -> fixture.repository().save(stored))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Published release bundle is immutable");
      stored.setCanonicalVersionId(OTHER_UUID);
      assertThatThrownBy(() -> fixture.repository().save(stored))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Published release bundle is immutable");
      PublishedReleaseBundle persistedAfterAttempts =
          fixture.repository().findByTenantIdAndVersionId("tenant-key", 17L).orElseThrow();
      assertThat(persistedAfterAttempts.getManifestHash()).isEqualTo("sha256:initial-test-value");
      assertThat(persistedAfterAttempts.getCanonicalTenantId()).isEqualTo(TENANT_UUID);
      assertThat(persistedAfterAttempts.getCanonicalVersionId()).isEqualTo(VERSION_UUID);
      assertThat(fixture.bundleCount()).isEqualTo(1);
    }
  }

  @Test
  void rejectsCallerSubstitutionWrongTenantUnmappedVersionAndContradictoryGameSource()
      throws Exception {
    try (Fixture fixture = fixture()) {
      insertGame(fixture.dsl(), 7L, "tenant-key", TENANT_UUID, 7L, "tenant-key", "NEW_GAME_ROW");
      insertVersion(
          fixture.dsl(),
          17L,
          "tenant-key",
          TENANT_UUID,
          VERSION_UUID,
          7L,
          "tenant-key",
          "NEW_GAME_ROW");
      insertVersion(fixture.dsl(), 18L, "tenant-key", null, null, null, null, null);
      insertVersion(
          fixture.dsl(),
          19L,
          "tenant-key",
          OTHER_UUID,
          UUID.randomUUID(),
          8L,
          "other-key",
          "NEW_GAME_ROW");

      PublishedReleaseBundle substituted = bundle("tenant-key", 17L);
      substituted.setCanonicalTenantId(OTHER_UUID);
      assertThatThrownBy(() -> fixture.repository().save(substituted))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Caller-supplied canonical release identity");

      assertThatThrownBy(() -> fixture.repository().save(bundle("other-tenant-key", 17L)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No exact canonical Game Design source association");
      assertThatThrownBy(() -> fixture.repository().save(bundle("tenant-key", 18L)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No exact canonical Game Design source association");
      assertThatThrownBy(() -> fixture.repository().save(bundle("tenant-key", 19L)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No exact canonical Game Design source association");
      assertThat(fixture.bundleCount()).isZero();
    }
  }

  private static Fixture fixture() throws Exception {
    Connection connection =
        DriverManager.getConnection(
            "jdbc:h2:mem:published-release-bundle-"
                + UUID.randomUUID()
                + ";DATABASE_TO_LOWER=TRUE");
    DSLContext dsl = DSL.using(connection, SQLDialect.H2);
    dsl.execute(
        "CREATE TABLE game ("
            + "id BIGINT PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, "
            + "canonical_tenant_id UUID NOT NULL, tenant_identity_provenance_kind VARCHAR(32), "
            + "tenant_identity_source_game_id BIGINT, "
            + "tenant_identity_source_legacy_tenant_id VARCHAR(36))");
    dsl.execute(
        "CREATE TABLE version ("
            + "id BIGINT PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, "
            + "canonical_tenant_id UUID, canonical_version_id UUID, "
            + "identity_source_game_row_id BIGINT, identity_source_game_tenant_key VARCHAR(36), "
            + "identity_source_provenance_kind VARCHAR(32))");
    dsl.execute(
        "CREATE TABLE published_release_bundle ("
            + "id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
            + "tenant_id VARCHAR(36) NOT NULL, version_id BIGINT NOT NULL, "
            + "canonical_tenant_id UUID, canonical_version_id UUID, version_number INT NOT NULL, "
            + "attestation_schema_version VARCHAR(16) NOT NULL, "
            + "publish_workflow_id VARCHAR(64) NOT NULL, manifest_hash VARCHAR(128) NOT NULL, "
            + "generation_config_revision VARCHAR(128), "
            + "required_manifest_asset_keys_json CLOB NOT NULL, "
            + "participant_digests_json CLOB NOT NULL, command_definitions_json CLOB NOT NULL, "
            + "script_only BOOLEAN NOT NULL, script_patch_version VARCHAR(100), "
            + "published_at TIMESTAMP NOT NULL)");
    return new Fixture(connection, dsl, new PublishedReleaseBundleRepository(dsl));
  }

  private static void insertGame(
      DSLContext dsl,
      long id,
      String tenantId,
      UUID canonicalTenantId,
      long sourceGameId,
      String sourceTenantKey,
      String provenanceKind) {
    dsl.execute(
        "INSERT INTO game (id, tenant_id, canonical_tenant_id, "
            + "tenant_identity_provenance_kind, tenant_identity_source_game_id, "
            + "tenant_identity_source_legacy_tenant_id) VALUES (?, ?, ?, ?, ?, ?)",
        id,
        tenantId,
        canonicalTenantId,
        provenanceKind,
        sourceGameId,
        sourceTenantKey);
  }

  private static void insertVersion(
      DSLContext dsl,
      long id,
      String tenantId,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      Long sourceGameId,
      String sourceTenantKey,
      String provenanceKind) {
    dsl.execute(
        "INSERT INTO version (id, tenant_id, canonical_tenant_id, canonical_version_id, "
            + "identity_source_game_row_id, identity_source_game_tenant_key, "
            + "identity_source_provenance_kind) VALUES (?, ?, ?, ?, ?, ?, ?)",
        id,
        tenantId,
        canonicalTenantId,
        canonicalVersionId,
        sourceGameId,
        sourceTenantKey,
        provenanceKind);
  }

  private static PublishedReleaseBundle bundle(String tenantId, long versionId) {
    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(tenantId);
    bundle.setVersionId(versionId);
    bundle.setVersionNumber(1);
    bundle.setAttestationSchemaVersion("1");
    bundle.setPublishWorkflowId("workflow-" + versionId);
    bundle.setManifestHash("sha256:initial-test-value");
    bundle.setGenerationConfigRevision("generation-1");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    return bundle;
  }

  private record Fixture(
      Connection connection, DSLContext dsl, PublishedReleaseBundleRepository repository)
      implements AutoCloseable {
    long bundleCount() {
      return dsl.fetchCount(DSL.table(DSL.name("published_release_bundle")));
    }

    @Override
    public void close() throws Exception {
      connection.close();
    }
  }
}
