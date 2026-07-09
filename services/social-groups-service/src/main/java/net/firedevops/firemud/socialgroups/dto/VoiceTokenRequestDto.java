package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request to obtain a temporary WebRTC token for voice chat. */
public record VoiceTokenRequestDto(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long accountId,
    @NotBlank String channelId) {}
