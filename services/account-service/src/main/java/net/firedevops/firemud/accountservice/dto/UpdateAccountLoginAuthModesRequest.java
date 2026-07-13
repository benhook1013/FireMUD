package net.firedevops.firemud.accountservice.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;

/** Replaces an account's allowed primary credential mechanisms. */
public record UpdateAccountLoginAuthModesRequest(
    @NotEmpty Set<@NotNull AccountLoginAuthMode> loginAuthModes) {
  public UpdateAccountLoginAuthModesRequest {
    loginAuthModes = Set.copyOf(loginAuthModes);
  }
}
