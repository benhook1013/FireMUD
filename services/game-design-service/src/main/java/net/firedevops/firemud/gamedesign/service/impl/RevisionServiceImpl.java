package net.firedevops.firemud.gamedesign.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamedesign.client.WorldManagementClient;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.RevisionDto;
import net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto;
import net.firedevops.firemud.gamedesign.entity.Game;
import net.firedevops.firemud.gamedesign.entity.Revision;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.RevisionMapper;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class RevisionServiceImpl implements RevisionService {
  private static final Logger logger = LoggingUtil.getLogger(RevisionServiceImpl.class);
  private static final String WORLD_DESIGN_MUTATION_KIND = "WORLD_DESIGN_MUTATION";
  private static final String COMMAND_DEFINITION_KIND = "COMMAND_DEFINITION";

  private final RevisionRepository revisionRepository;
  private final GameRepository gameRepository;
  private final VersionRepository versionRepository;
  private final RevisionMapper revisionMapper;
  private final WorldManagementClient worldManagementClient;
  private final ObjectMapper objectMapper;

  @Override
  @Transactional
  @Timed(value = "gamedesign.revision.save")
  public RevisionDto saveRevision(RevisionDto dto) {
    logger.info(
        "Saving revision for tenant {} version {} kind {}",
        dto.tenantId(),
        dto.versionId(),
        dto.revisionKind());
    Game game =
        Optional.ofNullable(gameRepository.findByTenantId(dto.tenantId()))
            .orElseThrow(() -> new IllegalArgumentException("game not found"));
    Version version =
        versionRepository
            .findByTenantIdAndId(dto.tenantId(), dto.versionId())
            .orElseThrow(() -> new IllegalArgumentException("version not found"));
    if (version.getVersionState() != VersionLifecycleState.DRAFT) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: published versions are immutable");
    }
    validateCommandDefinitionIfPresent(dto);
    AppliedWorldDesignMutationDto appliedWorldDesignMutation =
        applyWorldDesignMutationIfPresent(dto);
    Revision entity = revisionMapper.toEntity(dto);
    entity.setTenantId(game.getTenantId());
    entity.setVersionId(version.getId());
    entity.setData(canonicalRevisionData(dto));
    entity = revisionRepository.save(entity);
    return new RevisionDto(
        entity.getId(),
        entity.getTenantId(),
        entity.getVersionId(),
        entity.getAuthorAccountId(),
        entity.getData(),
        entity.getRevisionKind(),
        entity.getLogicalRevisionId(),
        dto.worldDesignMutation(),
        appliedWorldDesignMutation,
        entity.getCreatedAt());
  }

  private AppliedWorldDesignMutationDto applyWorldDesignMutationIfPresent(RevisionDto dto) {
    if (dto.worldDesignMutation() == null) {
      return null;
    }
    validateWorldMutationRequest(dto);
    return worldManagementClient.applyWorldDesignMutation(
        dto.tenantId(), dto.versionId(), dto.worldDesignMutation());
  }

  private void validateCommandDefinitionIfPresent(RevisionDto dto) {
    if (!COMMAND_DEFINITION_KIND.equals(dto.revisionKind())) {
      return;
    }
    try {
      JsonNode definition = objectMapper.readTree(dto.data());
      if (!definition.isObject() || definition.path("schemaVersion").asInt() != 1) {
        throw invalidCommandDefinition("schemaVersion must be 1");
      }
      requireText(definition, "commandId");
      requireText(definition, "semanticOwner");
      requireText(definition, "executionDiscipline");
      requireEnum(definition, "stageRequirement", "NONE", "LOGIN", "GAMEPLAY");
      requireEnum(definition, "promptPolicy", "NEVER", "WHEN_LOGGED_IN", "WHEN_GAMEPLAY");
      requireEnum(definition, "actionCategory", "GAMEPLAY", "SOCIAL", "META", "ADMIN", "SYSTEM");
      requireTextArray(definition, "aliases");
      if (!definition.path("actionTags").isArray() || !definition.path("effects").isArray()) {
        throw invalidCommandDefinition("actionTags and effects must be arrays");
      }
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      throw invalidCommandDefinition("must be valid JSON");
    }
  }

  private void requireText(JsonNode definition, String field) {
    if (!definition.path(field).isTextual() || definition.path(field).asText().isBlank()) {
      throw invalidCommandDefinition(field + " is required");
    }
  }

  private void requireEnum(JsonNode definition, String field, String... allowed) {
    requireText(definition, field);
    for (String value : allowed) {
      if (value.equals(definition.path(field).asText())) {
        return;
      }
    }
    throw invalidCommandDefinition("unsupported " + field);
  }

  private void requireTextArray(JsonNode definition, String field) {
    if (!definition.path(field).isArray()) {
      throw invalidCommandDefinition(field + " must be an array");
    }
    for (JsonNode value : definition.path(field)) {
      if (!value.isTextual() || value.asText().isBlank()) {
        throw invalidCommandDefinition(field + " entries must be nonblank strings");
      }
    }
  }

  private IllegalArgumentException invalidCommandDefinition(String message) {
    return new IllegalArgumentException("INVALID_ARGUMENT: commandDefinition " + message);
  }

  private void validateWorldMutationRequest(RevisionDto dto) {
    WorldDesignMutationRevisionDto mutation = dto.worldDesignMutation();
    if (!WORLD_DESIGN_MUTATION_KIND.equals(dto.revisionKind())) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation requires revisionKind WORLD_DESIGN_MUTATION");
    }
    if (mutation.logicalRevisionId() == null || mutation.logicalRevisionId().isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation.logicalRevisionId is required");
    }
    if (mutation.commitId() == null || mutation.commitId().isBlank()) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation.commitId is required");
    }
    requireKnownWorldMutationOperation(mutation.operation());
    requireKnownWorldAggregateType(mutation.aggregateType());
    requireKnownWorldScopeTypeIfPresent(mutation.scopeType());
    requireKnownWorldScopePolicyIfPresent(mutation.scopeMutationPolicy());
    requirePayloadMatchesAggregateType(mutation);
  }

  private void requireKnownWorldMutationOperation(String operation) {
    if (!"WORLD_DESIGN_MUTATION_OPERATION_UPSERT".equals(operation)
        && !"WORLD_DESIGN_MUTATION_OPERATION_DELETE".equals(operation)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: unsupported worldDesignMutation.operation");
    }
  }

  private void requireKnownWorldAggregateType(String aggregateType) {
    if (!"WORLD_DESIGN_AGGREGATE_TYPE_REGION".equals(aggregateType)
        && !"WORLD_DESIGN_AGGREGATE_TYPE_ZONE".equals(aggregateType)
        && !"WORLD_DESIGN_AGGREGATE_TYPE_ROOM".equals(aggregateType)
        && !"WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT".equals(aggregateType)
        && !"WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE".equals(aggregateType)
        && !"WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING".equals(aggregateType)
        && !"WORLD_DESIGN_AGGREGATE_TYPE_WORLD_GENERATION_SUBTREE".equals(aggregateType)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: unsupported worldDesignMutation.aggregateType");
    }
  }

  private void requireKnownWorldScopeTypeIfPresent(String scopeType) {
    if (scopeType == null || scopeType.isBlank()) {
      return;
    }
    if (!"WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE".equals(scopeType)
        && !"WORLD_DESIGN_SCOPE_TYPE_ZONE_SUBTREE".equals(scopeType)
        && !"WORLD_DESIGN_SCOPE_TYPE_NEW_EMPTY_REGION".equals(scopeType)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: unsupported worldDesignMutation.scopeType");
    }
  }

  private void requireKnownWorldScopePolicyIfPresent(String scopeMutationPolicy) {
    if (scopeMutationPolicy == null || scopeMutationPolicy.isBlank()) {
      return;
    }
    if (!"WORLD_DESIGN_SCOPE_MUTATION_POLICY_REPLACE_SCOPE".equals(scopeMutationPolicy)
        && !"WORLD_DESIGN_SCOPE_MUTATION_POLICY_SEED_APPEND_ONLY".equals(scopeMutationPolicy)) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: unsupported worldDesignMutation.scopeMutationPolicy");
    }
  }

  private void requirePayloadMatchesAggregateType(WorldDesignMutationRevisionDto mutation) {
    if ("WORLD_DESIGN_MUTATION_OPERATION_DELETE".equals(mutation.operation())) {
      return;
    }
    boolean valid =
        switch (mutation.aggregateType()) {
          case "WORLD_DESIGN_AGGREGATE_TYPE_REGION" -> mutation.region() != null;
          case "WORLD_DESIGN_AGGREGATE_TYPE_ZONE" -> mutation.zone() != null;
          case "WORLD_DESIGN_AGGREGATE_TYPE_ROOM" -> mutation.room() != null;
          case "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT" -> mutation.roomExit() != null;
          case "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE" -> mutation.generationRule() != null;
          case "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING" ->
              mutation.worldEntitySpawnBinding() != null;
          case "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_GENERATION_SUBTREE" ->
              mutation.worldGenerationSubtree() != null;
          default -> false;
        };
    if (!valid) {
      throw new IllegalArgumentException(
          "INVALID_ARGUMENT: worldDesignMutation payload must match aggregateType");
    }
  }

  private String canonicalRevisionData(RevisionDto dto) {
    if (dto.data() != null && !dto.data().isBlank()) {
      return dto.data();
    }
    if (dto.worldDesignMutation() == null) {
      throw new IllegalArgumentException("INVALID_ARGUMENT: revision data is required");
    }
    return "{"
        + "\"revisionKind\":\""
        + escapeJson(dto.revisionKind())
        + "\","
        + "\"versionId\":"
        + dto.versionId()
        + ","
        + "\"logicalRevisionId\":\""
        + escapeJson(dto.worldDesignMutation().logicalRevisionId())
        + "\","
        + "\"commitId\":\""
        + escapeJson(dto.worldDesignMutation().commitId())
        + "\","
        + "\"operation\":\""
        + escapeJson(dto.worldDesignMutation().operation())
        + "\","
        + "\"aggregateType\":\""
        + escapeJson(dto.worldDesignMutation().aggregateType())
        + "\","
        + "\"aggregateId\":\""
        + escapeJson(dto.worldDesignMutation().aggregateId())
        + "\""
        + "}";
  }

  private String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
