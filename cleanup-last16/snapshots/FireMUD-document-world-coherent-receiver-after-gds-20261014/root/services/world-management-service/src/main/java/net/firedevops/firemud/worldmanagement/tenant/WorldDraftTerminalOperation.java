package net.firedevops.firemud.worldmanagement.tenant;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;

/**
 * Exact identity for a World-local terminal result; the retained Account bytes are not permission.
 */
public final class WorldDraftTerminalOperation {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String SCHEMA = "world-draft-terminal-operation/v1";

  private final UUID operationId;
  private final UUID requestId;
  private final UUID commitId;
  private final UUID authorizationFenceId;
  private final UUID canonicalTenantId;
  private final UUID canonicalVersionId;
  private final DraftCommitBinding binding;
  private final OwnerBinding ownerBinding;
  private final byte[] accountBindingBytes;
  private final String accountBindingDigest;
  private final byte[] canonicalBytes;

  public WorldDraftTerminalOperation(
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID authorizationFenceId,
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      DraftCommitBinding binding,
      OwnerBinding ownerBinding,
      byte[] accountBindingBytes) {
    this.operationId = requireUuid(operationId, "operationId");
    this.requestId = requireUuid(requestId, "requestId");
    this.commitId = requireUuid(commitId, "commitId");
    this.authorizationFenceId = requireUuid(authorizationFenceId, "authorizationFenceId");
    this.canonicalTenantId = requireUuid(canonicalTenantId, "canonicalTenantId");
    this.canonicalVersionId = requireUuid(canonicalVersionId, "canonicalVersionId");
    this.binding = Objects.requireNonNull(binding, "binding");
    this.ownerBinding = Objects.requireNonNull(ownerBinding, "ownerBinding");
    this.accountBindingBytes = requireBytes(accountBindingBytes, "accountBindingBytes");
    this.accountBindingDigest = digest(this.accountBindingBytes);

    DraftAuthorizationFenceBinding accountBinding =
        DraftAuthorizationFenceBinding.fromStored(this.accountBindingBytes);
    if (!requestId.equals(binding.requestId())
        || !commitId.equals(binding.commitId())
        || !canonicalTenantId.equals(binding.target().canonicalTenantId())
        || !canonicalVersionId.equals(binding.target().canonicalVersionId())
        || !canonicalTenantId.equals(ownerBinding.canonicalTenantId())
        || !canonicalVersionId.equals(ownerBinding.canonicalVersionId())
        || binding.target().gameDesignVersionRowId() != ownerBinding.gameDesignVersionId()
        || !binding.requiredOwners().contains(DraftCommitBinding.Owner.WORLD_MANAGEMENT)
        || !binding.requiredOwners().contains(DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE)
        || binding.affectedUnits(DraftCommitBinding.Owner.WORLD_MANAGEMENT).isEmpty()) {
      throw new IllegalArgumentException(
          "World terminal identity differs from the complete Draft binding");
    }
    if (!operationId.equals(accountBinding.operationId())
        || !requestId.equals(accountBinding.requestId())
        || !commitId.equals(accountBinding.commitId())
        || !authorizationFenceId.equals(accountBinding.fenceId())
        || !canonicalTenantId.equals(accountBinding.tenantId())
        || !canonicalVersionId.equals(accountBinding.versionId())
        || !binding.baseCommitId().equals(accountBinding.baseCommitId())
        || !binding.digest().equals(accountBinding.inputDigest())
        || !Arrays.equals(binding.canonicalBytes(), accountBinding.gameDesignBinding())
        || !Arrays.equals(binding.canonicalBytes(), accountBinding.normalizedInput())) {
      throw new IllegalArgumentException(
          "Complete V57 Account binding differs from the exact World operation");
    }
    this.canonicalBytes = encodeCanonicalBytes();
  }

  public UUID operationId() {
    return operationId;
  }

  public UUID requestId() {
    return requestId;
  }

  public UUID commitId() {
    return commitId;
  }

  public UUID authorizationFenceId() {
    return authorizationFenceId;
  }

  public UUID canonicalTenantId() {
    return canonicalTenantId;
  }

  public UUID canonicalVersionId() {
    return canonicalVersionId;
  }

  public DraftCommitBinding binding() {
    return binding;
  }

  public OwnerBinding ownerBinding() {
    return ownerBinding;
  }

  public byte[] accountBindingBytes() {
    return accountBindingBytes.clone();
  }

  public String accountBindingDigest() {
    return accountBindingDigest;
  }

  public byte[] canonicalBytes() {
    return canonicalBytes.clone();
  }

  public String digest() {
    return digest(canonicalBytes);
  }

  private byte[] encodeCanonicalBytes() {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    frame(output, SCHEMA);
    for (UUID id :
        List.of(
            operationId,
            requestId,
            commitId,
            authorizationFenceId,
            canonicalTenantId,
            canonicalVersionId)) {
      frame(output, id.toString());
    }
    frame(output, binding.canonicalBytes());
    OwnerBinding owner = ownerBinding;
    for (String value :
        List.of(
            owner.targetNamespace(),
            owner.canonicalTenantId().toString(),
            owner.canonicalVersionId().toString(),
            owner.versionIdentityOperationId().toString(),
            Long.toString(owner.gameDesignVersionId()),
            owner.intakeRequestId().toString(),
            owner.intakeOperationId().toString(),
            owner.intakeRequestDigest(),
            owner.sourceOperationId().toString(),
            owner.sourceEvidenceDigest(),
            owner.intakeReceiptDigest())) {
      frame(output, value);
    }
    frame(output, accountBindingDigest);
    frame(output, accountBindingBytes);
    return output.toByteArray();
  }

  private static UUID requireUuid(UUID value, String label) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a non-nil UUID");
    }
    return value;
  }

  private static byte[] requireBytes(byte[] value, String label) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException(label + " must retain exact nonempty bytes");
    }
    return value.clone();
  }

  private static void frame(ByteArrayOutputStream output, String value) {
    frame(output, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void frame(ByteArrayOutputStream output, byte[] value) {
    try {
      DataOutputStream data = new DataOutputStream(output);
      data.writeInt(value.length);
      data.write(value);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
