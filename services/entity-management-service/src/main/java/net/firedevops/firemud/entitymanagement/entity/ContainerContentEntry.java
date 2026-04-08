package net.firedevops.firemud.entitymanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "container_contents")
public class ContainerContentEntry {
  @EmbeddedId private ContainerContentKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("containerInstanceId")
  @JoinColumn(name = "container_instance_id", nullable = false)
  private ContainerInstance containerInstance;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("itemId")
  @JoinColumn(name = "item_id", nullable = false)
  private Item item;

  @Column(nullable = false)
  private int quantity;

  @Version private int version;

  public ContainerContentKey getId() {
    if (id == null) {
      return null;
    }
    ContainerContentKey copy = new ContainerContentKey();
    copy.setTenantId(id.getTenantId());
    copy.setContainerInstanceId(id.getContainerInstanceId());
    copy.setItemId(id.getItemId());
    return copy;
  }

  public void setId(ContainerContentKey id) {
    if (id == null) {
      this.id = null;
    } else {
      ContainerContentKey copy = new ContainerContentKey();
      copy.setTenantId(id.getTenantId());
      copy.setContainerInstanceId(id.getContainerInstanceId());
      copy.setItemId(id.getItemId());
      this.id = copy;
    }
  }

  public ContainerInstance getContainerInstance() {
    if (containerInstance == null) {
      return null;
    }
    ContainerInstance copy = new ContainerInstance();
    copy.setId(containerInstance.getId());
    copy.setTenantId(containerInstance.getTenantId());
    copy.setCharacter(containerInstance.getCharacter());
    copy.setItem(containerInstance.getItem());
    return copy;
  }

  public void setContainerInstance(ContainerInstance containerInstance) {
    if (containerInstance == null) {
      this.containerInstance = null;
    } else {
      ContainerInstance copy = new ContainerInstance();
      copy.setId(containerInstance.getId());
      copy.setTenantId(containerInstance.getTenantId());
      copy.setCharacter(containerInstance.getCharacter());
      copy.setItem(containerInstance.getItem());
      this.containerInstance = copy;
    }
  }

  public Item getItem() {
    if (item == null) {
      return null;
    }
    Item copy = new Item();
    copy.setId(item.getId());
    copy.setTenantId(item.getTenantId());
    copy.setName(item.getName());
    copy.setDescription(item.getDescription());
    copy.setEquipmentSlot(item.getEquipmentSlot());
    copy.setContainer(item.isContainer());
    return copy;
  }

  public void setItem(Item item) {
    if (item == null) {
      this.item = null;
    } else {
      Item copy = new Item();
      copy.setId(item.getId());
      copy.setTenantId(item.getTenantId());
      copy.setName(item.getName());
      copy.setDescription(item.getDescription());
      copy.setEquipmentSlot(item.getEquipmentSlot());
      copy.setContainer(item.isContainer());
      this.item = copy;
    }
  }
}
