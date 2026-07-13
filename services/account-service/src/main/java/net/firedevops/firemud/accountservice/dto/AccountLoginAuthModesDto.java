package net.firedevops.firemud.accountservice.dto;

import java.util.Set;
import net.firedevops.firemud.accountservice.entity.AccountLoginAuthMode;

/** Account-selected primary credential mechanisms. */
public record AccountLoginAuthModesDto(Set<AccountLoginAuthMode> loginAuthModes) {
  public AccountLoginAuthModesDto {
    loginAuthModes = Set.copyOf(loginAuthModes);
  }
}
