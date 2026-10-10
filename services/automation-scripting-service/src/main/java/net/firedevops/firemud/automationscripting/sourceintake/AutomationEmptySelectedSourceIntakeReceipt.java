package net.firedevops.firemud.automationscripting.sourceintake;

import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerEmptySourceInputs;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadEvidence;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryEvidence;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryRequest;

/** Immutable retained outcome for one freshly founded empty Automation source scope. */
public final class AutomationEmptySelectedSourceIntakeReceipt {
  public static final String DOMAIN = "automation-empty-selected-source-intake-receipt/v1";
  public static final int MAX_BYTES =
      SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES
          + SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES
          + SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES
          + 16 * 1024;

  private final String targetNamespace;
  private final UUID operationId;
  private final UUID fenceId;
  private final UUID intakeRequestId;
  private final UUID canonicalTenantId;
  private final UUID canonicalVersionId;
  private final UUID selectedCommitId;
  private final UUID sourceRevisionId;
  private final String sourceRevisionOrder;
  private final long localTenantKey;
  private final long localVersionKey;
  private final String requestDigest;
  private final String authorizationBindingDigest;
  private final byte[] authorizationBindingBytes;
  private final UUID worldReadRequestId;
  private final String worldReadRequestDigest;
  private final byte[] worldReadRequestBytes;
  private final String worldInventoryDigest;
  private final byte[] worldInventoryBytes;
  private final long scriptsRowCount;
  private final long eventBindingsRowCount;
  private final long patchBaseBindingsRowCount;
  private final long unqualifiedScriptsRowCount;
  private final long unqualifiedEventBindingsRowCount;
  private final long unqualifiedPatchBaseBindingsRowCount;
  private final long emptyAssociatedScriptsRowCount;
  private final long emptyAssociatedEventBindingsRowCount;
  private final long emptyAssociatedPatchBaseBindingsRowCount;
  private final long selectedScopeScriptsRowCount;
  private final long selectedScopeEventBindingsRowCount;
  private final long selectedScopePatchBaseBindingsRowCount;
  private final OffsetDateTime retainedAt;
  private final byte[] canonicalBytes;
  private final String receiptDigest;
  private final SelectedOwnerIntakeAuthorizationBinding authorizationBinding;
  private final SelectedOwnerWorldInventoryReadEvidence worldInventoryReadEvidence;

