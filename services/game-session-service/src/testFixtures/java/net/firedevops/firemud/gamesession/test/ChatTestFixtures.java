package net.firedevops.firemud.gamesession.test;

import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamesession.service.SessionContext;

public final class ChatTestFixtures {
  public static final String ROOM_ID = "R-1021";
  public static final String TENANT = "demo";
  // Account database coordinates and character coordinates are independent fixture facts.
  public static final long EMBERLINE_ACCOUNT_ROW_ID = 7L;
  public static final long SORA_ACCOUNT_ROW_ID = 8L;
  public static final long NYX_ACCOUNT_ROW_ID = 9L;
  public static final long EMBERLINE_CHARACTER_ID = 7L;
  public static final long SORA_CHARACTER_ID = 8L;
  public static final long NYX_CHARACTER_ID = 9L;
  public static final String PLAYER_EMBERLINE = Long.toString(EMBERLINE_CHARACTER_ID);
  public static final String PLAYER_SORA = Long.toString(SORA_CHARACTER_ID);
  public static final String PLAYER_NYX = Long.toString(NYX_CHARACTER_ID);

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
    RoomEntity nyx =
        RoomEntity.newBuilder()
            .setEntityId(PLAYER_NYX)
            .setDisplayName("Nyx")
            .setEntityType(EntityType.PLAYER)
            .setRole("observer")
            .addStateFlags("observer_metadata_only")
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
        .addEntities(nyx)
        .addEntities(kobold)
        .build();
  }

  public static Character characterByName(String name) {
    if ("Emberline".equalsIgnoreCase(name)) {
      return Character.newBuilder()
          .setId(PLAYER_EMBERLINE)
          .setTenantId("1")
          .setAccountId(Long.toString(EMBERLINE_ACCOUNT_ROW_ID))
          .setName("Emberline")
          .build();
    }
    if ("Sora".equalsIgnoreCase(name)) {
      return Character.newBuilder()
          .setId(PLAYER_SORA)
          .setTenantId("1")
          .setAccountId(Long.toString(SORA_ACCOUNT_ROW_ID))
          .setName("Sora")
          .build();
    }
    if ("Nyx".equalsIgnoreCase(name)) {
      return Character.newBuilder()
          .setId(PLAYER_NYX)
          .setTenantId("1")
          .setAccountId(Long.toString(NYX_ACCOUNT_ROW_ID))
          .setName("Nyx")
          .build();
    }
    return Character.getDefaultInstance();
  }

  public static SessionContext soraRecipientSession(
      long sessionId, long tenantId, long gameInstanceId) {
    Character sora = characterByName("Sora");
    return new SessionContext(
        sessionId,
        tenantId,
        sora.getAccountId(),
        "sora@example.com",
        SORA_CHARACTER_ID,
        sora.getName(),
        gameInstanceId,
        ROOM_ID,
        "target-jwt",
        null,
        gameInstanceId,
        "demo",
        "production",
        1L,
        "SHARED");
  }

  public static String canonicalSayText() {
    return "You say, \"Hello travelers.\"";
  }

  public static String canonicalSayListenerText() {
    return "Emberline says, \"Hello travelers.\"";
  }

  public static String canonicalWhisperText() {
    return "You whisper to Sora, \"Keep quiet.\"";
  }

  public static String canonicalWhisperTargetText() {
    return "Emberline whispers to you, \"Keep quiet.\"";
  }

  public static String canonicalWhisperObserverMetadataText() {
    return "Emberline whispers something to Sora.";
  }

  public static String canonicalTellText() {
    return "You tell Sora, \"Meet me at the forge.\"";
  }

  public static String canonicalTellTargetText() {
    return "Emberline tells you, \"Meet me at the forge.\"";
  }
}
