package net.firedevops.firemud.entitymanagement.repository;

import static net.firedevops.firemud.entitymanagement.jooq.Tables.CRAFTING_INGREDIENTS;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.CRAFTING_RECIPES;
import static net.firedevops.firemud.entitymanagement.jooq.Tables.ITEMS;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashSet;
import java.util.List;
import net.firedevops.firemud.entitymanagement.entity.CraftingIngredient;
import net.firedevops.firemud.entitymanagement.entity.CraftingIngredientKey;
import net.firedevops.firemud.entitymanagement.entity.CraftingRecipe;
import net.firedevops.firemud.entitymanagement.entity.Item;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class CraftingRecipeRepository {
  private final DSLContext dsl;

  public CraftingRecipeRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public CraftingRecipe save(CraftingRecipe entity) {
    Long recipeId = entity.getId();
    if (recipeId == null) {
      recipeId =
          dsl.insertInto(CRAFTING_RECIPES)
              .set(CRAFTING_RECIPES.TENANT_ID, entity.getTenantId())
              .set(CRAFTING_RECIPES.VERSION_ID, entity.getVersionId())
              .set(CRAFTING_RECIPES.NAME, entity.getName())
              .set(CRAFTING_RECIPES.RESULT_ITEM_ID, entity.getResultItem().getId())
              .set(CRAFTING_RECIPES.RESULT_QUANTITY, entity.getResultQuantity())
              .returningResult(CRAFTING_RECIPES.ID)
              .fetchOne(CRAFTING_RECIPES.ID);
    } else {
      dsl.update(CRAFTING_RECIPES)
          .set(CRAFTING_RECIPES.TENANT_ID, entity.getTenantId())
          .set(CRAFTING_RECIPES.VERSION_ID, entity.getVersionId())
          .set(CRAFTING_RECIPES.NAME, entity.getName())
          .set(CRAFTING_RECIPES.RESULT_ITEM_ID, entity.getResultItem().getId())
          .set(CRAFTING_RECIPES.RESULT_QUANTITY, entity.getResultQuantity())
          .where(CRAFTING_RECIPES.ID.eq(recipeId))
          .execute();
      dsl.deleteFrom(CRAFTING_INGREDIENTS)
          .where(CRAFTING_INGREDIENTS.RECIPE_ID.eq(recipeId))
          .execute();
    }
    for (CraftingIngredient ingredient : entity.getIngredients()) {
      dsl.insertInto(CRAFTING_INGREDIENTS)
          .set(CRAFTING_INGREDIENTS.RECIPE_ID, recipeId)
          .set(CRAFTING_INGREDIENTS.ITEM_ID, ingredient.getItem().getId())
          .set(CRAFTING_INGREDIENTS.QUANTITY, ingredient.getQuantity())
          .execute();
    }
    return findWithIngredientsById(recipeId);
  }

  public CraftingRecipe findWithIngredientsById(Long id) {
    return dsl.select(
            CRAFTING_RECIPES.ID,
            CRAFTING_RECIPES.TENANT_ID,
            CRAFTING_RECIPES.VERSION_ID,
            CRAFTING_RECIPES.NAME,
            CRAFTING_RECIPES.RESULT_ITEM_ID,
            CRAFTING_RECIPES.RESULT_QUANTITY,
            ITEMS.ID,
            ITEMS.TENANT_ID,
            ITEMS.VERSION_ID,
            ITEMS.NAME,
            ITEMS.DESCRIPTION,
            ITEMS.EQUIPMENT_SLOT,
            ITEMS.EQUIPMENT_SLOT_GROUP_KEY,
            ITEMS.IS_CONTAINER,
            ITEMS.IS_STACKABLE,
            ITEMS.STACK_COMPATIBILITY_MODE,
            ITEMS.STACK_VARIANT_KEY,
            ITEMS.EFFECT_PAYLOAD_JSON)
        .from(CRAFTING_RECIPES)
        .join(ITEMS)
        .on(CRAFTING_RECIPES.RESULT_ITEM_ID.eq(ITEMS.ID))
        .where(CRAFTING_RECIPES.ID.eq(id))
        .fetchOne(this::toRecipeWithIngredients);
  }

  public List<CraftingRecipe> findByTenantIdOrderByIdAsc(Long tenantId) {
    return dsl
        .select(CRAFTING_RECIPES.ID)
        .from(CRAFTING_RECIPES)
        .where(CRAFTING_RECIPES.TENANT_ID.eq(tenantId))
        .orderBy(CRAFTING_RECIPES.ID.asc())
        .fetch(CRAFTING_RECIPES.ID)
        .stream()
        .map(this::findWithIngredientsById)
        .toList();
  }

  public List<CraftingRecipe> findByTenantIdAndVersionIdOrderByIdAsc(
      Long tenantId, Long versionId) {
    return dsl
        .select(CRAFTING_RECIPES.ID)
        .from(CRAFTING_RECIPES)
        .where(
            CRAFTING_RECIPES.TENANT_ID.eq(tenantId).and(CRAFTING_RECIPES.VERSION_ID.eq(versionId)))
        .orderBy(CRAFTING_RECIPES.ID.asc())
        .fetch(CRAFTING_RECIPES.ID)
        .stream()
        .map(this::findWithIngredientsById)
        .toList();
  }

  private CraftingRecipe toRecipeWithIngredients(Record record) {
    if (record == null) {
      return null;
    }
    CraftingRecipe recipe = new CraftingRecipe();
    recipe.setId(record.get(CRAFTING_RECIPES.ID));
    recipe.setTenantId(record.get(CRAFTING_RECIPES.TENANT_ID));
    recipe.setVersionId(record.get(CRAFTING_RECIPES.VERSION_ID));
    recipe.setName(record.get(CRAFTING_RECIPES.NAME));
    recipe.setResultItem(
        JooqEntityManagementRepositorySupport.partialItem(
            record.get(ITEMS.ID),
            record.get(ITEMS.TENANT_ID),
            record.get(ITEMS.VERSION_ID),
            record.get(ITEMS.NAME),
            record.get(ITEMS.DESCRIPTION),
            record.get(ITEMS.EQUIPMENT_SLOT),
            record.get(ITEMS.EQUIPMENT_SLOT_GROUP_KEY),
            record.get(ITEMS.IS_CONTAINER),
            record.get(ITEMS.IS_STACKABLE),
            record.get(ITEMS.STACK_COMPATIBILITY_MODE),
            record.get(ITEMS.STACK_VARIANT_KEY),
            record.get(ITEMS.EFFECT_PAYLOAD_JSON)));
    recipe.setResultQuantity(record.get(CRAFTING_RECIPES.RESULT_QUANTITY));
    recipe.setIngredients(new LinkedHashSet<>(loadIngredients(recipe.getId())));
    return recipe;
  }

  private List<CraftingIngredient> loadIngredients(Long recipeId) {
    return dsl.select(
            CRAFTING_INGREDIENTS.RECIPE_ID,
            CRAFTING_INGREDIENTS.ITEM_ID,
            CRAFTING_INGREDIENTS.QUANTITY)
        .from(CRAFTING_INGREDIENTS)
        .where(CRAFTING_INGREDIENTS.RECIPE_ID.eq(recipeId))
        .orderBy(CRAFTING_INGREDIENTS.ITEM_ID.asc())
        .fetch(this::toIngredient);
  }

  private CraftingIngredient toIngredient(Record record) {
    CraftingIngredient ingredient = new CraftingIngredient();
    CraftingIngredientKey key = new CraftingIngredientKey();
    key.setRecipeId(record.get(CRAFTING_INGREDIENTS.RECIPE_ID));
    key.setItemId(record.get(CRAFTING_INGREDIENTS.ITEM_ID));
    ingredient.setId(key);
    CraftingRecipe recipe = new CraftingRecipe();
    recipe.setId(record.get(CRAFTING_INGREDIENTS.RECIPE_ID));
    ingredient.setRecipe(recipe);
    Item item = new Item();
    item.setId(record.get(CRAFTING_INGREDIENTS.ITEM_ID));
    ingredient.setItem(item);
    ingredient.setQuantity(record.get(CRAFTING_INGREDIENTS.QUANTITY));
    return ingredient;
  }
}