  private AutomationEmptySelectedSourceIntakeReceipt(
      String targetNamespace,
      UUID operationId,
      UUID fenceId,
      UUID intakeRequestId,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      UUID selectedCommitId,
      UUID sourceRevisionId,
      String sourceRevisionOrder,
      long localTenantKey,
      long localVersionKey,
      String requestDigest,
      String authorizationBindingDigest,
      byte[] authorizationBindingBytes,
      UUID worldReadRequestId,
      String worldReadRequestDigest,
      byte[] worldReadRequestBytes,
      String worldInventoryDigest,
      byte[] worldInventoryBytes,
      long scriptsRowCount,
      long eventBindingsRowCount,
      long patchBaseBindingsRowCount,
      long unqualifiedScriptsRowCount,
      long unqualifiedEventBindingsRowCount,
      long unqualifiedPatchBaseBindingsRowCount,
      long emptyAssociatedScriptsRowCount,
      long emptyAssociatedEventBindingsRowCount,
      long emptyAssociatedPatchBaseBindingsRowCount,
      long selectedScopeScriptsRowCount,
      long selectedScopeEventBindingsRowCount,
      long selectedScopePatchBaseBindingsRowCount,
      OffsetDateTime retainedAt) {
    this.targetNamespace = requireText(targetNamespace, "targetNamespace");
    this.operationId = requireUuid(operationId, "operationId");
    this.fenceId = requireUuid(fenceId, "fenceId");
    this.intakeRequestId = requireUuid(intakeRequestId, "intakeRequestId");
    this.canonicalTenantId = requireUuid(canonicalTenantId, "canonicalTenantId");
    this.canonicalVersionId = requireUuid(canonicalVersionId, "canonicalVersionId");
    this.selectedCommitId = requireUuid(selectedCommitId, "selectedCommitId");
    this.sourceRevisionId = requireUuid(sourceRevisionId, "sourceRevisionId");
    this.sourceRevisionOrder = requireDecimal(sourceRevisionOrder, "sourceRevisionOrder");
    if (localTenantKey <= 0L || localVersionKey <= 0L || localTenantKey == localVersionKey) {
      throw new IllegalArgumentException("Distinct positive Automation local keys are required");
    }
    this.localTenantKey = localTenantKey;
    this.localVersionKey = localVersionKey;
    this.requestDigest = requireDigest(requestDigest, "requestDigest");
    this.authorizationBindingDigest =
        requireDigest(authorizationBindingDigest, "authorizationBindingDigest");
    this.authorizationBindingBytes =
        boundedBytes(
            authorizationBindingBytes,
            SelectedOwnerIntakeAuthorizationBinding.MAX_BYTES,
            "authorizationBindingBytes");
    this.worldReadRequestId = requireUuid(worldReadRequestId, "worldReadRequestId");
    this.worldReadRequestDigest = requireDigest(worldReadRequestDigest, "worldReadRequestDigest");
    this.worldReadRequestBytes =
        boundedBytes(
            worldReadRequestBytes,
            SelectedOwnerWorldInventoryReadEvidence.MAX_WIRE_BYTES,
            "worldReadRequestBytes");
    this.worldInventoryDigest = requireDigest(worldInventoryDigest, "worldInventoryDigest");
    this.worldInventoryBytes =
        boundedBytes(
            worldInventoryBytes,
            SelectedOwnerWorldInventoryReadEvidence.MAX_PUBLIC_INVENTORY_BYTES,
            "worldInventoryBytes");
    this.scriptsRowCount = nonnegative(scriptsRowCount, "scriptsRowCount");
    this.eventBindingsRowCount = nonnegative(eventBindingsRowCount, "eventBindingsRowCount");
    this.patchBaseBindingsRowCount =
        nonnegative(patchBaseBindingsRowCount, "patchBaseBindingsRowCount");
    this.unqualifiedScriptsRowCount =
        nonnegative(unqualifiedScriptsRowCount, "unqualifiedScriptsRowCount");
    this.unqualifiedEventBindingsRowCount =
        nonnegative(unqualifiedEventBindingsRowCount, "unqualifiedEventBindingsRowCount");
    this.unqualifiedPatchBaseBindingsRowCount =
        nonnegative(unqualifiedPatchBaseBindingsRowCount, "unqualifiedPatchBaseBindingsRowCount");
    this.emptyAssociatedScriptsRowCount =
        nonnegative(emptyAssociatedScriptsRowCount, "emptyAssociatedScriptsRowCount");
    this.emptyAssociatedEventBindingsRowCount =
        nonnegative(emptyAssociatedEventBindingsRowCount, "emptyAssociatedEventBindingsRowCount");
    this.emptyAssociatedPatchBaseBindingsRowCount =
        nonnegative(
            emptyAssociatedPatchBaseBindingsRowCount, "emptyAssociatedPatchBaseBindingsRowCount");
    this.selectedScopeScriptsRowCount =
        nonnegative(selectedScopeScriptsRowCount, "selectedScopeScriptsRowCount");
    this.selectedScopeEventBindingsRowCount =
        nonnegative(selectedScopeEventBindingsRowCount, "selectedScopeEventBindingsRowCount");
    this.selectedScopePatchBaseBindingsRowCount =
        nonnegative(
            selectedScopePatchBaseBindingsRowCount, "selectedScopePatchBaseBindingsRowCount");
    if (Math.addExact(unqualifiedScriptsRowCount, emptyAssociatedScriptsRowCount) != scriptsRowCount
        || Math.addExact(unqualifiedEventBindingsRowCount, emptyAssociatedEventBindingsRowCount)
            != eventBindingsRowCount
        || Math.addExact(
                unqualifiedPatchBaseBindingsRowCount, emptyAssociatedPatchBaseBindingsRowCount)
            != patchBaseBindingsRowCount) {
      throw new IllegalArgumentException("Automation source census partitions do not add up");
    }
    if (unqualifiedScriptsRowCount != 0L
        || unqualifiedEventBindingsRowCount != 0L
        || unqualifiedPatchBaseBindingsRowCount != 0L
        || emptyAssociatedScriptsRowCount != 0L
        || emptyAssociatedEventBindingsRowCount != 0L
        || emptyAssociatedPatchBaseBindingsRowCount != 0L
        || selectedScopeScriptsRowCount != 0L
        || selectedScopeEventBindingsRowCount != 0L
        || selectedScopePatchBaseBindingsRowCount != 0L) {
      throw new IllegalArgumentException(
          "Fresh empty Automation intake requires a zero unqualified and selected-scope census");
    }
    this.retainedAt = Objects.requireNonNull(retainedAt, "retainedAt");
    if (retainedAt.getOffset().getTotalSeconds() != 0) {
      throw new IllegalArgumentException("Receipt timestamp must use UTC");
    }

    this.authorizationBinding =
        SelectedOwnerIntakeAuthorizationBinding.fromStored(this.authorizationBindingBytes);
    if (!this.authorizationBinding.digest().equals(this.authorizationBindingDigest)) {
      throw new IllegalArgumentException("Retained Account authorization digest differs");
    }
    requireAuthorizationIdentity();
    this.worldInventoryReadEvidence = decodeWorldEvidence();
    this.canonicalBytes = encode();
    if (canonicalBytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Automation source receipt exceeds its size limit");
    }
    this.receiptDigest = DraftAuthorizationFenceBinding.digest(canonicalBytes);
  }

