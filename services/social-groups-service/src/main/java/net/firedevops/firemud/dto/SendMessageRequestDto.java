package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendMessageRequestDto(
    @NotNull Long tenantId,
    @NotNull Long senderAccountId,
    String channelId,
    @NotBlank String content) {}
