package net.firedevops.firemud.accountservice.dto;

/** Request to process a payment transaction using the saga workflow. */
public record PurchaseRequest(Long tenantId, Long accountId, Long amountCents) {}
