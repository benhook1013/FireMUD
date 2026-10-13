package net.firedevops.firemud.gamedesign.draft;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository.PublicationEvidence;

/**
 * Exact existing synchronized Draft selection. The shared codec preserves the original V43 bytes
 * and digest; neither this retained selection nor codec validation establishes publication
 * authority.
 */
public final class AuthoredDraftPublishSelection {
  private final AuthoredDraftPublishSelectionBinding binding;

  private AuthoredDraftPublishSelection(AuthoredDraftPublishSelectionBinding binding) {
    this.binding = Objects.requireNonNull(binding);
  }

  public static AuthoredDraftPublishSelection capture(
      PublishIntent intent, TargetProof target, PublicationEvidence evidence) {
    Objects.requireNonNull(evidence, "evidence");
    var fence = evidence.visibilityFence();
    return new AuthoredDraftPublishSelection(
        AuthoredDraftPublishSelectionBinding.capture(
            sharedIntent(intent),
            target,
            evidence.binding(),
            new AuthoredDraftPublishSelectionBinding.VisibilityFence(
                fence.target(),
                fence.requestId(),
                fence.commitId(),
                fence.inputDigest(),
                fence.resultVectorJson(),
                fence.createdAt())));
  }

  public static AuthoredDraftPublishSelection fromStored(String storedJson, String storedDigest) {
    return new AuthoredDraftPublishSelection(
        AuthoredDraftPublishSelectionBinding.fromStored(storedJson, storedDigest));
  }

  public PublishIntent intent() {
    var intent = binding.intent();
    return new PublishIntent(
        intent.canonicalTenantId(),
        intent.canonicalVersionId(),
        intent.publishRequestId(),
        intent.expectedVersionStateEpoch(),
        intent.notes(),
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest());
  }

  public TargetProof target() {
    return binding.target();
  }

  public DraftCommitBinding selectedCommit() {
    return binding.selectedCommit();
  }

  public UUID fenceRequestId() {
    return binding.fenceRequestId();
  }

  public UUID fenceCommitId() {
    return binding.fenceCommitId();
  }

  public String fenceInputDigest() {
    return binding.fenceInputDigest();
  }

  public String fenceResultVectorJson() {
    return binding.fenceResultVectorJson();
  }

  public String fenceCreatedAt() {
    return binding.fenceCreatedAt();
  }

  public String canonicalJson() {
    return binding.canonicalJson();
  }

  public byte[] canonicalBytes() {
    return binding.canonicalBytes();
  }

  public String digest() {
    return binding.digest();
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || (other instanceof AuthoredDraftPublishSelection selection
            && binding.equals(selection.binding));
  }

  @Override
  public int hashCode() {
    return binding.hashCode();
  }

  private static AuthoredDraftPublishSelectionBinding.PublishIntent sharedIntent(
      PublishIntent intent) {
    Objects.requireNonNull(intent, "intent");
    return new AuthoredDraftPublishSelectionBinding.PublishIntent(
        intent.canonicalTenantId(),
        intent.canonicalVersionId(),
        intent.publishRequestId(),
        intent.expectedVersionStateEpoch(),
        intent.notes(),
        intent.selectedCommitRequestId(),
        intent.selectedCommitId(),
        intent.selectedCommitDigest());
  }

  /** Full original eight caller inputs, retaining the existing public API. */
  public record PublishIntent(
      UUID canonicalTenantId,
      UUID canonicalVersionId,
      String publishRequestId,
      String expectedVersionStateEpoch,
      String notes,
      UUID selectedCommitRequestId,
      UUID selectedCommitId,
      String selectedCommitDigest) {
    public PublishIntent {
      new AuthoredDraftPublishSelectionBinding.PublishIntent(
          canonicalTenantId,
          canonicalVersionId,
          publishRequestId,
          expectedVersionStateEpoch,
          notes,
          selectedCommitRequestId,
          selectedCommitId,
          selectedCommitDigest);
    }
  }
}
