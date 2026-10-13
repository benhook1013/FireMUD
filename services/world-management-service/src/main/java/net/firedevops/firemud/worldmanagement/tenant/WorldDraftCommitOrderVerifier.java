package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService.CommitOrderProof;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService.CommitOrderVerifier;

/**
 * Unregistered production-shaped adapter: derives the exact Account read from the original World
 * operation and creates a World proof only after the mTLS client returns its validated HELD result.
 * This reads retained ordering, not physical commit-time terms currentness. A later terms schedule
 * can introduce a deadline after the original capture; runtime wiring remains denied until that
 * source transition and the World physical commit are safely fenced.
 */
public final class WorldDraftCommitOrderVerifier implements CommitOrderVerifier {
  private final DraftCommitOrderReadClient accountClient;
  private final String workloadNamespace;

  public WorldDraftCommitOrderVerifier(
      DraftCommitOrderReadClient accountClient, String workloadNamespace) {
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient");
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical World workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
  }

  @Override
  public CommitOrderProof verifyHeldOriginalCommitOrder(WorldDraftTerminalOperation operation) {
    Objects.requireNonNull(operation, "operation");
    if (!workloadNamespace.equals(operation.ownerBinding().targetNamespace())) {
      throw new ConflictException("Original World operation namespace differs from this workload");
    }
    DraftCommitOrderReadEvidence.Request request =
        DraftCommitOrderReadEvidence.Request.create(
            workloadNamespace, operation.accountBindingBytes());
    DraftCommitOrderReadEvidence evidence = Objects.requireNonNull(accountClient.read(request));
    if (!request.equals(Objects.requireNonNull(evidence.request()))) {
      throw new ConflictException("Account changed the exact original COMMIT_ORDER read request");
    }
    return new CommitOrderProof(operation);
  }
}
