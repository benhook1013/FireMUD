package net.firedevops.firemud.worldmanagement.service;

import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationRequestDto;
import net.firedevops.firemud.worldmanagement.dto.WorldDesignMutationResultDto;

public interface WorldDesignMutationService {
  WorldDesignMutationResultDto applyMutation(WorldDesignMutationRequestDto request);
}
