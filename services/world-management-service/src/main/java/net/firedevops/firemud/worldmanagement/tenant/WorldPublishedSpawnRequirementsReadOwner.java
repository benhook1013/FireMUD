package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.FamilyCount;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.GenerationRequirement;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.Request;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.SpawnRequirement;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.GenerationRuleIntent;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan.SpawnBindingIntent;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Standalone authenticated read owner for immutable published World spawn requirements. */
final class WorldPublishedSpawnRequirementsReadOwner {
  private final String workloadNamespace;
  private final AuthoredWorldLaunchDescriptorClient gameDesignClient;
  private final WorldPublishedStartLocationRepository publishedSources;
  private final PlatformTransactionManager transactionManager;

  WorldPublishedSpawnRequirementsReadOwner(
      String workloadNamespace,
      AuthoredWorldLaunchDescriptorClient gameDesignClient,
      WorldPublishedStartLocationRepository publishedSources,
      PlatformTransactionManager transactionManager) {
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    this.workloadNamespace = workloadNamespace;
    this.gameDesignClient = Objects.requireNonNull(gameDesignClient, "gameDesignClient");
    this.publishedSources = Objects.requireNonNull(publishedSources, "publishedSources");
    this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
  }

  WorldPublishedSpawnRequirementsEvidence read(
      ReadWorldPublishedSpawnRequirementsRequest wireRequest) {
    requireAuthenticatedEntityPeer();
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw rejected(
          Status.Code.FAILED_PRECONDITION,
          "Published spawn-requirement read requires no ambient World transaction");
    }

    final Request request;
    try {
      request = WorldPublishedSpawnRequirementsGrpcCodec.fromRequest(wireRequest);
    } catch (IllegalArgumentException invalid) {
      throw rejected(Status.Code.INVALID_ARGUMENT, "Published spawn selector is invalid");
    }
    if (!workloadNamespace.equals(request.targetNamespace())) {
      throw rejected(Status.Code.PERMISSION_DENIED, "Published spawn namespace is not authorized");
    }

    CompleteLaunchBindingEvidence launchBinding = readLaunchBinding(request);
    AuthoredWorldReleaseAttestationEvidence release = launchBinding.releaseAttestation();
    if (!workloadNamespace.equals(launchBinding.descriptor().targetNamespace())
        || !workloadNamespace.equals(release.targetNamespace())
        || !request.expectedReleaseAttestationDigest().equals(release.evidenceDigest())
        || !AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(
            release.schemaVersion())
        || release.worldStartLocationEvidence() == null) {
      throw rejected(
          Status.Code.FAILED_PRECONDITION,
          "Authenticated Game Design release differs from the exact published selector");
    }

    CaptureRequest captureRequest = captureRequest(release.worldStartLocationEvidence());
    Optional<WorldPublishedStartLocationSource> stored = readInOwnedSnapshot(captureRequest);
    if (stored.isEmpty()) {
      throw rejected(Status.Code.NOT_FOUND, "No exact frozen World source matches the release");
    }

