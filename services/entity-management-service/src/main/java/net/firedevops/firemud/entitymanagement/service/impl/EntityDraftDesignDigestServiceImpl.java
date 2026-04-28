package net.firedevops.firemud.entitymanagement.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import net.firedevops.firemud.entitymanagement.entity.CraftingIngredient;
import net.firedevops.firemud.entitymanagement.repository.CraftingRecipeRepository;
import net.firedevops.firemud.entitymanagement.repository.ItemRepository;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import net.firedevops.firemud.entitymanagement.service.EntityDraftDesignDigestService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Injected repositories and ObjectMapper are managed dependencies kept internal.")
public class EntityDraftDesignDigestServiceImpl implements EntityDraftDesignDigestService {
  private static final int DIGEST_SCHEMA_VERSION = 1;

  private final ItemRepository itemRepository;
  private final NpcRepository npcRepository;
  private final CraftingRecipeRepository craftingRecipeRepository;
  private final ObjectMapper objectMapper;

  public EntityDraftDesignDigestServiceImpl(
      ItemRepository itemRepository,
      NpcRepository npcRepository,
      CraftingRecipeRepository craftingRecipeRepository,
      ObjectMapper objectMapper) {
    this.itemRepository = itemRepository;
    this.npcRepository = npcRepository;
    this.craftingRecipeRepository = craftingRecipeRepository;
    this.objectMapper = objectMapper;
  }

  @Override
  public EntityDraftDesignDigest getDraftDesignDigest(String tenantId, String versionId) {
    if (versionId == null || versionId.isBlank()) {
      throw new IllegalArgumentException("version_id is required");
    }
    long tenantKey = Long.parseLong(tenantId);
    long versionKey = Long.parseLong(versionId);
    try {
      String canonicalJson =
          objectMapper.writeValueAsString(
              Map.of(
                  "items",
                  itemRepository
                      .findByTenantIdAndVersionIdOrderByIdAsc(tenantKey, versionKey)
                      .stream()
                      .map(
                          item ->
                              Map.<String, Object>of(
                                  "id", item.getId(),
                                  "name", item.getName(),
                                  "description", value(item.getDescription()),
                                  "equipmentSlot", value(item.getEquipmentSlot()),
                                  "container", item.isContainer(),
                                  "stackable", item.isStackable(),
                                  "stackCompatibilityMode", item.getStackCompatibilityMode().name(),
                                  "stackVariantKey", value(item.getStackVariantKey()),
                                  "effectPayloadJson", value(item.getEffectPayloadJson())))
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
                                              .thenComparingInt(CraftingIngredient::getQuantity))
                                      .map(
                                          ingredient ->
                                              Map.<String, Object>of(
                                                  "itemId", ingredient.getItem().getId(),
                                                  "quantity", ingredient.getQuantity()))
                                      .toList()))
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
