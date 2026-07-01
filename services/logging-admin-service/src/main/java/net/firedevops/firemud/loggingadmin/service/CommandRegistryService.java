package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.BuiltInCommandAliasValidationDto;

public interface CommandRegistryService {
  BuiltInCommandAliasValidationDto validateBuiltInCommandAlias(String alias);
}
