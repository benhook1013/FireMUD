package net.firedevops.firemud.entitymanagement.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.entity.Npc;
import net.firedevops.firemud.entitymanagement.repository.NpcRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class NpcServiceImplTest {

  @Test
  void shouldRespawnAfterDelay() {
    NpcRepository repo = Mockito.mock(NpcRepository.class);
    NpcServiceImpl service = new NpcServiceImpl(repo);

    Npc npc = new Npc();
    npc.setId(1L);
    npc.setTenantId(1L);
    npc.setName("Goblin");
    npc.setBehavior("aggressive");
    npc.setRespawnDelaySeconds(60);
    npc.setLastDefeatedAt(Instant.now().minusSeconds(61));

    when(repo.findById(1L)).thenReturn(Optional.of(npc));

    assertTrue(service.shouldRespawn(1L));
  }
}
