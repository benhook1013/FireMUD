package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.EntityDigestBaselineMigrationAuditRepository;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityDigestBaselineMigrationWriteServiceTest {
  @Mock private RecordedParticipantDigestRepository baselineRepository;
  @Mock private EntityDigestBaselineMigrationAuditRepository auditRepository;

  private EntityDigestBaselineMigrationWriteService service;
  private RecordedParticipantDigest source;
  private RecordedParticipantDigest replacement;
  private EntityDigestBaselineMigrationAudit audit;

  @BeforeEach
  void setUp() {
    service = new EntityDigestBaselineMigrationWriteService(baselineRepository, auditRepository);
    LocalDateTime time = LocalDateTime.parse("2026-09-26T01:02:03.123456");
    source = baseline(1, "old", time.minusDays(1), "publish:old", "verify:old");
    replacement = baseline(2, "new", time.minusDays(1), "publish:old", "verify:old");
    audit = audit(time);
  }

  @Test
  void compareAndSetAndAuditAreBothRequiredForCommit() {
    when(baselineRepository.migrateEntityFullVersionBaselineIfUnchanged(source, replacement))
        .thenReturn(1);

    service.commit(source, replacement, audit);

    verify(baselineRepository).migrateEntityFullVersionBaselineIfUnchanged(source, replacement);
    verify(auditRepository).insert(audit);
  }

  @Test
  void changedRowPreventsAuditInsert() {
    when(baselineRepository.migrateEntityFullVersionBaselineIfUnchanged(source, replacement))
        .thenReturn(0);

    assertThrows(IllegalStateException.class, () -> service.commit(source, replacement, audit));

    verify(auditRepository, never()).insert(any());
  }

  @Test
  void maintenanceOperationCannotReplacePublishProvenance() {
    replacement.setRecordedFromPublishWorkflowId("migration-op");

    assertThrows(IllegalArgumentException.class, () -> service.commit(source, replacement, audit));

    verify(baselineRepository, never()).migrateEntityFullVersionBaselineIfUnchanged(any(), any());
    verify(auditRepository, never()).insert(any());
  }

  private RecordedParticipantDigest baseline(
      int schema, String digest, LocalDateTime time, String recordedFrom, String lastVerified) {
    RecordedParticipantDigest baseline = new RecordedParticipantDigest();
    baseline.setId(91L);
    baseline.setTenantId("tenant-7");
    baseline.setPublishType(PublishType.FULL_VERSION);
    baseline.setParticipantKey(PublishParticipantKey.ENTITY_MANAGEMENT);
    baseline.setScopeValue("42");
    baseline.setBaseVersionId(null);
    baseline.setAppliedCommitId("version:42");
    baseline.setContentDigest(digest);
    baseline.setDigestSchemaVersion(schema);
    baseline.setRecordedFromPublishWorkflowId(recordedFrom);
    baseline.setRecordedAt(time);
    baseline.setLastVerifiedPublishWorkflowId(lastVerified);
    baseline.setLastVerifiedAt(time);
    return baseline;
  }

  private EntityDigestBaselineMigrationAudit audit(LocalDateTime time) {
    return new EntityDigestBaselineMigrationAudit(
        null,
        "migration-op",
        91L,
        "tenant-7",
        PublishType.FULL_VERSION,
        PublishParticipantKey.ENTITY_MANAGEMENT,
        null,
        "42",
        "version:42",
        "old",
        1,
        "publish:old",
        time.minusDays(1),
        "verify:old",
        time.minusDays(1),
        "tenant-7",
        PublishType.FULL_VERSION,
        PublishParticipantKey.ENTITY_MANAGEMENT,
        null,
        "42",
        "version:42",
        "new",
        2,
        "operator:reviewer-1",
        "job:entity-baseline",
        EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME,
        time);
  }
}
