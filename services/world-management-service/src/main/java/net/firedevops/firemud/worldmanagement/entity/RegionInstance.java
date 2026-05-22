package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class RegionInstance {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private WorldInstance worldInstance;
  private Integer shardId = 0;
  private String name;
  private String weather;
  private Long generationSeed = 0L;
  private String generatorType;
  private String generatorParams;
  private Double spacingMultiplier = 1.0;

  private int version;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "JPA association is intentionally exposed")
  public WorldInstance getWorldInstance() {
    return worldInstance;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "JPA association stored directly")
  public void setWorldInstance(WorldInstance worldInstance) {
    this.worldInstance = worldInstance;
  }
}
