package net.firedevops.firemud.gamedesign.publication;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.CommandSource;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.ByIdReadRequest;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec.PublicReceipt;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;

/**
 * Immutable relation between one captured selected template and the exact World-authored source
 * returned for the original publication freeze. Structured evidence is not producer authority;
 * production admission obtains it from authenticated World and owner-local reads.
 */
public final class SelectedDraftTemplateWorldSourceAssociation {
  private static final String SCHEMA = "game-design-selected-template-world-source-association/v1";

  private SelectedDraftTemplateWorldSourceAssociation() {}

  /**
   * Exact by-ID request and complete public World response, including the independent read echo.
   */
  public record SourceRead(ByIdReadRequest request, PublicReceipt receipt) {
    public SourceRead {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(receipt, "receipt");
      WorldAuthoredSourceIntakeGrpcCodec.toReadByIdResponse(request, receipt);
    }

    public byte[] requestBytes() {
      return WorldAuthoredSourceIntakeGrpcCodec.toReadByIdRequest(request).toByteArray();
    }

    public byte[] responseBytes() {
      return WorldAuthoredSourceIntakeGrpcCodec.toReadByIdResponse(request, receipt).toByteArray();
    }
  }

  /** Canonical frozen row to retain; the public World response contains no World-private keys. */
  public static final class Association {
    private final UUID canonicalTenantId;
    private final UUID canonicalVersionId;
    private final UUID selectedCommitId;
    private final long templateId;
    private final String publishWorkflowId;
    private final String targetNamespace;
    private final UUID intakeRequestId;
    private final UUID worldOperationId;
    private final UUID sourceOperationId;
    private final String worldSlug;
    private final String sourceEvidenceDigest;
    private final byte[] operationBytes;
    private final byte[] captureBytes;
    private final byte[] templateSnapshotBytes;
    private final byte[] templateEntryBytes;
    private final byte[] worldReadRequestBytes;
    private final byte[] worldReadResponseBytes;
    private final byte[] canonicalBytes;
    private final String digest;

    private Association(
        UUID canonicalTenantId,
        UUID canonicalVersionId,
        UUID selectedCommitId,
        long templateId,
        String publishWorkflowId,
        String targetNamespace,
        UUID intakeRequestId,
        UUID worldOperationId,
        UUID sourceOperationId,
        String worldSlug,
        String sourceEvidenceDigest,
        byte[] operationBytes,
        byte[] captureBytes,
        byte[] templateSnapshotBytes,
        byte[] templateEntryBytes,
        byte[] worldReadRequestBytes,
        byte[] worldReadResponseBytes) {
      this.canonicalTenantId = Objects.requireNonNull(canonicalTenantId);
      this.canonicalVersionId = Objects.requireNonNull(canonicalVersionId);
      this.selectedCommitId = Objects.requireNonNull(selectedCommitId);
      if (templateId <= 0)
        throw new IllegalArgumentException("Positive template identity required");
      this.templateId = templateId;
      this.publishWorkflowId = Objects.requireNonNull(publishWorkflowId);
      this.targetNamespace = Objects.requireNonNull(targetNamespace);
      this.intakeRequestId = Objects.requireNonNull(intakeRequestId);
      this.worldOperationId = Objects.requireNonNull(worldOperationId);
      this.sourceOperationId = Objects.requireNonNull(sourceOperationId);
      this.worldSlug = Objects.requireNonNull(worldSlug);
      this.sourceEvidenceDigest = Objects.requireNonNull(sourceEvidenceDigest);
      this.operationBytes = copy(operationBytes);
      this.captureBytes = copy(captureBytes);
      this.templateSnapshotBytes = copy(templateSnapshotBytes);
      this.templateEntryBytes = copy(templateEntryBytes);
      this.worldReadRequestBytes = copy(worldReadRequestBytes);
      this.worldReadResponseBytes = copy(worldReadResponseBytes);
      this.canonicalBytes = buildCanonicalBytes();
      this.digest = CommandSource.sha256(this.canonicalBytes);
    }

    public UUID canonicalTenantId() {
      return canonicalTenantId;
    }

    public UUID canonicalVersionId() {
      return canonicalVersionId;
    }

    public UUID selectedCommitId() {
      return selectedCommitId;
    }

    public long templateId() {
      return templateId;
    }

    public String publishWorkflowId() {
      return publishWorkflowId;
    }

    public String targetNamespace() {
      return targetNamespace;
    }

    public UUID intakeRequestId() {
      return intakeRequestId;
    }

    public UUID worldOperationId() {
      return worldOperationId;
    }

    public UUID sourceOperationId() {
      return sourceOperationId;
    }

    public String worldSlug() {
      return worldSlug;
    }

    public String sourceEvidenceDigest() {
      return sourceEvidenceDigest;
    }

    public byte[] operationBytes() {
      return copy(operationBytes);
    }

    public byte[] captureBytes() {
      return copy(captureBytes);
    }

    public byte[] templateSnapshotBytes() {
      return copy(templateSnapshotBytes);
    }

    public byte[] templateEntryBytes() {
      return copy(templateEntryBytes);
    }

    public byte[] worldReadRequestBytes() {
      return copy(worldReadRequestBytes);
    }

    public byte[] worldReadResponseBytes() {
      return copy(worldReadResponseBytes);
    }

    public byte[] canonicalBytes() {
      return copy(canonicalBytes);
    }

    public String digest() {
      return digest;
    }

