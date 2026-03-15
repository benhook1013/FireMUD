package net.firedevops.firemud.automationscripting.service.advanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import net.firedevops.firemud.automationscripting.model.AggressionState;
import net.firedevops.firemud.automationscripting.service.NpcAggressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NpcMoraleServiceImplTest {
  private NpcMoraleServiceImpl service;
  private NpcAggressionService aggressionService;

  @BeforeEach
  void setup() {
    aggressionService = mock(NpcAggressionService.class);
    service = new NpcMoraleServiceImpl(aggressionService);
  }

  @Test
  void evaluateState_returnsSurrendered_whenVeryLow() {
    AggressionState state = service.evaluateState(5, 8, -60);
    assertEquals(AggressionState.SURRENDERED, state);
  }

  @Test
  void evaluateState_returnsFleeing_whenLow() {
    AggressionState state = service.evaluateState(20, 30, -25);
    assertEquals(AggressionState.FLEEING, state);
  }

  @Test
  void updateState_setsAggression() {
    service.updateState(1L, 2L, 5, 5, -70);
    verify(aggressionService).setAggressionState(1L, 2L, AggressionState.SURRENDERED);
  }
}
