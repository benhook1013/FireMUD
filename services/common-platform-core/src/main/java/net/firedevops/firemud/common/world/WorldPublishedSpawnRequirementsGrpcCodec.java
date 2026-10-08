package net.firedevops.firemud.common.world;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.gamedesign.v1.GetCompleteLaunchBindingResponse;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.LaunchDescriptor;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsResponse;
import net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedGenerationRequirement;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirement;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirementsEntityTemplateReference;
import net.firedevops.firemud.worldmanagement.v1.WorldPublishedSpawnRequirementsFamilyCount;

/** Closed mapping and exact request-echo validation for published spawn-requirement reads. */
public final class WorldPublishedSpawnRequirementsGrpcCodec {
  private WorldPublishedSpawnRequirementsGrpcCodec() {}

  public static ReadWorldPublishedSpawnRequirementsRequest toRequest(
      WorldPublishedSpawnRequirementsEvidence.Request request) {
    Objects.requireNonNull(request, "request");
    return ReadWorldPublishedSpawnRequirementsRequest.newBuilder()
        .setSchemaVersion(WorldPublishedSpawnRequirementsEvidence.SCHEMA_VERSION)
        .setTargetNamespace(request.targetNamespace())
        .setReadRequestId(request.readRequestId().toString())
        .setLaunchBindingRequest(request.launchBindingRequest())
        .setExpectedReleaseAttestationDigest(request.expectedReleaseAttestationDigest())
        .build();
  }

