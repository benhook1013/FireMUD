package net.firedevops.firemud.entitymanagement.entity;

import java.io.Serializable;
import lombok.Data;

@Data
public class CharacterEquipmentKey implements Serializable {
  private Long characterId;
  private String slot;
}
