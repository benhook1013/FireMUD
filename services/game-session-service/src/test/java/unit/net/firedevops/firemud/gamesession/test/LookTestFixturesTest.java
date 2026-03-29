package net.firedevops.firemud.gamesession.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LookTestFixturesTest {

  @Test
  void sampleSnapshotsAreKeyedByRoomInstanceId() {
    assertEquals(
        LookTestFixtures.ROOM_NAME,
        LookTestFixtures.sampleRoomSnapshot(LookTestFixtures.ROOM_INSTANCE_ID).getRoomName());
    assertEquals(
        LookTestFixtures.DESTINATION_ROOM_NAME,
        LookTestFixtures.sampleRoomSnapshot(LookTestFixtures.DESTINATION_ROOM_ID).getRoomName());
    assertEquals(
        "NORTH",
        LookTestFixtures.sampleRoomSnapshot(LookTestFixtures.ROOM_INSTANCE_ID)
            .getExits(0)
            .getDirection());
    assertEquals(
        "SOUTH",
        LookTestFixtures.sampleRoomSnapshot(LookTestFixtures.DESTINATION_ROOM_ID)
            .getExits(0)
            .getDirection());
  }

  @Test
  void canonicalLookTextVariesByRoomInstanceId() {
    String source = LookTestFixtures.canonicalLookText(LookTestFixtures.ROOM_INSTANCE_ID);
    String destination = LookTestFixtures.canonicalLookText(LookTestFixtures.DESTINATION_ROOM_ID);

    assertTrue(source.contains("Candle-lit Antechamber"));
    assertTrue(destination.contains("Crafting Hall of Ember"));
    assertTrue(source.contains("NORTH (arched passage leading toward the cavern mouth)"));
    assertTrue(destination.contains("SOUTH (return path to the antechamber)"));
  }

  @Test
  void sampleLookResultIsKeyedByRoomInstanceId() {
    assertEquals(
        LookTestFixtures.ROOM_INSTANCE_ID,
        LookTestFixtures.sampleLookResult(LookTestFixtures.ROOM_INSTANCE_ID)
            .getRoomInstance()
            .getRoomInstanceId());
    assertEquals(
        LookTestFixtures.DESTINATION_ROOM_ID,
        LookTestFixtures.sampleLookResult(LookTestFixtures.DESTINATION_ROOM_ID)
            .getRoomInstance()
            .getRoomInstanceId());
    assertEquals(
        "NORTH",
        LookTestFixtures.sampleLookResult(LookTestFixtures.ROOM_INSTANCE_ID)
            .getExits(0)
            .getLabel());
    assertEquals(
        "SOUTH",
        LookTestFixtures.sampleLookResult(LookTestFixtures.DESTINATION_ROOM_ID)
            .getExits(0)
            .getLabel());
  }

  @Test
  void unknownRoomInstanceIdFailsFast() {
    assertThrows(
        IllegalArgumentException.class, () -> LookTestFixtures.sampleRoomSnapshot("unknown-room"));
  }
}
