package net.firedevops.firemud.gamesession.test;

import net.firedevops.firemud.entitymanagement.v1.Character;
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

  public static Character characterByName(String name) {
    if ("Emberline".equalsIgnoreCase(name)) {
      return Character.newBuilder()
          .setId(PLAYER_EMBERLINE)
          .setTenantId("1")
          .setAccountId("7")
          .setName("Emberline")
          .build();
    }
    if ("Sora".equalsIgnoreCase(name)) {
      return Character.newBuilder()
          .setId(PLAYER_SORA)
          .setTenantId("1")
          .setAccountId("8")
          .setName("Sora")
          .build();
    }
    return Character.getDefaultInstance();
  }

  public static String canonicalSayText() {
    return "You say, \"Hello travelers\"";
  }

  public static String canonicalWhisperText() {
    return "You whisper to Sora, \"Keep quiet\"";
  }

  public static String canonicalWhisperTargetText() {
    return "Emberline whispers to you, \"Keep quiet\"";
  }

  public static String canonicalWhisperObserverMetadataText() {
    return "Emberline whispers something to Sora.";
  }

  public static String canonicalTellText() {
    return "You tell Sora, \"Meet me at the forge\"";
  }

  public static String canonicalTellTargetText() {
    return "Emberline tells you, \"Meet me at the forge\"";
  }
}
