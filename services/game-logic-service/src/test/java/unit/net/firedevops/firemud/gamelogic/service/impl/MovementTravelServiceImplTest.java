package net.firedevops.firemud.gamelogic.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.firedevops.firemud.gamelogic.service.MovementTravelService;
import net.firedevops.firemud.gamelogic.service.MovementTravelService.RoomExit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MovementTravelServiceImplTest {
  private MovementTravelService service;

  @BeforeEach
  void setUp() {
    service = new MovementTravelServiceImpl();
  }

  @Test
  void findsSimplePath() {
    RoomExit e1 = new RoomExit(1L, 2L, 1, 1.0);
    RoomExit e2 = new RoomExit(2L, 3L, 1, 1.0);
    List<Long> path = service.findPath(List.of(e1, e2), 1L, 3L);
    assertEquals(List.of(1L, 2L, 3L), path);
  }
}
