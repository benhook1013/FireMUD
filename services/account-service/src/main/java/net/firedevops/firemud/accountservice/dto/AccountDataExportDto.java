package net.firedevops.firemud.accountservice.dto;

import java.util.List;

public record AccountDataExportDto(AccountDto account, List<ProfileDto> profiles) {
  public AccountDataExportDto {
    profiles = profiles == null ? List.of() : List.copyOf(profiles);
  }
}
