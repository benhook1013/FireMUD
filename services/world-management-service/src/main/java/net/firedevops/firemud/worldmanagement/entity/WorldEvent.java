package net.firedevops.firemud.worldmanagement.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class WorldEvent {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private RegionInstance regionInstance;
  private String eventType;
  private String eventData;
  private LocalDateTime executeAt;
  private boolean processed = false;

  private LocalDateTime processedAt;

  private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public RegionInstance getRegionInstance() {
    return regionInstance;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setRegionInstance(RegionInstance regionInstance) {
    this.regionInstance = regionInstance;
  }
}
