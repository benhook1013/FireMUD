package net.firedevops.firemud.automationscripting.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.Faction;
import net.firedevops.firemud.automationscripting.entity.FactionStanding;
import net.firedevops.firemud.automationscripting.repository.FactionRepository;
import net.firedevops.firemud.automationscripting.repository.FactionStandingRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class FactionServiceImplTest {
  @Test
  void adjustReputationCreatesStanding() {
    FactionRepository factionRepository = Mockito.mock(FactionRepository.class);
    FactionStandingRepository standingRepository = Mockito.mock(FactionStandingRepository.class);
    FactionServiceImpl service = new FactionServiceImpl(factionRepository, standingRepository);

    Faction faction = new Faction();
    faction.setId(1L);
    when(factionRepository.findById(1L)).thenReturn(Optional.of(faction));
    when(standingRepository.findByTenantIdAndCharacterIdAndPlayableStateKeyAndFaction_Id(
            1L, 2L, "shared-live", 1L))
        .thenReturn(Optional.empty());
    ArgumentCaptor<FactionStanding> captor = ArgumentCaptor.forClass(FactionStanding.class);
    when(standingRepository.save(captor.capture()))
        .thenAnswer(i -> i.getArgument(0, FactionStanding.class));

    int result =
        service.adjustReputation(
            1L, 2L, "live", PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED, 1L, 5);

    assertEquals(5, result);
    assertEquals(5, captor.getValue().getReputation());
    assertEquals("shared-live", captor.getValue().getPlayableStateKey());
  }
}
