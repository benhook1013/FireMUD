package net.firedevops.firemud.dto;

public record PaymentIntentDto(
    Long id,
    Long tenantId,
    Long accountId,
    Long amountCents,
    Long platformFeeCents,
    String currency,
    String clientSecret,
    boolean donation) {}
