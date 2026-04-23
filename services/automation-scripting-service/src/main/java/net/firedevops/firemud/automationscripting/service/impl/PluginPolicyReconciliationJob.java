package net.firedevops.firemud.automationscripting.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.annotation.Timed;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.config.ScriptRuntimeProperties;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected Spring collaborators are retained, not exposed externally.")
public class PluginPolicyReconciliationJob {
  private static final Logger LOGGER = LoggerFactory.getLogger(PluginPolicyReconciliationJob.class);

  private final PluginRuntimeStateService pluginRuntimeStateService;
  private final ScriptRuntimeProperties runtimeProperties;
  private final ScheduledJobReadinessGuard readinessGuard;

  public PluginPolicyReconciliationJob(
      PluginRuntimeStateService pluginRuntimeStateService,
      ScriptRuntimeProperties runtimeProperties,
      ScheduledJobReadinessGuard readinessGuard) {
    this.pluginRuntimeStateService = pluginRuntimeStateService;
    this.runtimeProperties = runtimeProperties;
    this.readinessGuard = readinessGuard;
  }

  @Timed(value = "pluginPolicy.reconcile")
  @Scheduled(
      fixedDelayString = "${script.runtime.plugin-policy-reconcile-interval-seconds:60}",
      timeUnit = TimeUnit.SECONDS)
  public void reconcileActivePluginPolicy() {
    if (!readinessGuard.canRun()) {
      return;
    }
    PluginRuntimeStateService.PolicyReconciliationResult result =
        pluginRuntimeStateService.reconcileActivePluginPolicy(
            runtimeProperties.getPluginPolicyReconcileBatchSize());
    if (result.inspectedCount() > 0) {
      LOGGER.info(
          "Reconciled plugin policy inspected={} disabled={}",
          result.inspectedCount(),
          result.disabledCount());
    }
  }
}
