package net.firedevops.firemud.gamelogic.config;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Bounded effective-settings read path for the surfaced communication domain. */
@Component
public class EffectiveCommunicationSettingsResolver {
  private final CommunicationProperties communicationDefaults;

  public EffectiveCommunicationSettingsResolver(CommunicationProperties communicationDefaults) {
    this.communicationDefaults =
        Objects.requireNonNull(communicationDefaults, "communicationDefaults must not be null");
  }

  public CommunicationProperties communication() {
    return communicationDefaults;
  }

  public ResolvedValue<CommunicationProperties> resolvedCommunication() {
    return new ResolvedValue<>(communicationDefaults, List.of("operatorDefaults"));
  }

  public record ResolvedValue<T>(T effective, List<String> sources) {
    public ResolvedValue {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }
}
