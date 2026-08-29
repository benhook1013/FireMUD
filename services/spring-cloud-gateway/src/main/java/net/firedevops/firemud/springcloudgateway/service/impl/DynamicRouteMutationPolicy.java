package net.firedevops.firemud.springcloudgateway.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Guards the development-only dynamic route mutation surface. */
@Component
public class DynamicRouteMutationPolicy {
  private static final String DISABLED_MESSAGE =
      "Dynamic gateway route mutation is disabled in this environment";

  private final boolean enabled;

  public DynamicRouteMutationPolicy(
      @Value("${firemud.gateway.dynamic-routes.enabled:false}") boolean enabled,
      Environment environment) {
    if (enabled && environment.acceptsProfiles(Profiles.of("prod"))) {
      throw new IllegalStateException(
          "Dynamic gateway route mutation must not be enabled with the prod profile");
    }
    this.enabled = enabled;
  }

  void requireEnabled() {
    if (!enabled) {
      throw new IllegalStateException(DISABLED_MESSAGE);
    }
  }
}
