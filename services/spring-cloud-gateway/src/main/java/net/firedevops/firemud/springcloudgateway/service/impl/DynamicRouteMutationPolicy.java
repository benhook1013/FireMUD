package net.firedevops.firemud.springcloudgateway.service.impl;

import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Guards the development-only dynamic route mutation surface. */
@Component
public class DynamicRouteMutationPolicy implements InitializingBean {
  private static final String DISABLED_MESSAGE =
      "Dynamic gateway route mutation is disabled in this environment";
  private static final String PROFILE_MESSAGE =
      "Dynamic gateway route mutation requires an explicitly active dev or test profile";

  private final boolean enabled;
  private final Environment environment;

  public DynamicRouteMutationPolicy(
      @Value("${firemud.gateway.dynamic-routes.enabled:false}") boolean enabled,
      Environment environment) {
    this.enabled = enabled;
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    validateEnabledProfile();
  }

  private void validateEnabledProfile() {
    if (enabled) {
      String[] activeProfiles = environment.getActiveProfiles();
      boolean hasDevOrTestProfile =
          Arrays.stream(activeProfiles)
              .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
      boolean hasUnsupportedProfile =
          Arrays.stream(activeProfiles)
              .anyMatch(profile -> !profile.equals("dev") && !profile.equals("test"));
      if (!hasDevOrTestProfile || hasUnsupportedProfile) {
        throw new IllegalStateException(PROFILE_MESSAGE);
      }
    }
  }

  void requireEnabled() {
    validateEnabledProfile();
    if (!enabled) {
      throw new IllegalStateException(DISABLED_MESSAGE);
    }
  }
}
