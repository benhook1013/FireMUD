package net.firedevops.firemud.accountservice.dto;

public record PaymentIntentDto(
    Long id,
    Long tenantId,
    Long accountId,
    Long amountCents,
    Long platformFeeCents,
    Long creatorShareCents,
    String currency,
    String clientSecret,
    boolean donation) {}
