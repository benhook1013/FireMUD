package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;

/** Immutable before-and-after evidence for one Entity digest baseline schema migration. */
public record EntityDigestBaselineMigrationAudit(
    Long id,
    String operationId,
    Long recordedParticipantDigestId,
    String sourceTenantId,
    PublishType sourcePublishType,
    PublishParticipantKey sourceParticipantKey,
    Long sourceBaseVersionId,
    String sourceScopeValue,
    String sourceAppliedCommitId,
    String sourceContentDigest,
    Integer sourceDigestSchemaVersion,
    String sourceRecordedFromPublishWorkflowId,
    LocalDateTime sourceRecordedAt,
    String sourceLastVerifiedPublishWorkflowId,
    LocalDateTime sourceLastVerifiedAt,
    String observedTenantId,
    PublishType observedPublishType,
    PublishParticipantKey observedParticipantKey,
    Long observedBaseVersionId,
    String observedScopeValue,
    String observedAppliedCommitId,
    String observedContentDigest,
    Integer observedDigestSchemaVersion,
    String actorIdentity,
    String workloadIdentity,
    String outcome,
    LocalDateTime committedAt) {
  public static final String COMMITTED_OUTCOME = "COMMITTED";
}
