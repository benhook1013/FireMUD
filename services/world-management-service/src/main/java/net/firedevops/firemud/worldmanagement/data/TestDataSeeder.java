package net.firedevops.firemud.worldmanagement.data;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.worldmanagement.entity.Region;
import net.firedevops.firemud.worldmanagement.entity.Room;
import net.firedevops.firemud.worldmanagement.entity.RoomExit;
import net.firedevops.firemud.worldmanagement.entity.Zone;
import net.firedevops.firemud.worldmanagement.repository.RegionRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomExitRepository;
import net.firedevops.firemud.worldmanagement.repository.RoomRepository;
import net.firedevops.firemud.worldmanagement.repository.ZoneRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds minimal world data for local development when the {@code dev} Spring profile is active. */
@Component
@Profile("dev")
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Repositories are injected for seeding and not exposed")
public class TestDataSeeder implements ApplicationRunner {
  private final RegionRepository regionRepository;
  private final ZoneRepository zoneRepository;
  private final RoomRepository roomRepository;
  private final RoomExitRepository roomExitRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (regionRepository.count() == 0) {
      Region region = new Region();
      region.setTenantId(1L);
      region.setShardId(0);
      region.setName("Demo Region");
      regionRepository.save(region);

      Zone zone = new Zone();
      zone.setTenantId(1L);
      zone.setRegion(region);
      zone.setName("Demo Zone");
      zoneRepository.save(zone);

      Room room1 = new Room();
      room1.setTenantId(1L);
      room1.setZone(zone);
      room1.setName("Room A");
      room1.setDescription("Seed room A");
      roomRepository.save(room1);

      Room room2 = new Room();
      room2.setTenantId(1L);
      room2.setZone(zone);
      room2.setName("Room B");
      room2.setDescription("Seed room B");
      roomRepository.save(room2);

      RoomExit exit = new RoomExit();
      exit.setTenantId(1L);
      exit.setFromRoom(room1);
      exit.setToRoom(room2);
      exit.setDirection("NORTH");
      roomExitRepository.save(exit);
    }
  }
}
