package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request to obtain a temporary WebRTC token for voice chat. */
public record VoiceTokenRequestDto(
    @NotNull Long tenantId, @NotNull Long accountId, @NotBlank String channelId) {}
