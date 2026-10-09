package integration.net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.GameTemplate;
import net.firedevops.firemud.gamedesign.entity.PublishedReleaseBundle;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.TemplateReferencePhase;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class LaunchDescriptorServiceIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private LaunchDescriptorService launchDescriptorService;
  @Autowired private LaunchDescriptorRepository launchDescriptorRepository;
  @Autowired private GameRepository gameRepository;
  @Autowired private GameTemplateRepository gameTemplateRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private PublishedReleaseBundleRepository publishedReleaseBundleRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void legacyNumericResolveFailsClosedWithoutPersistingDescriptor() {
    assertThatThrownBy(
            () ->
                launchDescriptorService.resolveLaunchDescriptor(
                    "1", 9L, "integration-cp-unbound", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "AUTHORED_WORLD_LAUNCH_BINDING_REQUIRED: canonical authored-world source binding is"
                + " required to resolve a launch descriptor");

    assertThat(
            launchDescriptorRepository.findByTenantIdAndGameTemplateIdAndControlPlaneRequestId(
                "1", 9L, "integration-cp-unbound"))
        .isEmpty();
  }

  @Test
  void authoredSourceMigrationBindsFullSourceTupleAndRetainsLegacyRows() {
    String tenantId = "launch-schema-" + UUID.randomUUID().toString().substring(0, 8);
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("launch schema proof");
    Game savedGame = gameRepository.save(game);

    GameTemplate template = new GameTemplate();
    template.setTenantId(tenantId);
    template.setName("launch schema template");
    template.setConfig("{}");
    template.setTemplateReferencePhase(TemplateReferencePhase.ENFORCED);
    GameTemplate savedTemplate = gameTemplateRepository.save(template);

    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    version.setNotes("launch schema proof");
    Version savedVersion = versionRepository.save(version);

    PublishedReleaseBundle bundle = new PublishedReleaseBundle();
    bundle.setTenantId(tenantId);
    bundle.setVersionId(savedVersion.getId());
    bundle.setVersionNumber(savedVersion.getVersionNumber());
    bundle.setAttestationSchemaVersion("v1");
    bundle.setPublishWorkflowId("launch-schema-" + UUID.randomUUID());
    bundle.setManifestHash(sha256("a"));
    bundle.setManifestSchemaVersion(1);
    bundle.setArtifactDigestsJson("[]");
    bundle.setGenerationConfigRevision("launch-schema-generation");
    bundle.setRequiredManifestAssetKeysJson("[]");
    bundle.setParticipantDigestsJson("[]");
    bundle.setCommandDefinitionsJson("[]");
    PublishedReleaseBundle savedBundle = publishedReleaseBundleRepository.save(bundle);

    AuthoredWorldSourceFixture source = insertAuthoredWorldSourceFixture(savedGame);
    String requestDigest = sha256("b");
    String resultDigest = sha256("c");
    insertSuccessDescriptor(
        "launch-schema-bound-" + UUID.randomUUID(),
        tenantId,
        savedTemplate,
        savedVersion,
        savedBundle,
        "launch-schema-bound-request",
        requestDigest,
        resultDigest,
        source,
        source.evidenceDigest());
    insertSuccessDescriptor(
        "launch-schema-legacy-" + UUID.randomUUID(),
        tenantId,
        savedTemplate,
        savedVersion,
        savedBundle,
        "launch-schema-legacy-request",
        "legacy-request-hash",
        null,
        null,
        null);

    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-missing-source",
                    UUID.randomUUID(),
                    source,
                    source.evidenceDigest(),
                    requestDigest))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-mismatched-source",
                    source.operationId(),
                    new AuthoredWorldSourceFixture(
                        source.targetNamespace(),
                        source.canonicalTenantId(),
                        source.tenantSlug(),
                        source.worldSlug(),
                        source.operationId(),
                        source.sourceGameRowId() + 1,
                        source.sourceGameTenantKey(),
                        source.provenanceKind(),
                        source.evidenceDigest()),
                    source.evidenceDigest(),
                    requestDigest))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-malformed-source-digest",
                    source.operationId(),
                    source,
                    "sha256:" + "d".repeat(63),
                    requestDigest))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertFailedDescriptor(
                    tenantId,
                    "launch-schema-malformed-request-digest",
                    source.operationId(),
                    source,
                    source.evidenceDigest(),
                    "not-a-sha256-digest"))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertSuccessDescriptor(
                    "launch-schema-malformed-result-" + UUID.randomUUID(),
                    tenantId,
                    savedTemplate,
                    savedVersion,
                    savedBundle,
                    "launch-schema-malformed-result-request",
                    requestDigest,
                    "sha256:" + "e".repeat(63),
                    source,
                    source.evidenceDigest()))
        .isInstanceOf(DataAccessException.class);

    assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE launch_descriptor"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("launch descriptors are immutable");
    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "DELETE FROM launch_descriptor WHERE control_plane_request_id = ?",
                    "launch-schema-bound-request"))
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("launch descriptors are immutable");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM launch_descriptor WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(2);
  }

  private AuthoredWorldSourceFixture insertAuthoredWorldSourceFixture(Game game) {
    String targetNamespace = "dev";
    String tenantSlug = "launch-schema-tenant";
    String worldSlug = "launch-schema-world";
    String provenanceKind = "NEW_GAME_ROW";
    UUID operationId = UUID.randomUUID();
    String requestDigest = sha256("f");
    String evidenceDigest = sha256("1");
    new TransactionTemplate(transactionManager)
        .execute(
            transaction -> {
              jdbcTemplate.update(
                  "INSERT INTO game_design_tenant_slug_binding "
                      + "(target_namespace, canonical_tenant_id, tenant_slug, source_game_row_id, "
                      + "source_game_tenant_key, provenance_kind) VALUES (?, ?, ?, ?, ?, ?)",
                  targetNamespace,
                  game.getCanonicalTenantId(),
                  tenantSlug,
                  game.getId(),
                  game.getTenantId(),
                  provenanceKind);
              jdbcTemplate.update(
                  "INSERT INTO game_design_authored_world_source_operations "
                      + "(operation_id, schema_version, target_namespace, registration_request_id, "
                      + "request_digest, canonical_tenant_id, tenant_slug, world_slug, "
                      + "world_display_name, source_game_row_id, source_game_tenant_key, "
                      + "provenance_kind, evidence_digest) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                  operationId,
                  targetNamespace,
                  UUID.randomUUID(),
                  requestDigest,
                  game.getCanonicalTenantId(),
                  tenantSlug,
                  worldSlug,
                  "Launch schema world",
                  game.getId(),
                  game.getTenantId(),
                  provenanceKind,
                  evidenceDigest);
              return null;
            });
    return new AuthoredWorldSourceFixture(
        targetNamespace,
        game.getCanonicalTenantId(),
        tenantSlug,
        worldSlug,
        operationId,
        game.getId(),
        game.getTenantId(),
        provenanceKind,
        evidenceDigest);
  }

  private void insertSuccessDescriptor(
      String descriptorId,
      String tenantId,
      GameTemplate template,
      Version version,
      PublishedReleaseBundle bundle,
      String controlPlaneRequestId,
      String requestDigest,
      String resultDigest,
      AuthoredWorldSourceFixture source,
      String sourceEvidenceDigest) {
    jdbcTemplate.update(
        "INSERT INTO launch_descriptor (launch_descriptor_id, tenant_id, game_template_id, "
            + "control_plane_request_id, request_hash, version_id, runtime_flags_json, "
            + "generation_config_revision, version_state_epoch, release_bundle_id, "
            + "published_release_bundle_ref, descriptor_schema_version, target_namespace, "
            + "canonical_tenant_id, authored_world_source_tenant_slug, world_slug, "
            + "authored_world_source_operation_id, authored_world_source_game_row_id, "
            + "authored_world_source_game_tenant_key, authored_world_source_provenance_kind, "
            + "authored_world_source_evidence_digest, request_digest, result_digest, "
            + "original_request_json, source_evidence_json, outcome_status) "
            + "VALUES (?, ?, ?, ?, ?, ?, '{}', ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS')",
        descriptorId,
        tenantId,
        template.getId(),
        controlPlaneRequestId,
        requestDigest,
        version.getId(),
        bundle.getGenerationConfigRevision(),
        bundle.getId(),
        bundle.getPublishedReleaseBundleRef(),
        source == null ? null : 1,
        source == null ? null : source.targetNamespace(),
        source == null ? null : source.canonicalTenantId(),
        source == null ? null : source.tenantSlug(),
        source == null ? null : source.worldSlug(),
        source == null ? null : source.operationId(),
        source == null ? null : source.sourceGameRowId(),
        source == null ? null : source.sourceGameTenantKey(),
        source == null ? null : source.provenanceKind(),
        sourceEvidenceDigest,
        source == null ? null : requestDigest,
        resultDigest,
        source == null ? null : "{}",
        source == null ? null : "{}");
  }

  private void insertFailedDescriptor(
      String tenantId,
      String controlPlaneRequestId,
      UUID operationId,
      AuthoredWorldSourceFixture source,
      String sourceEvidenceDigest,
      String requestDigest) {
    jdbcTemplate.update(
        "INSERT INTO launch_descriptor (tenant_id, control_plane_request_id, request_hash, "
            + "descriptor_schema_version, target_namespace, canonical_tenant_id, "
            + "authored_world_source_tenant_slug, world_slug, authored_world_source_operation_id, "
            + "authored_world_source_game_row_id, authored_world_source_game_tenant_key, "
            + "authored_world_source_provenance_kind, authored_world_source_evidence_digest, "
            + "request_digest, original_request_json, source_evidence_json, outcome_status, "
            + "failure_code, failure_message) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', '{}', 'FAILED', 'RELEASE_BUNDLE_NOT_FOUND', 'fixture failure')",
        tenantId,
        controlPlaneRequestId,
        requestDigest,
        source.targetNamespace(),
        source.canonicalTenantId(),
        source.tenantSlug(),
        source.worldSlug(),
        operationId,
        source.sourceGameRowId(),
        source.sourceGameTenantKey(),
        source.provenanceKind(),
        sourceEvidenceDigest,
        requestDigest);
  }

  private String sha256(String repeatedCharacter) {
    return "sha256:" + repeatedCharacter.repeat(64);
  }

  private record AuthoredWorldSourceFixture(
      String targetNamespace,
      UUID canonicalTenantId,
      String tenantSlug,
      String worldSlug,
      UUID operationId,
      Long sourceGameRowId,
      String sourceGameTenantKey,
      String provenanceKind,
      String evidenceDigest) {}
}