  static AutomationEmptySelectedSourceIntakeReceipt create(
      SelectedOwnerEmptySourceInputs inputs,
      long localTenantKey,
      long localVersionKey,
      String requestDigest,
      long scriptsRowCount,
      long eventBindingsRowCount,
      long patchBaseBindingsRowCount,
      long unqualifiedScriptsRowCount,
      long unqualifiedEventBindingsRowCount,
      long unqualifiedPatchBaseBindingsRowCount,
      long emptyAssociatedScriptsRowCount,
      long emptyAssociatedEventBindingsRowCount,
      long emptyAssociatedPatchBaseBindingsRowCount,
      long selectedScopeScriptsRowCount,
      long selectedScopeEventBindingsRowCount,
      long selectedScopePatchBaseBindingsRowCount,
      OffsetDateTime retainedAt) {
    Objects.requireNonNull(inputs, "validated empty-source inputs are required");
    var binding = inputs.authorizationBinding();
    var declaration = inputs.ownerSourceInventoryDeclaration();
    var worldEvidence = inputs.worldInventoryReadEvidence();
    var worldRequest = SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(worldEvidence.request());
    byte[] accountBytes = binding.canonicalBytes();
    byte[] worldRequestBytes = worldRequest.toByteArray();
    byte[] worldInventoryBytes = worldEvidence.inventory().canonicalBytes();
    return new AutomationEmptySelectedSourceIntakeReceipt(
        binding.targetNamespace(),
        binding.operationId(),
        binding.fenceId(),
        binding.intakeRequestId(),
        binding.tenantId(),
        binding.versionId(),
        binding.selected().commitId(),
        declaration.revisionId(),
        declaration.revisionOrder(),
        localTenantKey,
        localVersionKey,
        requestDigest,
        binding.digest(),
        accountBytes,
        worldEvidence.request().readRequestId(),
        DraftAuthorizationFenceBinding.digest(worldRequestBytes),
        worldRequestBytes,
        worldEvidence.inventory().digest(),
        worldInventoryBytes,
        scriptsRowCount,
        eventBindingsRowCount,
        patchBaseBindingsRowCount,
        unqualifiedScriptsRowCount,
        unqualifiedEventBindingsRowCount,
        unqualifiedPatchBaseBindingsRowCount,
        emptyAssociatedScriptsRowCount,
        emptyAssociatedEventBindingsRowCount,
        emptyAssociatedPatchBaseBindingsRowCount,
        selectedScopeScriptsRowCount,
        selectedScopeEventBindingsRowCount,
        selectedScopePatchBaseBindingsRowCount,
        retainedAt);
  }

