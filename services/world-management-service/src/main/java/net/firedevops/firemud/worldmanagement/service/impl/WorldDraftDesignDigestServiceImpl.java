package net.firedevops.firemud.worldmanagement.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import net.firedevops.firemud.worldmanagement.repository.GenerationRuleRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldEntitySpawnBindingRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import net.firedevops.firemud.worldmanagement.service.WorldDraftDesignDigestService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorldDraftDesignDigestServiceImpl implements WorldDraftDesignDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 2;

  private final RegionRepository regionRepository;
  private final ZoneRepository zoneRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;
  private final GenerationRuleRepository generationRuleRepository;
  private final WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository;
  private final ObjectMapper objectMapper;

  public WorldDraftDesignDigestServiceImpl(
      RegionRepository regionRepository,
      ZoneRepository zoneRepository,
      RoomRepository roomRepository,
      RoomExitRepository roomExitRepository,
      GenerationRuleRepository generationRuleRepository,
      WorldEntitySpawnBindingRepository worldEntitySpawnBindingRepository,
      ObjectMapper objectMapper) {
    this.regionRepository = regionRepository;
    this.zoneRepository = zoneRepository;
    this.roomRepository = roomRepository;
    this.roomExitRepository = roomExitRepository;
    this.generationRuleRepository = generationRuleRepository;
    this.worldEntitySpawnBindingRepository = worldEntitySpawnBindingRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public WorldDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalArgumentException("version_id is required");
    }
    long tenantKey = Long.parseLong(tenantId);
    long versionKey = Long.parseLong(versionId);
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              Map.of(
                  "regions",
                  regionRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          region ->
                              Map.<String, Object>of(
                                  "id", region.getId(),
                                  "shardId", region.getShardId(),
                                  "name", region.getName(),
                                  "weather", value(region.getWeather()),
                                  "generationSeed", region.getGenerationSeed(),
                                  "generatorType", value(region.getGeneratorType()),
                                  "generatorParams", value(region.getGeneratorParams()),
                                  "spacingMultiplier", region.getSpacingMultiplier()))
                      .toList(),
                  "zones",
                  zoneRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          zone ->
                              Map.<String, Object>of(
                                  "id", zone.getId(),
                                  "regionId", zone.getRegion().getId(),
                                  "name", zone.getName()))
                      .toList(),
                  "rooms",
                  roomRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          room ->
                              Map.<String, Object>of(
                                  "id", room.getId(),
                                  "zoneId", room.getZone().getId(),
                                  "name", room.getName(),
                                  "description", value(room.getDescription()),
                                  "nameLocalizedVariantsJson",
                                      value(room.getNameLocalizedVariantsJson()),
                                  "descriptionLocalizedVariantsJson",
                                      value(room.getDescriptionLocalizedVariantsJson())))
                      .toList(),
                  "roomExits",
                  roomExitRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          roomExit ->
                              Map.<String, Object>of(
                                  "id", roomExit.getId(),
                                  "fromRoomId", roomExit.getFromRoom().getId(),
                                  "toRoomId", roomExit.getToRoom().getId(),
                                  "direction", roomExit.getDirection(),
                                  "cost", roomExit.getCost()))
                      .toList(),
                  "generationRules",
                  generationRuleRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          rule ->
                              Map.<String, Object>of(
                                  "id", rule.getId(),
                                  "name", rule.getName(),
                                  "scopeType", value(rule.getScopeType()),
                                  "scopeId", value(rule.getScopeId()),
                                  "value", value(rule.getValue())))
                      .toList(),
                  "worldEntitySpawnBindings",
                  worldEntitySpawnBindingRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          binding ->
                              Map.<String, Object>of(
                                  "id", binding.getId(),
                                  "roomId", binding.getRoom().getId(),
                                  "entityTemplateType", binding.getEntityTemplateType(),
                                  "entityTemplateId", binding.getEntityTemplateId(),
                                  "spawnCount", binding.getSpawnCount(),
                                  "respawnDelaySeconds", binding.getRespawnDelaySeconds()))
                      .toList()));
      return new WorldDraftDesignDigest(
          tenantId,
          versionId,
          "version:" + versionId,
          sha256(canonicalJson),
          DIGEST_SCHEMA_VERSION);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute world draft design digest", ex);
    }
  }

  private String value(String value) {
    return value == null ? "" : value;
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
