package net.firedevops.firemud.entitymanagement.service.impl;

import io.micrometer.core.annotation.Timed;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.entitymanagement.service.ActorConditionMutationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActorConditionExpiryJob {
  private static final Logger LOGGER = LoggerFactory.getLogger(ActorConditionExpiryJob.class);

  private final ActorConditionMutationService actorConditionMutationService;

  public ActorConditionExpiryJob(ActorConditionMutationService actorConditionMutationService) {
    this.actorConditionMutationService = actorConditionMutationService;
  }

  @Timed(value = "actorCondition.expire")
  @Scheduled(
      fixedDelayString = "${entity.actor-condition.expiry-interval-seconds:30}",
      timeUnit = TimeUnit.SECONDS)
  public void expireElapsedConditions() {
    int expired = actorConditionMutationService.expireConditions(null);
    if (expired > 0) {
      LOGGER.debug("Expired {} actor conditions", expired);
    }
  }
}
