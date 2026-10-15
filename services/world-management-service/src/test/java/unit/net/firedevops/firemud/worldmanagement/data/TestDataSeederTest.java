package net.firedevops.firemud.worldmanagement.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.DefaultApplicationArguments;

class TestDataSeederTest {
  @Mock RegionRepository regionRepository;
  @Mock ZoneRepository zoneRepository;
  @Mock RoomRepository roomRepository;
  @Mock RoomExitRepository roomExitRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    seeder =
        new TestDataSeeder(regionRepository, zoneRepository, roomRepository, roomExitRepository);
  }

  @Test
  void runSeedsCanonicalTopologyWhenMissing() throws Exception {
    when(regionRepository.findFirstByTenantIdAndVersionIdAndShardIdAndName(
            1L, 1L, 0, "Demo Region"))
        .thenReturn(Optional.empty());
    Region savedRegion = new Region();
    savedRegion.setId(10L);
    when(regionRepository.save(any())).thenReturn(savedRegion);
    when(zoneRepository.findFirstByTenantIdAndVersionIdAndRegionIdAndName(1L, 1L, 10L, "Demo Zone"))
        .thenReturn(Optional.empty());
    Zone savedZone = new Zone();
    savedZone.setId(20L);
    savedZone.setRegion(savedRegion);
    when(zoneRepository.save(any())).thenReturn(savedZone);
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Candle-lit Antechamber"))
        .thenReturn(Optional.empty());
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Smith's Annex"))
        .thenReturn(Optional.empty());
    when(roomRepository.saveSeededWithExplicitId(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 1021L, 2045L, "NORTH"))
        .thenReturn(Optional.empty());
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 2045L, 1021L, "SOUTH"))
        .thenReturn(Optional.empty());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
    verify(roomRepository, times(2)).saveSeededWithExplicitId(roomCaptor.capture());
    assertEquals(
        List.of(1021L, 2045L), roomCaptor.getAllValues().stream().map(Room::getId).toList());
    assertEquals(
        List.of("Candle-lit Antechamber", "Smith's Annex"),
        roomCaptor.getAllValues().stream().map(Room::getName).toList());
    assertEquals(
        List.of(
            "Stalactites drip along the northern wall while a faint draft carries the smell of damp"
                + " earth from the lower tunnels. Torches flicker in alcoves, casting motion into the"
                + " shadowy archway to the north.",
            "An anvil, banked coals, and orderly tool racks mark this alcove as a working annex off"
                + " the starter chamber."),
        roomCaptor.getAllValues().stream().map(Room::getDescription).toList());
    assertEquals(
        List.of(1L, 1L), roomCaptor.getAllValues().stream().map(Room::getVersionId).toList());
    assertEquals(
        List.of(1L, 1L), roomCaptor.getAllValues().stream().map(Room::getTenantId).toList());
    assertEquals(
        List.of(20L, 20L),
        roomCaptor.getAllValues().stream().map(room -> room.getZone().getId()).toList());
    ArgumentCaptor<RoomExit> exitCaptor = ArgumentCaptor.forClass(RoomExit.class);
    verify(roomExitRepository, times(2)).save(exitCaptor.capture());
    assertEquals(
        List.of("NORTH", "SOUTH"),
        exitCaptor.getAllValues().stream().map(RoomExit::getDirection).toList());
    assertEquals(1021L, exitCaptor.getAllValues().get(0).getFromRoom().getId());
    assertEquals(2045L, exitCaptor.getAllValues().get(0).getToRoom().getId());
    assertEquals(2045L, exitCaptor.getAllValues().get(1).getFromRoom().getId());
    assertEquals(1021L, exitCaptor.getAllValues().get(1).getToRoom().getId());
  }

  @Test
  void runReassertsCanonicalTopologyWhenRowsAlreadyExist() throws Exception {
    Region existingRegion = new Region();
    existingRegion.setId(10L);
    Zone existingZone = new Zone();
    existingZone.setId(20L);
    existingZone.setRegion(existingRegion);
    Room existingRoom1 = new Room();
    existingRoom1.setId(1021L);
    existingRoom1.setZone(existingZone);
    Room existingRoom2 = new Room();
    existingRoom2.setId(2045L);
    existingRoom2.setZone(existingZone);

    when(regionRepository.findFirstByTenantIdAndVersionIdAndShardIdAndName(
            1L, 1L, 0, "Demo Region"))
        .thenReturn(Optional.of(existingRegion));
    when(regionRepository.save(any())).thenReturn(existingRegion);
    when(zoneRepository.findFirstByTenantIdAndVersionIdAndRegionIdAndName(1L, 1L, 10L, "Demo Zone"))
        .thenReturn(Optional.of(existingZone));
    when(zoneRepository.save(any())).thenReturn(existingZone);
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Candle-lit Antechamber"))
        .thenReturn(Optional.of(existingRoom1));
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Smith's Annex"))
        .thenReturn(Optional.of(existingRoom2));
    when(roomRepository.saveSeededWithExplicitId(any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 1021L, 2045L, "NORTH"))
        .thenReturn(Optional.of(existingExit(40L)));
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 2045L, 1021L, "SOUTH"))
        .thenReturn(Optional.of(existingExit(41L)));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    verify(roomRepository, times(2)).saveSeededWithExplicitId(any());
    ArgumentCaptor<RoomExit> exitCaptor = ArgumentCaptor.forClass(RoomExit.class);
    verify(roomExitRepository, times(2)).save(exitCaptor.capture());
    assertEquals(
        List.of("NORTH", "SOUTH"),
        exitCaptor.getAllValues().stream().map(RoomExit::getDirection).toList());
    assertEquals(
        List.of(40L, 41L), exitCaptor.getAllValues().stream().map(RoomExit::getId).toList());
    assertEquals(1021L, exitCaptor.getAllValues().get(0).getFromRoom().getId());
    assertEquals(2045L, exitCaptor.getAllValues().get(0).getToRoom().getId());
    assertEquals(2045L, exitCaptor.getAllValues().get(1).getFromRoom().getId());
    assertEquals(1021L, exitCaptor.getAllValues().get(1).getToRoom().getId());
  }

  @Test
  void runFailsClosedWhenPersistedDemoRoomUsesAnotherIdentity() {
    Region existingRegion = new Region();
    existingRegion.setId(10L);
    Zone existingZone = new Zone();
    existingZone.setId(20L);
    existingZone.setRegion(existingRegion);
    Room starterRoom = new Room();
    starterRoom.setId(30L);
    starterRoom.setZone(existingZone);

    when(regionRepository.findFirstByTenantIdAndVersionIdAndShardIdAndName(
            1L, 1L, 0, "Demo Region"))
        .thenReturn(Optional.of(existingRegion));
    when(regionRepository.save(any())).thenReturn(existingRegion);
    when(zoneRepository.findFirstByTenantIdAndVersionIdAndRegionIdAndName(1L, 1L, 10L, "Demo Zone"))
        .thenReturn(Optional.of(existingZone));
    when(zoneRepository.save(any())).thenReturn(existingZone);
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Candle-lit Antechamber"))
        .thenReturn(Optional.of(starterRoom));

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> seeder.run(new DefaultApplicationArguments(new String[] {})));

    assertTrue(exception.getMessage().startsWith("DEMO_TEMPLATE_ROOM_ID_MISMATCH:"));
    verify(roomRepository, never()).saveSeededWithExplicitId(any());
    verify(roomExitRepository, never()).save(any());
  }

  private RoomExit existingExit(long id) {
    RoomExit exit = new RoomExit();
    exit.setId(id);
    return exit;
  }
}
