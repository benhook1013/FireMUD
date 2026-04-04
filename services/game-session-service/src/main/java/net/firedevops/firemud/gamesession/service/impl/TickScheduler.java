package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.TickService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically processes ticks for all running sessions. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected repository and service are not exposed")
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
@RequiredArgsConstructor
public final class TickScheduler {
  private final GameInstanceRepository repository;
  private final TickService tickService;

  @Scheduled(fixedDelayString = "${game.tick-duration-ms:1000}")
  @Timed(value = "game_session.tick_scheduler")
  public void runTicks() {
    if (tickService.getTickStatus()
        == net.firedevops.firemud.gamesession.v1.TickStatus.TICK_STATUS_PAUSED) {
      return;
    }
    List<GameInstance> running = repository.findByStatus("RUNNING");
    for (GameInstance instance : running) {
      tickService.processTick(instance.getTenantId(), instance.getId());
    }
  }
}