    WorldPublishedStartLocationSource source = stored.orElseThrow();
    WorldCanonicalInstanceTopologyPlan topology;
    try {
      topology = WorldCanonicalInstanceTopologyPlan.create(source.frozenTopology());
      WorldCanonicalInstancePreparation.requireExactReleaseGraph(release, topology);
      WorldPublishedStartLocationEvidence actualSelector =
          actualSelectorEvidence(source, release.worldStartLocationEvidence().request());
      if (!actualSelector.equals(release.worldStartLocationEvidence())) {
        throw new IllegalArgumentException(
            "Frozen selector, Account binding, or APPLIED result differs from Game Design release");
      }
      var projection = projectRequirements(frozenGraph(source.frozenTopology()), topology);
      return new WorldPublishedSpawnRequirementsEvidence(
          request,
          launchBinding,
          source.frozenTopology().captureId(),
          WorldDraftGraphAppliedResult.digest(source.frozenTopology().graphBytes()),
          projection.familyCounts(),
          projection.spawnRequirements(),
          projection.generationRequirements());
    } catch (RuntimeException invalid) {
      throw rejected(
          Status.Code.FAILED_PRECONDITION,
          "Frozen World source differs from authenticated release evidence");
    }
  }

  private CompleteLaunchBindingEvidence readLaunchBinding(Request request) {
    try {
      return gameDesignClient.getComplete(request.launchBindingRequest());
    } catch (RuntimeException unavailable) {
      throw rejected(
          Status.Code.UNAVAILABLE, "Authenticated Game Design release read did not complete");
    }
  }

  private Optional<WorldPublishedStartLocationSource> readInOwnedSnapshot(
      CaptureRequest captureRequest) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setReadOnly(true);
    try {
      return Objects.requireNonNull(
          transaction.execute(status -> publishedSources.readInOwnedSnapshot(captureRequest)),
          "World published source transaction result");
    } catch (WorldDesignPublicationFenceRepository.ConflictException conflict) {
      throw rejected(
          Status.Code.FAILED_PRECONDITION,
          "Frozen World source conflicts with the exact publication selection");
    } catch (TransientDataAccessException unavailable) {
      throw rejected(Status.Code.UNAVAILABLE, "World published source is temporarily unavailable");
    } catch (DataAccessException unavailable) {
      throw rejected(Status.Code.UNAVAILABLE, "World published source is temporarily unavailable");
    } catch (RuntimeException failure) {
      throw rejected(Status.Code.INTERNAL, "World published source read failed");
    }
  }

  static RequirementProjection projectRequirements(
      WorldDraftTopologyInputGraph authoredGraph, WorldCanonicalInstanceTopologyPlan topology) {
    var declaration =
        authoredGraph
            .freshGraphDeclaration()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Published World source lacks its original complete family declaration"));

    Map<UUID, SpawnBindingIntent> spawnsByRevision = new HashMap<>();
    for (SpawnBindingIntent spawn : topology.spawnBindings()) {
      UUID revisionId = UUID.fromString(spawn.source().authoredRevision().getLogicalRevisionId());
      if (spawnsByRevision.putIfAbsent(revisionId, spawn) != null) {
        throw new IllegalArgumentException("Frozen World source repeats a spawn revision");
      }
    }
    Map<UUID, GenerationRuleIntent> generationsByRevision = new HashMap<>();
    for (GenerationRuleIntent generation : topology.generationRules()) {
      UUID revisionId =
          UUID.fromString(generation.source().authoredRevision().getLogicalRevisionId());
      if (generationsByRevision.putIfAbsent(revisionId, generation) != null) {
        throw new IllegalArgumentException("Frozen World source repeats a generation revision");
      }
    }

    List<SpawnRequirement> spawns = new ArrayList<>();
    List<GenerationRequirement> generations = new ArrayList<>();
    for (var node : authoredGraph.nodes()) {
      if (node.mutation().getAggregateType()
          == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING) {
        SpawnBindingIntent intent = spawnsByRevision.remove(node.revisionId());
        if (intent == null || !node.templateId().equals(intent.identity().templateId())) {
          throw new IllegalArgumentException("Spawn projection differs from its original revision");
        }
        var entity = intent.entityReference();
        spawns.add(
            new SpawnRequirement(
                node.revisionOrder(),
                node.revisionId(),
                intent.identity().templateId(),
                new RoomTemplateRef(
                    topology.tenantId(), topology.versionId(), intent.room().templateId()),
                new EntityTemplateReference(
                    entity.kind(), entity.tenantId(), entity.versionId(), entity.templateId()),
                intent.requiredIntent().getSpawnCount(),
                intent.requiredIntent().getRespawnDelaySeconds()));
      } else if (node.mutation().getAggregateType()
          == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE) {
        GenerationRuleIntent intent = generationsByRevision.remove(node.revisionId());
        if (intent == null || !node.templateId().equals(intent.identity().templateId())) {
          throw new IllegalArgumentException(
              "Generation projection differs from its original revision");
        }
        WorldDesignAggregateType scopeFamily =
            switch (intent.scope().family()) {
              case REGION -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION;
              case ZONE -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE;
              default ->
                  throw new IllegalArgumentException(
                      "Generation scope is not an authored REGION or ZONE");
            };
        generations.add(
            new GenerationRequirement(
                node.revisionOrder(),
                node.revisionId(),
                intent.identity().templateId(),
                scopeFamily,
                intent.scope().templateId(),
                intent.requiredIntent().getName(),
                intent.requiredIntent().getValue()));
      }
    }
    if (!spawnsByRevision.isEmpty() || !generationsByRevision.isEmpty()) {
      throw new IllegalArgumentException("Frozen source projections omit original intent rows");
    }

    List<FamilyCount> familyCounts =
        declaration.familyCounts().stream()
            .map(count -> new FamilyCount(count.family(), count.count()))
            .toList();
    return new RequirementProjection(familyCounts, spawns, generations);
  }

  private static WorldDraftTopologyInputGraph frozenGraph(WorldCanonicalFrozenTopology frozen) {
    return frozen.request().plan().graph();
  }

  private static CaptureRequest captureRequest(WorldPublishedStartLocationEvidence selector) {
    var request = selector.request();
    return new CaptureRequest(
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.canonicalVersionId(),
        request.intakeRequestId(),
        request.publicationFence(),
        request.publicationRequestId(),
        request.requestDigest(),
        request.versionStateEpoch(),
        request.publishWorkflowId(),
        request.appliedCommitId(),
        request.contentDigest(),
        request.digestSchemaVersion(),
        request.worldAffectedTuples().stream()
            .map(
                tuple ->
                    new WorldAuthoredGraphSnapshot.OwnedAffectedTuple(
                        tuple.owner(),
                        tuple.aggregateType(),
                        tuple.aggregateId(),
                        tuple.scopeType(),
                        tuple.scopeId(),
                        tuple.expectedEpoch()))
            .toList());
  }

  private static WorldPublishedStartLocationEvidence actualSelectorEvidence(
      WorldPublishedStartLocationSource source,
      WorldPublishedStartLocationEvidence.Request selectorRequest) {
    var receipt = source.selectorReceipt();
    var receiptEvidence =
        new net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence(
            receipt.targetNamespace(),
            receipt.operationId(),
            receipt.requestId(),
            receipt.commitId(),
            receipt.authorizationFenceId(),
            receipt.accountBindingDigest(),
            receipt.bindingDigest(),
            receipt.startLocation(),
            receipt.graphDigest(),
            receipt.receiptDigest());
    return new WorldPublishedStartLocationEvidence(
        selectorRequest,
        receiptEvidence.canonicalBytes(),
        source.appliedResult().application().operation().accountBindingBytes(),
        source.appliedResult().canonicalBytes());
  }

  private void requireAuthenticatedEntityPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || !peer.isService("entity-management-service")
        || !peer.isInNamespace(workloadNamespace)
        || SessionContext.hasAuthenticatedCallerContext()) {
      throw rejected(
          Status.Code.PERMISSION_DENIED,
          "Only the verified same-namespace Entity workload without end-user context is allowed");
    }
  }

  private static ReadRejectedException rejected(Status.Code code, String message) {
    return new ReadRejectedException(code, message);
  }

  static final class ReadRejectedException extends IllegalStateException {
    private final Status.Code code;

    ReadRejectedException(Status.Code code, String message) {
      super(message);
      this.code = code;
    }

    Status.Code code() {
      return code;
    }
  }

  record RequirementProjection(
      List<FamilyCount> familyCounts,
      List<SpawnRequirement> spawnRequirements,
      List<GenerationRequirement> generationRequirements) {
    RequirementProjection {
      familyCounts = List.copyOf(familyCounts);
      spawnRequirements = List.copyOf(spawnRequirements);
      generationRequirements = List.copyOf(generationRequirements);
    }
  }
}
