package net.firedevops.firemud.common.prev1;

import java.util.Arrays;
import org.springframework.core.env.Environment;

/**
 * Single switch for gates waived before v1 under ADR 0185. Every waived gate reads this switch, and
 * the waived gates are listed in the player access and session tracker's "Pre-v1 gate waivers"
 * section. A service refuses to start with the switch on under the {@code prod} profile.
 */
public final class PreV1GateWaivers {
  public static final String PROPERTY = "firemud.pre-v1.gate-waivers.enabled";
  private static final PreV1GateWaivers DISABLED = new PreV1GateWaivers(false);

  private final boolean enabled;

  private PreV1GateWaivers(boolean enabled) {
    this.enabled = enabled;
  }

  /** Reads the switch and refuses it under the production profile. */
  public static PreV1GateWaivers from(Environment environment) {
    boolean enabled = environment.getProperty(PROPERTY, Boolean.class, false);
    if (enabled
        && Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase)) {
      throw new IllegalStateException(
          PROPERTY + " must not be enabled under the prod profile; clear the pre-v1 gate waivers");
    }
    return enabled ? new PreV1GateWaivers(true) : DISABLED;
  }

  public static PreV1GateWaivers disabled() {
    return DISABLED;
  }

  public static PreV1GateWaivers enabledForTest() {
    return new PreV1GateWaivers(true);
  }

  public boolean enabled() {
    return enabled;
  }
}
