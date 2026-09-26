package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.EntityDigestBaselineMigrationAuditRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.BaselineSummary;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.ExpectedSource;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.MigrationCommand;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.MigrationDisposition;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.MigrationResult;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationService.ScopeStatus;
import net.firedevops.firemud.gamedesign.service.impl.EntityDigestBaselineMigrationWriteService;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for Entity v1 baseline enumeration, guarded migration, audit, and readback. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
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
class EntityDigestBaselineMigrationIntegrationTest {
  private static final LocalDateTime SOURCE_RECORDED_AT =
      LocalDateTime.of(2025, 1, 2, 3, 4, 5, 123_456_000);
  private static final LocalDateTime SOURCE_LAST_VERIFIED_AT =
      LocalDateTime.of(2025, 2, 3, 4, 5, 6, 234_567_000);

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private GameRepository gameRepository;
  @Autowired private VersionRepository versionRepository;
  @Autowired private RecordedParticipantDigestRepository baselineRepository;
  @Autowired private EntityDigestBaselineMigrationAuditRepository auditRepository;
  @Autowired private EntityDigestBaselineMigrationService migrationService;
  @Autowired private EntityDigestBaselineMigrationWriteService writeService;

  @MockitoBean private EntityManagementClient entityManagementClient;

  @BeforeEach
  void returnDeterministicEntityV2Evidence() {
    when(entityManagementClient.getDraftDesignDigestForVersion(any()))
        .thenAnswer(
            invocation -> {
              PublicationDigestRequestBinding binding = invocation.getArgument(0);
              long versionId = Long.parseLong(binding.versionId());
              return new PublishParticipantDigestDto(
                  PublishParticipantKey.ENTITY_MANAGEMENT.name(),
                  binding.versionId(),
                  null,
                  "version:" + versionId,
                  v2Digest(versionId),
                  2,
                  null,
                  null);
            });
  }

