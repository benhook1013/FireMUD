package net.firedevops.firemud.service;

import net.firedevops.firemud.model.PveEvent;

/** Generates random PvE encounters and environmental hazards. */
public interface PveEncounterService {
  /**
   * Generates a PvE event for the given region using the provided seed for deterministic results.
   */
  PveEvent generateEvent(String region, long seed);
}
