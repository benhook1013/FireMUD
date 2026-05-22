package net.firedevops.firemud.worldmanagement.entity;

import lombok.Data;

@Data
public class RoomInstanceExit {
  private Long id;
  private Long tenantId;
  private Long gameInstanceId;
  private RoomInstance fromRoomInstance;
  private RoomInstance toRoomInstance;
  private String direction;
  private int cost = 1;

  private int version;
}
