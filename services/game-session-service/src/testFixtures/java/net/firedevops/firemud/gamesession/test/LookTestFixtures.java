package net.firedevops.firemud.gamesession.test;

import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.gamelogic.v1.LookExit;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import net.firedevops.firemud.worldmanagement.v1.RoomExitSnapshot;
import net.firedevops.firemud.worldmanagement.v1.RoomSnapshot;

public final class LookTestFixtures {
  public static final String TENANT = "demo";
  public static final String ROOM_ID = "R-1021";
  public static final String DESTINATION_ROOM_ID = "R-2045";
  public static final String ROOM_INSTANCE_ID = ROOM_ID;
  public static final String GAME_INSTANCE_ID = "game-inst-demo";
  public static final String ROOM_NAME = "Candle-lit Antechamber";
  public static final String DESTINATION_ROOM_NAME = "Crafting Hall of Ember";

  private LookTestFixtures() {}

  public static RoomSnapshot sampleRoomSnapshot() {
    return sampleRoomSnapshot(ROOM_INSTANCE_ID);
  }

  public static RoomSnapshot sampleRoomSnapshot(String roomInstanceId) {
    if (ROOM_INSTANCE_ID.equals(roomInstanceId)) {
      return sourceRoomSnapshot();
    }
    if (DESTINATION_ROOM_ID.equals(roomInstanceId)) {
      return destinationRoomSnapshot();
    }
    throw new IllegalArgumentException("Unknown room instance id: " + roomInstanceId);
  }

  private static RoomSnapshot sourceRoomSnapshot() {
    return RoomSnapshot.newBuilder()
        .setRoomInstanceId(ROOM_INSTANCE_ID)
        .setGameInstanceId(GAME_INSTANCE_ID)
        .setRoomName(ROOM_NAME)
        .setShortDescription(
            "You stand in a basalt chamber warmed by the brazier near the western wall.")
        .setLongDescription(
            "Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.")
        .addExits(
            RoomExitSnapshot.newBuilder()
                .setDirection("NORTH")
                .setLabel("NORTH")
                .setTargetRoomInstanceId(DESTINATION_ROOM_ID)
                .setDescription("arched passage leading toward the cavern mouth")
                .build())
        .addExits(
            RoomExitSnapshot.newBuilder()
                .setDirection("EAST")
                .setLabel("EAST")
                .setTargetRoomInstanceId("room-inst-3042")
                .setDescription("narrow fissure descending toward the forges")
                .build())
        .build();
  }

  private static RoomSnapshot destinationRoomSnapshot() {
    return RoomSnapshot.newBuilder()
        .setRoomInstanceId(DESTINATION_ROOM_ID)
        .setGameInstanceId(GAME_INSTANCE_ID)
        .setRoomName(DESTINATION_ROOM_NAME)
        .setShortDescription("A soot-dark hall ringed with anvils and cooling braziers.")
        .setLongDescription(
            "The hall hums with the quiet afterglow of recent work. Ember dust settles across iron tools, and a stairback passage leads south toward the antechamber.")
        .addExits(
            RoomExitSnapshot.newBuilder()
                .setDirection("SOUTH")
                .setLabel("SOUTH")
                .setTargetRoomInstanceId(ROOM_INSTANCE_ID)
                .setDescription("return path to the antechamber")
                .build())
        .build();
  }

  public static ListRoomEntitiesResponse sampleEntities() {
    RoomEntity kobold =
        RoomEntity.newBuilder()
            .setEntityId("NPC-001")
            .setDisplayName("Kobold Scout")
            .setEntityType(EntityType.NPC)
            .setRole("scout")
            .addStateFlags("isAlert")
            .build();
    RoomEntity player =
        RoomEntity.newBuilder()
            .setEntityId("PLAYER-199")
            .setDisplayName("Sora")
            .setEntityType(EntityType.PLAYER)
            .setRole("adventurer")
            .build();
    RoomEntity item =
        RoomEntity.newBuilder()
            .setEntityId("ITEM-009")
            .setDisplayName("Backpack")
            .setEntityType(EntityType.ITEM)
            .addStateFlags("room-ground")
            .addStateFlags("container")
            .addStateFlags("wearable:BACK")
            .build();
    return ListRoomEntitiesResponse.newBuilder()
        .addEntities(kobold)
        .addEntities(player)
        .addEntities(item)
        .build();
  }

  public static String canonicalLookText() {
    return canonicalLookText(ROOM_INSTANCE_ID);
  }

