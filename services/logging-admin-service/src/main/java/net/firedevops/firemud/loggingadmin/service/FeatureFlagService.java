package net.firedevops.firemud.loggingadmin.service;

import net.firedevops.firemud.loggingadmin.dto.FeatureFlagDto;
import net.firedevops.firemud.loggingadmin.dto.ToggleFeatureFlagRequest;

public interface FeatureFlagService {
  FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request);
}
