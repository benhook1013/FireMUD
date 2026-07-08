package net.firedevops.firemud.gamelogic.service;

import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;

final class RuntimeRoomInstanceRefs {
  private RuntimeRoomInstanceRefs() {}

  static RoomInstanceRef requireCanonical(RoomInstanceRef roomInstance) {
    return roomInstance.toBuilder()
        .setRoomInstanceId(
            RequestIdValidation.requireCanonicalRuntimeRoomId(
                roomInstance.getRoomInstanceId(), "room_instance.room_instance_id"))
        .build();
  }
}