  public static String canonicalLookText(String roomInstanceId) {
    if (ROOM_INSTANCE_ID.equals(roomInstanceId)) {
      return String.join(
          "\n",
          "OK LOOK",
          "Room: Candle-lit Antechamber (ID: R-1021)",
          "Short: You stand in a basalt chamber warmed by the brazier near the western wall.",
          "Long: Stalactites drip along the northern wall while a faint draft carries the smell of damp earth from the lower tunnels. Torches flicker in alcoves, casting motion into the shadowy archway to the north.",
          "Exits: NORTH (arched passage leading toward the cavern mouth), EAST (narrow fissure descending toward the forges)",
          "Entities:",
          "- NPC \"Kobold Scout\" (scout) [isAlert]",
          "- PLAYER \"Sora\" (adventurer)",
          "- ITEM \"Backpack\" [room-ground; affordances: container, wearable BACK]",
          "");
    }
    if (DESTINATION_ROOM_ID.equals(roomInstanceId)) {
      return String.join(
          "\n",
          "OK LOOK",
          "Room: Crafting Hall of Ember (ID: R-2045)",
          "Short: A soot-dark hall ringed with anvils and cooling braziers.",
          "Long: The hall hums with the quiet afterglow of recent work. Ember dust settles across iron tools, and a stairback passage leads south toward the antechamber.",
          "Exits: SOUTH (return path to the antechamber)",
          "Entities:",
          "- NPC \"Kobold Scout\" (scout) [isAlert]",
          "- PLAYER \"Sora\" (adventurer)",
          "- ITEM \"Backpack\" [room-ground; affordances: container, wearable BACK]",
          "");
    }
    throw new IllegalArgumentException("Unknown room instance id: " + roomInstanceId);
  }

  public static LookResult sampleLookResult() {
    return sampleLookResult(ROOM_INSTANCE_ID);
  }

  public static LookResult sampleLookResult(String roomInstanceId) {
    RoomSnapshot snapshot = sampleRoomSnapshot(roomInstanceId);
    LookExit primaryExit =
        LookExit.newBuilder()
            .setLabel(ROOM_INSTANCE_ID.equals(roomInstanceId) ? "NORTH" : "SOUTH")
            .setTargetRoomInstanceId(
                ROOM_INSTANCE_ID.equals(roomInstanceId) ? DESTINATION_ROOM_ID : ROOM_INSTANCE_ID)
            .setDescription(
                ROOM_INSTANCE_ID.equals(roomInstanceId)
                    ? "arched passage leading toward the cavern mouth"
                    : "return path to the antechamber")
            .build();
    LookExit secondaryExit =
        LookExit.newBuilder()
            .setLabel("EAST")
            .setTargetRoomInstanceId("room-inst-3042")
            .setDescription("narrow fissure descending toward the forges")
            .build();
    net.firedevops.firemud.gamelogic.v1.RoomEntity koboldEntity =
        net.firedevops.firemud.gamelogic.v1.RoomEntity.newBuilder()
            .setEntityId("NPC-001")
            .setDisplayName("Kobold Scout")
            .setEntityType(net.firedevops.firemud.gamelogic.v1.EntityType.NPC)
            .setRole("scout")
            .addStateFlags("isAlert")
            .build();
    net.firedevops.firemud.gamelogic.v1.RoomEntity playerEntity =
        net.firedevops.firemud.gamelogic.v1.RoomEntity.newBuilder()
            .setEntityId("PLAYER-199")
            .setDisplayName("Sora")
            .setEntityType(net.firedevops.firemud.gamelogic.v1.EntityType.PLAYER)
            .setRole("adventurer")
            .build();
    net.firedevops.firemud.gamelogic.v1.RoomEntity itemEntity =
        net.firedevops.firemud.gamelogic.v1.RoomEntity.newBuilder()
            .setEntityId("ITEM-009")
            .setDisplayName("Backpack")
            .setEntityType(net.firedevops.firemud.gamelogic.v1.EntityType.ITEM)
            .addStateFlags("room-ground")
            .addStateFlags("container")
            .addStateFlags("wearable:BACK")
            .build();

    LookResult.Builder builder =
        LookResult.newBuilder()
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(TENANT)
                    .setGameInstanceId(GAME_INSTANCE_ID)
                    .setRoomInstanceId(roomInstanceId)
                    .build())
            .setRoomName(snapshot.getRoomName())
            .setShortDescription(snapshot.getShortDescription())
            .setLongDescription(snapshot.getLongDescription())
            .addEntities(koboldEntity)
            .addEntities(playerEntity)
            .addEntities(itemEntity);
    builder.addExits(primaryExit);
    if (ROOM_INSTANCE_ID.equals(roomInstanceId)) {
      builder.addExits(secondaryExit);
    }
    return builder.build();
  }
}
