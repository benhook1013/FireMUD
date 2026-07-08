package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class RoomInstance {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private Long roomInstanceRowId;
  private Long templateRoomId;
  private RegionInstance regionInstance;
  private ZoneInstance zoneInstance;
  private String name;
  private String description;
  private String nameLocalizedVariantsJson;
  private String descriptionLocalizedVariantsJson;

  private int version;
}
