package net.firedevops.firemud.gamesession.test;

import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;

public final class ChatTestFixtures {
  public static final String ROOM_ID = "R-1021";
  public static final String TENANT = "demo";
  public static final String PLAYER_EMBERLINE = "7";
  public static final String PLAYER_SORA = "8";

  private ChatTestFixtures() {}

  public static ListRoomEntitiesResponse sampleEntities() {
    RoomEntity emberline =
        RoomEntity.newBuilder()
            .setEntityId(PLAYER_EMBERLINE)
            .setDisplayName("Emberline")
            .setEntityType(EntityType.PLAYER)
            .setRole("emitter")
            .build();
    RoomEntity sora =
        RoomEntity.newBuilder()
            .setEntityId(PLAYER_SORA)
            .setDisplayName("Sora")
            .setEntityType(EntityType.PLAYER)
            .setRole("listener")
            .build();
    RoomEntity kobold =
        RoomEntity.newBuilder()
            .setEntityId("NPC-001")
            .setDisplayName("Kobold Scout")
            .setEntityType(EntityType.NPC)
            .setRole("scout")
            .build();
    return ListRoomEntitiesResponse.newBuilder()
        .addEntities(emberline)
        .addEntities(sora)
        .addEntities(kobold)
        .build();
  }

  public static String canonicalSayText() {
    return String.join(
        "\n",
        "OK SAY",
        "Speaker: Emberline",
        "Delivered-To: Emberline, Kobold Scout, Sora",
        "Message: Hello travelers");
  }
}
