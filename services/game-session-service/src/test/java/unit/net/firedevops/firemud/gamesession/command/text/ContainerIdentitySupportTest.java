package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
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
}
