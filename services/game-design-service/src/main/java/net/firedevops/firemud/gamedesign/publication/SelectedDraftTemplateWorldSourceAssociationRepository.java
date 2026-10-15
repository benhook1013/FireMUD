package net.firedevops.firemud.gamedesign.publication;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociation.Association;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociation.SourceRead;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadAuthoredWorldSourceIntakeByIdResponse;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable per-template relation to the exact selected publication's original World source. */
public final class SelectedDraftTemplateWorldSourceAssociationRepository {
  private static final String TABLE = "game_design_selected_template_world_source_association";
  private final DSLContext dsl;

  public SelectedDraftTemplateWorldSourceAssociationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  /**
   * Persists first-time associations in the publication reservation transaction, or exact-reads
   * them for an already retained operation. Replay never allocates a row or replaces its original
   * read identity.
   */
  public List<Association> retainOrRead(
      GameDesignPublicationOperation operation,
      TemplateConfigSourceSnapshot.Capture capture,
      SourceRead currentSourceRead,
      boolean allowCreate) {
    requireWritableReadCommittedTransaction();
    Objects.requireNonNull(currentSourceRead, "currentSourceRead");
    List<Association> expected = associations(operation, capture, currentSourceRead);
    List<Record> rows = rows(operation, capture);
    List<Boolean> inserted = new ArrayList<>(expected.size());
    if (allowCreate) {
      if (!rows.isEmpty()) {
        throw new IllegalStateException(
            "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_PREEXISTS_WITHOUT_OPERATION");
      }
      for (Association association : expected) inserted.add(insert(association));
    }
    List<Association> retained = readExact(operation, capture);
    if (retained.size() != expected.size()) {
      throw new IllegalStateException("SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_INCOMPLETE");
    }
    for (int index = 0; index < retained.size(); index++) {
      Association old = retained.get(index);
      Association current = expected.get(index);
      // A competing exact first reservation may have committed the same durable operation from a
      // separately authenticated by-ID read. Keep its original read correlation; only that
      // correlation/echo may differ, never the receipt, selected capture, or source identity.
      boolean matches =
          allowCreate && inserted.get(index)
              ? old.sameStoredValue(current)
              : sameOriginalSource(old, current, currentSourceRead);
      if (!matches) {
        throw new IllegalStateException(
            allowCreate
                ? "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_READBACK_CONFLICT"
                : "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_CONFLICT");
      }
    }
    return retained;
  }

