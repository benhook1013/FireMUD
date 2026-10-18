package net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
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
import net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.FamilyCount;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.GenerationRequirement;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.Request;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsEvidence.SpawnRequirement;
import net.firedevops.firemud.common.world.WorldPublishedSpawnRequirementsGrpcCodec;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredGraphSnapshot.CaptureRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Family;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalAuthoredGraph.Row;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.FreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTopologyInputGraph.Node;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
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
    try {
      if (!WorldCanonicalFrozenTopology.STATUS.equals(source.frozenTopology().status())) {
        throw new IllegalArgumentException("Frozen World source is not a retained capture");
      }
      requireExactReleaseGraph(release, source.frozenTopology());
      WorldPublishedStartLocationEvidence actualSelector =
          actualSelectorEvidence(source, release.worldStartLocationEvidence().request());
      if (!actualSelector.equals(release.worldStartLocationEvidence())) {
        throw new IllegalArgumentException(
            "Frozen selector, Account binding, or APPLIED result differs from Game Design release");
      }
      var projection =
          projectRequirements(
              source.frozenTopology().request().plan().graph(), source.frozenTopology().graph());
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
    } catch (StatusRuntimeException foreignFailure) {
      Status.Code code = foreignFailure.getStatus().getCode();
      Status.Code localCode =
          switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, RESOURCE_EXHAUSTED, INTERNAL, UNKNOWN ->
                code;
            default -> Status.Code.FAILED_PRECONDITION;
          };
      throw rejected(localCode, "Authenticated Game Design release read could not complete");
    } catch (IllegalArgumentException | IllegalStateException invalidCarrier) {
      throw rejected(Status.Code.INTERNAL, "Authenticated Game Design release evidence is invalid");
    } catch (RuntimeException failure) {
      throw rejected(Status.Code.INTERNAL, "Authenticated Game Design release read failed");
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
    } catch (TransientDataAccessException | RecoverableDataAccessException unavailable) {
      throw rejected(Status.Code.UNAVAILABLE, "World published source is temporarily unavailable");
    } catch (DataAccessException failure) {
      throw rejected(Status.Code.INTERNAL, "World published source read failed");
    } catch (RuntimeException failure) {
      throw rejected(Status.Code.INTERNAL, "World published source read failed");
    }
  }

  static RequirementProjection projectRequirements(
      WorldDraftTopologyInputGraph authoredGraph, WorldCanonicalAuthoredGraph frozenGraph) {
    FreshGraphDeclaration declaration =
        authoredGraph
            .freshGraphDeclaration()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Published World source lacks its original complete family declaration"));
    if (!authoredGraph.tenantId().equals(frozenGraph.tenantId())
        || !authoredGraph.versionId().equals(frozenGraph.versionId())
        || authoredGraph.nodes().size() != frozenGraph.rows().size()) {
      throw new IllegalArgumentException("Frozen World graph differs from its original source");
    }

    Map<UUID, Row> rowsByRevision = new HashMap<>();
    for (Row row : frozenGraph.rows()) {
      if (rowsByRevision.putIfAbsent(row.authored().revisionId(), row) != null) {
        throw new IllegalArgumentException("Frozen World source repeats a revision");
      }
    }

    List<SpawnRequirement> spawns = new ArrayList<>();
    List<GenerationRequirement> generations = new ArrayList<>();
    for (Node node : authoredGraph.nodes()) {
      Row row = rowsByRevision.remove(node.revisionId());
      if (row == null
          || !row.authored().equals(node)
          || !row.template().templateId().equals(node.templateId())
          || familyType(row.template().family()) != node.mutation().getAggregateType()) {
        throw new IllegalArgumentException(
            "Frozen World row differs from its exact original revision");
      }
      var content = row.content();
      if (node.mutation().getAggregateType()
          == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING) {
        if (!content.hasWorldEntitySpawnBinding() || row.entityReference() == null) {
          throw new IllegalArgumentException(
              "Spawn projection differs from its original canonical Entity reference");
        }
        var binding = content.getWorldEntitySpawnBinding();
        var entity = row.entityReference();
        if (entity.kind() != binding.getEntityTemplateType()
            || !entity.templateId().toString().equals(binding.getEntityTemplateId())
            || !entity.tenantId().equals(frozenGraph.tenantId())
            || !entity.versionId().equals(frozenGraph.versionId())) {
          throw new IllegalArgumentException(
              "Spawn projection differs from its exact canonical Entity reference");
        }
        spawns.add(
            new SpawnRequirement(
                node.revisionOrder(),
                node.revisionId(),
                node.templateId(),
                new RoomTemplateRef(
                    frozenGraph.tenantId(),
                    frozenGraph.versionId(),
                    UUID.fromString(binding.getRoomId())),
                new EntityTemplateReference(
                    entity.kind(), entity.tenantId(), entity.versionId(), entity.templateId()),
                binding.getSpawnCount(),
                binding.getRespawnDelaySeconds()));
      } else if (node.mutation().getAggregateType()
          == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE) {
        if (!content.hasGenerationRule()) {
          throw new IllegalArgumentException(
              "Generation projection differs from its original revision");
        }
        WorldDesignAggregateType scopeFamily =
            switch (node.mutation().getScopeType()) {
              case WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE ->
                  WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION;
              case WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE ->
                  WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE;
              default ->
                  throw new IllegalArgumentException(
                      "Generation scope is not an authored REGION or ZONE");
            };
        generations.add(
            new GenerationRequirement(
                node.revisionOrder(),
                node.revisionId(),
                node.templateId(),
                scopeFamily,
                node.scopeId(),
                content.getGenerationRule().getName(),
                content.getGenerationRule().getValue()));
      }
    }
    if (!rowsByRevision.isEmpty()) {
      throw new IllegalArgumentException("Frozen source omits original revision rows");
    }

    List<FamilyCount> familyCounts =
        declaration.familyCounts().stream()
            .map(count -> new FamilyCount(count.family(), count.count()))
            .toList();
    for (FamilyCount count : familyCounts) {
      if (frozenGraph.family(graphFamily(count.family())).size() != count.count()) {
        throw new IllegalArgumentException(
            "Frozen World family count differs from its complete original declaration");
      }
    }
    return new RequirementProjection(familyCounts, spawns, generations);
  }

  private static Family graphFamily(WorldDesignAggregateType family) {
    return switch (family) {
      case WORLD_DESIGN_AGGREGATE_TYPE_REGION -> Family.REGION;
      case WORLD_DESIGN_AGGREGATE_TYPE_ZONE -> Family.ZONE;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM -> Family.ROOM;
      case WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT -> Family.ROOM_EXIT;
      case WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE -> Family.GENERATION_RULE;
      case WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING ->
          Family.WORLD_ENTITY_SPAWN_BINDING;
      default -> throw new IllegalArgumentException("Unsupported World graph family");
    };
  }

  private static WorldDesignAggregateType familyType(Family family) {
    return switch (family) {
      case REGION -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION;
      case ZONE -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE;
      case ROOM -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM;
      case ROOM_EXIT -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT;
      case GENERATION_RULE -> WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE;
      case WORLD_ENTITY_SPAWN_BINDING ->
          WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING;
    };
  }

  private static void requireExactReleaseGraph(
      AuthoredWorldReleaseAttestationEvidence release,
      WorldCanonicalFrozenTopology frozenTopology) {
    var source = frozenTopology.request();
    var plan = source.plan();
    var binding = plan.binding();
    var owner = plan.ownerBinding();
    var freeze = source.freeze();
    var worldParticipants =
        release.participantDigests().stream()
            .filter(participant -> "WORLD_MANAGEMENT".equals(participant.participantKey()))
            .toList();
    if (!release.targetNamespace().equals(owner.targetNamespace())
        || !release.targetNamespace().equals(freeze.targetNamespace())
        || !release.canonicalTenantId().equals(binding.target().canonicalTenantId())
        || !release.canonicalTenantId().equals(plan.graph().tenantId())
        || !release.canonicalTenantId().equals(freeze.canonicalTenantId())
        || !release.canonicalVersionId().equals(binding.target().canonicalVersionId())
        || !release.canonicalVersionId().equals(plan.graph().versionId())
        || !release.canonicalVersionId().equals(freeze.canonicalVersionId())
        || !release.commitId().equals(binding.commitId().toString())
        || !release.commitId().equals(freeze.appliedCommitId())
        || !release.publishWorkflowId().equals(freeze.publishWorkflowId())
        || worldParticipants.size() != 1) {
      throw new IllegalArgumentException(
          "Release differs from the exact selected frozen World graph");
    }
    var world = worldParticipants.getFirst();
    if (!world.scopeValue().equals(Long.toString(owner.gameDesignVersionId()))
        || world.baseVersionIdPresent()
        || world.baseVersionId() != null
        || !world.appliedCommitId().equals(freeze.appliedCommitId())
        || !world.contentDigest().equals(freeze.contentDigest())
        || world.digestSchemaVersion()
            != AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                "WORLD_MANAGEMENT", release.schemaVersion())
        || world.digestSchemaVersion() != freeze.digestSchemaVersion()) {
      throw new IllegalArgumentException(
          "Release World participant differs from its exact frozen checkpoint");
    }
    if (!AuthoredWorldReleaseAttestationEvidence.requiresWorldStartLocationEvidence(
            release.schemaVersion())
        || release.worldStartLocationEvidence() == null) {
      throw new IllegalArgumentException(
          "Published spawn requirements require selected release World selector evidence");
    }

    var selector = release.worldStartLocationEvidence();
    var expected =
        new WorldPublishedStartLocationEvidence.Request(
            freeze.targetNamespace(),
            freeze.canonicalTenantId(),
            freeze.canonicalVersionId(),
            freeze.intakeRequestId(),
            freeze.publicationFence(),
            freeze.publicationRequestId(),
            freeze.requestDigest(),
            freeze.versionStateEpoch(),
            freeze.publishWorkflowId(),
            freeze.appliedCommitId(),
            freeze.contentDigest(),
            freeze.digestSchemaVersion(),
            freeze.suppliedOwnedAffectedTuples().stream()
                .map(
                    tuple ->
                        new WorldPublishedStartLocationEvidence.OwnedAffectedTuple(
                            tuple.owner(),
                            tuple.aggregateType(),
                            tuple.aggregateId(),
                            tuple.scopeType(),
                            tuple.scopeId(),
                            tuple.expectedEpoch()))
                .toList());
    var receipt = WorldDraftStartLocationEvidence.fromStored(selector.selectorReceiptBytes());
    FreshGraphDeclaration declaration =
        plan.graph()
            .freshGraphDeclaration()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Selected World graph has no original fresh declaration"));
    if (!selector.request().equals(expected)
        || !declaration.startLocation().equals(receipt.startLocation())
        || !receipt.bindingDigest().equals(binding.digest())
        || frozenTopology.graph().family(Family.ROOM).stream()
                .filter(
                    room ->
                        room.template()
                            .templateId()
                            .equals(receipt.startLocation().roomTemplateId()))
                .count()
            != 1) {
      throw new IllegalArgumentException(
          "Release selector differs from the exact frozen request, declaration or ROOM");
    }
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
