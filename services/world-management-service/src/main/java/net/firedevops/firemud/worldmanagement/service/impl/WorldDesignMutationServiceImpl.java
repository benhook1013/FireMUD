package net.firedevops.firemud.worldmanagement.service.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import net.firedevops.firemud.worldmanagement.client.EntityManagementClient;
import net.firedevops.firemud.worldmanagement.client.GameDesignClient;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationResultDto;
import net.firedevops.firemud.worldmanagement.entity.GenerationRule;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignAggregateEpoch;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignRevisionLedger;
import net.firedevops.firemud.worldmanagement.entity.WorldDesignScopeEpoch;
import net.firedevops.firemud.worldmanagement.entity.WorldEntitySpawnBinding;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignAggregateEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignRevisionLedgerRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldDesignScopeEpochRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldDesignMutationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorldDesignMutationServiceImpl implements WorldDesignMutationService {
  private static final String RESULT_APPLIED = "APPLIED";
  private static final String RESULT_NOOP = "NO_OP_ALREADY_APPLIED";
  private static final String OPERATION_UPSERT = "UPSERT";
  private static final String OPERATION_DELETE = "DELETE";
  private static final String SCOPE_POLICY_REPLACE_SCOPE = "REPLACE_SCOPE";
  private static final String SCOPE_POLICY_SEED_APPEND_ONLY = "SEED_APPEND_ONLY";
  private static final String SCOPE_TYPE_REGION_SUBTREE = "REGION_SUBTREE";
  private static final String SCOPE_TYPE_ZONE_SUBTREE = "ZONE_SUBTREE";
  private static final String SCOPE_TYPE_NEW_EMPTY_REGION = "NEW_EMPTY_REGION";

  private final RegionRepository regionRepository;
  private final ZoneRepository zoneRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;
  private final GenerationRuleRepository generationRuleRepository;
  private final WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository;
  private final WorldDesignRevisionLedgerRepository ledgerRepository;
  private final WorldDesignAggregateEpochRepository aggregateEpochRepository;
  private final WorldDesignScopeEpochRepository scopeEpochRepository;
  private final GameDesignClient gameDesignClient;
  private final EntityManagementClient entityManagementClient;

  @Override
  @Transactional
  public WorldDesignMutationResultDto applyMutation(WorldDesignMutationRequestDto request) {
    validateRequest(request);
    requireDraftVersion(request.tenantId(), request.versionId());

    String requestedAggregateId = normalizeId(request.aggregateId());
    var existingLedger =
        ledgerRepository
            .findByTenantIdAndVersionIdAndCommitIdAndRevisionIdAndOperationTypeAndAggregateTypeAndRequestedAggregateId(
                request.tenantId(),
                request.versionId(),
                request.commitId(),
                request.revisionId(),
                request.operationType(),
                request.aggregateType(),
                requestedAggregateId);
    if (existingLedger.isPresent()) {
      WorldDesignRevisionLedger ledger = existingLedger.get();
      return new WorldDesignMutationResultDto(
          RESULT_NOOP,
          ledger.getTenantId(),
          ledger.getVersionId(),
          ledger.getAppliedAggregateId(),
          ledger.getAggregateEpochAfter(),
          ledger.getScopeEpochAfter());
    }

    validateExpectedScopeEpochBeforeMutation(request);
    validateExpectedAggregateEpochBeforeMutation(request);

    Long aggregateId = applyAggregateMutation(request);
    WorldDesignAggregateEpoch aggregateEpoch =
        aggregateEpochRepository
            .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
                request.tenantId(), request.versionId(), request.aggregateType(), aggregateId)
            .orElseGet(() -> newAggregateEpoch(request, aggregateId));
    aggregateEpoch.setDraftRevisionEpoch(aggregateEpoch.getDraftRevisionEpoch() + 1L);
    aggregateEpoch.setUpdatedAt(LocalDateTime.now());
    aggregateEpochRepository.save(aggregateEpoch);

    Long scopeEpochAfter = advanceScopeEpoch(request);

    WorldDesignRevisionLedger ledger = new WorldDesignRevisionLedger();
    ledger.setTenantId(request.tenantId());
    ledger.setVersionId(request.versionId());
    ledger.setCommitId(request.commitId());
    ledger.setRevisionId(request.revisionId());
    ledger.setOperationType(request.operationType());
    ledger.setAggregateType(request.aggregateType());
    ledger.setRequestedAggregateId(requestedAggregateId);
    ledger.setAppliedAggregateId(aggregateId);
    ledger.setResult(RESULT_APPLIED);
    ledger.setAggregateEpochAfter(aggregateEpoch.getDraftRevisionEpoch());
    ledger.setScopeEpochAfter(scopeEpochAfter);
    ledgerRepository.save(ledger);

    return new WorldDesignMutationResultDto(
        RESULT_APPLIED,
        request.tenantId(),
        request.versionId(),
        aggregateId,
        aggregateEpoch.getDraftRevisionEpoch(),
        scopeEpochAfter);
  }

  private Long applyAggregateMutation(WorldDesignMutationRequestDto request) {
    return switch (request.aggregateType()) {
      case "REGION" -> applyRegion(request);
      case "ZONE" -> applyZone(request);
      case "ROOM" -> applyRoom(request);
      case "ROOM_EXIT" -> applyRoomExit(request);
      case "GENERATION_RULE" -> applyGenerationRule(request);
      case "WORLD_ENTITY_SPAWN_BINDING" -> applyWorldEntitySpawnBinding(request);
      case "WORLD_GENERATION_SUBTREE" -> applyWorldGenerationSubtree(request);
      default -> throw appError("UNSUPPORTED_SCOPE", "unsupported aggregate type");
    };
  }

  private Long applyRegion(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      failIfSeedAppendOnlyDelete(request);
      Region region = existingRegion(request);
      validateRegionWithinScope(request, region);
      regionRepository.delete(region);
      return region.getId();
    }
    var payload = request.region();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "region payload is required");
    }
    failIfSeedAppendOnlyUpdate(request);
    Region region =
        StringUtils.hasText(request.aggregateId()) ? existingRegion(request) : new Region();
    region.setTenantId(request.tenantId());
    region.setVersionId(request.versionId());
    region.setName(requireText(payload.name(), "region.name"));
    region.setWeather(blankToNull(payload.weather()));
    region.setShardId(payload.shardId());
    region.setGenerationSeed(payload.generationSeed());
    region.setGeneratorType(blankToNull(payload.generatorType()));
    region.setGeneratorParams(blankToNull(payload.generatorParams()));
    region.setSpacingMultiplier(
        payload.spacingMultiplier() == 0.0d ? 1.0d : payload.spacingMultiplier());
    Region saved = regionRepository.save(region);
    validateRegionWithinScope(request, saved);
    return saved.getId();
  }

  private Long applyZone(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      failIfSeedAppendOnlyDelete(request);
      Zone zone = existingZone(request);
      validateZoneWithinScope(request, zone);
      zoneRepository.delete(zone);
      return zone.getId();
    }
    var payload = request.zone();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "zone payload is required");
    }
    failIfSeedAppendOnlyUpdate(request);
    Zone zone = StringUtils.hasText(request.aggregateId()) ? existingZone(request) : new Zone();
    zone.setTenantId(request.tenantId());
    zone.setVersionId(request.versionId());
    zone.setName(requireText(payload.name(), "zone.name"));
    zone.setRegion(
        regionRepository
            .findByTenantIdAndVersionIdAndId(
                request.tenantId(),
                request.versionId(),
                parseId(payload.regionId(), "zone.region_id"))
            .orElseThrow(() -> appError("UNRESOLVED_REFERENCE", "region not found")));
    Zone saved = zoneRepository.save(zone);
    validateZoneWithinScope(request, saved);
    return saved.getId();
  }

  private Long applyRoom(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      failIfSeedAppendOnlyDelete(request);
      Room room = existingRoom(request);
      validateRoomWithinScope(request, room);
      roomRepository.delete(room);
      return room.getId();
    }
    var payload = request.room();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "room payload is required");
    }
    failIfSeedAppendOnlyUpdate(request);
    Room room = StringUtils.hasText(request.aggregateId()) ? existingRoom(request) : new Room();
    room.setTenantId(request.tenantId());
    room.setVersionId(request.versionId());
    room.setName(requireText(payload.name(), "room.name"));
    room.setDescription(blankToNull(payload.description()));
    room.setNameLocalizedVariantsJson(blankToNull(payload.nameLocalizedVariantsJson()));
    room.setDescriptionLocalizedVariantsJson(
        blankToNull(payload.descriptionLocalizedVariantsJson()));
    room.setZone(
        zoneRepository
            .findByTenantIdAndVersionIdAndId(
                request.tenantId(), request.versionId(), parseId(payload.zoneId(), "room.zone_id"))
            .orElseThrow(() -> appError("UNRESOLVED_REFERENCE", "zone not found")));
    validateRoomWithinScope(request, room);
    Room saved = roomRepository.save(room);
    return saved.getId();
  }

  private Long applyRoomExit(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      failIfSeedAppendOnlyDelete(request);
      RoomExit exit = existingRoomExit(request);
      validateRoomExitWithinScope(request, exit);
      roomExitRepository.delete(exit);
      return exit.getId();
    }
    var payload = request.roomExit();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "room_exit payload is required");
    }
    failIfSeedAppendOnlyUpdate(request);
    RoomExit exit =
        StringUtils.hasText(request.aggregateId()) ? existingRoomExit(request) : new RoomExit();
    exit.setTenantId(request.tenantId());
    exit.setVersionId(request.versionId());
    exit.setDirection(requireText(payload.direction(), "room_exit.direction"));
    exit.setCost(payload.cost() <= 0 ? 1 : payload.cost());
    exit.setFromRoom(
        roomRepository
            .findByTenantIdAndVersionIdAndId(
                request.tenantId(),
                request.versionId(),
                parseId(payload.fromRoomId(), "room_exit.from_room_id"))
            .orElseThrow(() -> appError("UNRESOLVED_REFERENCE", "from room not found")));
    exit.setToRoom(
        roomRepository
            .findByTenantIdAndVersionIdAndId(
                request.tenantId(),
                request.versionId(),
                parseId(payload.toRoomId(), "room_exit.to_room_id"))
            .orElseThrow(() -> appError("UNRESOLVED_REFERENCE", "to room not found")));
    validateRoomExitWithinScope(request, exit);
    RoomExit saved = roomExitRepository.save(exit);
    return saved.getId();
  }

  private Long applyGenerationRule(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      failIfSeedAppendOnlyDelete(request);
      GenerationRule rule = existingGenerationRule(request);
      validateGenerationRuleScope(request, rule);
      generationRuleRepository.delete(rule);
      return rule.getId();
    }
    var payload = request.generationRule();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "generation_rule payload is required");
    }
    String ruleName = requireText(payload.name(), "generation_rule.name");
    validateGenerationRuleDeclaredScope(request);
    GenerationRule rule = resolveGenerationRuleForMutation(request, ruleName);
    if (isSeedAppendOnlyPolicy(request) && rule.getId() != null) {
      throw appError("OUT_OF_SYNC", "SEED_APPEND_ONLY cannot rewrite an existing generation rule");
    }
    if (isReplaceScopePolicy(request)) {
      deleteExistingGenerationRulesInScope(request, rule);
    }
    rule.setTenantId(request.tenantId());
    rule.setVersionId(request.versionId());
    rule.setName(ruleName);
    rule.setScopeType(blankToNull(request.scopeType()));
    rule.setScopeId(blankToNull(request.scopeId()));
    rule.setValue(blankToNull(payload.value()));
    return generationRuleRepository.save(rule).getId();
  }

  private Long applyWorldEntitySpawnBinding(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      failIfSeedAppendOnlyDelete(request);
      WorldEntitySpawnBinding binding = existingWorldEntitySpawnBinding(request);
      worldEntitySpawnBindingRepository.delete(binding);
      return binding.getId();
    }
    var payload = request.worldEntitySpawnBinding();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "world_entity_spawn_binding payload is required");
    }
    long roomId = parseId(payload.roomId(), "world_entity_spawn_binding.room_id");
    Room room =
        roomRepository
            .findByTenantIdAndVersionIdAndId(request.tenantId(), request.versionId(), roomId)
            .orElseThrow(() -> appError("UNRESOLVED_REFERENCE", "room not found"));
    String entityTemplateType =
        requireText(
            payload.entityTemplateType(), "world_entity_spawn_binding.entity_template_type");
    long entityTemplateId =
        parseId(payload.entityTemplateId(), "world_entity_spawn_binding.entity_template_id");
    if (!entityManagementClient.validateEntityTemplateReference(
        request.tenantId(), request.versionId(), entityTemplateType, entityTemplateId)) {
      throw appError("UNRESOLVED_REFERENCE", "entity template not found");
    }
    WorldEntitySpawnBinding binding =
        StringUtils.hasText(request.aggregateId())
            ? existingWorldEntitySpawnBinding(request)
            : worldEntitySpawnBindingRepository
                .findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
                    request.tenantId(),
                    request.versionId(),
                    roomId,
                    entityTemplateType,
                    entityTemplateId)
                .orElseGet(WorldEntitySpawnBinding::new);
    if (binding.getId() != null && !StringUtils.hasText(request.aggregateId())) {
      validateExpectedAggregateEpoch(request, binding.getId());
    }
    if (isSeedAppendOnlyPolicy(request) && binding.getId() != null) {
      throw appError("OUT_OF_SYNC", "SEED_APPEND_ONLY cannot rewrite an existing spawn binding");
    }
    if (isReplaceScopePolicy(request)) {
      deleteExistingSpawnBindingsInScope(request, room);
    }
    binding.setTenantId(request.tenantId());
    binding.setVersionId(request.versionId());
    binding.setRoom(room);
    binding.setEntityTemplateType(entityTemplateType);
    binding.setEntityTemplateId(entityTemplateId);
    binding.setSpawnCount(payload.spawnCount() <= 0 ? 1 : payload.spawnCount());
    binding.setRespawnDelaySeconds(Math.max(payload.respawnDelaySeconds(), 0));
    return worldEntitySpawnBindingRepository.save(binding).getId();
  }

  private Long applyWorldGenerationSubtree(WorldDesignMutationRequestDto request) {
    if (OPERATION_DELETE.equals(request.operationType())) {
      throw appError("UNSUPPORTED_SCOPE", "WORLD_GENERATION_SUBTREE supports only upsert");
    }
    var payload = request.worldGenerationSubtree();
    if (payload == null) {
      throw appError("INVALID_ARGUMENT", "world_generation_subtree payload is required");
    }
    if (!StringUtils.hasText(request.scopeType()) || !StringUtils.hasText(request.scopeId())) {
      throw appError("INVALID_ARGUMENT", "WORLD_GENERATION_SUBTREE requires a declared scope");
    }
    validateGenerationRuleDeclaredScope(request);
    if (isReplaceScopePolicy(request)) {
      deleteExistingGeneratedRowsInScope(request);
    }
    applySubtreeGenerationRules(request, payload);
    Map<String, Room> createdRooms = applySubtreeRooms(request, payload);
    applySubtreeRoomExits(request, payload, createdRooms);
    applySubtreeSpawnBindings(request, payload, createdRooms);
    return syntheticScopeAggregateId(request);
  }

  private void validateExpectedAggregateEpochBeforeMutation(WorldDesignMutationRequestDto request) {
    if (StringUtils.hasText(request.aggregateId())) {
      validateExpectedAggregateEpoch(request, parseId(request.aggregateId(), "aggregate_id"));
      return;
    }
    if (request.expectedDraftRevisionEpoch() != 0L) {
      throw appError(
          "DRAFT_WRITE_CONFLICT",
          "expected Draft aggregate epoch "
              + request.expectedDraftRevisionEpoch()
              + " but found 0");
    }
  }

  private void validateExpectedAggregateEpoch(
      WorldDesignMutationRequestDto request, Long aggregateId) {
    Long currentEpoch =
        aggregateEpochRepository
            .findByTenantIdAndVersionIdAndAggregateTypeAndAggregateId(
                request.tenantId(), request.versionId(), request.aggregateType(), aggregateId)
            .map(WorldDesignAggregateEpoch::getDraftRevisionEpoch)
            .orElse(0L);
    if (!currentEpoch.equals(request.expectedDraftRevisionEpoch())) {
      throw appError(
          "DRAFT_WRITE_CONFLICT",
          "expected Draft aggregate epoch "
              + request.expectedDraftRevisionEpoch()
              + " but found "
              + currentEpoch);
    }
  }

  private void validateExpectedScopeEpochBeforeMutation(WorldDesignMutationRequestDto request) {
    if (!StringUtils.hasText(request.scopeType()) && !StringUtils.hasText(request.scopeId())) {
      return;
    }
    String scopeType = requireText(request.scopeType(), "scope_type");
    String scopeId = requireText(request.scopeId(), "scope_id");
    Long currentEpoch =
        scopeEpochRepository
            .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                request.tenantId(), request.versionId(), scopeType, scopeId)
            .map(WorldDesignScopeEpoch::getDraftScopeRevisionEpoch)
            .orElse(0L);
    if (!currentEpoch.equals(request.expectedDraftScopeRevisionEpoch())) {
      throw appError(
          "DRAFT_WRITE_CONFLICT",
          "expected Draft scope epoch "
              + request.expectedDraftScopeRevisionEpoch()
              + " but found "
              + currentEpoch);
    }
  }

  private void deleteExistingSpawnBindingsInScope(
      WorldDesignMutationRequestDto request, Room room) {
    String scopeType = requireText(request.scopeType(), "scope_type");
    long scopeId = parseId(request.scopeId(), "scope_id");
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(scopeType)) {
      throw appError(
          "UNSUPPORTED_SCOPE",
          "REPLACE_SCOPE for world entity spawn bindings does not support NEW_EMPTY_REGION");
    }
    if (!roomWithinScope(room, scopeType, scopeId)) {
      throw appError("OUT_OF_SYNC", "spawn binding room is outside the declared scope");
    }
    List<WorldEntitySpawnBinding> inScopeBindings =
        worldEntitySpawnBindingRepository
            .findByTenantIdAndVersionIdOrderByIdAsc(request.tenantId(), request.versionId())
            .stream()
            .filter(binding -> bindingWithinScope(binding, scopeType, scopeId))
            .filter(
                binding ->
                    !StringUtils.hasText(request.aggregateId())
                        || !binding.getId().equals(parseId(request.aggregateId(), "aggregate_id")))
            .toList();
    if (!inScopeBindings.isEmpty()) {
      worldEntitySpawnBindingRepository.deleteAll(inScopeBindings);
    }
  }

  private GenerationRule resolveGenerationRuleForMutation(
      WorldDesignMutationRequestDto request, String ruleName) {
    if (StringUtils.hasText(request.aggregateId())) {
      GenerationRule existing = existingGenerationRule(request);
      validateGenerationRuleScope(request, existing);
      return existing;
    }
    if (StringUtils.hasText(request.scopeType())) {
      return generationRuleRepository
          .findByTenantIdAndVersionIdAndScopeTypeAndScopeIdAndName(
              request.tenantId(),
              request.versionId(),
              request.scopeType(),
              request.scopeId(),
              ruleName)
          .map(
              existing -> {
                validateExpectedAggregateEpoch(request, existing.getId());
                return existing;
              })
          .orElseGet(GenerationRule::new);
    }
    failIfSeedAppendOnlyUpdate(request);
    return new GenerationRule();
  }

  private void deleteExistingGenerationRulesInScope(
      WorldDesignMutationRequestDto request, GenerationRule selectedRule) {
    String scopeType = requireText(request.scopeType(), "scope_type");
    String scopeId = requireText(request.scopeId(), "scope_id");
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(scopeType)) {
      throw appError(
          "UNSUPPORTED_SCOPE",
          "REPLACE_SCOPE for generation rules does not support NEW_EMPTY_REGION");
    }
    List<GenerationRule> inScopeRules =
        generationRuleRepository
            .findByTenantIdAndVersionIdAndScopeTypeAndScopeIdOrderByIdAsc(
                request.tenantId(), request.versionId(), scopeType, scopeId)
            .stream()
            .filter(
                rule -> selectedRule.getId() == null || !rule.getId().equals(selectedRule.getId()))
            .toList();
    if (!inScopeRules.isEmpty()) {
      generationRuleRepository.deleteAll(inScopeRules);
    }
  }

  private void validateGenerationRuleScope(
      WorldDesignMutationRequestDto request, GenerationRule rule) {
    if (!StringUtils.hasText(request.scopeType())) {
      if (StringUtils.hasText(rule.getScopeType()) || StringUtils.hasText(rule.getScopeId())) {
        throw appError("OUT_OF_SYNC", "generation rule requires its declared scope");
      }
      return;
    }
    if (!request.scopeType().equals(rule.getScopeType())
        || !request.scopeId().equals(rule.getScopeId())) {
      throw appError("OUT_OF_SYNC", "generation rule is outside the declared scope");
    }
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(request.scopeType())) {
      throw appError(
          "UNSUPPORTED_SCOPE", "generation rule mutation does not support NEW_EMPTY_REGION");
    }
  }

  private void validateGenerationRuleDeclaredScope(WorldDesignMutationRequestDto request) {
    if (!StringUtils.hasText(request.scopeType())) {
      return;
    }
    long scopeId = parseId(request.scopeId(), "scope_id");
    switch (request.scopeType()) {
      case SCOPE_TYPE_REGION_SUBTREE ->
          regionRepository
              .findByTenantIdAndVersionIdAndId(request.tenantId(), request.versionId(), scopeId)
              .orElseThrow(
                  () -> appError("UNRESOLVED_REFERENCE", "generation rule region scope not found"));
      case SCOPE_TYPE_ZONE_SUBTREE ->
          zoneRepository
              .findByTenantIdAndVersionIdAndId(request.tenantId(), request.versionId(), scopeId)
              .orElseThrow(
                  () -> appError("UNRESOLVED_REFERENCE", "generation rule zone scope not found"));
      case SCOPE_TYPE_NEW_EMPTY_REGION ->
          throw appError(
              "UNSUPPORTED_SCOPE", "generation rule mutation does not support NEW_EMPTY_REGION");
      default -> throw appError("INVALID_ARGUMENT", "unsupported scope_type");
    }
  }

  private void deleteExistingGeneratedRowsInScope(WorldDesignMutationRequestDto request) {
    String scopeType = requireText(request.scopeType(), "scope_type");
    long scopeId = parseId(request.scopeId(), "scope_id");
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(scopeType)) {
      throw appError(
          "UNSUPPORTED_SCOPE",
          "REPLACE_SCOPE for generated subtrees does not support NEW_EMPTY_REGION");
    }
    List<WorldEntitySpawnBinding> inScopeBindings =
        worldEntitySpawnBindingRepository
            .findByTenantIdAndVersionIdOrderByIdAsc(request.tenantId(), request.versionId())
            .stream()
            .filter(binding -> bindingWithinScope(binding, scopeType, scopeId))
            .toList();
    if (!inScopeBindings.isEmpty()) {
      worldEntitySpawnBindingRepository.deleteAll(inScopeBindings);
    }
    List<RoomExit> inScopeExits =
        roomExitRepository
            .findByTenantIdAndVersionIdOrderByIdAsc(request.tenantId(), request.versionId())
            .stream()
            .filter(
                exit ->
                    roomWithinScope(exit.getFromRoom(), scopeType, scopeId)
                        || roomWithinScope(exit.getToRoom(), scopeType, scopeId))
            .toList();
    if (!inScopeExits.isEmpty()) {
      roomExitRepository.deleteAll(inScopeExits);
    }
    List<Room> inScopeRooms =
        roomRepository
            .findByTenantIdAndVersionIdOrderByIdAsc(request.tenantId(), request.versionId())
            .stream()
            .filter(room -> roomWithinScope(room, scopeType, scopeId))
            .toList();
    if (!inScopeRooms.isEmpty()) {
      roomRepository.deleteAll(inScopeRooms);
    }
    deleteExistingGenerationRulesInScope(request, new GenerationRule());
  }

  private void applySubtreeGenerationRules(
      WorldDesignMutationRequestDto request,
      WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto payload) {
    for (WorldDesignMutationRequestDto.GenerationRuleMutationDto rulePayload :
        nullToEmpty(payload.generationRules())) {
      String ruleName = requireText(rulePayload.name(), "generation_rule.name");
      GenerationRule rule =
          generationRuleRepository
              .findByTenantIdAndVersionIdAndScopeTypeAndScopeIdAndName(
                  request.tenantId(),
                  request.versionId(),
                  request.scopeType(),
                  request.scopeId(),
                  ruleName)
              .orElseGet(GenerationRule::new);
      if (isSeedAppendOnlyPolicy(request) && rule.getId() != null) {
        throw appError(
            "OUT_OF_SYNC", "SEED_APPEND_ONLY cannot rewrite an existing generation rule");
      }
      rule.setTenantId(request.tenantId());
      rule.setVersionId(request.versionId());
      rule.setName(ruleName);
      rule.setScopeType(request.scopeType());
      rule.setScopeId(request.scopeId());
      rule.setValue(blankToNull(rulePayload.value()));
      generationRuleRepository.save(rule);
    }
  }

  private Map<String, Room> applySubtreeRooms(
      WorldDesignMutationRequestDto request,
      WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto payload) {
    Map<String, Room> createdRooms = new HashMap<>();
    for (WorldDesignMutationRequestDto.GeneratedRoomMutationDto roomPayload :
        nullToEmpty(payload.rooms())) {
      String clientRef = requireText(roomPayload.clientRef(), "generated_room.client_ref");
      if (createdRooms.containsKey(clientRef)) {
        throw appError("INVALID_ARGUMENT", "duplicate generated_room.client_ref");
      }
      Zone zone =
          zoneRepository
              .findByTenantIdAndVersionIdAndId(
                  request.tenantId(),
                  request.versionId(),
                  parseId(roomPayload.zoneId(), "generated_room.zone_id"))
              .orElseThrow(() -> appError("UNRESOLVED_REFERENCE", "generated room zone not found"));
      Room room = new Room();
      room.setTenantId(request.tenantId());
      room.setVersionId(request.versionId());
      room.setZone(zone);
      room.setName(requireText(roomPayload.name(), "generated_room.name"));
      room.setDescription(blankToNull(roomPayload.description()));
      room.setNameLocalizedVariantsJson(blankToNull(roomPayload.nameLocalizedVariantsJson()));
      room.setDescriptionLocalizedVariantsJson(
          blankToNull(roomPayload.descriptionLocalizedVariantsJson()));
      validateRoomWithinScope(request, room);
      createdRooms.put(clientRef, roomRepository.save(room));
    }
    return createdRooms;
  }

  private void applySubtreeRoomExits(
      WorldDesignMutationRequestDto request,
      WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto payload,
      Map<String, Room> createdRooms) {
    for (WorldDesignMutationRequestDto.GeneratedRoomExitMutationDto exitPayload :
        nullToEmpty(payload.roomExits())) {
      RoomExit exit = new RoomExit();
      exit.setTenantId(request.tenantId());
      exit.setVersionId(request.versionId());
      exit.setDirection(requireText(exitPayload.direction(), "generated_room_exit.direction"));
      exit.setCost(exitPayload.cost() <= 0 ? 1 : exitPayload.cost());
      exit.setFromRoom(
          resolveGeneratedRoomRef(createdRooms, exitPayload.fromRoomRef(), "from_room_ref"));
      exit.setToRoom(resolveGeneratedRoomRef(createdRooms, exitPayload.toRoomRef(), "to_room_ref"));
      validateRoomExitWithinScope(request, exit);
      roomExitRepository.save(exit);
    }
  }

  private void applySubtreeSpawnBindings(
      WorldDesignMutationRequestDto request,
      WorldDesignMutationRequestDto.WorldGenerationSubtreeMutationDto payload,
      Map<String, Room> createdRooms) {
    for (WorldDesignMutationRequestDto.GeneratedWorldEntitySpawnBindingMutationDto bindingPayload :
        nullToEmpty(payload.worldEntitySpawnBindings())) {
      Room room = resolveGeneratedRoomRef(createdRooms, bindingPayload.roomRef(), "room_ref");
      String entityTemplateType =
          requireText(
              bindingPayload.entityTemplateType(), "generated_spawn_binding.entity_template_type");
      long entityTemplateId =
          parseId(bindingPayload.entityTemplateId(), "generated_spawn_binding.entity_template_id");
      if (!entityManagementClient.validateEntityTemplateReference(
          request.tenantId(), request.versionId(), entityTemplateType, entityTemplateId)) {
        throw appError("UNRESOLVED_REFERENCE", "entity template not found");
      }
      WorldEntitySpawnBinding binding =
          worldEntitySpawnBindingRepository
              .findByTenantIdAndVersionIdAndRoomIdAndEntityTemplateTypeAndEntityTemplateId(
                  request.tenantId(),
                  request.versionId(),
                  room.getId(),
                  entityTemplateType,
                  entityTemplateId)
              .orElseGet(WorldEntitySpawnBinding::new);
      if (isSeedAppendOnlyPolicy(request) && binding.getId() != null) {
        throw appError("OUT_OF_SYNC", "SEED_APPEND_ONLY cannot rewrite an existing spawn binding");
      }
      binding.setTenantId(request.tenantId());
      binding.setVersionId(request.versionId());
      binding.setRoom(room);
      binding.setEntityTemplateType(entityTemplateType);
      binding.setEntityTemplateId(entityTemplateId);
      binding.setSpawnCount(bindingPayload.spawnCount() <= 0 ? 1 : bindingPayload.spawnCount());
      binding.setRespawnDelaySeconds(Math.max(bindingPayload.respawnDelaySeconds(), 0));
      worldEntitySpawnBindingRepository.save(binding);
    }
  }

  private Room resolveGeneratedRoomRef(
      Map<String, Room> createdRooms, String roomRef, String fieldName) {
    Room room = createdRooms.get(requireText(roomRef, "generated_" + fieldName));
    if (room == null) {
      throw appError("UNRESOLVED_REFERENCE", "generated room reference not found");
    }
    return room;
  }

  private Long syntheticScopeAggregateId(WorldDesignMutationRequestDto request) {
    long scopeId = parseId(request.scopeId(), "scope_id");
    if (SCOPE_TYPE_REGION_SUBTREE.equals(request.scopeType())) {
      return 1_000_000_000_000L + scopeId;
    }
    if (SCOPE_TYPE_ZONE_SUBTREE.equals(request.scopeType())) {
      return 2_000_000_000_000L + scopeId;
    }
    throw appError("UNSUPPORTED_SCOPE", "WORLD_GENERATION_SUBTREE requires region or zone scope");
  }

  private <T> List<T> nullToEmpty(List<T> values) {
    return values == null ? List.of() : values;
  }

  private Long advanceScopeEpoch(WorldDesignMutationRequestDto request) {
    if (!StringUtils.hasText(request.scopeType()) && !StringUtils.hasText(request.scopeId())) {
      return null;
    }
    String scopeType = requireText(request.scopeType(), "scope_type");
    String scopeId = requireText(request.scopeId(), "scope_id");
    WorldDesignScopeEpoch scopeEpoch =
        scopeEpochRepository
            .findByTenantIdAndVersionIdAndScopeTypeAndScopeId(
                request.tenantId(), request.versionId(), scopeType, scopeId)
            .orElseGet(() -> newScopeEpoch(request, scopeType, scopeId));
    if (!scopeEpoch
        .getDraftScopeRevisionEpoch()
        .equals(request.expectedDraftScopeRevisionEpoch())) {
      throw appError(
          "DRAFT_WRITE_CONFLICT",
          "expected Draft scope epoch "
              + request.expectedDraftScopeRevisionEpoch()
              + " but found "
              + scopeEpoch.getDraftScopeRevisionEpoch());
    }
    scopeEpoch.setDraftScopeRevisionEpoch(scopeEpoch.getDraftScopeRevisionEpoch() + 1L);
    scopeEpoch.setUpdatedAt(LocalDateTime.now());
    return scopeEpochRepository.save(scopeEpoch).getDraftScopeRevisionEpoch();
  }

  private void requireDraftVersion(long tenantId, long versionId) {
    var response = gameDesignClient.getVersionState(tenantId, versionId);
    if (response.hasError()) {
      throw appError(response.getError().getCode(), response.getError().getMessage());
    }
    if (response.getVersionState().getVersionState()
        != VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT) {
      throw appError(
          "INVALID_VERSION_STATE", "World design mutations are allowed only for Draft versions");
    }
  }

  private void validateRequest(WorldDesignMutationRequestDto request) {
    requireText(request.commitId(), "commit_id");
    requireText(request.revisionId(), "revision_id");
    if (!OPERATION_UPSERT.equals(request.operationType())
        && !OPERATION_DELETE.equals(request.operationType())) {
      throw appError("INVALID_ARGUMENT", "unsupported operation");
    }
    requireText(request.aggregateType(), "aggregate_type");
    if (StringUtils.hasText(request.scopeType())
        && !SCOPE_TYPE_REGION_SUBTREE.equals(request.scopeType())
        && !SCOPE_TYPE_ZONE_SUBTREE.equals(request.scopeType())
        && !SCOPE_TYPE_NEW_EMPTY_REGION.equals(request.scopeType())) {
      throw appError("INVALID_ARGUMENT", "unsupported scope_type");
    }
    if (StringUtils.hasText(request.scopeMutationPolicy())
        && !SCOPE_POLICY_REPLACE_SCOPE.equals(request.scopeMutationPolicy())
        && !SCOPE_POLICY_SEED_APPEND_ONLY.equals(request.scopeMutationPolicy())) {
      throw appError("INVALID_ARGUMENT", "unsupported scope_mutation_policy");
    }
    if (StringUtils.hasText(request.scopeType()) != StringUtils.hasText(request.scopeId())) {
      throw appError("INVALID_ARGUMENT", "scope_type and scope_id must be set together");
    }
    if (StringUtils.hasText(request.scopeMutationPolicy())
        && (!StringUtils.hasText(request.scopeType()) || !StringUtils.hasText(request.scopeId()))) {
      throw appError("INVALID_ARGUMENT", "scope_type and scope_id are required for scope policy");
    }
    if (OPERATION_DELETE.equals(request.operationType())
        && !StringUtils.hasText(request.aggregateId())) {
      throw appError("INVALID_ARGUMENT", "aggregate_id is required for delete");
    }
  }

  private boolean isSeedAppendOnlyPolicy(WorldDesignMutationRequestDto request) {
    return SCOPE_POLICY_SEED_APPEND_ONLY.equals(request.scopeMutationPolicy());
  }

  private boolean isReplaceScopePolicy(WorldDesignMutationRequestDto request) {
    return SCOPE_POLICY_REPLACE_SCOPE.equals(request.scopeMutationPolicy());
  }

  private void failIfSeedAppendOnlyDelete(WorldDesignMutationRequestDto request) {
    if (isSeedAppendOnlyPolicy(request)) {
      throw appError("OUT_OF_SYNC", "SEED_APPEND_ONLY cannot delete existing rows");
    }
  }

  private void failIfSeedAppendOnlyUpdate(WorldDesignMutationRequestDto request) {
    if (isSeedAppendOnlyPolicy(request) && StringUtils.hasText(request.aggregateId())) {
      throw appError("OUT_OF_SYNC", "SEED_APPEND_ONLY cannot rewrite an existing aggregate");
    }
  }

  private WorldDesignAggregateEpoch newAggregateEpoch(
      WorldDesignMutationRequestDto request, Long aggregateId) {
    WorldDesignAggregateEpoch epoch = new WorldDesignAggregateEpoch();
    epoch.setTenantId(request.tenantId());
    epoch.setVersionId(request.versionId());
    epoch.setAggregateType(request.aggregateType());
    epoch.setAggregateId(aggregateId);
    return epoch;
  }

  private WorldDesignScopeEpoch newScopeEpoch(
      WorldDesignMutationRequestDto request, String scopeType, String scopeId) {
    WorldDesignScopeEpoch epoch = new WorldDesignScopeEpoch();
    epoch.setTenantId(request.tenantId());
    epoch.setVersionId(request.versionId());
    epoch.setScopeType(scopeType);
    epoch.setScopeId(scopeId);
    return epoch;
  }

  private Region existingRegion(WorldDesignMutationRequestDto request) {
    return regionRepository
        .findByTenantIdAndVersionIdAndId(
            request.tenantId(), request.versionId(), parseId(request.aggregateId(), "aggregate_id"))
        .orElseThrow(() -> appError("NOT_FOUND", "region not found"));
  }

  private Zone existingZone(WorldDesignMutationRequestDto request) {
    return zoneRepository
        .findByTenantIdAndVersionIdAndId(
            request.tenantId(), request.versionId(), parseId(request.aggregateId(), "aggregate_id"))
        .orElseThrow(() -> appError("NOT_FOUND", "zone not found"));
  }

  private Room existingRoom(WorldDesignMutationRequestDto request) {
    return roomRepository
        .findByTenantIdAndVersionIdAndId(
            request.tenantId(), request.versionId(), parseId(request.aggregateId(), "aggregate_id"))
        .orElseThrow(() -> appError("NOT_FOUND", "room not found"));
  }

  private RoomExit existingRoomExit(WorldDesignMutationRequestDto request) {
    return roomExitRepository
        .findByTenantIdAndVersionIdAndId(
            request.tenantId(), request.versionId(), parseId(request.aggregateId(), "aggregate_id"))
        .orElseThrow(() -> appError("NOT_FOUND", "room exit not found"));
  }

  private GenerationRule existingGenerationRule(WorldDesignMutationRequestDto request) {
    return generationRuleRepository
        .findByTenantIdAndVersionIdAndId(
            request.tenantId(), request.versionId(), parseId(request.aggregateId(), "aggregate_id"))
        .orElseThrow(() -> appError("NOT_FOUND", "generation rule not found"));
  }

  private WorldEntitySpawnBinding existingWorldEntitySpawnBinding(
      WorldDesignMutationRequestDto request) {
    return worldEntitySpawnBindingRepository
        .findByTenantIdAndVersionIdAndId(
            request.tenantId(), request.versionId(), parseId(request.aggregateId(), "aggregate_id"))
        .orElseThrow(() -> appError("NOT_FOUND", "world entity spawn binding not found"));
  }

  private boolean bindingWithinScope(
      WorldEntitySpawnBinding binding, String scopeType, long scopeId) {
    return binding.getRoom() != null && roomWithinScope(binding.getRoom(), scopeType, scopeId);
  }

  private void validateRegionWithinScope(WorldDesignMutationRequestDto request, Region region) {
    if (!StringUtils.hasText(request.scopeType())) {
      return;
    }
    String scopeType = request.scopeType();
    long scopeId = parseId(request.scopeId(), "scope_id");
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(scopeType)) {
      if (!OPERATION_UPSERT.equals(request.operationType())
          || StringUtils.hasText(request.aggregateId())) {
        throw appError("UNSUPPORTED_SCOPE", "NEW_EMPTY_REGION supports only new region upserts");
      }
      return;
    }
    if (!SCOPE_TYPE_REGION_SUBTREE.equals(scopeType)) {
      throw appError("OUT_OF_SYNC", "region mutation is outside the declared scope");
    }
    if (region.getId() == null || region.getId() != scopeId) {
      throw appError("OUT_OF_SYNC", "region mutation is outside the declared scope");
    }
  }

  private void validateZoneWithinScope(WorldDesignMutationRequestDto request, Zone zone) {
    if (!StringUtils.hasText(request.scopeType())) {
      return;
    }
    String scopeType = request.scopeType();
    long scopeId = parseId(request.scopeId(), "scope_id");
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(scopeType)) {
      throw appError("UNSUPPORTED_SCOPE", "zone mutation does not support NEW_EMPTY_REGION");
    }
    boolean inScope =
        switch (scopeType) {
          case SCOPE_TYPE_ZONE_SUBTREE -> zone.getId() != null && zone.getId() == scopeId;
          case SCOPE_TYPE_REGION_SUBTREE ->
              zone.getRegion() != null
                  && zone.getRegion().getId() != null
                  && zone.getRegion().getId() == scopeId;
          default -> false;
        };
    if (!inScope) {
      throw appError("OUT_OF_SYNC", "zone mutation is outside the declared scope");
    }
  }

  private void validateRoomWithinScope(WorldDesignMutationRequestDto request, Room room) {
    if (!StringUtils.hasText(request.scopeType())) {
      return;
    }
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(request.scopeType())) {
      throw appError("UNSUPPORTED_SCOPE", "room mutation does not support NEW_EMPTY_REGION");
    }
    if (!roomWithinScope(room, request.scopeType(), parseId(request.scopeId(), "scope_id"))) {
      throw appError("OUT_OF_SYNC", "room mutation is outside the declared scope");
    }
  }

  private void validateRoomExitWithinScope(WorldDesignMutationRequestDto request, RoomExit exit) {
    if (!StringUtils.hasText(request.scopeType())) {
      return;
    }
    if (SCOPE_TYPE_NEW_EMPTY_REGION.equals(request.scopeType())) {
      throw appError("UNSUPPORTED_SCOPE", "room exit mutation does not support NEW_EMPTY_REGION");
    }
    long scopeId = parseId(request.scopeId(), "scope_id");
    if (!roomWithinScope(exit.getFromRoom(), request.scopeType(), scopeId)
        || !roomWithinScope(exit.getToRoom(), request.scopeType(), scopeId)) {
      throw appError("OUT_OF_SYNC", "room exit mutation is outside the declared scope");
    }
  }

  private boolean roomWithinScope(Room room, String scopeType, long scopeId) {
    if (room.getZone() == null) {
      return false;
    }
    return switch (scopeType) {
      case SCOPE_TYPE_ZONE_SUBTREE ->
          room.getZone().getId() != null && room.getZone().getId() == scopeId;
      case SCOPE_TYPE_REGION_SUBTREE ->
          room.getZone().getRegion() != null
              && room.getZone().getRegion().getId() != null
              && room.getZone().getRegion().getId() == scopeId;
      default -> false;
    };
  }

  private String requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw appError("INVALID_ARGUMENT", field + " is required");
    }
    return value.trim();
  }

  private Long parseId(String value, String field) {
    try {
      return RequestIdValidation.requirePositiveLong(requireText(value, field), field);
    } catch (IllegalArgumentException ex) {
      throw appError("INVALID_ARGUMENT", ex.getMessage());
    }
  }

  private String normalizeId(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }

  private IllegalArgumentException appError(String code, String message) {
    return new IllegalArgumentException(code + ": " + message);
  }
}
