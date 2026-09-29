package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.entitymanagement.entity.BodyLayoutSlotDefinition;
import net.firedevops.firemud.entitymanagement.entity.CraftingIngredient;
import net.firedevops.firemud.entitymanagement.entity.EquipmentSlotDefinition;
import net.firedevops.firemud.entitymanagement.repository.BodyLayoutSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import net.firedevops.firemud.entitymanagement.repository.EquipmentSlotDefinitionRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected repositories and ObjectMapper are managed dependencies kept internal.")
public class EntityDraftDesignDigestServiceImpl implements EntityDraftDesignDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 2;

  private final ItemRepository itemRepository;
  private final NpcRepository npcRepository;
  private final CraftingRecipeRepository craftingRecipeRepository;
  private final EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository;
  private final BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository;
  private final ObjectMapper objectMapper;

  public EntityDraftDesignDigestServiceImpl(
      ItemRepository itemRepository,
      NpcRepository npcRepository,
      CraftingRecipeRepository craftingRecipeRepository,
      EquipmentSlotDefinitionRepository equipmentSlotDefinitionRepository,
      BodyLayoutSlotDefinitionRepository bodyLayoutSlotDefinitionRepository,
      ObjectMapper objectMapper) {
    this.itemRepository = itemRepository;
    this.npcRepository = npcRepository;
    this.craftingRecipeRepository = craftingRecipeRepository;
    this.equipmentSlotDefinitionRepository = equipmentSlotDefinitionRepository;
    this.bodyLayoutSlotDefinitionRepository = bodyLayoutSlotDefinitionRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public EntityDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalArgumentException("version_id is required");
    }
    long tenantKey = RequestIdValidation.requirePositiveLong(tenantId, "tenantId");
    long versionKey = RequestIdValidation.requirePositiveLong(versionId, "versionId");
    try {
      String canonicalJson =
          objectMapper
              .writer(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(
                  Map.of(
                      "items",
                      itemRepository
                          .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                          .stream()
                          .map(
                              item ->
                                  Map.<String, Object>ofEntries(
                                      Map.entry("id", item.getId()),
                                      Map.entry("name", item.getName()),
                                      Map.entry("description", value(item.getDescription())),
                                      Map.entry(
                                          "equipmentSlot",
                                          normalizeOptionalKey(item.getEquipmentSlot())),
                                      Map.entry(
                                          "equipmentSlotGroupKey",
                                          normalizeOptionalKey(item.getEquipmentSlotGroupKey())),
                                      Map.entry("container", item.isContainer()),
                                      Map.entry("stackable", item.isStackable()),
                                      Map.entry(
                                          "stackCompatibilityMode",
                                          item.getStackCompatibilityMode().name()),
                                      Map.entry(
                                          "stackVariantKey", value(item.getStackVariantKey())),
                                      Map.entry(
                                          "effectPayloadJson",
                                          canonicalizeOptionalJson(item.getEffectPayloadJson()))))
                          .toList(),
                      "npcs",
                      npcRepository
                          .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                          .stream()
                          .map(
                              npc ->
                                  Map.<String, Object>of(
                                      "id", npc.getId(),
                                      "name", npc.getName(),
                                      "behavior", value(npc.getBehavior()),
                                      "respawnDelaySeconds", npc.getRespawnDelaySeconds()))
                          .toList(),
                      "craftingRecipes",
                      craftingRecipeRepository
                          .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                          .stream()
                          .map(
                              recipe ->
                                  Map.<String, Object>of(
                                      "id",
                                      recipe.getId(),
                                      "name",
                                      recipe.getName(),
                                      "resultItemId",
                                      recipe.getResultItem().getId(),
                                      "resultQuantity",
                                      recipe.getResultQuantity(),
                                      "ingredients",
                                      recipe.getIngredients().stream()
                                          .sorted(
                                              Comparator.comparing(
                                                      (CraftingIngredient ingredient) ->
                                                          ingredient.getItem().getId())
                                                  .thenComparingInt(
                                                      CraftingIngredient::getQuantity))
                                          .map(
                                              ingredient ->
                                                  Map.<String, Object>of(
                                                      "itemId", ingredient.getItem().getId(),
                                                      "quantity", ingredient.getQuantity()))
                                          .toList()))
                          .toList(),
                      "equipmentSlotDefinitions",
                      equipmentSlotDefinitionRepository
                          .findByTenantIdAndVersionIdOrderBySlotKeyAsc(tenantKey, versionKey)
                          .stream()
                          .sorted(
                              Comparator.comparing(
                                      (EquipmentSlotDefinition definition) ->
                                          normalizeOptionalKey(definition.getSlotKey()))
                                  .thenComparing(definition -> value(definition.getDisplayName()))
                                  .thenComparing(
                                      definition ->
                                          normalizeOptionalKey(definition.getSlotGroupKey())))
                          .map(
                              definition ->
                                  Map.<String, Object>of(
                                      "slotKey", normalizeOptionalKey(definition.getSlotKey()),
                                      "displayName", value(definition.getDisplayName()),
                                      "slotGroupKey",
                                          normalizeOptionalKey(definition.getSlotGroupKey())))
                          .toList(),
                      "bodyLayoutSlotDefinitions",
                      bodyLayoutSlotDefinitionRepository
                          .findByTenantIdAndVersionIdOrderByBodyLayoutKeyAscSlotKeyAsc(
                              tenantKey, versionKey)
                          .stream()
                          .sorted(
                              Comparator.comparing(
                                      (BodyLayoutSlotDefinition definition) ->
                                          normalizeOptionalKey(definition.getBodyLayoutKey()))
                                  .thenComparing(
                                      definition -> normalizeOptionalKey(definition.getSlotKey())))
                          .map(
                              definition ->
                                  Map.<String, Object>of(
                                      "bodyLayoutKey",
                                          normalizeOptionalKey(definition.getBodyLayoutKey()),
                                      "slotKey", normalizeOptionalKey(definition.getSlotKey())))
                          .toList()));
      return new EntityDraftDesignDigest(
          tenantId,
          versionId,
          "version:" + versionId,
          sha256(canonicalJson),
          DIGEST_SCHEMA_VERSION);
    } catch (Exception ex) {
      throw new IllegalStateException("failed to compute entity draft design digest", ex);
    }
  }

  private String value(String value) {
    return value == null ? "" : value;
  }

  private String normalizeOptionalKey(String value) {
    return value == null || value.isBlank() ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  private String canonicalizeOptionalJson(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    try {
      return objectMapper.writeValueAsString(canonicalizeJsonNode(objectMapper.readTree(value)));
    } catch (Exception ex) {
      throw new IllegalStateException("effectPayloadJson is not valid JSON", ex);
    }
  }

  private JsonNode canonicalizeJsonNode(JsonNode node) {
    if (node == null || node.isNull() || node.isValueNode()) {
      return node;
    }
    if (node.isObject()) {
      var canonicalObject = objectMapper.createObjectNode();
      node.properties().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(
              entry -> canonicalObject.set(entry.getKey(), canonicalizeJsonNode(entry.getValue())));
      return canonicalObject;
    }
    var canonicalArray = objectMapper.createArrayNode();
    node.valueStream().map(this::canonicalizeJsonNode).forEach(canonicalArray::add);
    return canonicalArray;
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