  /** Exact read-only completeness proof for an already retained source-backed operation. */
  public List<Association> readExact(
      GameDesignPublicationOperation operation, TemplateConfigSourceSnapshot.Capture capture) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capture, "capture");
    if (!Arrays.equals(operation.canonicalBytes(), capture.operation().canonicalBytes())) {
      throw new IllegalArgumentException("Association read requires its exact captured operation");
    }
    List<Record> rows = rows(operation, capture);
    List<TemplateConfigSource.Entry> entries = capture.snapshot().entries();
    if (rows.size() != entries.size()) {
      throw new IllegalStateException("SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_INCOMPLETE");
    }
    List<Association> result = new ArrayList<>(entries.size());
    for (TemplateConfigSource.Entry entry : entries) {
      long templateId = parseTemplateId(entry.templateId());
      Record row =
          rows.stream()
              .filter(value -> Objects.equals(value.get("template_id", Long.class), templateId))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_ENTRY_MISSING"));
      Association retained = decode(operation, capture, entry, row);
      requireRowMatches(retained, row);
      result.add(retained);
    }
    return List.copyOf(result);
  }

  private List<Association> associations(
      GameDesignPublicationOperation operation,
      TemplateConfigSourceSnapshot.Capture capture,
      SourceRead sourceRead) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capture, "capture");
    return capture.snapshot().entries().stream()
        .map(
            entry ->
                SelectedDraftTemplateWorldSourceAssociation.create(
                    operation, capture, entry, sourceRead))
        .toList();
  }

  private boolean insert(Association value) {
    return dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (canonical_tenant_id, canonical_version_id, commit_id, template_id, publish_workflow_id, "
                + "target_namespace, intake_request_id, world_operation_id, source_operation_id, world_slug, "
                + "source_evidence_digest, operation_bytes, capture_bytes, template_snapshot_bytes, template_entry_bytes, "
                + "world_read_request_bytes, world_read_response_bytes, association_bytes, association_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            value.canonicalTenantId(),
            value.canonicalVersionId(),
            value.selectedCommitId(),
            value.templateId(),
            value.publishWorkflowId(),
            value.targetNamespace(),
            value.intakeRequestId(),
            value.worldOperationId(),
            value.sourceOperationId(),
            value.worldSlug(),
            value.sourceEvidenceDigest(),
            value.operationBytes(),
            value.captureBytes(),
            value.templateSnapshotBytes(),
            value.templateEntryBytes(),
            value.worldReadRequestBytes(),
            value.worldReadResponseBytes(),
            value.canonicalBytes(),
            value.digest())
        == 1;
  }

  private List<Record> rows(
      GameDesignPublicationOperation operation, TemplateConfigSourceSnapshot.Capture capture) {
    var selection = operation.account().input().selection();
    return dsl.fetch(
        "SELECT * FROM "
            + TABLE
            + " WHERE canonical_tenant_id = ? AND canonical_version_id = ? AND commit_id = ? ORDER BY template_id",
        selection.target().canonicalTenantId(),
        selection.target().canonicalVersionId(),
        capture.snapshot().binding().commitId());
  }

  private Association decode(
      GameDesignPublicationOperation operation,
      TemplateConfigSourceSnapshot.Capture capture,
      TemplateConfigSource.Entry entry,
      Record row) {
    try {
      byte[] requestBytes = row.get("world_read_request_bytes", byte[].class);
      byte[] responseBytes = row.get("world_read_response_bytes", byte[].class);
      var requestMessage = ReadAuthoredWorldSourceIntakeByIdRequest.parseFrom(requestBytes);
      var request = WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdRequest(requestMessage);
      var responseMessage = ReadAuthoredWorldSourceIntakeByIdResponse.parseFrom(responseBytes);
      var receipt =
          WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdResponse(request, responseMessage);
      var sourceRead = new SourceRead(request, receipt);
      if (!Arrays.equals(requestBytes, sourceRead.requestBytes())
          || !Arrays.equals(responseBytes, sourceRead.responseBytes())) {
        throw new IllegalStateException("noncanonical retained World source read");
      }
      return SelectedDraftTemplateWorldSourceAssociation.create(
          operation, capture, entry, sourceRead);
    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
      throw new IllegalStateException(
          "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_EVIDENCE_CORRUPT", failure);
    }
  }

  private static void requireRowMatches(Association value, Record row) {
    if (!value.canonicalTenantId().equals(row.get("canonical_tenant_id", java.util.UUID.class))
        || !value.canonicalVersionId().equals(row.get("canonical_version_id", java.util.UUID.class))
        || !value.selectedCommitId().equals(row.get("commit_id", java.util.UUID.class))
        || value.templateId() != row.get("template_id", Long.class)
        || !value.publishWorkflowId().equals(row.get("publish_workflow_id", String.class))
        || !value.targetNamespace().equals(row.get("target_namespace", String.class))
        || !value.intakeRequestId().equals(row.get("intake_request_id", java.util.UUID.class))
        || !value.worldOperationId().equals(row.get("world_operation_id", java.util.UUID.class))
        || !value.sourceOperationId().equals(row.get("source_operation_id", java.util.UUID.class))
        || !value.worldSlug().equals(row.get("world_slug", String.class))
        || !value.sourceEvidenceDigest().equals(row.get("source_evidence_digest", String.class))
        || !Arrays.equals(value.operationBytes(), row.get("operation_bytes", byte[].class))
        || !Arrays.equals(value.captureBytes(), row.get("capture_bytes", byte[].class))
        || !Arrays.equals(
            value.templateSnapshotBytes(), row.get("template_snapshot_bytes", byte[].class))
        || !Arrays.equals(value.templateEntryBytes(), row.get("template_entry_bytes", byte[].class))
        || !Arrays.equals(
            value.worldReadRequestBytes(), row.get("world_read_request_bytes", byte[].class))
        || !Arrays.equals(
            value.worldReadResponseBytes(), row.get("world_read_response_bytes", byte[].class))
        || !Arrays.equals(value.canonicalBytes(), row.get("association_bytes", byte[].class))
        || !value.digest().equals(row.get("association_digest", String.class))) {
      throw new IllegalStateException(
          "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_READBACK_CONFLICT");
    }
  }

  private static boolean sameOriginalSource(
      Association retained, Association candidate, SourceRead currentRead) {
    return retained.canonicalTenantId().equals(candidate.canonicalTenantId())
        && retained.canonicalVersionId().equals(candidate.canonicalVersionId())
        && retained.selectedCommitId().equals(candidate.selectedCommitId())
        && retained.templateId() == candidate.templateId()
        && retained.publishWorkflowId().equals(candidate.publishWorkflowId())
        && retained.targetNamespace().equals(candidate.targetNamespace())
        && retained.intakeRequestId().equals(candidate.intakeRequestId())
        && retained.worldOperationId().equals(candidate.worldOperationId())
        && retained.sourceOperationId().equals(candidate.sourceOperationId())
        && retained.worldSlug().equals(candidate.worldSlug())
        && retained.sourceEvidenceDigest().equals(candidate.sourceEvidenceDigest())
        && Arrays.equals(retained.operationBytes(), candidate.operationBytes())
        && Arrays.equals(retained.captureBytes(), candidate.captureBytes())
        && Arrays.equals(retained.templateSnapshotBytes(), candidate.templateSnapshotBytes())
        && Arrays.equals(retained.templateEntryBytes(), candidate.templateEntryBytes())
        && currentRead.request().targetNamespace().equals(retained.targetNamespace())
        && currentRead.request().intakeRequestId().equals(retained.intakeRequestId())
        && currentRead.request().canonicalTenantId().equals(retained.canonicalTenantId())
        && currentRead.receipt().equals(retainedReceipt(retained));
  }

  private static WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt retainedReceipt(
      Association association) {
    try {
      var request =
          WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdRequest(
              ReadAuthoredWorldSourceIntakeByIdRequest.parseFrom(
                  association.worldReadRequestBytes()));
      return WorldAuthoredSourceIntakeGrpcCodec.fromReadByIdResponse(
          request,
          ReadAuthoredWorldSourceIntakeByIdResponse.parseFrom(
              association.worldReadResponseBytes()));
    } catch (IOException | IllegalArgumentException invalid) {
      throw new IllegalStateException(
          "SELECTED_TEMPLATE_WORLD_SOURCE_ASSOCIATION_EVIDENCE_CORRUPT", invalid);
    }
  }

  private static long parseTemplateId(String templateId) {
    try {
      return Long.parseLong(templateId);
    } catch (NumberFormatException invalid) {
      throw new IllegalStateException("TEMPLATE_CONFIG_CAPTURE_ID_INVALID", invalid);
    }
  }

  private static void requireWritableReadCommittedTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        || !Integer.valueOf(TransactionDefinition.ISOLATION_READ_COMMITTED)
            .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
      throw new IllegalStateException(
          "Template/world source association requires writable READ_COMMITTED transaction");
    }
  }
}
