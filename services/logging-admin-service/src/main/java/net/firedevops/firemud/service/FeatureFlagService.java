package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.FeatureFlagDto;
import net.firedevops.firemud.dto.ToggleFeatureFlagRequest;

public interface FeatureFlagService {
  FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request);
}
