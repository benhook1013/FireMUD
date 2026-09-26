package net.firedevops.firemud.gamedesign.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.gamedesign.client.EntityManagementClient;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.EntityDigestBaselineMigrationAudit;
import net.firedevops.firemud.gamedesign.entity.RecordedParticipantDigest;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.model.PublishParticipantKey;
import net.firedevops.firemud.gamedesign.model.PublishType;
import net.firedevops.firemud.gamedesign.repository.EntityDigestBaselineMigrationAuditRepository;
import net.firedevops.firemud.gamedesign.repository.RecordedParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import org.springframework.stereotype.Service;

/** Bounded enumeration, preflight, and one-scope recomputation for retained Entity v1 baselines. */
@Service
public class EntityDigestBaselineMigrationService {
  private static final int SOURCE_SCHEMA_VERSION = 1;
  private static final int TARGET_SCHEMA_VERSION = 2;
  private static final int MAX_BATCH_SIZE = 500;
  private static final int MAX_OPERATION_ID_UTF8_BYTES = 128;
  private static final int MAX_AUDIT_IDENTITY_UTF8_BYTES = 256;
  private static final String PUBLICATION_REQUEST_PREFIX = "entity-baseline-migration-";

  private final RecordedParticipantDigestRepository baselineRepository;
  private final EntityDigestBaselineMigrationAuditRepository auditRepository;
  private final VersionRepository versionRepository;
  private final EntityManagementClient entityManagementClient;
  private final EntityDigestBaselineMigrationWriteService writeService;

  public EntityDigestBaselineMigrationService(
      RecordedParticipantDigestRepository baselineRepository,
      EntityDigestBaselineMigrationAuditRepository auditRepository,
      VersionRepository versionRepository,
      EntityManagementClient entityManagementClient,
      EntityDigestBaselineMigrationWriteService writeService) {
    this.baselineRepository = baselineRepository;
    this.auditRepository = auditRepository;
    this.versionRepository = versionRepository;
    this.entityManagementClient = entityManagementClient;
    this.writeService = writeService;
  }

