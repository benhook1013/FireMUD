package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotBlank;

/** Request payload for enqueuing a command via REST. */
public record EnqueueCommandRequest(@NotBlank String command, boolean requiresSoloTick) {}
