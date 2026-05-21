package net.firedevops.firemud.gamesession.entity;

import lombok.Data;

@Data
public class GameManifest {
  private Long id;
  private String versionId;
  private String description;
}
