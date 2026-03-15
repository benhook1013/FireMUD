package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.entitymanagement.entity.Npc;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import net.firedevops.firemud.entitymanagement.service.NpcService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NpcServiceImpl implements NpcService {

  private final NpcRepository npcRepository;

  @Override
  @Transactional(readOnly = true)
  @Timed(value = "npc.shouldRespawn")
  public boolean shouldRespawn(Long npcId) {
    Npc npc = npcRepository.findById(npcId).orElseThrow();
    if (npc.getLastDefeatedAt() == null) {
      return false;
    }
    return npc.getLastDefeatedAt()
        .plusSeconds(npc.getRespawnDelaySeconds())
        .isBefore(Instant.now());
  }
}
