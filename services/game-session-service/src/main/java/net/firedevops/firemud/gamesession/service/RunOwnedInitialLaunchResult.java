package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;

/** Exact Game Session target returned after World owner readback proves ACTIVE. */
public record RunOwnedInitialLaunchResult(
    GameInstanceDto gameInstance, long activeLifecycleEpoch) {}
