package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.BindableService;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import net.firedevops.firemud.worldmanagement.service.impl.WorldDraftDesignDigestServiceImpl;
import org.jooq.DSLContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Test-only composition of the existing unregistered World selected-publication owners. */
public final class GenuineWorldSelectedPublicationProofFactory {
  private final BindableService freezeAndInventoryService;
  private final BindableService publishedStartLocationReadService;

  public GenuineWorldSelectedPublicationProofFactory(
      String workloadNamespace,
      DSLContext dsl,
      PlatformTransactionManager transactionManager,
      WorldAuthoredSourceIntakeRepository intakeRepository,
      WorldDesignPublicationFenceRepository fence,
      WorldDraftGraphApplicationRepository graphApplications,
      AuthoredDraftPublishSelectionReadClient selectionReadClient,
      AuthoredWorldVersionStateClient versionStateClient,
      AccountPublicationAuthorizationReadClient accountReadClient,
      ObjectMapper objectMapper) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    Objects.requireNonNull(dsl, "dsl");
    Objects.requireNonNull(transactionManager, "transactionManager");
    Objects.requireNonNull(fence, "fence");
    Objects.requireNonNull(graphApplications, "graphApplications");
    Objects.requireNonNull(intakeRepository, "intakeRepository");
    Objects.requireNonNull(objectMapper, "objectMapper");

    var topology = new WorldDraftTopologyCommitRepository(dsl, fence, objectMapper);
    var digestService = createDigestService(dsl, objectMapper);
    var frozenTopologyRepository =
        new WorldCanonicalFrozenTopologyRepository(
            dsl, new WorldAuthoredGraphSnapshotRepository(dsl), topology, digestService);
    var canonicalFrozenTopology =
        new WorldCanonicalFrozenTopologyService(frozenTopologyRepository, transactionManager);
    var selectorCapture =
        new WorldSelectedPublicationSelectorCapture(workloadNamespace, canonicalFrozenTopology);
    var authorizationRepository = new WorldSelectedDraftPublicationAuthorizationRepository(dsl);
    var artifactInventoryRepository = new WorldSelectedPublicationArtifactInventoryRepository(dsl);
    var freezeService =
        new WorldSelectedDraftPublicationFreezeService(
            workloadNamespace,
            Objects.requireNonNull(selectionReadClient, "selectionReadClient"),
            Objects.requireNonNull(versionStateClient, "versionStateClient"),
            Objects.requireNonNull(accountReadClient, "accountReadClient"),
            intakeRepository,
            fence,
            new WorldSelectedDraftPublicationCheckpointRepository(
                fence, graphApplications, topology, digestService),
            authorizationRepository,
            artifactInventoryRepository,
            graphApplications,
            selectorCapture,
            transactionManager);
    var inventoryReadService =
        new WorldSelectedPublicationArtifactInventoryReadService(
            workloadNamespace, fence, authorizationRepository, artifactInventoryRepository);
    freezeAndInventoryService =
        new WorldSelectedDraftPublicationFreezeGrpcService(
            freezeService, inventoryReadService, workloadNamespace);
    var publishedSelectorRepository =
        new WorldPublishedStartLocationRepository(dsl, frozenTopologyRepository, graphApplications);
    publishedStartLocationReadService =
        new WorldPublishedStartLocationReadGrpcService(
            publishedSelectorRepository, workloadNamespace);
  }

  public BindableService freezeAndInventoryService() {
    return freezeAndInventoryService;
  }

  public BindableService publishedStartLocationReadService() {
    return publishedStartLocationReadService;
  }

  private static WorldDraftDesignDigestService createDigestService(
      DSLContext dsl, ObjectMapper objectMapper) {
    return new WorldDraftDesignDigestServiceImpl(
        new RegionRepository(dsl),
        new ZoneRepository(dsl),
        new RoomRepository(dsl),
        new RoomExitRepository(dsl),
        new GenerationRuleRepository(dsl),
        new WorldEntitySpawnBindingRepository(dsl),
        objectMapper);
  }
}
