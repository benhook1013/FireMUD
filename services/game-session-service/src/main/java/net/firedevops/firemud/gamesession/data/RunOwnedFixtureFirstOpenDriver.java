package net.firedevops.firemud.gamesession.data;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retries the canonical run-owned fixture until its first OPEN commits. Each run resumes from the
 * owners' retained state; a failure is logged and retried on the next tick.
 */
public final class RunOwnedFixtureFirstOpenDriver {
  private static final Logger LOG = LoggerFactory.getLogger(RunOwnedFixtureFirstOpenDriver.class);
  private static final long RETRY_INTERVAL_MS = 10_000L;

  private final RunOwnedFixtureFirstOpenCoordinator coordinator;
  private final AtomicBoolean opened = new AtomicBoolean();

  public RunOwnedFixtureFirstOpenDriver(RunOwnedFixtureFirstOpenCoordinator coordinator) {
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  @Scheduled(initialDelay = RETRY_INTERVAL_MS, fixedDelay = RETRY_INTERVAL_MS)
  public void openWhenReady() {
    if (opened.get()) return;
    try {
      var proof = coordinator.openFirstRealm();
      opened.set(true);
      LOG.info(
          "Run-owned fixture first OPEN committed (pointerVersion={})",
          proof.committedPointerVersion());
    } catch (RuntimeException notYet) {
      LOG.warn("Run-owned fixture first OPEN not complete yet: {}", notYet.toString());
    }
  }

  public boolean opened() {
    return opened.get();
  }
}
