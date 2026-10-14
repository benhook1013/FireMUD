package net.firedevops.firemud.worldmanagement.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceEvidence.OwnerBinding;

/**
 * Unregistered original-Account entry for applying a source-qualified fresh World graph.
 *
 * <p>The Account bytes are immutable input, not permission. An exact committed result is recovered
 * before consulting mutable owner state; a new application still enters the held-COMMIT_ORDER
 * verifier through {@link WorldDraftGraphApplicationService}.
 */
public final class WorldOriginalDraftGraphApplicationService {
  private static final List<DraftCommitBinding.Owner> DRAFT_OWNERS =
      List.of(
          DraftCommitBinding.Owner.WORLD_MANAGEMENT,
          DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE);
  private static final List<Owner> ACCOUNT_OWNERS = List.of(Owner.GAME_DESIGN, Owner.WORLD);

  private final WorldAuthoredVersionIdentityRepository versionIdentities;
  private final WorldAuthoredSourceIntakeRepository sourceIntakes;
  private final WorldDraftGraphApplicationService graphApplications;

  public WorldOriginalDraftGraphApplicationService(
      WorldAuthoredVersionIdentityRepository versionIdentities,
      WorldAuthoredSourceIntakeRepository sourceIntakes,
      WorldDraftGraphApplicationService graphApplications) {
    this.versionIdentities = Objects.requireNonNull(versionIdentities, "versionIdentities");
    this.sourceIntakes = Objects.requireNonNull(sourceIntakes, "sourceIntakes");
    this.graphApplications = Objects.requireNonNull(graphApplications, "graphApplications");
  }

  /**
   * Resolves the complete World owner binding from committed World receipts, then applies the
   * original Account operation. The only caller-supplied operation material is its receiver
   * namespace and unchanged complete Account binding bytes.
   */
  public WorldDraftGraphAppliedResult apply(
      String receiverNamespace, byte[] originalAccountBindingBytes) {
    if (receiverNamespace == null || !GrpcPeerIdentity.isValidNamespace(receiverNamespace)) {
      throw new IllegalArgumentException("Receiver namespace must be one canonical DNS label");
    }
    byte[] originalBytes = requireBytes(originalAccountBindingBytes);
    DraftAuthorizationFenceBinding accountBinding =
        DraftAuthorizationFenceBinding.fromStored(originalBytes);
    byte[] completeBindingBytes = accountBinding.gameDesignBinding();
    DraftCommitBinding binding =
        DraftCommitBinding.fromStored(
            new String(completeBindingBytes, StandardCharsets.UTF_8), digest(completeBindingBytes));
    requireSupportedOwnerVectors(accountBinding, binding);

    var prior = graphApplications.readCommitted(receiverNamespace, originalBytes);
    if (prior.isPresent()) {
      return prior.orElseThrow();
    }

    DraftCommitBinding.TargetProof target = binding.target();
    if (!"NEW_GAME_ROW".equals(target.sourceProvenanceKind())) {
      throw conflict("World original graph application requires retained fresh-source provenance");
    }
    WorldAuthoredVersionIdentityReceipt identity =
        versionIdentities
            .readByCanonicalTarget(
                receiverNamespace,
                target.canonicalTenantId(),
                target.canonicalVersionId(),
                target.gameDesignVersionRowId())
            .orElseThrow(
                () ->
                    new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
                        "Exact World authored-Version identity receipt is missing"));

    WorldAuthoredSourceIntakeReceipt retainedIntake = identity.sourceIntakeReceipt();
    WorldAuthoredSourceIntakeReceipt committedIntake =
        sourceIntakes
            .read(receiverNamespace, retainedIntake.intakeRequestId())
            .orElseThrow(
                () ->
                    new WorldAuthoredVersionIdentityRepository.InvalidIdentityEvidenceException(
                        "Exact World source intake association is missing"));
    if (!retainedIntake.equals(committedIntake)) {
      throw conflict("World Version identity differs from its independently read source intake");
    }

    requireExactSourceTarget(receiverNamespace, target, identity, committedIntake);
    OwnerBinding ownerBinding = ownerBinding(receiverNamespace, identity, committedIntake);
    WorldDraftTerminalOperation operation =
        new WorldDraftTerminalOperation(
            accountBinding.operationId(),
            accountBinding.requestId(),
            accountBinding.commitId(),
            accountBinding.fenceId(),
            accountBinding.tenantId(),
            accountBinding.versionId(),
            binding,
            ownerBinding,
            originalBytes);
    WorldDraftTopologyCommitPlan plan = WorldDraftTopologyCommitPlan.create(binding, ownerBinding);
    return graphApplications.apply(new WorldDraftGraphApplication(operation, plan));
  }

  private static void requireSupportedOwnerVectors(
      DraftAuthorizationFenceBinding accountBinding, DraftCommitBinding binding) {
    if (!DRAFT_OWNERS.equals(binding.requiredOwners())
        || !ACCOUNT_OWNERS.equals(accountBinding.requiredOwners())) {
      throw conflict(
          "World original graph application requires the exact supported GD+World owners");
    }
  }

  private static void requireExactSourceTarget(
      String receiverNamespace,
      DraftCommitBinding.TargetProof target,
      WorldAuthoredVersionIdentityReceipt identity,
      WorldAuthoredSourceIntakeReceipt intake) {
    var stateRequest = identity.versionStateEvidence().request();
    var source = intake.source();
    if (!"NEW_GAME_ROW".equals(source.provenanceKind())
        || !receiverNamespace.equals(identity.targetNamespace())
        || !receiverNamespace.equals(intake.targetNamespace())
        || !target.canonicalTenantId().equals(identity.canonicalTenantId())
        || !target.canonicalTenantId().equals(intake.canonicalTenantId())
        || !target.canonicalTenantId().equals(source.canonicalTenantId())
        || !target.canonicalVersionId().equals(identity.canonicalVersionId())
        || target.gameDesignVersionRowId() != identity.gameDesignVersionId()
        || target.gameDesignVersionRowId() != stateRequest.versionId()
        || !receiverNamespace.equals(stateRequest.targetNamespace())
        || !target.canonicalTenantId().equals(stateRequest.canonicalTenantId())
        || target.sourceGameRowId() != source.sourceGameRowId()
        || !target.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !target.gameDesignVersionTenantKey().equals(source.sourceGameTenantKey())
        || !target.sourceProvenanceKind().equals(source.provenanceKind())
        || !intake.sourceOperationId().equals(source.operationId())
        || !intake.sourceEvidenceDigest().equals(source.evidenceDigest())) {
      throw conflict(
          "Original Draft target differs from the exact retained World source and Version receipt");
    }
  }

  private static OwnerBinding ownerBinding(
      String receiverNamespace,
      WorldAuthoredVersionIdentityReceipt identity,
      WorldAuthoredSourceIntakeReceipt intake) {
    return new OwnerBinding(
        receiverNamespace,
        identity.canonicalTenantId(),
        identity.canonicalVersionId(),
        identity.operationId(),
        identity.gameDesignVersionId(),
        intake.intakeRequestId(),
        intake.operationId(),
        intake.requestDigest(),
        intake.sourceOperationId(),
        intake.sourceEvidenceDigest(),
        intake.receiptDigest());
  }

  private static byte[] requireBytes(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("Complete original Account binding bytes are required");
    }
    return bytes.clone();
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static WorldAuthoredVersionIdentityRepository.RegistrationConflictException conflict(
      String message) {
    return new WorldAuthoredVersionIdentityRepository.RegistrationConflictException(message);
  }
}
