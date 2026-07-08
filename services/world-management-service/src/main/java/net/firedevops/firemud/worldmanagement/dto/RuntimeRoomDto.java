package net.firedevops.firemud.worldmanagement.dto;

import java.io.Serializable;

public record RuntimeRoomDto(
    Long roomInstanceRowId,
    Long tenantId,
    Long gameInstanceId,
    Long regionId,
    String name,
    String description)
    implements Serializable {
  private static final long serialVersionUID = 1L;
}
