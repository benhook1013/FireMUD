package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.VersionStateDto;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
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
  @Autowired private VersionService versionService;

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
  void draftV1ScopeIsReportedBlockedAndMigrationDoesNotReadEntityDigest() {
    createGame("9301");
    Version draftVersion = createVersion("9301", 1, VersionLifecycleState.DRAFT);
    RecordedParticipantDigest baseline = seedEntityBaseline("9301", draftVersion, 1);
    MigrationCommand command = command("draft-scope-operation", "9301", baseline);

    EntityDigestBaselineMigrationService.EntityV1Batch page =
        migrationService.enumerateEntityV1Batch(baseline.getId() - 1, 10);
    assertThat(page.baselines())
        .filteredOn(summary -> summary.baselineId() == baseline.getId())
        .singleElement()
        .extracting(BaselineSummary::status)
        .isEqualTo(ScopeStatus.BLOCKED_DRAFT);
    assertThat(migrationService.preflightScope("9301", draftVersion.getId()).status())
        .isEqualTo(ScopeStatus.BLOCKED_DRAFT);

    assertThatThrownBy(() -> migrationService.migrate(command))
        .isInstanceOf(EntityDigestBaselineMigrationService.MigrationRejectedException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((EntityDigestBaselineMigrationService.MigrationRejectedException) failure)
                            .failureCode())
                    .isEqualTo("VERSION_DRAFT"));
    verify(entityManagementClient, never()).getDraftDesignDigestForVersion(any());
    assertThat(baselineRepository.findById(baseline.getId()).orElseThrow())
        .usingRecursiveComparison()
        .isEqualTo(baseline);
    assertThat(auditRepository.findByOperationId(command.operationId())).isEmpty();
  }

  @Test
  void competingSameEpochCasTransitionsAllowOnlyOneWriter() throws Exception {
    createGame("cas-tenant");
    Version version = createVersion("cas-tenant", 1, VersionLifecycleState.PUBLISHED);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      List<Future<?>> transitions =
          List.of(
              executor.submit(
                  () -> {
                    awaitLatch(start);
                    return versionService.compareAndSetVersionState(
                        "cas-tenant", version.getId(), 1L, VersionLifecycleState.DRAFT, "cas-1");
                  }),
              executor.submit(
                  () -> {
                    awaitLatch(start);
                    return versionService.compareAndSetVersionState(
                        "cas-tenant", version.getId(), 1L, VersionLifecycleState.RETIRED, "cas-2");
                  }));
      start.countDown();

      int successes = 0;
      int staleFailures = 0;
      for (Future<?> transition : transitions) {
        try {
          transition.get(5, TimeUnit.SECONDS);
          successes++;
        } catch (ExecutionException failure) {
          assertThat(failure.getCause())
              .isInstanceOf(IllegalArgumentException.class)
              .hasMessageStartingWith("VERSION_STATE_EPOCH_STALE:");
          staleFailures++;
        }
      }
      assertThat(successes).isOne();
      assertThat(staleFailures).isOne();
    }

    Version finalVersion =
        versionRepository.findByTenantIdAndId("cas-tenant", version.getId()).orElseThrow();
    assertThat(finalVersion.getVersionStateEpoch()).isEqualTo(2L);
    assertThat(finalVersion.getVersionState())
        .isIn(VersionLifecycleState.DRAFT, VersionLifecycleState.RETIRED);
  }

  @Test
  void migrationDigestOverlapWithLifecycleTransitionRejectsStaleEpoch() throws Exception {
    String tenantId = "migration-race-tenant";
    createGame(tenantId);
    Version version = createVersion(tenantId, 1, VersionLifecycleState.PUBLISHED);
    RecordedParticipantDigest baseline = seedEntityBaseline(tenantId, version, 1);
    MigrationCommand command = command("migration-race-operation", tenantId, baseline);
    CountDownLatch digestRead = new CountDownLatch(1);
    CountDownLatch releaseDigest = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              digestRead.countDown();
              awaitLatch(releaseDigest);
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
            })
        .when(entityManagementClient)
        .getDraftDesignDigestForVersion(any());

    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> migration = executor.submit(() -> migrationService.migrate(command));
      assertThat(digestRead.await(5, TimeUnit.SECONDS)).isTrue();

      VersionStateDto transitioned =
          versionService.compareAndSetVersionState(
              tenantId, version.getId(), 1L, VersionLifecycleState.ACTIVE, "migration-race");
      assertThat(transitioned.versionState()).isEqualTo(VersionLifecycleState.ACTIVE);
      assertThat(transitioned.versionStateEpoch()).isEqualTo(2L);
      releaseDigest.countDown();

      assertThatThrownBy(() -> migration.get(5, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(EntityDigestBaselineMigrationService.MigrationRejectedException.class)
          .hasRootCauseMessage(
              "version lifecycle changed while the Entity digest was being recomputed");
    } finally {
      releaseDigest.countDown();
    }

    assertBaselineUnchanged(baseline);
    assertThat(auditRepository.findByOperationId(command.operationId())).isEmpty();
    Version finalVersion =
        versionRepository.findByTenantIdAndId(tenantId, version.getId()).orElseThrow();
    assertThat(finalVersion.getVersionState()).isEqualTo(VersionLifecycleState.ACTIVE);
    assertThat(finalVersion.getVersionStateEpoch()).isEqualTo(2L);
  }

  @Test
  void exactRetryReadsCommittedEvidenceWithoutCallingEntityAgain() {
    createGame("9302");
    Version version = createVersion("9302", 1);
    RecordedParticipantDigest source = seedEntityBaseline("9302", version, 1);
    MigrationCommand command = command("retry-operation", "9302", source);

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

    assertThatThrownBy(
            () ->
                writeService.commit(
                    expectedOld, replacement, duplicateAudit, secondVersion.getVersionStateEpoch()))
        .isInstanceOf(RuntimeException.class);

    RecordedParticipantDigest readback =
        baselineRepository.findById(secondSource.getId()).orElseThrow();
    assertThat(readback).usingRecursiveComparison().isEqualTo(secondSource);
    assertThat(auditRepository.findByOperationId(firstCommand.operationId()).orElseThrow().id())
        .isEqualTo(firstResult.auditId());
  }

  @Test
  void databaseMigrationRolesHaveOnlyTheirDirectTablePrivileges() throws Exception {
    createGame("9701");
    Version version = createVersion("9701", 1);
    RecordedParticipantDigest baseline = seedEntityBaseline("9701", version, 1);
    provisionMigrationDatabaseRoles();

    try (Connection reader =
        connectAs("firemud_game_design_baseline_reader_test", postgres.getPassword())) {
      assertThat(reader.getMetaData().getUserName())
          .isEqualTo("firemud_game_design_baseline_reader_test");
      assertCount(reader, "version");
      assertCount(reader, "publish_recorded_participant_digest");
      assertVersionLockDenied(reader, "9701", version.getId());
      assertDenied(
          reader,
          "UPDATE game_design_service.publish_recorded_participant_digest "
              + "SET content_digest = 'forbidden' WHERE false");
      assertDenied(
          reader,
          "INSERT INTO game_design_service.publish_recorded_participant_digest "
              + "(tenant_id) VALUES ('forbidden')");
      assertDenied(reader, "CREATE TABLE game_design_service.reader_forbidden (id integer)");
      assertDenied(
          reader, "CREATE TABLE baseline_migration_other_schema.reader_forbidden (id integer)");
      assertDenied(
          reader, "UPDATE baseline_migration_other_schema.sentinel SET id = id WHERE false");
    }

    try (Connection writer =
        connectAs("firemud_game_design_baseline_writer_test", postgres.getPassword())) {
      assertThat(writer.getMetaData().getUserName())
          .isEqualTo("firemud_game_design_baseline_writer_test");
      assertCount(writer, "version");
      assertCount(writer, "publish_recorded_participant_digest");
      assertCount(writer, "entity_digest_baseline_migration_audit");

      writer.setAutoCommit(false);
      try (PreparedStatement lock =
          writer.prepareStatement(
              "SELECT id, tenant_id, version_number, version_state, version_state_epoch,"
                  + " script_patch_version, base_version_id, is_script_only, notes, created_at,"
                  + " updated_at FROM"
                  + " game_design_service.lock_version_for_entity_digest_baseline_migration(?,"
                  + " ?)")) {
        lock.setString(1, "9701");
        lock.setLong(2, version.getId());
        try (var rows = lock.executeQuery()) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getLong("id")).isEqualTo(version.getId());
          assertThat(rows.getString("tenant_id")).isEqualTo("9701");
          assertThat(rows.getInt("version_number")).isEqualTo(1);
          assertThat(rows.getString("version_state")).isEqualTo("PUBLISHED");
          assertThat(rows.getLong("version_state_epoch")).isEqualTo(version.getVersionStateEpoch());
          assertThat(rows.getString("script_patch_version"))
              .isEqualTo(version.getScriptPatchVersion());
          assertThat(rows.getObject("base_version_id")).isNull();
          assertThat(rows.getBoolean("is_script_only")).isFalse();
          assertThat(rows.getString("notes")).isEqualTo(version.getNotes());
          assertThat(rows.next()).isFalse();
        }
      }
      writer.commit();
      assertNoVersionLockRow(writer, "wrong-tenant", version.getId());
      assertNoVersionLockRow(writer, "9701", version.getId() + 1_000_000L);
      writer.setAutoCommit(true);

      try (PreparedStatement update =
          writer.prepareStatement(
              "UPDATE game_design_service.publish_recorded_participant_digest "
                  + "SET content_digest = ?, digest_schema_version = 2 "
                  + "WHERE id = ? AND content_digest = ? AND digest_schema_version = 1")) {
        update.setString(1, "entity-v2-restricted-writer-proof");
        update.setLong(2, baseline.getId());
        update.setString(3, baseline.getContentDigest());
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      try (PreparedStatement insert =
          writer.prepareStatement(
              "INSERT INTO game_design_service.entity_digest_baseline_migration_audit ("
                  + "operation_id, recorded_participant_digest_id, source_tenant_id, "
                  + "source_publish_type, source_participant_key, source_base_version_id, "
                  + "source_scope_value, source_applied_commit_id, source_content_digest, "
                  + "source_digest_schema_version, source_recorded_from_publish_workflow_id, "
                  + "source_recorded_at, source_last_verified_publish_workflow_id, "
                  + "source_last_verified_at, observed_tenant_id, observed_publish_type, "
                  + "observed_participant_key, observed_base_version_id, observed_scope_value, "
                  + "observed_applied_commit_id, observed_content_digest, "
                  + "observed_digest_schema_version, actor_identity, workload_identity, outcome, "
                  + "committed_at) "
                  + "SELECT ?, id, tenant_id, publish_type, participant_key, base_version_id, "
                  + "scope_value, applied_commit_id, ?, 1, recorded_from_publish_workflow_id, "
                  + "recorded_at, last_verified_publish_workflow_id, last_verified_at, tenant_id, "
                  + "publish_type, participant_key, base_version_id, scope_value, "
                  + "applied_commit_id, content_digest, digest_schema_version, ?, ?, "
                  + "'COMMITTED', CURRENT_TIMESTAMP "
                  + "FROM game_design_service.publish_recorded_participant_digest WHERE id = ?")) {
        insert.setString(1, "restricted-writer-proof-" + baseline.getId());
        insert.setString(2, baseline.getContentDigest());
        insert.setString(3, "operator:test-actor");
        insert.setString(4, "spiffe://firemud/ns/dev/sa/game-design-baseline-migrator");
        insert.setLong(5, baseline.getId());
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }

      assertDenied(
          writer,
          "UPDATE game_design_service.version SET version_state = 'DRAFT' WHERE id = "
              + version.getId());
      assertDenied(
          writer,
          "UPDATE game_design_service.publish_recorded_participant_digest "
              + "SET tenant_id = 'forbidden' WHERE id = "
              + baseline.getId());
      assertDenied(
          writer, "INSERT INTO game_design_service.version (tenant_id) VALUES ('forbidden')");
      assertDenied(
          writer,
          "SELECT id FROM game_design_service.version WHERE id = "
              + version.getId()
              + " FOR UPDATE");
      assertDenied(
          writer,
          "UPDATE game_design_service.flyway_schema_history_game_design_service "
              + "SET success = success WHERE false");
      assertDenied(writer, "CREATE TABLE game_design_service.writer_forbidden (id integer)");
      assertDenied(
          writer, "CREATE TABLE baseline_migration_other_schema.writer_forbidden (id integer)");
      assertDenied(
          writer, "UPDATE baseline_migration_other_schema.sentinel SET id = id WHERE false");
    }

    try (Connection unrelated =
        connectAs("firemud_game_design_baseline_unrelated_test", postgres.getPassword())) {
      assertThat(unrelated.getMetaData().getUserName())
          .isEqualTo("firemud_game_design_baseline_unrelated_test");
      assertVersionLockDenied(unrelated, "9701", version.getId());
    }
  }

  private void provisionMigrationDatabaseRoles() throws SQLException {
    try (Connection admin =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute(
          "CREATE ROLE firemud_game_design_baseline_reader_test LOGIN NOINHERIT PASSWORD"
              + " 'test'");
      statement.execute(
          "CREATE ROLE firemud_game_design_baseline_writer_test LOGIN NOINHERIT PASSWORD"
              + " 'test'");
      statement.execute(
          "CREATE ROLE firemud_game_design_baseline_unrelated_test LOGIN NOINHERIT "
              + "PASSWORD 'test'");
      statement.execute("CREATE SCHEMA baseline_migration_other_schema");
      statement.execute(
          "CREATE TABLE baseline_migration_other_schema.sentinel (id integer NOT NULL)");
      statement.execute("INSERT INTO baseline_migration_other_schema.sentinel VALUES (1)");
      statement.execute(
          "GRANT USAGE ON SCHEMA game_design_service TO "
              + "firemud_game_design_baseline_reader_test");
      statement.execute(
          "GRANT USAGE ON SCHEMA game_design_service TO "
              + "firemud_game_design_baseline_writer_test");
      statement.execute(
          "GRANT USAGE ON SCHEMA game_design_service TO"
              + " firemud_game_design_baseline_unrelated_test");
      statement.execute(
          "GRANT SELECT ON TABLE game_design_service.version, "
              + "game_design_service.publish_recorded_participant_digest "
              + "TO firemud_game_design_baseline_reader_test");
      statement.execute(
          "GRANT SELECT ON TABLE game_design_service.version, "
              + "game_design_service.publish_recorded_participant_digest, "
              + "game_design_service.entity_digest_baseline_migration_audit "
              + "TO firemud_game_design_baseline_writer_test");
      statement.execute(
          "GRANT UPDATE (applied_commit_id, content_digest, digest_schema_version) "
              + "ON TABLE game_design_service.publish_recorded_participant_digest "
              + "TO firemud_game_design_baseline_writer_test");
      statement.execute(
          "GRANT INSERT ON TABLE game_design_service.entity_digest_baseline_migration_audit "
              + "TO firemud_game_design_baseline_writer_test");
      statement.execute(
          "GRANT USAGE, SELECT ON SEQUENCE "
              + "game_design_service.entity_digest_baseline_migration_audit_id_seq "
              + "TO firemud_game_design_baseline_writer_test");
      statement.execute(
          "GRANT EXECUTE ON FUNCTION "
              + "game_design_service.lock_version_for_entity_digest_baseline_migration("
              + "VARCHAR(36), BIGINT) "
              + "TO firemud_game_design_baseline_writer_test");
    }
  }

  private Connection connectAs(String username, String password) throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl() + "?currentSchema=game_design_service", username, password);
  }

  private void assertCount(Connection connection, String tableName) throws SQLException {
    try (Statement statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT COUNT(*) FROM game_design_service." + tableName)) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getLong(1)).isGreaterThanOrEqualTo(0);
    }
  }

  private void assertNoVersionLockRow(Connection connection, String tenantId, long versionId)
      throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement(
            "SELECT id FROM "
                + "game_design_service.lock_version_for_entity_digest_baseline_migration(?, ?)")) {
      lock.setString(1, tenantId);
      lock.setLong(2, versionId);
      try (var rows = lock.executeQuery()) {
        assertThat(rows.next()).isFalse();
      }
    }
  }

  private void assertVersionLockDenied(Connection connection, String tenantId, long versionId)
      throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement(
            "SELECT id FROM "
                + "game_design_service.lock_version_for_entity_digest_baseline_migration(?, ?)")) {
      lock.setString(1, tenantId);
      lock.setLong(2, versionId);
      try {
        try (var rows = lock.executeQuery()) {
          assertThat(rows.next()).isFalse();
        }
      } catch (SQLException denied) {
        assertThat(denied.getSQLState()).isEqualTo("42501");
        return;
      }
    }
    throw new AssertionError("restricted migration role unexpectedly executed version lock");
  }

  private void assertDenied(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      try {
        statement.execute(sql);
      } catch (SQLException denied) {
        assertThat(denied.getSQLState()).isEqualTo("42501");
        return;
      }
    }
    throw new AssertionError("restricted migration database role unexpectedly succeeded: " + sql);
  }

  private void createGame(String tenantId) {
    Game game = new Game();
    game.setTenantId(tenantId);
    game.setName("entity-baseline-migration-" + tenantId);
    gameRepository.save(game);
  }

  private Version createVersion(String tenantId, int versionNumber) {
    return createVersion(tenantId, versionNumber, VersionLifecycleState.PUBLISHED);
  }

  private Version createVersion(String tenantId, int versionNumber, VersionLifecycleState state) {
    Version version = new Version();
    version.setTenantId(tenantId);
    version.setVersionNumber(versionNumber);
    version.setVersionState(state);
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

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted while waiting for test coordination", exception);
    }
  }

  private String v2Digest(long versionId) {
    return "entity-v2-observed-" + versionId;
  }
}
