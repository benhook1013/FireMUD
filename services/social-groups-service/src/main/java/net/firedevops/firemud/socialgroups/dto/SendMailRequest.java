package net.firedevops.firemud.socialgroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SendMailRequest(
    @NotNull @Positive Long tenantId,
    @NotNull @Positive Long senderAccountId,
    @NotNull @Positive Long recipientAccountId,
    @NotBlank String subject,
    @NotBlank String content) {}
