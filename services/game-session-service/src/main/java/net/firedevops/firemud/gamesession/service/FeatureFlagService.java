package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.FeatureFlagDto;
import net.firedevops.firemud.gamesession.dto.ToggleFeatureFlagRequest;

public interface FeatureFlagService {
  FeatureFlagDto toggleFlag(ToggleFeatureFlagRequest request);
}
