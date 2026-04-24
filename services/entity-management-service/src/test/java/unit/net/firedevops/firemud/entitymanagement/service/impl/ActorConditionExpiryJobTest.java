package net.firedevops.firemud.entitymanagement.service.impl;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ActorConditionExpiryJobTest {
  @Test
  void scheduledJobDelegatesExpiryToMutationServiceClock() {
    ActorConditionMutationService mutationService =
        Mockito.mock(ActorConditionMutationService.class);
    when(mutationService.expireConditions(null)).thenReturn(2);

    new ActorConditionExpiryJob(mutationService).expireElapsedConditions();

    verify(mutationService).expireConditions(null);
  }
}
