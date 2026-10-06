package net.firedevops.firemud.worldmanagement.tenant;

import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityClient;
import net.firedevops.firemud.common.gamedesign.DraftSynchronizedVisibilityEvidence;

/** Standalone, opt-in normal-read selector for the exact immutable World graph behind a GD fence. */
public final class WorldSynchronizedDraftTopologyReadService {
  private final DraftSynchronizedVisibilityClient visibilityClient;
  private final WorldDraftTopologyCommitRepository commitRepository;

  public WorldSynchronizedDraftTopologyReadService(
      DraftSynchronizedVisibilityClient visibilityClient,
      WorldDraftTopologyCommitRepository commitRepository) {
    this.visibilityClient = Objects.requireNonNull(visibilityClient, "visibilityClient");
    this.commitRepository = Objects.requireNonNull(commitRepository, "commitRepository");
  }

  /**
   * Reads the durable Game Design fence first, then selects only the retained graph for its full
   * binding. This does not authorize writes, publication, preparation, or runtime admission.
   */
  public WorldCanonicalAuthoredGraph read(DraftSynchronizedVisibilityEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    DraftSynchronizedVisibilityEvidence evidence = visibilityClient.read(request);
    if (!request.equals(evidence.request())) {
      throw new IllegalStateException("Game Design visibility reply changed the exact read target");
    }
    evidence.requireValid();
    if (!evidence.binding().requiredOwners().contains(DraftCommitBinding.Owner.WORLD_MANAGEMENT)) {
      throw new IllegalStateException("Synchronized binding has no World owner result");
    }
    return commitRepository
        .readSynchronized(evidence)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No retained immutable World graph matches the synchronized full binding"));
  }
}