    public boolean sameStoredValue(Association other) {
      return other != null
          && canonicalTenantId.equals(other.canonicalTenantId)
          && canonicalVersionId.equals(other.canonicalVersionId)
          && selectedCommitId.equals(other.selectedCommitId)
          && templateId == other.templateId
          && publishWorkflowId.equals(other.publishWorkflowId)
          && targetNamespace.equals(other.targetNamespace)
          && intakeRequestId.equals(other.intakeRequestId)
          && worldOperationId.equals(other.worldOperationId)
          && sourceOperationId.equals(other.sourceOperationId)
          && worldSlug.equals(other.worldSlug)
          && sourceEvidenceDigest.equals(other.sourceEvidenceDigest)
          && Arrays.equals(operationBytes, other.operationBytes)
          && Arrays.equals(captureBytes, other.captureBytes)
          && Arrays.equals(templateSnapshotBytes, other.templateSnapshotBytes)
          && Arrays.equals(templateEntryBytes, other.templateEntryBytes)
          && Arrays.equals(worldReadRequestBytes, other.worldReadRequestBytes)
          && Arrays.equals(worldReadResponseBytes, other.worldReadResponseBytes)
          && Arrays.equals(canonicalBytes, other.canonicalBytes)
          && digest.equals(other.digest);
    }

    private byte[] buildCanonicalBytes() {
      var output = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(output, SCHEMA);
      DraftAuthorizationFenceBinding.frame(output, canonicalTenantId.toString());
      DraftAuthorizationFenceBinding.frame(output, canonicalVersionId.toString());
      DraftAuthorizationFenceBinding.frame(output, selectedCommitId.toString());
      DraftAuthorizationFenceBinding.frame(output, Long.toString(templateId));
      DraftAuthorizationFenceBinding.frame(output, publishWorkflowId);
      DraftAuthorizationFenceBinding.frame(output, targetNamespace);
      DraftAuthorizationFenceBinding.frame(output, intakeRequestId.toString());
      DraftAuthorizationFenceBinding.frame(output, worldOperationId.toString());
      DraftAuthorizationFenceBinding.frame(output, sourceOperationId.toString());
      DraftAuthorizationFenceBinding.frame(output, worldSlug);
      DraftAuthorizationFenceBinding.frame(output, sourceEvidenceDigest);
      DraftAuthorizationFenceBinding.frame(output, operationBytes);
      DraftAuthorizationFenceBinding.frame(output, captureBytes);
      DraftAuthorizationFenceBinding.frame(output, templateSnapshotBytes);
      DraftAuthorizationFenceBinding.frame(output, templateEntryBytes);
      DraftAuthorizationFenceBinding.frame(output, worldReadRequestBytes);
      DraftAuthorizationFenceBinding.frame(output, worldReadResponseBytes);
      return output.toByteArray();
    }
  }

  public static Association create(
      GameDesignPublicationOperation operation,
      TemplateConfigSourceSnapshot.Capture capture,
      TemplateConfigSource.Entry entry,
      SourceRead worldSourceRead) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(capture, "capture");
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(worldSourceRead, "worldSourceRead");
    if (!Arrays.equals(operation.canonicalBytes(), capture.operation().canonicalBytes())
        || !capture
            .snapshot()
            .binding()
            .equals(operation.account().input().selection().selectedCommit())
        || !capture.snapshot().entries().contains(entry)) {
      throw new IllegalArgumentException("Association requires the exact captured template entry");
    }
    var target = operation.account().input().selection().target();
    WorldPublishedStartLocationEvidence.Request selectedWorld = operation.world().request();
    ByIdReadRequest request = worldSourceRead.request();
    PublicReceipt receipt = worldSourceRead.receipt();
    if (!target.canonicalTenantId().equals(selectedWorld.canonicalTenantId())
        || receipt.source().sourceGameRowId() != target.sourceGameRowId()
        || !receipt.source().sourceGameTenantKey().equals(target.sourceGameTenantKey())
        || !receipt.source().provenanceKind().equals(target.sourceProvenanceKind())
        || !selectedWorld.targetNamespace().equals(request.targetNamespace())
        || !selectedWorld.intakeRequestId().equals(request.intakeRequestId())
        || !selectedWorld.canonicalTenantId().equals(request.canonicalTenantId())
        || !selectedWorld.targetNamespace().equals(receipt.targetNamespace())
        || !selectedWorld.canonicalTenantId().equals(receipt.canonicalTenantId())
        || !selectedWorld.intakeRequestId().equals(receipt.intakeRequestId())) {
      throw new IllegalArgumentException(
          "World source receipt differs from the original selected publication freeze");
    }
    long templateId;
    try {
      templateId = Long.parseLong(entry.templateId());
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(
          "Captured template identity is not a stored row ID", invalid);
    }
    return new Association(
        target.canonicalTenantId(),
        target.canonicalVersionId(),
        capture.snapshot().binding().commitId(),
        templateId,
        operation.workflowId(),
        receipt.targetNamespace(),
        receipt.intakeRequestId(),
        receipt.operationId(),
        receipt.sourceOperationId(),
        receipt.worldSlug(),
        receipt.sourceEvidenceDigest(),
        operation.canonicalBytes(),
        capture.canonicalBytes(),
        capture.snapshot().canonicalBytes(),
        CommandSource.canonical(entry.object()).getBytes(StandardCharsets.UTF_8),
        worldSourceRead.requestBytes(),
        worldSourceRead.responseBytes());
  }

  private static byte[] copy(byte[] value) {
    return Objects.requireNonNull(value, "value").clone();
  }
}
