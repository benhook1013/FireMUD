package net.firedevops.firemud.gamedesign.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.repository.EntityDigestBaselineMigrationAuditRepository;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits one guarded baseline replacement and its immutable audit row in the same transaction. */
@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring repositories are internal collaborators.")
public class EntityDigestBaselineMigrationWriteService {
  private final RecordedParticipantDigestRepository baselineRepository;
  private final EntityDigestBaselineMigrationAuditRepository auditRepository;

  public EntityDigestBaselineMigrationWriteService(
      RecordedParticipantDigestRepository baselineRepository,
      EntityDigestBaselineMigrationAuditRepository auditRepository) {
    this.baselineRepository = baselineRepository;
    this.auditRepository = auditRepository;
  }

  @Transactional
  public void commit(
      RecordedParticipantDigest expectedOld,
      RecordedParticipantDigest replacement,
      EntityDigestBaselineMigrationAudit audit) {
    validateWriteSet(expectedOld, replacement, audit);
    int updated =
        baselineRepository.migrateEntityFullVersionBaselineIfUnchanged(expectedOld, replacement);
    if (updated != 1) {
      throw new IllegalStateException(
          "Entity v1 baseline changed before the guarded migration write");
    }
    auditRepository.insert(audit);
  }

  private void validateWriteSet(
      RecordedParticipantDigest expectedOld,
      RecordedParticipantDigest replacement,
      EntityDigestBaselineMigrationAudit audit) {
    Objects.requireNonNull(expectedOld, "expectedOld must not be null");
    Objects.requireNonNull(replacement, "replacement must not be null");
    Objects.requireNonNull(audit, "audit must not be null");
    if (!Objects.equals(expectedOld.getId(), replacement.getId())
        || !Objects.equals(expectedOld.getTenantId(), replacement.getTenantId())
        || expectedOld.getPublishType() != replacement.getPublishType()
        || expectedOld.getParticipantKey() != replacement.getParticipantKey()
        || !Objects.equals(expectedOld.getScopeValue(), replacement.getScopeValue())
        || !Objects.equals(expectedOld.getAppliedCommitId(), replacement.getAppliedCommitId())
        || !Objects.equals(expectedOld.getBaseVersionId(), replacement.getBaseVersionId())
        || !Objects.equals(
            expectedOld.getRecordedFromPublishWorkflowId(),
            audit.sourceRecordedFromPublishWorkflowId())
        || !Objects.equals(expectedOld.getRecordedAt(), audit.sourceRecordedAt())
        || !Objects.equals(
            expectedOld.getLastVerifiedPublishWorkflowId(),
            audit.sourceLastVerifiedPublishWorkflowId())
        || !Objects.equals(expectedOld.getLastVerifiedAt(), audit.sourceLastVerifiedAt())
        || !Objects.equals(expectedOld.getContentDigest(), audit.sourceContentDigest())
        || !Objects.equals(expectedOld.getDigestSchemaVersion(), audit.sourceDigestSchemaVersion())
        || expectedOld.getPublishType() != audit.sourcePublishType()
        || expectedOld.getParticipantKey() != audit.sourceParticipantKey()
        || !Objects.equals(expectedOld.getBaseVersionId(), audit.sourceBaseVersionId())
        || !Integer.valueOf(1).equals(expectedOld.getDigestSchemaVersion())
        || !Objects.equals(expectedOld.getId(), audit.recordedParticipantDigestId())
        || !Objects.equals(expectedOld.getTenantId(), audit.sourceTenantId())
        || !Objects.equals(expectedOld.getScopeValue(), audit.sourceScopeValue())
        || !Objects.equals(expectedOld.getAppliedCommitId(), audit.sourceAppliedCommitId())
        || !Objects.equals(replacement.getTenantId(), audit.observedTenantId())
        || replacement.getPublishType() != audit.observedPublishType()
        || replacement.getParticipantKey() != audit.observedParticipantKey()
        || !Objects.equals(replacement.getBaseVersionId(), audit.observedBaseVersionId())
        || !Objects.equals(replacement.getScopeValue(), audit.observedScopeValue())
        || !Objects.equals(replacement.getAppliedCommitId(), audit.observedAppliedCommitId())
        || !Objects.equals(replacement.getContentDigest(), audit.observedContentDigest())
        || !Objects.equals(
            replacement.getDigestSchemaVersion(), audit.observedDigestSchemaVersion())
        || !Integer.valueOf(2).equals(replacement.getDigestSchemaVersion())
        || audit.observedContentDigest() == null
        || audit.observedContentDigest().isBlank()
        || !Objects.equals(
            replacement.getRecordedFromPublishWorkflowId(),
            expectedOld.getRecordedFromPublishWorkflowId())
        || !Objects.equals(replacement.getRecordedAt(), expectedOld.getRecordedAt())
        || !Objects.equals(
            replacement.getLastVerifiedPublishWorkflowId(),
            expectedOld.getLastVerifiedPublishWorkflowId())
        || !Objects.equals(replacement.getLastVerifiedAt(), expectedOld.getLastVerifiedAt())
        || !EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME.equals(audit.outcome())) {
      throw new IllegalArgumentException(
          "migration audit and guarded baseline replacement do not describe one operation");
    }
  }
}
