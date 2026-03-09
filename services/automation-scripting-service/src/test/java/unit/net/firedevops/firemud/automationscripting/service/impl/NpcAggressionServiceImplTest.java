package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.NpcMemory;
import net.firedevops.firemud.automationscripting.model.AggressionState;
import net.firedevops.firemud.automationscripting.repository.NpcMemoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class NpcAggressionServiceImplTest {
  @Test
  void setAndGetAggressionState() {
    NpcMemoryRepository repository = Mockito.mock(NpcMemoryRepository.class);
    NpcAggressionServiceImpl service = new NpcAggressionServiceImpl(repository);

    when(repository.findByNpcIdAndKeyAndTenantId(2L, "aggression_state", 1L))
        .thenReturn(Optional.empty());

    ArgumentCaptor<NpcMemory> captor = ArgumentCaptor.forClass(NpcMemory.class);
    when(repository.save(captor.capture())).thenAnswer(i -> i.getArgument(0, NpcMemory.class));

    service.setAggressionState(1L, 2L, AggressionState.FLEEING);

    when(repository.findByNpcIdAndKeyAndTenantId(2L, "aggression_state", 1L))
        .thenReturn(Optional.of(captor.getValue()));

    AggressionState state = service.getAggressionState(1L, 2L);
    assertEquals(AggressionState.FLEEING, state);
  }
}
