package net.firedevops.firemud.worldmanagement.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class Instance {
  private Long id;
  private Long tenantId;
  private Zone zone;
  private Long ownerAccountId;
  private LocalDateTime createdAt = LocalDateTime.now();
  private LocalDateTime expiresAt;

  private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public Zone getZone() {
    return zone;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setZone(Zone zone) {
    this.zone = zone;
  }
}
