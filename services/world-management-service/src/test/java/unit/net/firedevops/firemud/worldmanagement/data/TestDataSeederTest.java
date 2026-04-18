package net.firedevops.firemud.worldmanagement.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
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
  void runSeedsDataWhenRepositoriesEmpty() throws Exception {
    when(regionRepository.count()).thenReturn(0L);
    when(zoneRepository.findByTenantIdOrderByIdAsc(1L)).thenReturn(List.of());
    when(roomRepository.findByTenantIdOrderByIdAsc(1L)).thenReturn(List.of());
    when(roomExitRepository.findByTenantIdOrderByIdAsc(1L)).thenReturn(List.of());
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
}
