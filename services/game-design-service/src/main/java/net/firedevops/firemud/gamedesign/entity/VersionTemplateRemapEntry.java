package net.firedevops.firemud.gamedesign.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class VersionTemplateRemapEntry {
  private Long id;
  private VersionTemplateRemapSet remapSet;
  private String mappingDomain;
  private String mappingType;
  private String sourceTemplateKey;
  private String targetTemplateKey;
  private LocalDateTime createdAt;

  void prePersist() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
