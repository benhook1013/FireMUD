package net.firedevops.firemud.worldmanagement.tenant;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.OpenOwner;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftRegionGraphStager.UnverifiedStagedContent;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicitly unwired internal staging prerequisite over the actual source-qualified owner graph.
 * This grants no write permission, APPLIED result, synchronized visibility, or publication proof.
 */
public class WorldDraftRegionStagingService {
  private final WorldDesignPublicationFenceRepository fenceRepository;
  private final WorldAuthoredGraphReader graphReader;
  private final WorldDraftRegionGraphStager stager;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Construction acquires no resources and this internal component has no finalizer.")
  public WorldDraftRegionStagingService(
      DSLContext dsl, WorldDesignPublicationFenceRepository fenceRepository) {
    this(fenceRepository, new WorldAuthoredGraphReader(dsl), new WorldDraftRegionGraphStager());
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Construction acquires no resources and this internal component has no finalizer.")
  WorldDraftRegionStagingService(
      WorldDesignPublicationFenceRepository fenceRepository,
      WorldAuthoredGraphReader graphReader,
      WorldDraftRegionGraphStager stager) {
    this.fenceRepository = Objects.requireNonNull(fenceRepository, "fenceRepository");
    this.graphReader = Objects.requireNonNull(graphReader, "graphReader");
    this.stager = Objects.requireNonNull(stager, "stager");
  }

  /**
   * Reads all six families after exact intake/V27 resolution and the shared OPEN owner lock. The
   * caller owns the writable READ COMMITTED transaction. Only the existing owner lock row may be
   * created; staged content remains in memory.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UnverifiedStagedContent stage(WorldDraftRegionCommitPlan plan) {
    Objects.requireNonNull(plan, "plan");
    OpenOwner owner = fenceRepository.lockOpenAndResolve(plan.ownerBinding());
    TargetProof target = plan.binding().target();
    AuthoredWorldSourceEvidence source = owner.receipt().source();
    if (target.sourceGameRowId() != source.sourceGameRowId()
        || !Objects.equals(target.sourceGameTenantKey(), source.sourceGameTenantKey())
        || !Objects.equals(target.sourceProvenanceKind(), source.provenanceKind())) {
      throw new WorldDesignPublicationFenceRepository.ConflictException(
          "World Draft commit source provenance differs from the exact retained intake");
    }
    WorldAuthoredGraph prior =
        graphReader.readAndValidateGraph(owner.localTenantKey(), owner.localVersionKey());
    return stager.stage(plan, prior);
  }
}