  /** Strictly decodes the complete immutable owner receipt retained by Automation. */
  public static AutomationEmptySelectedSourceIntakeReceipt fromStored(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Stored Automation source receipt size is invalid");
    }
    byte[] stored = bytes.clone();
    var reader = new DraftAuthorizationFenceBinding.FrameReader(stored);
    reader.expect(DOMAIN);
    if (!"1".equals(reader.text())) {
      throw new IllegalArgumentException("Unsupported Automation source receipt schema");
    }
    var result =
        new AutomationEmptySelectedSourceIntakeReceipt(
            reader.text(),
            parseUuid(reader.text(), "operationId"),
            parseUuid(reader.text(), "fenceId"),
            parseUuid(reader.text(), "intakeRequestId"),
            parseUuid(reader.text(), "canonicalTenantId"),
            parseUuid(reader.text(), "canonicalVersionId"),
            parseUuid(reader.text(), "selectedCommitId"),
            parseUuid(reader.text(), "sourceRevisionId"),
            reader.text(),
            parsePositiveLong(reader.text(), "localTenantKey"),
            parsePositiveLong(reader.text(), "localVersionKey"),
            reader.text(),
            reader.text(),
            reader.bytes(),
            parseUuid(reader.text(), "worldReadRequestId"),
            reader.text(),
            reader.bytes(),
            reader.text(),
            reader.bytes(),
            parseLong(reader.text(), "scriptsRowCount"),
            parseLong(reader.text(), "eventBindingsRowCount"),
            parseLong(reader.text(), "patchBaseBindingsRowCount"),
            parseLong(reader.text(), "unqualifiedScriptsRowCount"),
            parseLong(reader.text(), "unqualifiedEventBindingsRowCount"),
            parseLong(reader.text(), "unqualifiedPatchBaseBindingsRowCount"),
            parseLong(reader.text(), "emptyAssociatedScriptsRowCount"),
            parseLong(reader.text(), "emptyAssociatedEventBindingsRowCount"),
            parseLong(reader.text(), "emptyAssociatedPatchBaseBindingsRowCount"),
            parseLong(reader.text(), "selectedScopeScriptsRowCount"),
            parseLong(reader.text(), "selectedScopeEventBindingsRowCount"),
            parseLong(reader.text(), "selectedScopePatchBaseBindingsRowCount"),
            parseTimestamp(reader.text()));
    reader.requireEnd();
    if (!Arrays.equals(stored, result.canonicalBytes)) {
      throw new IllegalArgumentException("Noncanonical stored Automation source receipt");
    }
    return result;
  }

  public String targetNamespace() {
    return targetNamespace;
  }

  public UUID operationId() {
    return operationId;
  }

  public UUID fenceId() {
    return fenceId;
  }

  public UUID intakeRequestId() {
    return intakeRequestId;
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

  public UUID sourceRevisionId() {
    return sourceRevisionId;
  }

  public String sourceRevisionOrder() {
    return sourceRevisionOrder;
  }

  public long localTenantKey() {
    return localTenantKey;
  }

  public long localVersionKey() {
    return localVersionKey;
  }

  public String requestDigest() {
    return requestDigest;
  }

  public String authorizationBindingDigest() {
    return authorizationBindingDigest;
  }

  public byte[] authorizationBindingBytes() {
    return authorizationBindingBytes.clone();
  }

  public UUID worldReadRequestId() {
    return worldReadRequestId;
  }

  public String worldReadRequestDigest() {
    return worldReadRequestDigest;
  }

  public byte[] worldReadRequestBytes() {
    return worldReadRequestBytes.clone();
  }

  public String worldInventoryDigest() {
    return worldInventoryDigest;
  }

  public byte[] worldInventoryBytes() {
    return worldInventoryBytes.clone();
  }

  public long scriptsRowCount() {
    return scriptsRowCount;
  }

  public long eventBindingsRowCount() {
    return eventBindingsRowCount;
  }

  public long patchBaseBindingsRowCount() {
    return patchBaseBindingsRowCount;
  }

  public long unqualifiedScriptsRowCount() {
    return unqualifiedScriptsRowCount;
  }

  public long unqualifiedEventBindingsRowCount() {
    return unqualifiedEventBindingsRowCount;
  }

  public long unqualifiedPatchBaseBindingsRowCount() {
    return unqualifiedPatchBaseBindingsRowCount;
  }

  public long emptyAssociatedScriptsRowCount() {
    return emptyAssociatedScriptsRowCount;
  }

  public long emptyAssociatedEventBindingsRowCount() {
    return emptyAssociatedEventBindingsRowCount;
  }

  public long emptyAssociatedPatchBaseBindingsRowCount() {
    return emptyAssociatedPatchBaseBindingsRowCount;
  }

  public long selectedScopeScriptsRowCount() {
    return selectedScopeScriptsRowCount;
  }

  public long selectedScopeEventBindingsRowCount() {
    return selectedScopeEventBindingsRowCount;
  }

  public long selectedScopePatchBaseBindingsRowCount() {
    return selectedScopePatchBaseBindingsRowCount;
  }

  public OffsetDateTime retainedAt() {
    return retainedAt;
  }

  public String receiptDigest() {
    return receiptDigest;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String outcome() {
    return "COMMITTED_EMPTY";
  }

  /** This bounded slice never settles or releases Account's original source participation. */
  public String sourceParticipationDisposition() {
    return "PENDING_OWNER_TERMINAL_SETTLEMENT";
  }

  SelectedOwnerIntakeAuthorizationBinding authorizationBinding() {
    return authorizationBinding;
  }

  SelectedOwnerWorldInventoryReadEvidence worldInventoryReadEvidence() {
    return worldInventoryReadEvidence;
  }

  /** Checks a caller retry against the original Account binding and exact selected World freeze. */
  public void requireSameRequest(
      String namespace,
      SelectedOwnerIntakeAuthorizationBinding candidateBinding,
      WorldSelectedDraftPublicationFreezeEvidence candidateFreeze) {
    Objects.requireNonNull(candidateBinding, "candidate Account binding is required");
    Objects.requireNonNull(candidateFreeze, "candidate World freeze is required");
    String candidateDigest = requestDigest(namespace, candidateBinding, candidateFreeze);
    if (!targetNamespace.equals(namespace)
        || !requestDigest.equals(candidateDigest)
        || !Arrays.equals(authorizationBindingBytes, candidateBinding.canonicalBytes())
        || !worldInventoryReadEvidence.request().freezeEvidence().equals(candidateFreeze)) {
      throw new IllegalStateException(
          "Automation source intake request identity already records different selected evidence");
    }
  }

  /** Also binds a concurrent first-write race to the exact newly read World inventory content. */
  void requireSameInputs(SelectedOwnerEmptySourceInputs candidateInputs) {
    Objects.requireNonNull(candidateInputs, "validated empty-source inputs are required");
    var candidateBinding = candidateInputs.authorizationBinding();
    var candidateWorld = candidateInputs.worldInventoryReadEvidence();
    requireSameRequest(
        candidateBinding.targetNamespace(),
        candidateBinding,
        candidateWorld.request().freezeEvidence());
    if (!Arrays.equals(worldInventoryBytes, candidateWorld.inventory().canonicalBytes())
        || !worldInventoryDigest.equals(candidateWorld.inventory().digest())) {
      throw new IllegalStateException(
          "Automation source intake request identity already records different World evidence");
    }
  }

  /** Digest for the caller-stable request, excluding the fresh World read correlation. */
  public static String requestDigest(
      String namespace,
      SelectedOwnerIntakeAuthorizationBinding binding,
      WorldSelectedDraftPublicationFreezeEvidence freezeEvidence) {
    requireText(namespace, "targetNamespace");
    Objects.requireNonNull(binding, "binding");
    Objects.requireNonNull(freezeEvidence, "freezeEvidence");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, "automation-empty-selected-source-intake-request/v1");
    DraftAuthorizationFenceBinding.frame(out, namespace);
    DraftAuthorizationFenceBinding.frame(out, binding.canonicalBytes());
    DraftAuthorizationFenceBinding.frame(
        out,
        WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(freezeEvidence.request())
            .toByteArray());
    DraftAuthorizationFenceBinding.frame(
        out,
        WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(freezeEvidence.acknowledgement())
            .toByteArray());
    return DraftAuthorizationFenceBinding.digest(out.toByteArray());
  }

  private void requireAuthorizationIdentity() {
    if (authorizationBinding.owner() != Owner.AUTOMATION_SCRIPTING
        || !authorizationBinding.schema().equals("account-automation-intake-authorization/v1")
        || !authorizationBinding.purpose().equals("AUTOMATION_INTAKE_RETENTION")
        || !authorizationBinding.targetNamespace().equals(targetNamespace)
        || !authorizationBinding.operationId().equals(operationId)
        || !authorizationBinding.fenceId().equals(fenceId)
        || !authorizationBinding.intakeRequestId().equals(intakeRequestId)
        || !authorizationBinding.tenantId().equals(canonicalTenantId)
        || !authorizationBinding.versionId().equals(canonicalVersionId)
        || !authorizationBinding.selected().commitId().equals(selectedCommitId)) {
      throw new IllegalArgumentException(
          "Automation receipt differs from its original Account binding");
    }
    var declaration =
        new SelectedOwnerEmptySourceInputs(authorizationBinding, decodeWorldEvidence())
            .ownerSourceInventoryDeclaration();
    if (declaration.owner() != Owner.AUTOMATION_SCRIPTING
        || !declaration.revisionId().equals(sourceRevisionId)
        || !declaration.revisionOrder().equals(sourceRevisionOrder)) {
      throw new IllegalArgumentException(
          "Automation receipt differs from its original source revision");
    }
    if (!requestDigest.equals(
        requestDigest(
            targetNamespace,
            authorizationBinding,
            worldInventoryReadEvidence.request().freezeEvidence()))) {
      throw new IllegalArgumentException("Automation receipt request digest differs");
    }
  }

  private SelectedOwnerWorldInventoryReadEvidence decodeWorldEvidence() {
    if (!DraftAuthorizationFenceBinding.digest(worldReadRequestBytes)
        .equals(worldReadRequestDigest)) {
      throw new IllegalArgumentException("Retained World read request digest differs");
    }
    final ReadSelectedOwnerWorldInventoryRequest requestMessage;
    try {
      requestMessage = ReadSelectedOwnerWorldInventoryRequest.parseFrom(worldReadRequestBytes);
    } catch (InvalidProtocolBufferException invalid) {
      throw new IllegalArgumentException("Retained World read request is malformed", invalid);
    }
    if (!Arrays.equals(worldReadRequestBytes, requestMessage.toByteArray())) {
      throw new IllegalArgumentException("Retained World read request is not canonical");
    }
    var request = SelectedOwnerWorldInventoryReadGrpcCodec.fromRequest(requestMessage);
    if (!request.readRequestId().equals(worldReadRequestId)
        || !request.targetNamespace().equals(targetNamespace)
        || !Arrays.equals(
            authorizationBindingBytes, request.authorizationBinding().canonicalBytes())) {
      throw new IllegalArgumentException(
          "Retained World request differs from the original binding");
    }
    var inventory =
        WorldSelectedPublicationArtifactInventoryEvidence.fromCanonicalBytes(
            request.freezeEvidence(), worldInventoryBytes, worldInventoryDigest);
    return new SelectedOwnerWorldInventoryReadEvidence(request, inventory);
  }

  private byte[] encode() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    frame(out, DOMAIN);
    frame(out, "1");
    frame(out, targetNamespace);
    frame(out, operationId.toString());
    frame(out, fenceId.toString());
    frame(out, intakeRequestId.toString());
    frame(out, canonicalTenantId.toString());
    frame(out, canonicalVersionId.toString());
    frame(out, selectedCommitId.toString());
    frame(out, sourceRevisionId.toString());
    frame(out, sourceRevisionOrder);
    frame(out, Long.toString(localTenantKey));
    frame(out, Long.toString(localVersionKey));
    frame(out, requestDigest);
    frame(out, authorizationBindingDigest);
    frame(out, authorizationBindingBytes);
    frame(out, worldReadRequestId.toString());
    frame(out, worldReadRequestDigest);
    frame(out, worldReadRequestBytes);
    frame(out, worldInventoryDigest);
    frame(out, worldInventoryBytes);
    frame(out, Long.toString(scriptsRowCount));
    frame(out, Long.toString(eventBindingsRowCount));
    frame(out, Long.toString(patchBaseBindingsRowCount));
    frame(out, Long.toString(unqualifiedScriptsRowCount));
    frame(out, Long.toString(unqualifiedEventBindingsRowCount));
    frame(out, Long.toString(unqualifiedPatchBaseBindingsRowCount));
    frame(out, Long.toString(emptyAssociatedScriptsRowCount));
    frame(out, Long.toString(emptyAssociatedEventBindingsRowCount));
    frame(out, Long.toString(emptyAssociatedPatchBaseBindingsRowCount));
    frame(out, Long.toString(selectedScopeScriptsRowCount));
    frame(out, Long.toString(selectedScopeEventBindingsRowCount));
    frame(out, Long.toString(selectedScopePatchBaseBindingsRowCount));
    frame(out, retainedAt.toString());
    return out.toByteArray();
  }

  private static void frame(ByteArrayOutputStream out, String value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static void frame(ByteArrayOutputStream out, byte[] value) {
    DraftAuthorizationFenceBinding.frame(out, value);
  }

  private static byte[] boundedBytes(byte[] value, int max, String label) {
    if (value == null || value.length == 0 || value.length > max) {
      throw new IllegalArgumentException(label + " is absent or exceeds its limit");
    }
    return value.clone();
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(label + " is required");
    }
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      if (Character.isHighSurrogate(character)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
          throw new IllegalArgumentException(label + " contains an unpaired surrogate");
        }
      } else if (Character.isLowSurrogate(character)) {
        throw new IllegalArgumentException(label + " contains an unpaired surrogate");
      }
    }
    return value;
  }

  private static String requireDigest(String value, String label) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(label + " must be a canonical SHA-256 digest");
    }
    return value;
  }

  private static String requireDecimal(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, true);
    return value;
  }

  private static long nonnegative(long value, String label) {
    if (value < 0L) {
      throw new IllegalArgumentException(label + " cannot be negative");
    }
    return value;
  }

  private static UUID requireUuid(UUID value, String label) {
    try {
      DraftAuthorizationFenceBinding.requireUuid(value);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
    return value;
  }

  private static UUID parseUuid(String value, String label) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equals(value)) {
        throw new IllegalArgumentException("Noncanonical UUID");
      }
      return requireUuid(id, label);
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static long parsePositiveLong(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, false);
    long parsed = Long.parseLong(value);
    if (parsed <= 0L || !Long.toString(parsed).equals(value)) {
      throw new IllegalArgumentException(label + " must be a positive canonical decimal");
    }
    return parsed;
  }

  private static long parseLong(String value, String label) {
    DraftAuthorizationFenceBinding.decimal(value, true);
    return Long.parseLong(value);
  }

  private static OffsetDateTime parseTimestamp(String value) {
    OffsetDateTime parsed = OffsetDateTime.parse(value);
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Receipt timestamp is not canonical");
    }
    return parsed;
  }
}
