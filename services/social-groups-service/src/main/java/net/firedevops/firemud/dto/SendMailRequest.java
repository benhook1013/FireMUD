package net.firedevops.firemud.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendMailRequest(
    @NotNull Long tenantId,
    @NotNull Long senderAccountId,
    @NotNull Long recipientAccountId,
    @NotBlank String subject,
    @NotBlank String content) {}