  /** Parses the closed owner request only after transport authentication by its caller. */
  public static WorldPublishedSpawnRequirementsEvidence.Request fromRequest(
      ReadWorldPublishedSpawnRequirementsRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFieldsRecursively(request, "ReadWorldPublishedSpawnRequirementsRequest");
    if (request.getSchemaVersion() != WorldPublishedSpawnRequirementsEvidence.SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported published spawn-requirements schema version");
    }
    if (!request.hasLaunchBindingRequest()) {
      throw new IllegalArgumentException(
          "Published spawn request lacks its complete binding selector");
    }
    return new WorldPublishedSpawnRequirementsEvidence.Request(
        request.getTargetNamespace(),
        WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
            request.getReadRequestId(), "readRequestId"),
        request.getLaunchBindingRequest(),
        request.getExpectedReleaseAttestationDigest());
  }

  /**
   * Encodes complete immutable evidence without changing its request selector or authored values.
   */
  public static ReadWorldPublishedSpawnRequirementsResponse toResponse(
      WorldPublishedSpawnRequirementsEvidence.Request request,
      WorldPublishedSpawnRequirementsEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    if (!request.equals(evidence.request())) {
      throw new IllegalArgumentException(
          "Published spawn evidence differs from the exact source-read request");
    }
    var builder =
        ReadWorldPublishedSpawnRequirementsResponse.newBuilder()
            .setRequest(toRequest(request))
            .setCompleteLaunchBinding(
                toCompleteLaunchBindingResponse(
                    request.launchBindingRequest(), evidence.launchBinding()))
            .setCaptureId(evidence.captureId().toString())
            .setGraphDigest(evidence.graphDigest());
    for (WorldPublishedSpawnRequirementsEvidence.FamilyCount familyCount :
        evidence.familyCounts()) {
      builder.addFamilyCounts(
          WorldPublishedSpawnRequirementsFamilyCount.newBuilder()
              .setFamily(familyCount.family())
              .setCount(familyCount.count()));
    }
    for (WorldPublishedSpawnRequirementsEvidence.SpawnRequirement spawn :
        evidence.spawnRequirements()) {
      builder.addSpawnRequirements(
          WorldPublishedSpawnRequirement.newBuilder()
              .setRevisionOrder(spawn.revisionOrder())
              .setRevisionId(spawn.revisionId().toString())
              .setSpawnBindingId(spawn.spawnBindingId().toString())
              .setRoomTemplate(
                  RoomTemplateRef.newBuilder()
                      .setTenantId(spawn.roomTemplate().tenantId().toString())
                      .setVersionId(spawn.roomTemplate().versionId().toString())
                      .setRoomTemplateId(spawn.roomTemplate().roomTemplateId().toString()))
              .setEntityTemplate(
                  WorldPublishedSpawnRequirementsEntityTemplateReference.newBuilder()
                      .setKind(spawn.entityTemplate().kind())
                      .setTenantId(spawn.entityTemplate().tenantId().toString())
                      .setVersionId(spawn.entityTemplate().versionId().toString())
                      .setTemplateId(spawn.entityTemplate().templateId().toString()))
              .setSpawnCount(spawn.spawnCount())
              .setRespawnDelaySeconds(spawn.respawnDelaySeconds()));
    }
    for (WorldPublishedSpawnRequirementsEvidence.GenerationRequirement generation :
        evidence.generationRequirements()) {
      builder.addGenerationRequirements(
          WorldPublishedGenerationRequirement.newBuilder()
              .setRevisionOrder(generation.revisionOrder())
              .setRevisionId(generation.revisionId().toString())
              .setRuleTemplateId(generation.ruleTemplateId().toString())
              .setScopeFamily(generation.scopeFamily())
              .setScopeId(generation.scopeId().toString())
              .setName(generation.name())
              .setValue(generation.value()));
    }
    return builder.build();
  }

  /** Validates the complete source response, exact request echo and complete Game Design pair. */
  public static WorldPublishedSpawnRequirementsEvidence fromResponse(
      WorldPublishedSpawnRequirementsEvidence.Request request,
      ReadWorldPublishedSpawnRequirementsResponse response) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFieldsRecursively(response, "ReadWorldPublishedSpawnRequirementsResponse");
    if (response.hasError()) {
      throw new IllegalArgumentException("World rejected the published spawn-requirements read");
    }
    if (!response.hasRequest() || !response.hasCompleteLaunchBinding()) {
      throw new IllegalArgumentException(
          "Published spawn response lacks its exact request or complete launch binding");
    }
    WorldPublishedSpawnRequirementsEvidence.Request echoed = fromRequest(response.getRequest());
    if (!request.equals(echoed)) {
      throw new IllegalArgumentException(
          "Published spawn response changed the exact source-read request");
    }
    CompleteLaunchBindingEvidence launchBinding =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(
            request.launchBindingRequest(), response.getCompleteLaunchBinding());

    List<WorldPublishedSpawnRequirementsEvidence.FamilyCount> familyCounts =
        new ArrayList<>(response.getFamilyCountsCount());
    for (WorldPublishedSpawnRequirementsFamilyCount count : response.getFamilyCountsList()) {
      requireNoUnknownFieldsRecursively(count, "WorldPublishedSpawnRequirementsFamilyCount");
      if (!count.hasCount()) {
        throw new IllegalArgumentException(
            "Published World family count is not explicitly present");
      }
      familyCounts.add(
          new WorldPublishedSpawnRequirementsEvidence.FamilyCount(
              count.getFamily(), count.getCount()));
    }

    List<WorldPublishedSpawnRequirementsEvidence.SpawnRequirement> spawns =
        new ArrayList<>(response.getSpawnRequirementsCount());
    for (WorldPublishedSpawnRequirement spawn : response.getSpawnRequirementsList()) {
      requireNoUnknownFieldsRecursively(spawn, "WorldPublishedSpawnRequirement");
      if (!spawn.hasRoomTemplate() || !spawn.hasEntityTemplate()) {
        throw new IllegalArgumentException("Published spawn requirement lacks typed references");
      }
      var room = spawn.getRoomTemplate();
      var entity = spawn.getEntityTemplate();
      spawns.add(
          new WorldPublishedSpawnRequirementsEvidence.SpawnRequirement(
              spawn.getRevisionOrder(),
              WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                  spawn.getRevisionId(), "revisionId"),
              WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                  spawn.getSpawnBindingId(), "spawnBindingId"),
              new net.firedevops.firemud.common.world.RoomTemplateRef(
                  WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                      room.getTenantId(), "room tenantId"),
                  WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                      room.getVersionId(), "room versionId"),
                  WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                      room.getRoomTemplateId(), "roomTemplateId")),
              new WorldPublishedSpawnRequirementsEvidence.EntityTemplateReference(
                  entity.getKind(),
                  WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                      entity.getTenantId(), "entity tenantId"),
                  WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                      entity.getVersionId(), "entity versionId"),
                  WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                      entity.getTemplateId(), "entity templateId")),
              spawn.getSpawnCount(),
              spawn.getRespawnDelaySeconds()));
    }

    List<WorldPublishedSpawnRequirementsEvidence.GenerationRequirement> generations =
        new ArrayList<>(response.getGenerationRequirementsCount());
    for (WorldPublishedGenerationRequirement generation :
        response.getGenerationRequirementsList()) {
      requireNoUnknownFieldsRecursively(generation, "WorldPublishedGenerationRequirement");
      generations.add(
          new WorldPublishedSpawnRequirementsEvidence.GenerationRequirement(
              generation.getRevisionOrder(),
              WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                  generation.getRevisionId(), "revisionId"),
              WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                  generation.getRuleTemplateId(), "ruleTemplateId"),
              generation.getScopeFamily(),
              WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
                  generation.getScopeId(), "scopeId"),
              generation.getName(),
              generation.getValue()));
    }
    return new WorldPublishedSpawnRequirementsEvidence(
        request,
        launchBinding,
        WorldPublishedSpawnRequirementsEvidence.parseCanonicalUuid(
            response.getCaptureId(), "captureId"),
        response.getGraphDigest(),
        familyCounts,
        spawns,
        generations);
  }

  /** Encodes a parsed launch pair for the outer response while validating its exact selector. */
  public static GetCompleteLaunchBindingResponse toCompleteLaunchBindingResponse(
      GetLaunchDescriptorRequest request, CompleteLaunchBindingEvidence evidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(evidence, "evidence");
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence.descriptor();
    var bindingBuilder =
        net.firedevops.firemud.gamedesign.v1.AuthoredWorldLaunchDescriptorEvidence.newBuilder()
            .setSchemaVersion(descriptor.schemaVersion())
            .setTargetNamespace(descriptor.targetNamespace())
            .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
            .setCanonicalTenantId(descriptor.canonicalTenantId().toString())
            .setWorldSlug(descriptor.worldSlug())
            .setAuthoredWorldSourceOperationId(
                descriptor.authoredWorldSourceOperationId().toString())
            .setAuthoredWorldSourceEvidenceDigest(descriptor.authoredWorldSourceEvidenceDigest())
            .setGameTemplateId(descriptor.gameTemplateId())
            .setRequestDigest(descriptor.requestDigest())
            .setLaunchDescriptorId(descriptor.launchDescriptorId())
            .setVersionId(descriptor.versionId())
            .setRuntimeFlagsJson(descriptor.runtimeFlagsJson())
            .setGenerationConfigRevision(descriptor.generationConfigRevision())
            .setVersionStateEpoch(descriptor.versionStateEpoch())
            .setReleaseBundleId(descriptor.releaseBundleId())
            .setPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef())
            .setResultDigest(descriptor.resultDigest());
    if (descriptor.requestedScriptPatchVersionPresent()) {
      bindingBuilder.setRequestedScriptPatchVersion(descriptor.requestedScriptPatchVersion());
    }
    if (descriptor.sourceVersionIdPresent()) {
      bindingBuilder.setSourceVersionId(descriptor.sourceVersionId());
    }
    if (descriptor.targetVersionIdPresent()) {
      bindingBuilder.setTargetVersionId(descriptor.targetVersionId());
    }
    if (descriptor.requestedRuntimeFlagsJsonPresent()) {
      bindingBuilder.setRequestedRuntimeFlagsJson(descriptor.requestedRuntimeFlagsJson());
    }
    if (descriptor.scriptPatchVersionPresent()) {
      bindingBuilder.setScriptPatchVersion(descriptor.scriptPatchVersion());
    }
    if (descriptor.remapSetIdPresent()) {
      bindingBuilder.setRemapSetId(descriptor.remapSetId());
    }

    var descriptorBuilder =
        LaunchDescriptor.newBuilder()
            .setLaunchDescriptorId(descriptor.launchDescriptorId())
            .setCanonicalTenantId(descriptor.canonicalTenantId().toString())
            .setGameTemplateId(descriptor.gameTemplateId())
            .setControlPlaneRequestId(descriptor.controlPlaneRequestId())
            .setVersionId(descriptor.versionId())
            .setRuntimeFlagsJson(descriptor.runtimeFlagsJson())
            .setGenerationConfigRevision(descriptor.generationConfigRevision())
            .setVersionStateEpoch(descriptor.versionStateEpoch())
            .setReleaseBundleId(descriptor.releaseBundleId())
            .setPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef())
            .setAuthoredWorldBinding(bindingBuilder.build());
    if (descriptor.scriptPatchVersionPresent()) {
      descriptorBuilder.setScriptPatchVersion(descriptor.scriptPatchVersion());
    }
    if (descriptor.remapSetIdPresent()) {
      descriptorBuilder.setRemapSetId(descriptor.remapSetId());
    }

    GetCompleteLaunchBindingResponse response =
        GetCompleteLaunchBindingResponse.newBuilder()
            .setRequestId(request.getRequestId())
            .setLaunchDescriptor(descriptorBuilder)
            .setReleaseAttestation(
                AuthoredWorldLaunchDescriptorGrpcCodec.toReleaseAttestation(
                    evidence.releaseAttestation()))
            .build();
    CompleteLaunchBindingEvidence decoded =
        AuthoredWorldLaunchDescriptorGrpcCodec.fromCompleteResponse(request, response);
    if (!decoded.equals(evidence)) {
      throw new IllegalArgumentException(
          "Encoded complete launch binding differs from its immutable evidence pair");
    }
    return response;
  }

  private static void requireNoUnknownFieldsRecursively(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (var entry : message.getAllFields().entrySet()) {
      FieldDescriptor field = entry.getKey();
      if (field.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
        continue;
      }
      Object value = entry.getValue();
      if (field.isRepeated()) {
        for (Object element : (List<?>) value) {
          requireNoUnknownFieldsRecursively((Message) element, field.getName());
        }
      } else {
        requireNoUnknownFieldsRecursively((Message) value, field.getName());
      }
    }
  }
}
