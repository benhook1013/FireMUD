package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.firedevops.firemud.automationscripting.model.PveEvent;
import org.junit.jupiter.api.Test;

class PveEncounterServiceImplTest {
  @Test
  void generateEventIsDeterministicForSeed() {
    PveEncounterServiceImpl svc = new PveEncounterServiceImpl();
    PveEvent e1 = svc.generateEvent("forest", 123L);
    PveEvent e2 = svc.generateEvent("forest", 123L);
    assertEquals(e1, e2);
  }
}
