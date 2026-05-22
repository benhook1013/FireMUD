package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class Zone {
  private Long id;
  private Long tenantId;
  private Long versionId = 1L;
  private Region region;
  private String name;

  private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Region getRegion() {
    return region;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setRegion(Region region) {
    this.region = region;
  }
}
