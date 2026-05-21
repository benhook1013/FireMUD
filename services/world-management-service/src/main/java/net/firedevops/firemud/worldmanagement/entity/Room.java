package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class Room {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private Zone zone;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA relationship is intentionally exposed")
  public Zone getZone() {
    return zone;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA relationship is stored directly")
  public void setZone(Zone zone) {
    this.zone = zone;
  }

  private String name;
  private String description;
  private String nameLocalizedVariantsJson;
  private String descriptionLocalizedVariantsJson;

  private int version;
}
