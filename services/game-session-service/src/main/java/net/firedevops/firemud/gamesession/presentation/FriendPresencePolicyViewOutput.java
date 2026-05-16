package net.firedevops.firemud.gamesession.presentation;

import java.util.List;
import java.util.Objects;

public record FriendPresencePolicyViewOutput(String currentPolicy, List<Option> options)
    implements PlayerOutputPayload {
  public FriendPresencePolicyViewOutput {
    Objects.requireNonNull(currentPolicy, "currentPolicy must not be null");
    options = options == null ? List.of() : List.copyOf(options);
  }

  public record Option(String policy, String description, boolean current, boolean selectable) {
    public Option {
      Objects.requireNonNull(policy, "policy must not be null");
      Objects.requireNonNull(description, "description must not be null");
    }
  }
}
