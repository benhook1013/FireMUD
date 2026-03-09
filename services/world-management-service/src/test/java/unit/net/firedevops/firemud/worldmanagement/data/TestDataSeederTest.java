package net.firedevops.firemud.worldmanagement.data;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  void runSeedsDataWhenRepositoriesEmpty() throws Exception {
    when(regionRepository.count()).thenReturn(0L);

    seeder.run(new DefaultApplicationArguments(new String[] {}));

    verify(regionRepository).save(any());
    verify(zoneRepository).save(any());
    verify(roomRepository, times(2)).save(any());
    verify(roomExitRepository).save(any());
  }
}
