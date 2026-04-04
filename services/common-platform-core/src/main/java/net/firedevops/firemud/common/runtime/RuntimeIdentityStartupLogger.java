package net.firedevops.firemud.common.runtime;

import java.util.Arrays;
import net.firedevops.firemud.common.LoggingUtil;
import org.slf4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/** Emits one structured startup log with runtime identity details. */
public class RuntimeIdentityStartupLogger {
  private static final Logger logger = LoggingUtil.getLogger(RuntimeIdentityStartupLogger.class);

  private final RuntimeIdentity runtimeIdentity;
  private final Environment environment;

  public RuntimeIdentityStartupLogger(RuntimeIdentity runtimeIdentity, Environment environment) {
    this.runtimeIdentity = runtimeIdentity;
    this.environment = environment;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    String profiles =
        environment.getActiveProfiles().length == 0
            ? "default"
            : String.join(",", Arrays.asList(environment.getActiveProfiles()));
    logger.info(
        "Service startup complete service={} serviceInstanceId={} hostname={} profiles={} buildVersion={} buildSha={} imageTag={} bootedAt={}",
        runtimeIdentity.service(),
        runtimeIdentity.serviceInstanceId(),
        runtimeIdentity.hostname(),
        profiles,
        valueOrUnknown(runtimeIdentity.buildVersion()),
        valueOrUnknown(runtimeIdentity.buildSha()),
        valueOrUnknown(runtimeIdentity.imageTag()),
        runtimeIdentity.bootedAt());
  }

  private static String valueOrUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }
}