  @Test
  void insertedBaselineReturnsGeneratedIdAndExactDatabaseRow() {
    createGame("9601");
    Version version = createVersion("9601", 1);

    RecordedParticipantDigest inserted = seedEntityBaseline("9601", version, 1);

    assertThat(inserted.getId()).isPositive();
    assertThat(baselineRepository.findById(inserted.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(inserted);
    assertThat(inserted.getTenantId()).isEqualTo("9601");
    assertThat(inserted.getScopeValue()).isEqualTo(Long.toString(version.getId()));
    assertThat(inserted.getContentDigest()).isEqualTo("entity-v1-" + version.getId());
    assertThat(inserted.getDigestSchemaVersion()).isEqualTo(1);
    assertThat(inserted.getRecordedAt()).isEqualTo(SOURCE_RECORDED_AT);
    assertThat(inserted.getLastVerifiedAt()).isEqualTo(SOURCE_LAST_VERIFIED_AT);
  }

  @Test
  void enumeratesMixedRowsMigratesOnlyExactTenantVersionAndReadsBackAuditAndBaseline() {
    createGame("9201");
    createGame("9202");
    Version targetVersion = createVersion("9201", 1);
    Version siblingVersion = createVersion("9201", 2);
    Version existingV2Version = createVersion("9201", 3);
    Version noDataVersion = createVersion("9201", 4);
    Version otherTenantVersion = createVersion("9202", 1);

    RecordedParticipantDigest target = seedEntityBaseline("9201", targetVersion, 1);
    RecordedParticipantDigest sibling = seedEntityBaseline("9201", siblingVersion, 1);
    RecordedParticipantDigest existingV2 = seedEntityBaseline("9201", existingV2Version, 2);
    RecordedParticipantDigest otherTenant = seedEntityBaseline("9202", otherTenantVersion, 1);
    RecordedParticipantDigest otherParticipant =
        seedBaseline(
            "9201",
            targetVersion,
            PublishParticipantKey.WORLD_MANAGEMENT,
            2,
            "world-digest-" + targetVersion.getId());

    EntityDigestBaselineMigrationService.EntityV1Batch batch =
        migrationService.enumerateEntityV1Batch(target.getId() - 1, 50);
    assertThat(batch.baselines())
        .extracting(BaselineSummary::baselineId)
        .containsExactlyInAnyOrder(target.getId(), sibling.getId(), otherTenant.getId());
    assertThat(migrationService.preflightScope("9201", targetVersion.getId()).status())
        .isEqualTo(ScopeStatus.V1_REQUIRES_MIGRATION);
    assertThat(migrationService.preflightScope("9201", noDataVersion.getId()).status())
        .isEqualTo(ScopeStatus.NO_RECORDED_BASELINE);
    assertThat(
            baselineRepository.findEntityFullVersionBaselinesByScope(
                "9202", Long.toString(otherTenantVersion.getId())))
        .extracting(RecordedParticipantDigest::getId)
        .containsExactly(otherTenant.getId());

    MigrationCommand command = command("mixed-scope-operation", "9201", target);
    MigrationResult result = migrationService.migrate(command);

    assertThat(result.disposition()).isEqualTo(MigrationDisposition.APPLIED);
    assertThat(result.appliedCommitId()).isEqualTo(target.getAppliedCommitId());
    assertThat(result.digestSchemaVersion()).isEqualTo(2);
    RecordedParticipantDigest migrated = baselineRepository.findById(target.getId()).orElseThrow();
    EntityDigestBaselineMigrationAudit audit =
        auditRepository.findByOperationId(command.operationId()).orElseThrow();
    RecordedParticipantDigest expectedMigrated =
        replacement(target, v2Digest(targetVersion.getId()));
    assertThat(migrated).usingRecursiveComparison().isEqualTo(expectedMigrated);
    EntityDigestBaselineMigrationAudit expectedAudit =
        audit(
            target,
            migrated,
            command.operationId(),
            command.actorIdentity(),
            command.workloadIdentity(),
            audit.committedAt());
    assertThat(audit).usingRecursiveComparison().ignoringFields("id").isEqualTo(expectedAudit);
    assertThat(migrated.getTenantId()).isEqualTo("9201");
    assertThat(migrated.getScopeValue()).isEqualTo(Long.toString(targetVersion.getId()));
    assertThat(migrated.getAppliedCommitId()).isEqualTo("version:" + targetVersion.getId());
    assertThat(migrated.getContentDigest()).isEqualTo(v2Digest(targetVersion.getId()));
    assertThat(migrated.getDigestSchemaVersion()).isEqualTo(2);
    assertThat(migrated.getRecordedFromPublishWorkflowId())
        .isEqualTo(target.getRecordedFromPublishWorkflowId());
    assertThat(migrated.getLastVerifiedPublishWorkflowId())
        .isEqualTo(target.getLastVerifiedPublishWorkflowId());
    assertThat(migrated.getRecordedAt()).isEqualTo(target.getRecordedAt());
    assertThat(migrated.getLastVerifiedAt()).isEqualTo(target.getLastVerifiedAt());
    assertThat(audit.id()).isEqualTo(result.auditId());
    assertThat(audit.recordedParticipantDigestId()).isEqualTo(target.getId());
    assertThat(audit.sourceContentDigest()).isEqualTo(target.getContentDigest());
    assertThat(audit.sourceDigestSchemaVersion()).isEqualTo(1);
    assertThat(audit.sourceRecordedFromPublishWorkflowId())
        .isEqualTo(target.getRecordedFromPublishWorkflowId());
    assertThat(audit.sourceRecordedAt()).isEqualTo(target.getRecordedAt());
    assertThat(audit.sourceLastVerifiedPublishWorkflowId())
        .isEqualTo(target.getLastVerifiedPublishWorkflowId());
    assertThat(audit.sourceLastVerifiedAt()).isEqualTo(target.getLastVerifiedAt());
    assertThat(audit.observedTenantId()).isEqualTo("9201");
    assertThat(audit.observedScopeValue()).isEqualTo(Long.toString(targetVersion.getId()));
    assertThat(audit.observedContentDigest()).isEqualTo(v2Digest(targetVersion.getId()));
    assertThat(audit.observedDigestSchemaVersion()).isEqualTo(2);
    assertThat(audit.actorIdentity()).isEqualTo(command.actorIdentity());
    assertThat(audit.workloadIdentity()).isEqualTo(command.workloadIdentity());
    assertThat(audit.outcome()).isEqualTo(EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME);

    assertBaselineUnchanged(sibling);
    assertBaselineUnchanged(otherTenant);
    assertBaselineUnchanged(existingV2);
    assertBaselineUnchanged(otherParticipant);
  }

  @Test
  void exactRetryReadsCommittedEvidenceWithoutCallingEntityAgain() {
    createGame("9301");
    Version version = createVersion("9301", 1);
    RecordedParticipantDigest source = seedEntityBaseline("9301", version, 1);
    MigrationCommand command = command("retry-operation", "9301", source);

    MigrationResult first = migrationService.migrate(command);
    MigrationResult retry = migrationService.migrate(command);

    assertThat(first.disposition()).isEqualTo(MigrationDisposition.APPLIED);
    assertThat(retry.disposition()).isEqualTo(MigrationDisposition.EXACT_RETRY);
    assertThat(retry.auditId()).isEqualTo(first.auditId());
    assertThat(retry.baselineId()).isEqualTo(first.baselineId());
    assertThat(retry.contentDigest()).isEqualTo(first.contentDigest());
    assertThat(retry.digestSchemaVersion()).isEqualTo(2);
    verify(entityManagementClient, times(1)).getDraftDesignDigestForVersion(any());
    assertThat(auditRepository.findByOperationId(command.operationId())).isPresent();
    assertThat(baselineRepository.findById(source.getId()).orElseThrow().getContentDigest())
        .isEqualTo(v2Digest(version.getId()));
  }

  @Test
  void compareAndSwapRejectsAChangedV1SourceRow() {
    createGame("9401");
    Version version = createVersion("9401", 1);
    RecordedParticipantDigest source = seedEntityBaseline("9401", version, 1);
    RecordedParticipantDigest expectedOld =
        baselineRepository.findById(source.getId()).orElseThrow();
    RecordedParticipantDigest changed = baselineRepository.findById(source.getId()).orElseThrow();
    changed.setContentDigest("changed-after-preflight");
    baselineRepository.save(changed);

    RecordedParticipantDigest replacement = replacement(expectedOld, "v2-new");

    assertThat(
            baselineRepository.migrateEntityFullVersionBaselineIfUnchanged(
                expectedOld, replacement))
        .isZero();
    RecordedParticipantDigest readback = baselineRepository.findById(source.getId()).orElseThrow();
    assertThat(readback.getDigestSchemaVersion()).isEqualTo(1);
    assertThat(readback.getContentDigest()).isEqualTo("changed-after-preflight");
    assertThat(auditRepository.findByOperationId("cas-test-operation")).isEmpty();
  }

  @Test
  void auditInsertFailureRollsBackTheGuardedBaselineReplacement() {
    createGame("9501");
    Version firstVersion = createVersion("9501", 1);
    Version secondVersion = createVersion("9501", 2);
    RecordedParticipantDigest firstSource = seedEntityBaseline("9501", firstVersion, 1);
    RecordedParticipantDigest secondSource = seedEntityBaseline("9501", secondVersion, 1);

    MigrationCommand firstCommand = command("duplicate-operation-id", "9501", firstSource);
    MigrationResult firstResult = migrationService.migrate(firstCommand);
    RecordedParticipantDigest expectedOld =
        baselineRepository.findById(secondSource.getId()).orElseThrow();
    LocalDateTime migrationTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    RecordedParticipantDigest replacement =
        replacement(expectedOld, v2Digest(secondVersion.getId()));
    replacement.setRecordedAt(migrationTime);
    replacement.setLastVerifiedAt(migrationTime);
    EntityDigestBaselineMigrationAudit duplicateAudit =
        audit(
            expectedOld,
            replacement,
            firstCommand.operationId(),
            firstCommand.actorIdentity(),
            firstCommand.workloadIdentity(),
            migrationTime);

    assertThatThrownBy(() -> writeService.commit(expectedOld, replacement, duplicateAudit))
        .isInstanceOf(RuntimeException.class);

    RecordedParticipantDigest readback =
        baselineRepository.findById(secondSource.getId()).orElseThrow();
    assertThat(readback).usingRecursiveComparison().isEqualTo(secondSource);
    assertThat(auditRepository.findByOperationId(firstCommand.operationId()).orElseThrow().id())
        .isEqualTo(firstResult.auditId());
  }

  private void createGame(String tenantId) {
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("entity-baseline-migration-" + tenantId);
    gameRepository.save(game);
  }

  private Version createVersion(String tenantId, int versionNumber) {
    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(versionNumber);
    version.setNotes("Entity baseline migration integration fixture");
    return versionRepository.save(version);
  }

  private RecordedParticipantDigest seedEntityBaseline(
      String tenantId, Version version, int digestSchemaVersion) {
    return seedBaseline(
        tenantId,
        version,
        PublishParticipantKey.ENTITY_MANAGEMENT,
        digestSchemaVersion,
        "entity-v" + digestSchemaVersion + "-" + version.getId());
  }

  private RecordedParticipantDigest seedBaseline(
      String tenantId,
      Version version,
      PublishParticipantKey participantKey,
      int digestSchemaVersion,
      String contentDigest) {
    RecordedParticipantDigest baseline = new RecordedParticipantDigest();
    baseline.setTenantId(tenantId);
    baseline.setPublishType(PublishType.FULL_VERSION);
    baseline.setParticipantKey(participantKey);
    baseline.setScopeValue(Long.toString(version.getId()));
    baseline.setBaseVersionId(null);
    baseline.setAppliedCommitId("version:" + version.getId());
    baseline.setContentDigest(contentDigest);
    baseline.setDigestSchemaVersion(digestSchemaVersion);
    baseline.setRecordedFromPublishWorkflowId("initial-publish-" + version.getId());
    baseline.setRecordedAt(SOURCE_RECORDED_AT);
    baseline.setLastVerifiedPublishWorkflowId("last-verified-" + version.getId());
    baseline.setLastVerifiedAt(SOURCE_LAST_VERIFIED_AT);
    return baselineRepository.save(baseline);
  }

  private MigrationCommand command(
      String operationId, String tenantId, RecordedParticipantDigest source) {
    return new MigrationCommand(
        operationId,
        source.getId(),
        tenantId,
        Long.parseLong(source.getScopeValue()),
        expectedSource(source),
        "operator:test-actor",
        "spiffe://firemud/ns/dev/sa/game-design-baseline-migrator");
  }

  private ExpectedSource expectedSource(RecordedParticipantDigest source) {
    return new ExpectedSource(
        source.getId(),
        source.getTenantId(),
        source.getPublishType(),
        source.getParticipantKey(),
        source.getBaseVersionId(),
        source.getScopeValue(),
        source.getAppliedCommitId(),
        source.getContentDigest(),
        source.getDigestSchemaVersion(),
        source.getRecordedFromPublishWorkflowId(),
        source.getRecordedAt(),
        source.getLastVerifiedPublishWorkflowId(),
        source.getLastVerifiedAt());
  }

  private RecordedParticipantDigest replacement(
      RecordedParticipantDigest source, String contentDigest) {
    RecordedParticipantDigest replacement = new RecordedParticipantDigest();
    replacement.setId(source.getId());
    replacement.setTenantId(source.getTenantId());
    replacement.setPublishType(source.getPublishType());
    replacement.setParticipantKey(source.getParticipantKey());
    replacement.setBaseVersionId(null);
    replacement.setScopeValue(source.getScopeValue());
    replacement.setAppliedCommitId(source.getAppliedCommitId());
    replacement.setContentDigest(contentDigest);
    replacement.setDigestSchemaVersion(2);
    replacement.setRecordedFromPublishWorkflowId(source.getRecordedFromPublishWorkflowId());
    replacement.setRecordedAt(source.getRecordedAt());
    replacement.setLastVerifiedPublishWorkflowId(source.getLastVerifiedPublishWorkflowId());
    replacement.setLastVerifiedAt(source.getLastVerifiedAt());
    return replacement;
  }

  private EntityDigestBaselineMigrationAudit audit(
      RecordedParticipantDigest source,
      RecordedParticipantDigest target,
      String operationId,
      String actorIdentity,
      String workloadIdentity,
      LocalDateTime committedAt) {
    return new EntityDigestBaselineMigrationAudit(
        null,
        operationId,
        source.getId(),
        source.getTenantId(),
        source.getPublishType(),
        source.getParticipantKey(),
        source.getBaseVersionId(),
        source.getScopeValue(),
        source.getAppliedCommitId(),
        source.getContentDigest(),
        source.getDigestSchemaVersion(),
        source.getRecordedFromPublishWorkflowId(),
        source.getRecordedAt(),
        source.getLastVerifiedPublishWorkflowId(),
        source.getLastVerifiedAt(),
        target.getTenantId(),
        target.getPublishType(),
        target.getParticipantKey(),
        target.getBaseVersionId(),
        target.getScopeValue(),
        target.getAppliedCommitId(),
        target.getContentDigest(),
        target.getDigestSchemaVersion(),
        actorIdentity,
        workloadIdentity,
        EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME,
        committedAt);
  }

  private void assertBaselineUnchanged(RecordedParticipantDigest expected) {
    RecordedParticipantDigest actual = baselineRepository.findById(expected.getId()).orElseThrow();
    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }

  private String v2Digest(long versionId) {
    return "entity-v2-observed-" + versionId;
  }
}
