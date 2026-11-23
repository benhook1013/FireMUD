package net.firedevops.firemud.gamesession.data;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalGameplayStoreBootstrap;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayStandIns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Pre-v1 gate waiver (ADR 0185): runs the canonical gameplay store bootstrap once at startup, then
 * provisions the fixture tenant's budgets once its realm exists. Each tick replays the owners'
 * retained state; a failure is logged and retried. PLAY stays unavailable until {@link #ready()}.
 */
public final class PreV1CanonicalPlayBootstrapDriver {
  private static final Logger LOG =
      LoggerFactory.getLogger(PreV1CanonicalPlayBootstrapDriver.class);
  private static final long RETRY_INTERVAL_MS = 10_000L;

  private final PreV1CanonicalPlayStandIns standIns;
  private final AtomicReference<PreV1CanonicalGameplayStoreBootstrap.Result> store =
      new AtomicReference<>();
  private final AtomicBoolean budgets = new AtomicBoolean();

  public PreV1CanonicalPlayBootstrapDriver(PreV1CanonicalPlayStandIns standIns) {
    this.standIns = Objects.requireNonNull(standIns, "standIns");
  }

  @Scheduled(initialDelay = 1_000L, fixedDelay = RETRY_INTERVAL_MS)
  public void bootstrapWhenReady() {
    if (store.get() == null) {
      try {
        var result = standIns.storeBootstrap().run();
        store.set(result);
        LOG.info(
            "Pre-v1 canonical gameplay store ready (legacy={}, issuer={}, bootstrap={})",
            result.legacyMigrationState(),
            result.issuerAdmissionControlState(),
            result.issuerBootstrapOperationId());
      } catch (RuntimeException notYet) {
        LOG.warn("Pre-v1 canonical gameplay store bootstrap not complete: {}", notYet.toString());
        return;
      }
    }
    if (budgets.get()) return;
    try {
      var tenant = standIns.budgets().fixtureTenant();
      if (tenant.isEmpty()) return;
      standIns.budgets().provision(tenant.get());
      budgets.set(true);
      LOG.info("Pre-v1 canonical PLAY budgets provisioned for fixture tenant {}", tenant.get());
    } catch (RuntimeException notYet) {
      LOG.warn("Pre-v1 canonical PLAY budgets not provisioned: {}", notYet.toString());
    }
  }

  public Optional<PreV1CanonicalGameplayStoreBootstrap.Result> storeResult() {
    return Optional.ofNullable(store.get());
  }

  public boolean ready() {
    return store.get() != null && budgets.get();
  }
}