  /** Returns one keyset page of retained Entity full-version schema-v1 rows. */
  public EntityV1Batch enumerateEntityV1Batch(long afterBaselineId, int limit) {
    if (afterBaselineId < 0) {
      throw new IllegalArgumentException("afterBaselineId must be non-negative");
    }
    if (limit < 1 || limit > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_BATCH_SIZE);
    }
    List<RecordedParticipantDigest> rows =
        baselineRepository.findEntityV1FullVersionBatchAfterId(afterBaselineId, limit);
    if (rows.size() > limit) {
      throw new IllegalStateException("baseline batch exceeded the requested bound");
    }
    List<BaselineSummary> summaries = rows.stream().map(this::toV1Summary).toList();
    Long nextAfterBaselineId =
        summaries.isEmpty() ? null : summaries.get(summaries.size() - 1).baselineId();
    return new EntityV1Batch(afterBaselineId, limit, summaries, nextAfterBaselineId);
  }

  /**
   * Classifies exactly one tenant/version scope without writing. NO_RECORDED_BASELINE proves only
   * this queried scope has no retained Entity full-version baseline at read time.
   */
  public ScopePreflight preflightScope(String tenantId, long versionId) {
    requireText(tenantId, "tenantId", 36);
    requirePositive(versionId, "versionId");
    Version version =
        versionRepository
            .findByTenantIdAndId(tenantId, versionId)
            .orElseThrow(() -> rejected("VERSION_MISSING", "tenant/version does not exist"));
    if (!Objects.equals(version.getTenantId(), tenantId)
        || !Objects.equals(version.getId(), versionId)
        || version.isScriptOnly()) {
      throw rejected("VERSION_SCOPE_MISMATCH", "version is outside the tenant or is script-only");
    }
    List<RecordedParticipantDigest> rows =
        baselineRepository.findEntityFullVersionBaselinesByScope(
            tenantId, Long.toString(versionId));
    if (rows.size() > 2) {
      throw new IllegalStateException("scope preflight exceeded its two-row bound");
    }
    for (RecordedParticipantDigest row : rows) {
      if (!Objects.equals(row.getTenantId(), tenantId)
          || !Objects.equals(row.getScopeValue(), Long.toString(versionId))
          || row.getPublishType() != PublishType.FULL_VERSION
          || row.getParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
          || row.getBaseVersionId() != null) {
        throw new IllegalStateException("scope query returned a row outside its exact scope");
      }
    }
    ScopeStatus status;
    if (rows.isEmpty()) {
      status = ScopeStatus.NO_RECORDED_BASELINE;
    } else if (rows.size() > 1) {
      status = ScopeStatus.AMBIGUOUS_BASELINE;
    } else if (Objects.equals(rows.get(0).getDigestSchemaVersion(), SOURCE_SCHEMA_VERSION)) {
      status = ScopeStatus.V1_REQUIRES_MIGRATION;
    } else if (Objects.equals(rows.get(0).getDigestSchemaVersion(), TARGET_SCHEMA_VERSION)) {
      status = ScopeStatus.V2_ALREADY_RECORDED;
    } else {
      status = ScopeStatus.UNSUPPORTED_SCHEMA;
    }
    return new ScopePreflight(
        tenantId, versionId, status, rows.stream().map(this::toSummary).toList());
  }

  /** Recomputes and atomically re-records one exact Entity full-version v1 baseline. */
  public MigrationResult migrate(MigrationCommand command) {
    validateCommand(command);

    Optional<EntityDigestBaselineMigrationAudit> priorAudit =
        auditRepository.findByOperationId(command.operationId());
    if (priorAudit.isPresent()) {
      EntityDigestBaselineMigrationAudit audit = priorAudit.orElseThrow();
      assertAuditSourceMatchesCommand(audit, command);
      return readBackCommittedResult(command, audit, MigrationDisposition.EXACT_RETRY);
    }

    RecordedParticipantDigest current =
        baselineRepository
            .findById(command.baselineId())
            .orElseThrow(() -> rejected("BASELINE_MISSING", "baseline row does not exist"));
    validateCurrentRowIdentity(current, command);
    List<RecordedParticipantDigest> scopeRows =
        baselineRepository.findEntityFullVersionBaselinesByScope(
            command.tenantId(), Long.toString(command.versionId()));
    if (scopeRows.size() != 1) {
      throw rejected(
          scopeRows.isEmpty() ? "BASELINE_MISSING" : "BASELINE_SCOPE_AMBIGUOUS",
          "the exact tenant/version scope must contain one Entity full-version baseline");
    }
    if (!Objects.equals(scopeRows.get(0).getId(), command.baselineId())) {
      throw rejected(
          "BASELINE_ID_SCOPE_MISMATCH", "baseline id does not own the requested tenant/version");
    }
    assertSourceMatchesCommand(current, command.expectedSource());

    Version version =
        versionRepository
            .findByTenantIdAndId(command.tenantId(), command.versionId())
            .orElseThrow(() -> rejected("VERSION_MISSING", "tenant/version does not exist"));
    if (!Objects.equals(version.getTenantId(), command.tenantId())
        || !Objects.equals(version.getId(), command.versionId())
        || version.isScriptOnly()) {
      throw rejected("VERSION_SCOPE_MISMATCH", "version is outside the tenant or is script-only");
    }

    PublicationDigestRequestBinding binding =
        PublicationDigestRequestBinding.full(
            command.tenantId(),
            Long.toString(command.versionId()),
            stablePublicationRequestId(command.operationId()));
    PublishParticipantDigestDto observed =
        entityManagementClient.getDraftDesignDigestForVersion(binding);
    validateObservedDigest(observed, command);

    LocalDateTime migrationTime = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    RecordedParticipantDigest replacement =
        replacementFor(current, observed, command.operationId(), migrationTime);
    EntityDigestBaselineMigrationAudit audit = auditFor(current, observed, command, migrationTime);
    try {
      writeService.commit(current, replacement, audit);
    } catch (RuntimeException uncertainWrite) {
      try {
        return readBackCommittedResult(
            command, audit, MigrationDisposition.RECOVERED_AFTER_WRITE_ERROR);
      } catch (RuntimeException readbackFailure) {
        uncertainWrite.addSuppressed(readbackFailure);
        throw rejected(
            "WRITE_OUTCOME_UNPROVEN",
            "migration write outcome could not be proven by exact audit and baseline readback",
            uncertainWrite);
      }
    }
    return readBackCommittedResult(command, audit, MigrationDisposition.APPLIED);
  }

  private void validateCommand(MigrationCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("migration command is required");
    }
    requireText(command.operationId(), "operationId", MAX_OPERATION_ID_UTF8_BYTES);
    requireText(command.tenantId(), "tenantId", 36);
    requirePositive(command.baselineId(), "baselineId");
    requirePositive(command.versionId(), "versionId");
    requireText(command.actorIdentity(), "actorIdentity", MAX_AUDIT_IDENTITY_UTF8_BYTES);
    requireText(command.workloadIdentity(), "workloadIdentity", MAX_AUDIT_IDENTITY_UTF8_BYTES);
    ExpectedSource expected = Objects.requireNonNull(command.expectedSource(), "expectedSource");
    requireText(expected.tenantId(), "expected tenantId", 36);
    requireText(expected.scopeValue(), "expected scopeValue", 128);
    requireText(expected.appliedCommitId(), "expected appliedCommitId", 128);
    requireText(expected.contentDigest(), "expected contentDigest", 128);
    requireText(expected.recordedFromPublishWorkflowId(), "expected recordedFrom workflow", 1024);
    requireText(expected.lastVerifiedPublishWorkflowId(), "expected lastVerified workflow", 1024);
    if (expected.digestSchemaVersion() == null
        || expected.digestSchemaVersion() != SOURCE_SCHEMA_VERSION
        || expected.recordedAt() == null
        || expected.lastVerifiedAt() == null
        || expected.baselineId() == null
        || expected.baselineId() < 1
        || !Objects.equals(expected.baselineId(), command.baselineId())
        || !Objects.equals(expected.tenantId(), command.tenantId())
        || expected.publishType() != PublishType.FULL_VERSION
        || expected.participantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || expected.baseVersionId() != null
        || !Objects.equals(expected.scopeValue(), Long.toString(command.versionId()))) {
      throw rejected(
          "SOURCE_SCHEMA_OR_PROVENANCE_INVALID",
          "expected source must be a complete schema-v1 baseline with provenance");
    }
  }

  private void validateCurrentRowIdentity(
      RecordedParticipantDigest current, MigrationCommand command) {
    if (!Objects.equals(current.getId(), command.baselineId())) {
      throw rejected("BASELINE_ID_MISMATCH", "baseline readback id does not match the command");
    }
    if (!Objects.equals(current.getTenantId(), command.tenantId())) {
      throw rejected("BASELINE_TENANT_MISMATCH", "baseline belongs to another tenant");
    }
    if (current.getPublishType() != PublishType.FULL_VERSION
        || current.getParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || current.getBaseVersionId() != null) {
      throw rejected(
          "BASELINE_PARTICIPANT_OR_SCOPE_INVALID", "baseline is not an Entity full-version record");
    }
    if (!Objects.equals(current.getScopeValue(), Long.toString(command.versionId()))) {
      throw rejected("BASELINE_VERSION_MISMATCH", "baseline belongs to another version");
    }
    if (!Objects.equals(current.getDigestSchemaVersion(), SOURCE_SCHEMA_VERSION)) {
      throw rejected(
          "SOURCE_SCHEMA_UNSUPPORTED", "migration source must be an Entity schema-v1 row");
    }
  }

  private void assertSourceMatchesCommand(
      RecordedParticipantDigest current, ExpectedSource expected) {
    if (!Objects.equals(current.getId(), expected.baselineId())
        || !Objects.equals(current.getTenantId(), expected.tenantId())
        || current.getPublishType() != expected.publishType()
        || current.getParticipantKey() != expected.participantKey()
        || !Objects.equals(current.getBaseVersionId(), expected.baseVersionId())
        || !Objects.equals(current.getScopeValue(), expected.scopeValue())
        || !Objects.equals(current.getAppliedCommitId(), expected.appliedCommitId())
        || !Objects.equals(current.getContentDigest(), expected.contentDigest())
        || !Objects.equals(current.getDigestSchemaVersion(), expected.digestSchemaVersion())
        || !Objects.equals(
            current.getRecordedFromPublishWorkflowId(), expected.recordedFromPublishWorkflowId())
        || !Objects.equals(current.getRecordedAt(), expected.recordedAt())
        || !Objects.equals(
            current.getLastVerifiedPublishWorkflowId(), expected.lastVerifiedPublishWorkflowId())
        || !Objects.equals(current.getLastVerifiedAt(), expected.lastVerifiedAt())) {
      throw rejected(
          "SOURCE_BASELINE_CHANGED",
          "baseline tuple or provenance changed after the expected source was captured");
    }
  }

  private void validateObservedDigest(
      PublishParticipantDigestDto observed, MigrationCommand command) {
    if (observed == null || !observed.succeeded()) {
      throw rejected(
          "ENTITY_DIGEST_UNAVAILABLE",
          observed == null ? "Entity returned no digest response" : observed.errorCode());
    }
    if (!PublishParticipantKey.ENTITY_MANAGEMENT.name().equals(observed.participantKey())
        || !Long.toString(command.versionId()).equals(observed.scopeValue())
        || observed.baseVersionId() != null) {
      throw rejected(
          "ENTITY_DIGEST_SCOPE_MISMATCH", "Entity v2 evidence does not match the exact scope");
    }
    if (!Objects.equals(observed.digestSchemaVersion(), TARGET_SCHEMA_VERSION)) {
      throw rejected("ENTITY_DIGEST_SCHEMA_UNSUPPORTED", "Entity did not return digest schema 2");
    }
    if (observed.contentDigest() == null || observed.contentDigest().isBlank()) {
      throw rejected("ENTITY_DIGEST_INCOMPLETE", "Entity returned a blank v2 content digest");
    }
    if (observed.contentDigest().getBytes(StandardCharsets.UTF_8).length > 128) {
      throw rejected("ENTITY_DIGEST_INCOMPLETE", "Entity v2 content digest exceeds storage bounds");
    }
    if (observed.appliedCommitId() == null
        || observed.appliedCommitId().isBlank()
        || !Objects.equals(
            observed.appliedCommitId(), command.expectedSource().appliedCommitId())) {
      throw rejected(
          "ENTITY_APPLIED_COMMIT_MISMATCH",
          "Entity v2 evidence does not preserve the expected applied-commit identity");
    }
  }

  private RecordedParticipantDigest replacementFor(
      RecordedParticipantDigest current,
      PublishParticipantDigestDto observed,
      String operationId,
      LocalDateTime migrationTime) {
    RecordedParticipantDigest replacement = new RecordedParticipantDigest();
    replacement.setId(current.getId());
    replacement.setTenantId(current.getTenantId());
    replacement.setPublishType(current.getPublishType());
    replacement.setParticipantKey(current.getParticipantKey());
    replacement.setScopeValue(current.getScopeValue());
    replacement.setBaseVersionId(null);
    replacement.setAppliedCommitId(observed.appliedCommitId());
    replacement.setContentDigest(observed.contentDigest());
    replacement.setDigestSchemaVersion(TARGET_SCHEMA_VERSION);
    replacement.setRecordedFromPublishWorkflowId(operationId);
    replacement.setRecordedAt(migrationTime);
    replacement.setLastVerifiedPublishWorkflowId(operationId);
    replacement.setLastVerifiedAt(migrationTime);
    return replacement;
  }

  private EntityDigestBaselineMigrationAudit auditFor(
      RecordedParticipantDigest source,
      PublishParticipantDigestDto observed,
      MigrationCommand command,
      LocalDateTime migrationTime) {
    return new EntityDigestBaselineMigrationAudit(
        null,
        command.operationId(),
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
        source.getTenantId(),
        source.getPublishType(),
        source.getParticipantKey(),
        null,
        source.getScopeValue(),
        observed.appliedCommitId(),
        observed.contentDigest(),
        observed.digestSchemaVersion(),
        command.actorIdentity(),
        command.workloadIdentity(),
        EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME,
        migrationTime);
  }

  private void assertAuditSourceMatchesCommand(
      EntityDigestBaselineMigrationAudit audit, MigrationCommand command) {
    ExpectedSource expected = command.expectedSource();
    if (!Objects.equals(audit.operationId(), command.operationId())
        || audit.id() == null
        || audit.id() < 1
        || !Objects.equals(audit.recordedParticipantDigestId(), command.baselineId())
        || !Objects.equals(audit.sourceTenantId(), command.tenantId())
        || audit.sourcePublishType() != expected.publishType()
        || audit.sourceParticipantKey() != expected.participantKey()
        || !Objects.equals(audit.sourceBaseVersionId(), expected.baseVersionId())
        || !Objects.equals(audit.sourceScopeValue(), Long.toString(command.versionId()))
        || !Objects.equals(audit.sourceAppliedCommitId(), expected.appliedCommitId())
        || !Objects.equals(audit.sourceContentDigest(), expected.contentDigest())
        || !Objects.equals(audit.sourceDigestSchemaVersion(), expected.digestSchemaVersion())
        || !Objects.equals(
            audit.sourceRecordedFromPublishWorkflowId(), expected.recordedFromPublishWorkflowId())
        || !Objects.equals(audit.sourceRecordedAt(), expected.recordedAt())
        || !Objects.equals(
            audit.sourceLastVerifiedPublishWorkflowId(), expected.lastVerifiedPublishWorkflowId())
        || !Objects.equals(audit.sourceLastVerifiedAt(), expected.lastVerifiedAt())
        || !Objects.equals(audit.actorIdentity(), command.actorIdentity())
        || !Objects.equals(audit.workloadIdentity(), command.workloadIdentity())
        || !Objects.equals(audit.observedTenantId(), command.tenantId())
        || audit.observedPublishType() != PublishType.FULL_VERSION
        || audit.observedParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || audit.observedBaseVersionId() != null
        || !Objects.equals(audit.observedScopeValue(), Long.toString(command.versionId()))
        || !Objects.equals(audit.observedAppliedCommitId(), expected.appliedCommitId())
        || !Objects.equals(audit.observedDigestSchemaVersion(), TARGET_SCHEMA_VERSION)
        || audit.observedContentDigest() == null
        || audit.observedContentDigest().isBlank()
        || !EntityDigestBaselineMigrationAudit.COMMITTED_OUTCOME.equals(audit.outcome())) {
      throw rejected(
          "OPERATION_ID_CONFLICT", "operation id is already bound to a different source command");
    }
  }

  private MigrationResult readBackCommittedResult(
      MigrationCommand command,
      EntityDigestBaselineMigrationAudit expectedAudit,
      MigrationDisposition disposition) {
    EntityDigestBaselineMigrationAudit actualAudit =
        auditRepository
            .findByOperationId(command.operationId())
            .orElseThrow(
                () ->
                    rejected("AUDIT_READBACK_MISSING", "committed migration audit was not found"));
    assertAuditSourceMatchesCommand(actualAudit, command);
    if (!sameAuditTarget(actualAudit, expectedAudit)) {
      throw rejected("AUDIT_READBACK_MISMATCH", "audit target differs from the attempted result");
    }
    RecordedParticipantDigest actualBaseline =
        baselineRepository
            .findById(command.baselineId())
            .orElseThrow(
                () -> rejected("BASELINE_READBACK_MISSING", "migrated baseline was not found"));
    if (!Objects.equals(actualBaseline.getId(), actualAudit.recordedParticipantDigestId())
        || !Objects.equals(actualBaseline.getTenantId(), actualAudit.observedTenantId())
        || actualBaseline.getPublishType() != actualAudit.observedPublishType()
        || actualBaseline.getParticipantKey() != actualAudit.observedParticipantKey()
        || !Objects.equals(actualBaseline.getBaseVersionId(), actualAudit.observedBaseVersionId())
        || !Objects.equals(actualBaseline.getScopeValue(), actualAudit.observedScopeValue())
        || !Objects.equals(
            actualBaseline.getAppliedCommitId(), actualAudit.observedAppliedCommitId())
        || !Objects.equals(actualBaseline.getContentDigest(), actualAudit.observedContentDigest())
        || !Objects.equals(
            actualBaseline.getDigestSchemaVersion(), actualAudit.observedDigestSchemaVersion())
        || !Objects.equals(actualBaseline.getRecordedFromPublishWorkflowId(), command.operationId())
        || !Objects.equals(actualBaseline.getRecordedAt(), actualAudit.committedAt())
        || !Objects.equals(actualBaseline.getLastVerifiedPublishWorkflowId(), command.operationId())
        || !Objects.equals(actualBaseline.getLastVerifiedAt(), actualAudit.committedAt())) {
      throw rejected(
          "BASELINE_READBACK_MISMATCH", "persisted baseline does not exactly match its audit");
    }
    return new MigrationResult(
        command.operationId(),
        actualAudit.id(),
        actualBaseline.getId(),
        command.tenantId(),
        command.versionId(),
        actualBaseline.getAppliedCommitId(),
        actualBaseline.getContentDigest(),
        actualBaseline.getDigestSchemaVersion(),
        disposition);
  }

  private boolean sameAuditTarget(
      EntityDigestBaselineMigrationAudit actual, EntityDigestBaselineMigrationAudit expected) {
    return (expected.id() == null || Objects.equals(actual.id(), expected.id()))
        && Objects.equals(actual.operationId(), expected.operationId())
        && Objects.equals(
            actual.recordedParticipantDigestId(), expected.recordedParticipantDigestId())
        && Objects.equals(actual.observedTenantId(), expected.observedTenantId())
        && actual.observedPublishType() == expected.observedPublishType()
        && actual.observedParticipantKey() == expected.observedParticipantKey()
        && Objects.equals(actual.observedBaseVersionId(), expected.observedBaseVersionId())
        && Objects.equals(actual.observedScopeValue(), expected.observedScopeValue())
        && Objects.equals(actual.observedAppliedCommitId(), expected.observedAppliedCommitId())
        && Objects.equals(actual.observedContentDigest(), expected.observedContentDigest())
        && Objects.equals(
            actual.observedDigestSchemaVersion(), expected.observedDigestSchemaVersion())
        && Objects.equals(actual.actorIdentity(), expected.actorIdentity())
        && Objects.equals(actual.workloadIdentity(), expected.workloadIdentity())
        && Objects.equals(actual.outcome(), expected.outcome())
        && Objects.equals(actual.committedAt(), expected.committedAt());
  }

  private BaselineSummary toV1Summary(RecordedParticipantDigest row) {
    if (row.getDigestSchemaVersion() == null
        || row.getDigestSchemaVersion() != SOURCE_SCHEMA_VERSION
        || row.getPublishType() != PublishType.FULL_VERSION
        || row.getParticipantKey() != PublishParticipantKey.ENTITY_MANAGEMENT
        || row.getBaseVersionId() != null) {
      throw new IllegalStateException("v1 batch query returned a non-Entity full-version row");
    }
    return toSummary(row);
  }

  private BaselineSummary toSummary(RecordedParticipantDigest row) {
    if (row.getId() == null || row.getId() < 1) {
      throw new IllegalStateException("baseline query returned a row without a stable id");
    }
    long versionId = parsePositiveVersionId(row.getId(), row.getScopeValue());
    return new BaselineSummary(
        row.getId(),
        row.getTenantId(),
        versionId,
        row.getAppliedCommitId(),
        row.getContentDigest(),
        row.getDigestSchemaVersion(),
        row.getRecordedFromPublishWorkflowId(),
        row.getRecordedAt(),
        row.getLastVerifiedPublishWorkflowId(),
        row.getLastVerifiedAt());
  }

  private long parsePositiveVersionId(long baselineId, String scopeValue) {
    try {
      long id = Long.parseLong(scopeValue);
      if (id < 1 || !Long.toString(id).equals(scopeValue)) {
        throw new NumberFormatException("non-canonical positive id");
      }
      return id;
    } catch (RuntimeException invalidScope) {
      throw new IllegalStateException(
          "Entity full-version baseline "
              + baselineId
              + " has a non-canonical positive version scope: "
              + scopeValue,
          invalidScope);
    }
  }

  private String stablePublicationRequestId(String operationId) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(operationId.getBytes(StandardCharsets.UTF_8));
      return PUBLICATION_REQUEST_PREFIX + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private void requireText(String value, String name, int maxUtf8Bytes) {
    if (value == null
        || value.isBlank()
        || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
      throw new IllegalArgumentException(name + " must be non-blank and within its storage bound");
    }
  }

  private void requirePositive(long value, String name) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private MigrationRejectedException rejected(String code, String message) {
    return new MigrationRejectedException(code, message, null);
  }

  private MigrationRejectedException rejected(String code, String message, Throwable cause) {
    return new MigrationRejectedException(code, message, cause);
  }

  public enum ScopeStatus {
    V1_REQUIRES_MIGRATION,
    V2_ALREADY_RECORDED,
    NO_RECORDED_BASELINE,
    AMBIGUOUS_BASELINE,
    UNSUPPORTED_SCHEMA
  }

  public enum MigrationDisposition {
    APPLIED,
    EXACT_RETRY,
    RECOVERED_AFTER_WRITE_ERROR
  }

  public record ExpectedSource(
      Long baselineId,
      String tenantId,
      PublishType publishType,
      PublishParticipantKey participantKey,
      Long baseVersionId,
      String scopeValue,
      String appliedCommitId,
      String contentDigest,
      Integer digestSchemaVersion,
      String recordedFromPublishWorkflowId,
      LocalDateTime recordedAt,
      String lastVerifiedPublishWorkflowId,
      LocalDateTime lastVerifiedAt) {}

  public record MigrationCommand(
      String operationId,
      long baselineId,
      String tenantId,
      long versionId,
      ExpectedSource expectedSource,
      String actorIdentity,
      String workloadIdentity) {}

  public record BaselineSummary(
      long baselineId,
      String tenantId,
      long versionId,
      String appliedCommitId,
      String contentDigest,
      Integer digestSchemaVersion,
      String recordedFromPublishWorkflowId,
      LocalDateTime recordedAt,
      String lastVerifiedPublishWorkflowId,
      LocalDateTime lastVerifiedAt) {}

  public record EntityV1Batch(
      long scannedAfterBaselineId,
      int limit,
      List<BaselineSummary> baselines,
      Long nextAfterBaselineId) {
    public EntityV1Batch {
      baselines = List.copyOf(baselines);
    }

    public boolean isEmptyAtCursor() {
      return baselines.isEmpty();
    }
  }

  public record ScopePreflight(
      String tenantId, long versionId, ScopeStatus status, List<BaselineSummary> baselines) {
    public ScopePreflight {
      baselines = List.copyOf(baselines);
    }
  }

  public record MigrationResult(
      String operationId,
      Long auditId,
      Long baselineId,
      String tenantId,
      long versionId,
      String appliedCommitId,
      String contentDigest,
      Integer digestSchemaVersion,
      MigrationDisposition disposition) {}

  public static final class MigrationRejectedException extends IllegalStateException {
    private final String failureCode;

    private MigrationRejectedException(String failureCode, String message, Throwable cause) {
      super(message, cause);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}
