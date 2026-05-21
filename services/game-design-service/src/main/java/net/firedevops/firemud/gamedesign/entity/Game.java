package net.firedevops.firemud.gamedesign.entity;

import lombok.Data;

@Data
public class Game {
  private Long id;
  private String tenantId;
  private String name;
  private String description;
}
