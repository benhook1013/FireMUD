package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.EntityDigestBaselineMigrationAuditRepository;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityDigestBaselineMigrationServiceTest {
  private static final long BASELINE_ID = 91L;
  private static final long VERSION_ID = 42L;
  private static final long AUDIT_ID = 701L;
  private static final String OPERATION_ID = "migration-op-42";
  private static final String TENANT_ID = "tenant-7";
  private static final LocalDateTime SOURCE_RECORDED_AT =
      LocalDateTime.parse("2026-09-20T12:30:00.123456");
  private static final LocalDateTime SOURCE_VERIFIED_AT =
      LocalDateTime.parse("2026-09-21T12:30:00.654321");

  @Mock private RecordedParticipantDigestRepository baselineRepository;
  @Mock private EntityDigestBaselineMigrationAuditRepository auditRepository;
  @Mock private VersionRepository versionRepository;
  @Mock private EntityManagementClient entityManagementClient;

  private RecordedParticipantDigest source;
  private EntityDigestBaselineMigrationService.ExpectedSource expectedSource;
  private EntityDigestBaselineMigrationService.MigrationCommand command;

  @BeforeEach
  void setUp() {
    source = sourceBaseline();
    expectedSource = expectedSource(source);
    command = command(expectedSource);
  }

  @Test
  void preflightDistinguishesNoBaselineV2AndMissingVersion() {
    EntityDigestBaselineMigrationService service = serviceWithMockWriter();
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(), List.of(v2Baseline()));

    assertEquals(
        EntityDigestBaselineMigrationService.ScopeStatus.NO_RECORDED_BASELINE,
        service.preflightScope(TENANT_ID, VERSION_ID).status());
    assertEquals(
        EntityDigestBaselineMigrationService.ScopeStatus.V2_ALREADY_RECORDED,
        service.preflightScope(TENANT_ID, VERSION_ID).status());

    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID)).thenReturn(Optional.empty());
    EntityDigestBaselineMigrationService.MigrationRejectedException missingVersion =
        assertThrows(
            EntityDigestBaselineMigrationService.MigrationRejectedException.class,
            () -> service.preflightScope(TENANT_ID, VERSION_ID));
    assertEquals("VERSION_MISSING", missingVersion.failureCode());
  }

  @Test
  void preflightClassifiesRetainedEntityV1Row() {
    EntityDigestBaselineMigrationService service = serviceWithMockWriter();
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(source));

    EntityDigestBaselineMigrationService.ScopePreflight result =
        service.preflightScope(TENANT_ID, VERSION_ID);

    assertEquals(
        EntityDigestBaselineMigrationService.ScopeStatus.V1_REQUIRES_MIGRATION, result.status());
    assertEquals(BASELINE_ID, result.baselines().get(0).baselineId());
    assertEquals("version:42", result.baselines().get(0).appliedCommitId());
  }

  @Test
  void preflightReportsAmbiguousScopeInsteadOfSelectingARow() {
    EntityDigestBaselineMigrationService service = serviceWithMockWriter();
    RecordedParticipantDigest duplicate = sourceBaseline();
    duplicate.setId(BASELINE_ID + 1);
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(source, duplicate));

    EntityDigestBaselineMigrationService.ScopePreflight result =
        service.preflightScope(TENANT_ID, VERSION_ID);

    assertEquals(
        EntityDigestBaselineMigrationService.ScopeStatus.AMBIGUOUS_BASELINE, result.status());
    assertEquals(2, result.baselines().size());
  }

  @Test
  void enumerationReturnsBoundedCursorPageWithExactScopeIdentities() {
    EntityDigestBaselineMigrationService service = serviceWithMockWriter();
    when(baselineRepository.findEntityV1FullVersionBatchAfterId(10L, 25))
        .thenReturn(List.of(source));

    EntityDigestBaselineMigrationService.EntityV1Batch page =
        service.enumerateEntityV1Batch(10L, 25);

    assertEquals(10L, page.scannedAfterBaselineId());
    assertEquals(25, page.limit());
    assertEquals(BASELINE_ID, page.nextAfterBaselineId());
    assertEquals(TENANT_ID, page.baselines().get(0).tenantId());
    assertEquals(VERSION_ID, page.baselines().get(0).versionId());
    assertFalse(page.isEmptyAtCursor());
  }

  @Test
  void migrationCommitsAndProvesExactV2BaselineAndAuditReadback() {
    AtomicReference<RecordedParticipantDigest> persistedBaseline = new AtomicReference<>(source);
    AtomicReference<EntityDigestBaselineMigrationAudit> persistedAudit = new AtomicReference<>();
    EntityDigestBaselineMigrationWriteService writer =
        new EntityDigestBaselineMigrationWriteService(baselineRepository, auditRepository);
    EntityDigestBaselineMigrationService service =
        new EntityDigestBaselineMigrationService(
            baselineRepository, auditRepository, versionRepository, entityManagementClient, writer);
    stubInitialMigrationReads(persistedBaseline, persistedAudit);
    when(entityManagementClient.getDraftDesignDigestForVersion(any()))
        .thenReturn(v2Digest("v2-content", "version:42"));
    when(baselineRepository.migrateEntityFullVersionBaselineIfUnchanged(any(), any()))
        .thenAnswer(
            invocation -> {
              RecordedParticipantDigest expected = invocation.getArgument(0);
              if (!sameSource(persistedBaseline.get(), expected)) {
                return 0;
              }
              persistedBaseline.set(invocation.getArgument(1));
              return 1;
            });
    when(auditRepository.insert(any()))
        .thenAnswer(
            invocation -> {
              EntityDigestBaselineMigrationAudit inserted = invocation.getArgument(0);
              EntityDigestBaselineMigrationAudit stored = withId(inserted, AUDIT_ID);
              persistedAudit.set(stored);
              return stored;
            });

    EntityDigestBaselineMigrationService.MigrationResult result = service.migrate(command);

    assertEquals(
        EntityDigestBaselineMigrationService.MigrationDisposition.APPLIED, result.disposition());
    assertEquals(AUDIT_ID, result.auditId());
    assertEquals(BASELINE_ID, result.baselineId());
    assertEquals("version:42", result.appliedCommitId());
    assertEquals("v2-content", result.contentDigest());
    assertEquals(2, result.digestSchemaVersion());
    assertEquals(OPERATION_ID, persistedBaseline.get().getRecordedFromPublishWorkflowId());
    assertEquals(OPERATION_ID, persistedBaseline.get().getLastVerifiedPublishWorkflowId());
    assertEquals(source.getContentDigest(), persistedAudit.get().sourceContentDigest());
    assertEquals("v2-content", persistedAudit.get().observedContentDigest());
  }

  @Test
  void exactRetryReturnsOnlyAfterBaselineAndAuditReadbackWithoutRecomputing() {
    RecordedParticipantDigest migrated = migratedBaseline("v2-content", "version:42", auditTime());
    EntityDigestBaselineMigrationAudit audit = audit(source, migrated, AUDIT_ID, auditTime());
    when(auditRepository.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(audit));
    when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(migrated));

    EntityDigestBaselineMigrationService.MigrationResult result =
        serviceWithMockWriter().migrate(command);

    assertEquals(
        EntityDigestBaselineMigrationService.MigrationDisposition.EXACT_RETRY,
        result.disposition());
    assertEquals("v2-content", result.contentDigest());
    verify(entityManagementClient, never()).getDraftDesignDigestForVersion(any());
    verify(versionRepository, never()).findByTenantIdAndId(any(), any());
  }

  @Test
  void operationIdReuseWithDifferentSourceIsRejected() {
    EntityDigestBaselineMigrationAudit conflicting =
        audit(
            source,
            migratedBaseline("v2-content", "version:42", auditTime()),
            AUDIT_ID,
            auditTime());
    conflicting = withSourceContentDigest(conflicting, "different-v1-digest");
    when(auditRepository.findByOperationId(OPERATION_ID)).thenReturn(Optional.of(conflicting));

    EntityDigestBaselineMigrationService.MigrationRejectedException rejected =
        assertThrows(
            EntityDigestBaselineMigrationService.MigrationRejectedException.class,
            () -> serviceWithMockWriter().migrate(command));

    assertEquals("OPERATION_ID_CONFLICT", rejected.failureCode());
    verify(baselineRepository, never()).findById(BASELINE_ID);
    verify(entityManagementClient, never()).getDraftDesignDigestForVersion(any());
  }

  @Test
  void changedOldTupleIsRejectedBeforeEntityRecomputation() {
    RecordedParticipantDigest changed = sourceBaseline();
    changed.setLastVerifiedAt(SOURCE_VERIFIED_AT.plusSeconds(1));
    when(auditRepository.findByOperationId(OPERATION_ID)).thenReturn(Optional.empty());
    when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(changed));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(changed));

    EntityDigestBaselineMigrationService.MigrationRejectedException rejected =
        assertThrows(
            EntityDigestBaselineMigrationService.MigrationRejectedException.class,
            () -> serviceWithMockWriter().migrate(command));

    assertEquals("SOURCE_BASELINE_CHANGED", rejected.failureCode());
    verify(entityManagementClient, never()).getDraftDesignDigestForVersion(any());
  }

  @Test
  void wrongTenantBaselineIsRejectedBeforeScopeOrEntityRead() {
    RecordedParticipantDigest wrongTenant = sourceBaseline();
    wrongTenant.setTenantId("tenant-other");
    when(auditRepository.findByOperationId(OPERATION_ID)).thenReturn(Optional.empty());
    when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(wrongTenant));

    EntityDigestBaselineMigrationService.MigrationRejectedException rejected =
        assertThrows(
            EntityDigestBaselineMigrationService.MigrationRejectedException.class,
            () -> serviceWithMockWriter().migrate(command));

    assertEquals("BASELINE_TENANT_MISMATCH", rejected.failureCode());
    verify(entityManagementClient, never()).getDraftDesignDigestForVersion(any());
  }

  @Test
  void schemaOneSourceCannotBeSilentlyReplacedWhenEntityCommitIdentityChanges() {
    when(auditRepository.findByOperationId(OPERATION_ID)).thenReturn(Optional.empty());
    when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(source));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(source));
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
    when(entityManagementClient.getDraftDesignDigestForVersion(any()))
        .thenReturn(v2Digest("v2-content", "version:43"));

    EntityDigestBaselineMigrationService.MigrationRejectedException rejected =
        assertThrows(
            EntityDigestBaselineMigrationService.MigrationRejectedException.class,
            () -> serviceWithMockWriter().migrate(command));

    assertEquals("ENTITY_APPLIED_COMMIT_MISMATCH", rejected.failureCode());
  }

  @Test
  void uncertainWriterOutcomeSucceedsOnlyWhenExactCommittedReadbackIsFound() {
    AtomicReference<RecordedParticipantDigest> persistedBaseline = new AtomicReference<>(source);
    AtomicReference<EntityDigestBaselineMigrationAudit> persistedAudit = new AtomicReference<>();
    EntityDigestBaselineMigrationWriteService writer =
        org.mockito.Mockito.mock(EntityDigestBaselineMigrationWriteService.class);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              persistedBaseline.set(invocation.getArgument(1));
              persistedAudit.set(withId(invocation.getArgument(2), AUDIT_ID));
              throw new IllegalStateException("connection lost after commit");
            })
        .when(writer)
        .commit(any(), any(), any());
    when(auditRepository.findByOperationId(OPERATION_ID))
        .thenAnswer(invocation -> Optional.ofNullable(persistedAudit.get()));
    when(baselineRepository.findById(BASELINE_ID))
        .thenAnswer(invocation -> Optional.ofNullable(persistedBaseline.get()));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(source));
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
    when(entityManagementClient.getDraftDesignDigestForVersion(any()))
        .thenReturn(v2Digest("v2-content", "version:42"));

    EntityDigestBaselineMigrationService.MigrationResult result = service(writer).migrate(command);

    assertEquals(
        EntityDigestBaselineMigrationService.MigrationDisposition.RECOVERED_AFTER_WRITE_ERROR,
        result.disposition());
    assertEquals(AUDIT_ID, result.auditId());
  }

  @Test
  void uncertainWriterOutcomeFailsWhenReadbackCannotProveBothRows() {
    EntityDigestBaselineMigrationWriteService writer =
        org.mockito.Mockito.mock(EntityDigestBaselineMigrationWriteService.class);
    doThrow(new IllegalStateException("commit acknowledgement lost"))
        .when(writer)
        .commit(any(), any(), any());
    when(auditRepository.findByOperationId(OPERATION_ID))
        .thenReturn(Optional.empty(), Optional.empty());
    when(baselineRepository.findById(BASELINE_ID)).thenReturn(Optional.of(source));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenReturn(List.of(source));
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
    when(entityManagementClient.getDraftDesignDigestForVersion(any()))
        .thenReturn(v2Digest("v2-content", "version:42"));

    EntityDigestBaselineMigrationService.MigrationRejectedException rejected =
        assertThrows(
            EntityDigestBaselineMigrationService.MigrationRejectedException.class,
            () -> service(writer).migrate(command));

    assertEquals("WRITE_OUTCOME_UNPROVEN", rejected.failureCode());
    assertTrue(rejected.getCause().getSuppressed().length > 0);
  }

  private EntityDigestBaselineMigrationService serviceWithMockWriter() {
    return service(org.mockito.Mockito.mock(EntityDigestBaselineMigrationWriteService.class));
  }

  private EntityDigestBaselineMigrationService service(
      EntityDigestBaselineMigrationWriteService writer) {
    return new EntityDigestBaselineMigrationService(
        baselineRepository, auditRepository, versionRepository, entityManagementClient, writer);
  }

  private void stubInitialMigrationReads(
      AtomicReference<RecordedParticipantDigest> persistedBaseline,
      AtomicReference<EntityDigestBaselineMigrationAudit> persistedAudit) {
    when(auditRepository.findByOperationId(OPERATION_ID))
        .thenAnswer(invocation -> Optional.ofNullable(persistedAudit.get()));
    when(baselineRepository.findById(BASELINE_ID))
        .thenAnswer(invocation -> Optional.ofNullable(persistedBaseline.get()));
    when(baselineRepository.findEntityFullVersionBaselinesByScope(TENANT_ID, "42"))
        .thenAnswer(invocation -> List.of(persistedBaseline.get()));
    when(versionRepository.findByTenantIdAndId(TENANT_ID, VERSION_ID))
        .thenReturn(Optional.of(version()));
  }

  private boolean sameSource(
      RecordedParticipantDigest current, RecordedParticipantDigest expected) {
    return current != null
        && current.getId().equals(expected.getId())
        && current.getAppliedCommitId().equals(expected.getAppliedCommitId())
        && current.getContentDigest().equals(expected.getContentDigest())
        && current.getDigestSchemaVersion().equals(expected.getDigestSchemaVersion())
        && current.getLastVerifiedAt().equals(expected.getLastVerifiedAt());
  }

  private RecordedParticipantDigest sourceBaseline() {
    RecordedParticipantDigest baseline = new RecordedParticipantDigest();
    baseline.setId(BASELINE_ID);
    baseline.setTenantId(TENANT_ID);
    baseline.setPublishType(PublishType.FULL_VERSION);
    baseline.setParticipantKey(PublishParticipantKey.ENTITY_MANAGEMENT);
    baseline.setScopeValue(Long.toString(VERSION_ID));
    baseline.setBaseVersionId(null);
    baseline.setAppliedCommitId("version:42");
    baseline.setContentDigest("v1-content");
    baseline.setDigestSchemaVersion(1);
    baseline.setRecordedFromPublishWorkflowId("publish:tenant-7:old-request");
    baseline.setRecordedAt(SOURCE_RECORDED_AT);
    baseline.setLastVerifiedPublishWorkflowId("publish:tenant-7:old-verification");
    baseline.setLastVerifiedAt(SOURCE_VERIFIED_AT);
    return baseline;
  }

  private EntityDigestBaselineMigrationService.ExpectedSource expectedSource(
      RecordedParticipantDigest baseline) {
    return new EntityDigestBaselineMigrationService.ExpectedSource(
        baseline.getId(),
        baseline.getTenantId(),
        baseline.getPublishType(),
        baseline.getParticipantKey(),
        baseline.getBaseVersionId(),
        baseline.getScopeValue(),
        baseline.getAppliedCommitId(),
        baseline.getContentDigest(),
        baseline.getDigestSchemaVersion(),
        baseline.getRecordedFromPublishWorkflowId(),
        baseline.getRecordedAt(),
        baseline.getLastVerifiedPublishWorkflowId(),
        baseline.getLastVerifiedAt());
  }

  private EntityDigestBaselineMigrationService.MigrationCommand command(
      EntityDigestBaselineMigrationService.ExpectedSource expected) {
    return new EntityDigestBaselineMigrationService.MigrationCommand(
        OPERATION_ID,
        BASELINE_ID,
        TENANT_ID,
        VERSION_ID,
        expected,
        "operator:reviewer-1",
        "job:entity-baseline");
  }

  private Version version() {
    Version version = new Version();
    version.setId(VERSION_ID);
    version.setTenantId(TENANT_ID);
    version.setVersionState(VersionLifecycleState.PUBLISHED);
    version.setScriptOnly(false);
    return version;
  }

  private RecordedParticipantDigest v2Baseline() {
    RecordedParticipantDigest baseline = sourceBaseline();
    baseline.setDigestSchemaVersion(2);
    baseline.setContentDigest("already-v2");
    return baseline;
  }

  private PublishParticipantDigestDto v2Digest(String digest, String appliedCommitId) {
    return new PublishParticipantDigestDto(
        "ENTITY_MANAGEMENT", "42", null, appliedCommitId, digest, 2, null, null);
  }

  private RecordedParticipantDigest migratedBaseline(
      String digest, String appliedCommitId, LocalDateTime committedAt) {
    RecordedParticipantDigest baseline = sourceBaseline();
    baseline.setAppliedCommitId(appliedCommitId);
    baseline.setContentDigest(digest);
    baseline.setDigestSchemaVersion(2);
    baseline.setRecordedFromPublishWorkflowId(OPERATION_ID);
    baseline.setRecordedAt(committedAt);
    baseline.setLastVerifiedPublishWorkflowId(OPERATION_ID);
    baseline.setLastVerifiedAt(committedAt);
    return baseline;
  }

  private EntityDigestBaselineMigrationAudit audit(
      RecordedParticipantDigest oldBaseline,
      RecordedParticipantDigest target,
      Long auditId,
      LocalDateTime committedAt) {
    return new EntityDigestBaselineMigrationAudit(
        auditId,
        OPERATION_ID,
        BASELINE_ID,
        oldBaseline.getTenantId(),
        oldBaseline.getPublishType(),
        oldBaseline.getParticipantKey(),
        oldBaseline.getBaseVersionId(),
        oldBaseline.getScopeValue(),
        oldBaseline.getAppliedCommitId(),
        oldBaseline.getContentDigest(),
        oldBaseline.getDigestSchemaVersion(),
        oldBaseline.getRecordedFromPublishWorkflowId(),
        oldBaseline.getRecordedAt(),
        oldBaseline.getLastVerifiedPublishWorkflowId(),
        oldBaseline.getLastVerifiedAt(),
        target.getTenantId(),
        target.getPublishType(),
        target.getParticipantKey(),
        target.getBaseVersionId(),
        target.getScopeValue(),
        target.getAppliedCommitId(),
        target.getContentDigest(),
        target.getDigestSchemaVersion(),
        "operator:reviewer-1",
        "job:entity-baseline",
        EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME,
        committedAt);
  }

  private EntityDigestBaselineMigrationAudit withId(
      EntityDigestBaselineMigrationAudit audit, Long id) {
    return new EntityDigestBaselineMigrationAudit(
        id,
        audit.operationId(),
        audit.recordedParticipantDigestId(),
        audit.sourceTenantId(),
        audit.sourcePublishType(),
        audit.sourceParticipantKey(),
        audit.sourceBaseVersionId(),
        audit.sourceScopeValue(),
        audit.sourceAppliedCommitId(),
        audit.sourceContentDigest(),
        audit.sourceDigestSchemaVersion(),
        audit.sourceRecordedFromPublishWorkflowId(),
        audit.sourceRecordedAt(),
        audit.sourceLastVerifiedPublishWorkflowId(),
        audit.sourceLastVerifiedAt(),
        audit.observedTenantId(),
        audit.observedPublishType(),
        audit.observedParticipantKey(),
        audit.observedBaseVersionId(),
        audit.observedScopeValue(),
        audit.observedAppliedCommitId(),
        audit.observedContentDigest(),
        audit.observedDigestSchemaVersion(),
        audit.actorIdentity(),
        audit.workloadIdentity(),
        audit.outcome(),
        audit.committedAt());
  }

  private EntityDigestBaselineMigrationAudit withSourceContentDigest(
      EntityDigestBaselineMigrationAudit audit, String contentDigest) {
    return new EntityDigestBaselineMigrationAudit(
        audit.id(),
        audit.operationId(),
        audit.recordedParticipantDigestId(),
        audit.sourceTenantId(),
        audit.sourcePublishType(),
        audit.sourceParticipantKey(),
        audit.sourceBaseVersionId(),
        audit.sourceScopeValue(),
        audit.sourceAppliedCommitId(),
        contentDigest,
        audit.sourceDigestSchemaVersion(),
        audit.sourceRecordedFromPublishWorkflowId(),
        audit.sourceRecordedAt(),
        audit.sourceLastVerifiedPublishWorkflowId(),
        audit.sourceLastVerifiedAt(),
        audit.observedTenantId(),
        audit.observedPublishType(),
        audit.observedParticipantKey(),
        audit.observedBaseVersionId(),
        audit.observedScopeValue(),
        audit.observedAppliedCommitId(),
        audit.observedContentDigest(),
        audit.observedDigestSchemaVersion(),
        audit.actorIdentity(),
        audit.workloadIdentity(),
        audit.outcome(),
        audit.committedAt());
  }

  private LocalDateTime auditTime() {
    return LocalDateTime.parse("2026-09-26T01:02:03.123456");
  }
}
