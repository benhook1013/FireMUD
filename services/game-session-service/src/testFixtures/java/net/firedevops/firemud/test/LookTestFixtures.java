package net.firedevops.firemud.test;

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
  public static final String ROOM_INSTANCE_ID = "room-inst-1021";
  public static final String GAME_INSTANCE_ID = "game-inst-demo";
  public static final String ROOM_NAME = "Candle-lit Antechamber";

  private LookTestFixtures() {}

  public static RoomSnapshot sampleRoomSnapshot() {
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
                .setLabel("NORTH")
                .setTargetRoomInstanceId("room-inst-3042")
                .setDescription("arched passage leading toward the cavern mouth")
                .build())
        .addExits(
            RoomExitSnapshot.newBuilder()
                .setLabel("EAST")
                .setTargetRoomInstanceId("room-inst-2045")
                .setDescription("narrow fissure descending toward the forges")
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
    return ListRoomEntitiesResponse.newBuilder().addEntities(kobold).addEntities(player).build();
  }

  public static String canonicalLookText() {
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
        "");
  }

  public static LookResult sampleLookResult() {
    LookExit northExit =
        LookExit.newBuilder()
            .setLabel("NORTH")
            .setTargetRoomInstanceId("room-inst-3042")
            .setDescription("arched passage leading toward the cavern mouth")
            .build();
    LookExit eastExit =
        LookExit.newBuilder()
            .setLabel("EAST")
            .setTargetRoomInstanceId("room-inst-2045")
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

    return LookResult.newBuilder()
        .setRoomInstance(
            RoomInstanceRef.newBuilder()
                .setTenantId(TENANT)
                .setGameInstanceId(GAME_INSTANCE_ID)
                .setRoomInstanceId(ROOM_INSTANCE_ID)
                .build())
        .setRoomName(ROOM_NAME)
        .setShortDescription(sampleRoomSnapshot().getShortDescription())
        .setLongDescription(sampleRoomSnapshot().getLongDescription())
        .addExits(northExit)
        .addExits(eastExit)
        .addEntities(koboldEntity)
        .addEntities(playerEntity)
        .build();
  }
}
