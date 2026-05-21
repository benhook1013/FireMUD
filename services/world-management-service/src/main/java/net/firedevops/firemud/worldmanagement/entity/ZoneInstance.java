package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class ZoneInstance {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private Long zoneInstanceId;
  private Long templateZoneId;
  private RegionInstance regionInstance;
  private String name;

  private int version;
}
