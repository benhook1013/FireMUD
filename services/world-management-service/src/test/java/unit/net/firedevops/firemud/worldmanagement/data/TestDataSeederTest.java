package net.firedevops.firemud.worldmanagement.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.Zone;
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
    GameplayCatalogProperties gameplayCatalogProperties = new GameplayCatalogProperties();
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
            gameplayCatalogProperties);
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
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L))
        .thenReturn(Optional.empty());
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.empty());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    verify(roomRepository, times(2)).save(any());
    verify(worldInstanceRepository, times(2)).save(any());
    ArgumentCaptor<net.firedevops.firemud.worldmanagement.entity.RoomExit> exitCaptor =
        ArgumentCaptor.forClass(net.firedevops.firemud.worldmanagement.entity.RoomExit.class);
    verify(roomExitRepository).save(exitCaptor.capture());
    assertEquals("NORTH", exitCaptor.getValue().getDirection());
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
        .thenReturn(Optional.of(new net.firedevops.firemud.worldmanagement.entity.RoomExit()));
    when(zoneRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(roomExitRepository.findByTenantIdAndVersionIdOrderByIdAsc(1L, 1L)).thenReturn(List.of());
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 1L))
        .thenReturn(Optional.empty());
    when(worldInstanceRepository.findByTenantIdAndGameInstanceId(1L, 2L))
        .thenReturn(Optional.empty());

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    verify(roomRepository, times(2)).save(any());
    verify(roomExitRepository).save(any());
    verify(regionRepository, never()).count();
  }
}
