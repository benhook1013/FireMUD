package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import org.junit.jupiter.api.Test;

class ContainerIdentitySupportTest {
  @Test
  void resolveContainerInstanceIdFallsBackToItemIdWhenFieldEmpty() {
    InventoryItem item = InventoryItem.newBuilder().setItemId("item-9").build();

    assertThat(ContainerIdentitySupport.resolveContainerInstanceId(item)).isEqualTo("item-9");
  }

  @Test
  void resolveContainerInstanceIdPrefersInventoryContainerInstanceId() {
    InventoryItem item =
        InventoryItem.newBuilder()
            .setItemId("item-9")
            .setContainerInstanceId("container-42")
            .build();

    assertThat(ContainerIdentitySupport.resolveContainerInstanceId(item)).isEqualTo("container-42");
  }

  @Test
  void resolveContainerInstanceIdPrefersEquipmentContainerInstanceId() {
    EquipmentItem item =
        EquipmentItem.newBuilder()
            .setItemId("item-9")
            .setSlot("BACK")
            .setContainerInstanceId("container-42")
            .build();

    assertThat(ContainerIdentitySupport.resolveContainerInstanceId(item)).isEqualTo("container-42");
  }

  @Test
  void matchesReferenceAcceptsContainerInstanceIds() {
    InventoryItem item =
        InventoryItem.newBuilder()
            .setItemId("item-9")
            .setContainerInstanceId("container-42")
            .setItemName("Old Chest")
            .build();

    assertThat(ContainerIdentitySupport.matchesReference(item, "container-42")).isTrue();
  }

  @Test
  void compactReferenceUsesNormalizedNameAndInstanceSuffix() {
    InventoryItem item =
        InventoryItem.newBuilder().setItemName("Old Chest").setVisibleRef("oldchest12").build();

    assertThat(ContainerIdentitySupport.compactReference(item)).isEqualTo("oldchest12");
    assertThat(ContainerIdentitySupport.matchesReference(item, "oldchest12")).isTrue();
  }

  @Test
  void roomEntityReferencesAcceptCompactContainerReference() {
    RoomEntity entity =
        RoomEntity.newBuilder()
            .setEntityId("22:77:R-7:10")
            .setDisplayName("Old Chest")
            .setVisibleRef("oldchest12")
            .addStateFlags("container-instance:container-42")
            .build();

    assertThat(ContainerIdentitySupport.compactReference(entity)).isEqualTo("oldchest12");
    assertThat(ContainerIdentitySupport.matchesReference(entity, "oldchest12")).isTrue();
  }
}
