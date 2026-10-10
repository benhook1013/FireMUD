package net.firedevops.firemud.entitymanagement.sourceintake;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import org.junit.jupiter.api.Test;

class EntityEmptySelectedSourceIntakeRepositoryTest {
  @Test
  void recognizesAllFourteenExplicitFreshEmptyOwnerProviders() {
    for (String family :
        java.util.List.of(
            "ACTOR_BODY_LAYOUT_ASSIGNMENTS",
            "ARCHETYPE_ASSIGNMENTS",
            "ARCHETYPE_CONSTRAINTS",
            "ARCHETYPE_ROOTS",
            "BALANCE_CURVE_ATTACHMENTS",
            "BALANCE_CURVE_ROOTS",
            "EQUIPMENT_ATTACHMENT_RULES",
            "EQUIPMENT_CAPABILITIES",
            "EQUIPMENT_COMPATIBILITY_RULES",
            "EQUIPMENT_OCCUPANCY_RULES",
            "INBOUND_LOOT_BINDINGS",
            "LOOT_ITEM_MAPPINGS",
            "LOOT_TABLE_ROOTS",
            "OTHER_ACTOR_TEMPLATE_ROOTS")) {
      EntityEmptySelectedSourceIntakeRepository.requireReadableProvider(family);
    }
  }

  @Test
  void recognizesAllNineExistingV1CensusFamilies() {
    for (String family :
        java.util.List.of(
            "ITEM_TEMPLATE_ROOTS",
            "NPC_TEMPLATE_ROOTS",
            "CRAFTING_RECIPE_ROOTS",
            "CRAFTING_RECIPE_RESULT_BINDINGS",
            "CRAFTING_INGREDIENT_BINDINGS",
            "EQUIPMENT_SLOT_ROOTS",
            "EQUIPMENT_SLOT_GROUPS",
            "BODY_LAYOUT_ROOTS",
            "BODY_LAYOUT_MEMBERSHIPS")) {
      EntityEmptySelectedSourceIntakeRepository.requireReadableProvider(family);
    }
  }

  @Test
  void rejectsFamiliesWithNoDeclaredOwnerProvider() {
    assertThatThrownBy(
            () -> EntityEmptySelectedSourceIntakeRepository.requireReadableProvider("UNKNOWN"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No readable persisted Entity source provider");
  }

  @Test
  void anEmptyFamilyStateCannotCarryRowsOrReferences() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EntityEmptySelectedSourceIntakeReceipt.FamilyCensus(
                "ITEM_TEMPLATE_ROOTS",
                EntityEmptySelectedSourceIntakeReceipt.FamilyState.EMPTY,
                EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind.V1_SOURCE_CENSUS,
                0L,
                0L,
                0L,
                0L,
                1L));
  }

  @Test
  void rejectsEvidenceKindThatDoesNotMatchFamilyProvider() {
    assertThatThrownBy(
            () ->
                new EntityEmptySelectedSourceIntakeReceipt.FamilyCensus(
                    "ARCHETYPE_ROOTS",
                    EntityEmptySelectedSourceIntakeReceipt.FamilyState.EMPTY,
                    EntityEmptySelectedSourceIntakeReceipt.FamilyEvidenceKind.V1_SOURCE_CENSUS,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("evidence kind differs");
  }
}
