package net.firedevops.firemud.automationscripting.service.impl;

import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.automationscripting.entity.NpcMemory;
import net.firedevops.firemud.automationscripting.model.AggressionState;
import net.firedevops.firemud.automationscripting.repository.NpcMemoryRepository;
import net.firedevops.firemud.automationscripting.service.NpcAggressionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NpcAggressionServiceImpl implements NpcAggressionService {
  private static final String AGGRESSION_KEY = "aggression_state";

  private final NpcMemoryRepository memoryRepository;

  @Override
  @Transactional
  @Timed(value = "npc.setAggressionState")
  public void setAggressionState(Long tenantId, Long npcId, AggressionState state) {
    NpcMemory mem =
        memoryRepository
            .findByNpcIdAndKeyAndTenantId(npcId, AGGRESSION_KEY, tenantId)
            .orElseGet(
                () -> {
                  NpcMemory newMem = new NpcMemory();
                  newMem.setNpcId(npcId);
                  newMem.setKey(AGGRESSION_KEY);
                  newMem.setTenantId(tenantId);
                  return newMem;
                });
    mem.setValue(state.name());
    memoryRepository.save(mem);
  }

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "npc.getAggressionState")
  public AggressionState getAggressionState(Long tenantId, Long npcId) {
    return memoryRepository
        .findByNpcIdAndKeyAndTenantId(npcId, AGGRESSION_KEY, tenantId)
        .map(m -> AggressionState.valueOf(m.getValue()))
        .orElse(AggressionState.NEUTRAL);
  }
}
