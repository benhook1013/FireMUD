package net.firedevops.firemud.worldmanagement.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.worldmanagement.config.SmokeDemoRuntimeSeedProperties;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.RegionInstance;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.RoomInstance;
import net.firedevops.firemud.worldmanagement.entity.RoomInstanceExit;
import net.firedevops.firemud.worldmanagement.entity.WorldInstance;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.entity.ZoneInstance;
import net.firedevops.firemud.worldmanagement.repository.RegionInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.WorldInstanceRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneInstanceRepository;
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
  @Mock WorldInstanceRepository worldInstanceRepository;
  @Mock RegionInstanceRepository regionInstanceRepository;
  @Mock ZoneInstanceRepository zoneInstanceRepository;
  @Mock RoomInstanceRepository roomInstanceRepository;
  @Mock RoomInstanceExitRepository roomInstanceExitRepository;

  private TestDataSeeder seeder;

  @BeforeEach
  void setup() {
    MockitoAnnotations.openMocks(this);
    SmokeDemoRuntimeSeedProperties smokeDemoRuntimeSeedProperties =
        new SmokeDemoRuntimeSeedProperties();
    SmokeDemoRuntimeSeedProperties.RuntimeTargetSeed target =
        new SmokeDemoRuntimeSeedProperties.RuntimeTargetSeed();
    target.setTenantId(1L);
    target.setGameInstanceId(1L);
    smokeDemoRuntimeSeedProperties.setTargets(List.of(target));
    seeder =
        new TestDataSeeder(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            worldInstanceRepository,
            regionInstanceRepository,
            zoneInstanceRepository,
            roomInstanceRepository,
            roomInstanceExitRepository,
            smokeDemoRuntimeSeedProperties);
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
    Room savedRoom1 = new Room();
    savedRoom1.setId(30L);
    savedRoom1.setZone(savedZone);
    savedRoom1.setName("Candle-lit Antechamber");
    Room savedRoom2 = new Room();
    savedRoom2.setId(31L);
    savedRoom2.setZone(savedZone);
    savedRoom2.setName("Smith's Annex");
    when(roomRepository.save(any())).thenReturn(savedRoom1, savedRoom2);
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 30L, 31L, "NORTH"))
        .thenReturn(Optional.empty());
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 31L, 30L, "SOUTH"))
        .thenReturn(Optional.empty());
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L))
        .thenReturn(Optional.empty());
    when(worldInstanceRepository.save(any()))
        .thenAnswer(invocation -> withWorldInstanceId(invocation.getArgument(0)));
    when(regionInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L)).thenReturn(List.of());
    when(regionInstanceRepository.save(any()))
        .thenAnswer(invocation -> withRegionInstanceId(invocation.getArgument(0)));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    verify(roomRepository, times(2)).save(any());
    verify(worldInstanceRepository).save(any());
    verify(regionInstanceRepository).save(any());
    ArgumentCaptor<net.firedevops.firemud.worldmanagement.entity.RoomExit> exitCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.worldmanagement.entity.RoomExit.class);
    verify(roomExitRepository, times(2)).save(exitCaptor.capture());
    assertEquals(
        List.of("NORTH", "SOUTH"),
        exitCaptor.getAllValues().stream().map(RoomExit::getDirection).toList());
    assertEquals(30L, exitCaptor.getAllValues().get(0).getFromRoom().getId());
    assertEquals(31L, exitCaptor.getAllValues().get(0).getToRoom().getId());
    assertEquals(31L, exitCaptor.getAllValues().get(1).getFromRoom().getId());
    assertEquals(30L, exitCaptor.getAllValues().get(1).getToRoom().getId());
  }

  @Test
  void runReassertsCanonicalTopologyWhenRowsAlreadyExist() throws Exception {
    Region existingRegion = new Region();
    existingRegion.setId(10L);
    Zone existingZone = new Zone();
    existingZone.setId(20L);
    existingZone.setRegion(existingRegion);
    Room existingRoom1 = new Room();
    existingRoom1.setId(30L);
    existingRoom1.setZone(existingZone);
    Room existingRoom2 = new Room();
    existingRoom2.setId(31L);
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
    when(roomRepository.save(any())).thenReturn(existingRoom1, existingRoom2);
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 30L, 31L, "NORTH"))
        .thenReturn(Optional.of(existingExit(40L)));
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 31L, 30L, "SOUTH"))
        .thenReturn(Optional.of(existingExit(41L)));
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L))
        .thenReturn(Optional.empty());
    when(worldInstanceRepository.save(any()))
        .thenAnswer(invocation -> withWorldInstanceId(invocation.getArgument(0)));
    when(regionInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L)).thenReturn(List.of());
    when(regionInstanceRepository.save(any()))
        .thenAnswer(invocation -> withRegionInstanceId(invocation.getArgument(0)));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    verify(roomRepository, times(2)).save(any());
    ArgumentCaptor<RoomExit> exitCaptor = ArgumentCaptor.forClass(RoomExit.class);
    verify(roomExitRepository, times(2)).save(exitCaptor.capture());
    assertEquals(
        List.of("NORTH", "SOUTH"),
        exitCaptor.getAllValues().stream().map(RoomExit::getDirection).toList());
    assertEquals(
        List.of(40L, 41L), exitCaptor.getAllValues().stream().map(RoomExit::getId).toList());
    verify(regionRepository, never()).count();
  }

  @Test
  void runReassertsRuntimeTopologyWhenWorldAlreadyExistsButRoomsAreMissing() throws Exception {
    Region existingRegion = new Region();
    existingRegion.setId(10L);
    Zone existingZone = new Zone();
    existingZone.setId(20L);
    existingZone.setRegion(existingRegion);
    Room templateRoom1 = new Room();
    templateRoom1.setId(30L);
    templateRoom1.setZone(existingZone);
    templateRoom1.setName("Candle-lit Antechamber");
    templateRoom1.setDescription("starter");
    Room templateRoom2 = new Room();
    templateRoom2.setId(31L);
    templateRoom2.setZone(existingZone);
    templateRoom2.setName("Smith's Annex");
    templateRoom2.setDescription("annex");
    RoomExit templateExit = new RoomExit();
    templateExit.setFromRoom(templateRoom1);
    templateExit.setToRoom(templateRoom2);
    templateExit.setDirection("NORTH");
    templateExit.setCost(1);
    RoomExit reverseTemplateExit = new RoomExit();
    reverseTemplateExit.setFromRoom(templateRoom2);
    reverseTemplateExit.setToRoom(templateRoom1);
    reverseTemplateExit.setDirection("SOUTH");
    reverseTemplateExit.setCost(1);
    WorldInstance existingWorld = new WorldInstance();
    existingWorld.setId(200L);
    existingWorld.setTenantId(1L);
    existingWorld.setGameInstanceId(1L);
    existingWorld.setRowVersion(0L);

    when(regionRepository.findFirstByTenantIdAndVersionIdAndShardIdAndName(
            1L, 1L, 0, "Demo Region"))
        .thenReturn(Optional.of(existingRegion));
    when(regionRepository.save(any())).thenReturn(existingRegion);
    when(zoneRepository.findFirstByTenantIdAndVersionIdAndRegionIdAndName(1L, 1L, 10L, "Demo Zone"))
        .thenReturn(Optional.of(existingZone));
    when(zoneRepository.save(any())).thenReturn(existingZone);
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Candle-lit Antechamber"))
        .thenReturn(Optional.of(templateRoom1));
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Smith's Annex"))
        .thenReturn(Optional.of(templateRoom2));
    when(roomRepository.save(any())).thenReturn(templateRoom1, templateRoom2);
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 30L, 31L, "NORTH"))
        .thenReturn(Optional.of(templateExit));
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 31L, 30L, "SOUTH"))
        .thenReturn(Optional.of(reverseTemplateExit));
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L))
        .thenReturn(List.of(existingZone));
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L))
        .thenReturn(List.of(templateRoom1, templateRoom2));
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L))
        .thenReturn(List.of(templateExit, reverseTemplateExit));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L))
        .thenReturn(Optional.of(existingWorld));
    when(worldInstanceRepository.save(any()))
        .thenAnswer(invocation -> withWorldInstanceId(invocation.getArgument(0)));
    when(regionInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L)).thenReturn(List.of());
    when(regionInstanceRepository.save(any()))
        .thenAnswer(invocation -> withRegionInstanceId(invocation.getArgument(0)));
    when(zoneInstanceRepository.findByTenantIdAndGameInstanceIdAndZoneInstanceId(1L, 1L, 20L))
        .thenReturn(Optional.empty());
    when(zoneInstanceRepository.save(any()))
        .thenAnswer(invocation -> withZoneInstanceId(invocation.getArgument(0)));
    when(roomInstanceRepository.findByTenantIdAndGameInstanceIdOrderByRoomInstanceRowIdAsc(1L, 1L))
        .thenReturn(List.of());
    when(roomInstanceRepository.save(any()))
        .thenAnswer(invocation -> withRoomInstanceRowId(invocation.getArgument(0)));
    when(roomInstanceExitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(
            1L, 1L, 1021L))
        .thenReturn(List.of());
    when(roomInstanceExitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(
            1L, 1L, 2045L))
        .thenReturn(List.of());
    when(roomInstanceExitRepository.save(any()))
        .thenAnswer(invocation -> withRoomInstanceExitId(invocation.getArgument(0)));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(worldInstanceRepository).save(any());
    verify(regionInstanceRepository).save(any());
    verify(zoneInstanceRepository).save(any());
    verify(roomInstanceRepository, times(2)).save(any());
    ArgumentCaptor<RoomInstance> roomCaptor = ArgumentCaptor.forClass(RoomInstance.class);
    verify(roomInstanceRepository, times(2)).save(roomCaptor.capture());
    List<RoomInstance> savedRooms = roomCaptor.getAllValues();
    assertEquals(1021L, savedRooms.get(0).getRoomInstanceRowId());
    assertEquals(2045L, savedRooms.get(1).getRoomInstanceRowId());
    ArgumentCaptor<RoomInstanceExit> exitCaptor = ArgumentCaptor.forClass(RoomInstanceExit.class);
    verify(roomInstanceExitRepository, times(2)).save(exitCaptor.capture());
    assertEquals(
        List.of("NORTH", "SOUTH"),
        exitCaptor.getAllValues().stream().map(RoomInstanceExit::getDirection).toList());
    assertEquals(
        1021L, exitCaptor.getAllValues().get(0).getFromRoomInstance().getRoomInstanceRowId());
    assertEquals(
        2045L, exitCaptor.getAllValues().get(0).getToRoomInstance().getRoomInstanceRowId());
    assertEquals(
        2045L, exitCaptor.getAllValues().get(1).getFromRoomInstance().getRoomInstanceRowId());
    assertEquals(
        1021L, exitCaptor.getAllValues().get(1).getToRoomInstance().getRoomInstanceRowId());
  }

  @Test
  void runMaterializesRuntimeTopologyForNonDefaultTenantFromCanonicalDemoTemplate()
      throws Exception {
    SmokeDemoRuntimeSeedProperties properties = new SmokeDemoRuntimeSeedProperties();
    SmokeDemoRuntimeSeedProperties.RuntimeTargetSeed target =
        new SmokeDemoRuntimeSeedProperties.RuntimeTargetSeed();
    target.setTenantId(2L);
    target.setGameInstanceId(55L);
    properties.setTargets(List.of(target));
    seeder =
        new TestDataSeeder(
            regionRepository,
            zoneRepository,
            roomRepository,
            roomExitRepository,
            worldInstanceRepository,
            regionInstanceRepository,
            zoneInstanceRepository,
            roomInstanceRepository,
            roomInstanceExitRepository,
            properties);

    Region existingRegion = new Region();
    existingRegion.setId(10L);
    Zone existingZone = new Zone();
    existingZone.setId(20L);
    existingZone.setRegion(existingRegion);
    Room templateRoom1 = new Room();
    templateRoom1.setId(30L);
    templateRoom1.setZone(existingZone);
    templateRoom1.setName("Candle-lit Antechamber");
    templateRoom1.setDescription("starter");
    Room templateRoom2 = new Room();
    templateRoom2.setId(31L);
    templateRoom2.setZone(existingZone);
    templateRoom2.setName("Smith's Annex");
    templateRoom2.setDescription("annex");
    RoomExit templateExit = new RoomExit();
    templateExit.setFromRoom(templateRoom1);
    templateExit.setToRoom(templateRoom2);
    templateExit.setDirection("NORTH");
    templateExit.setCost(1);
    RoomExit reverseTemplateExit = new RoomExit();
    reverseTemplateExit.setFromRoom(templateRoom2);
    reverseTemplateExit.setToRoom(templateRoom1);
    reverseTemplateExit.setDirection("SOUTH");
    reverseTemplateExit.setCost(1);

    when(regionRepository.findFirstByTenantIdAndVersionIdAndShardIdAndName(
            1L, 1L, 0, "Demo Region"))
        .thenReturn(Optional.of(existingRegion));
    when(regionRepository.save(any())).thenReturn(existingRegion);
    when(zoneRepository.findFirstByTenantIdAndVersionIdAndRegionIdAndName(1L, 1L, 10L, "Demo Zone"))
        .thenReturn(Optional.of(existingZone));
    when(zoneRepository.save(any())).thenReturn(existingZone);
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Candle-lit Antechamber"))
        .thenReturn(Optional.of(templateRoom1));
    when(roomRepository.findFirstByTenantIdAndVersionIdAndZoneIdAndName(
            1L, 1L, 20L, "Smith's Annex"))
        .thenReturn(Optional.of(templateRoom2));
    when(roomRepository.save(any())).thenReturn(templateRoom1, templateRoom2);
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 30L, 31L, "NORTH"))
        .thenReturn(Optional.of(templateExit));
    when(roomExitRepository.findFirstByTenantIdAndVersionIdAndFromRoomIdAndToRoomIdAndDirection(
            1L, 1L, 31L, 30L, "SOUTH"))
        .thenReturn(Optional.of(reverseTemplateExit));
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L))
        .thenReturn(List.of(existingZone));
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L))
        .thenReturn(List.of(templateRoom1, templateRoom2));
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L))
        .thenReturn(List.of(templateExit, reverseTemplateExit));
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(2L, 55L))
        .thenReturn(Optional.empty());
    when(worldInstanceRepository.save(any()))
        .thenAnswer(invocation -> withWorldInstanceId(invocation.getArgument(0)));
    when(regionInstanceRepository.findByTenantIdAndGameInstanceId(2L, 55L)).thenReturn(List.of());
    when(regionInstanceRepository.save(any()))
        .thenAnswer(invocation -> withRegionInstanceId(invocation.getArgument(0)));
    when(zoneInstanceRepository.findByTenantIdAndGameInstanceIdAndZoneInstanceId(2L, 55L, 20L))
        .thenReturn(Optional.empty());
    when(zoneInstanceRepository.save(any()))
        .thenAnswer(invocation -> withZoneInstanceId(invocation.getArgument(0)));
    when(roomInstanceRepository.findByTenantIdAndGameInstanceIdOrderByRoomInstanceRowIdAsc(2L, 55L))
        .thenReturn(List.of());
    when(roomInstanceRepository.save(any()))
        .thenAnswer(invocation -> withRoomInstanceRowId(invocation.getArgument(0)));
    when(roomInstanceExitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(
            2L, 55L, 1021L))
        .thenReturn(List.of());
    when(roomInstanceExitRepository.findByTenantIdAndGameInstanceIdAndFromRoomInstanceRecordId(
            2L, 55L, 2045L))
        .thenReturn(List.of());
    when(roomInstanceExitRepository.save(any()))
        .thenAnswer(invocation -> withRoomInstanceExitId(invocation.getArgument(0)));

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(zoneRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L);
    verify(roomRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L);
    verify(roomExitRepository).findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L);
    verify(worldInstanceRepository).findByTenantIdAndGameInstanceId(2L, 55L);
    verify(zoneInstanceRepository).findByTenantIdAndGameInstanceIdAndZoneInstanceId(2L, 55L, 20L);
    verify(roomInstanceRepository, times(2)).save(any());
    ArgumentCaptor<RoomInstanceExit> exitCaptor = ArgumentCaptor.forClass(RoomInstanceExit.class);
    verify(roomInstanceExitRepository, times(2)).save(exitCaptor.capture());
    List<RoomInstanceExit> savedExits = exitCaptor.getAllValues();
    assertEquals(
        List.of("NORTH", "SOUTH"),
        savedExits.stream().map(RoomInstanceExit::getDirection).toList());
    assertEquals(2L, savedExits.get(0).getTenantId());
    assertEquals(55L, savedExits.get(0).getGameInstanceId());
    assertEquals(1021L, savedExits.get(0).getFromRoomInstance().getRoomInstanceRowId());
    assertEquals(2045L, savedExits.get(0).getToRoomInstance().getRoomInstanceRowId());
    assertEquals(2L, savedExits.get(1).getTenantId());
    assertEquals(55L, savedExits.get(1).getGameInstanceId());
    assertEquals(2045L, savedExits.get(1).getFromRoomInstance().getRoomInstanceRowId());
    assertEquals(1021L, savedExits.get(1).getToRoomInstance().getRoomInstanceRowId());
  }

  private WorldInstance withWorldInstanceId(WorldInstance worldInstance) {
    if (worldInstance.getId() == null) {
      worldInstance.setId(200L);
    }
    if (worldInstance.getRowVersion() == null) {
      worldInstance.setRowVersion(0L);
    }
    return worldInstance;
  }

  private RoomExit existingExit(long id) {
    RoomExit exit = new RoomExit();
    exit.setId(id);
    return exit;
  }

  private RegionInstance withRegionInstanceId(RegionInstance regionInstance) {
    if (regionInstance.getId() == null) {
      regionInstance.setId(300L);
    }
    return regionInstance;
  }

  private ZoneInstance withZoneInstanceId(ZoneInstance zoneInstance) {
    if (zoneInstance.getId() == null) {
      zoneInstance.setId(400L + zoneInstance.getZoneInstanceId());
    }
    return zoneInstance;
  }

  private RoomInstance withRoomInstanceRowId(RoomInstance roomInstance) {
    if (roomInstance.getId() == null) {
      roomInstance.setId(roomInstance.getRoomInstanceRowId());
    }
    return roomInstance;
  }

  private RoomInstanceExit withRoomInstanceExitId(RoomInstanceExit roomInstanceExit) {
    if (roomInstanceExit.getId() == null) {
      roomInstanceExit.setId(500L);
    }
    return roomInstanceExit;
  }
}
