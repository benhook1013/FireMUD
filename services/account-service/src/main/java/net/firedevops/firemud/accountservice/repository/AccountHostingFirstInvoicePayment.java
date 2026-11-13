package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Exact retained first invoice. This value and provider linkage confer no hosting authority. */
public record AccountHostingFirstInvoicePayment(
    UUID subscriptionId,
    UUID creationRequestId,
    UUID tenantId,
    UUID ownerAccountId,
    UUID paymentInstrumentId,
    long instrumentVersion,
    String providerSubscriptionId,
    String providerCustomerId,
    String providerPaymentMethodId,
    String invoiceId,
    String paymentIntentId,
    long amount,
    String currency,
    String quoteDigest,
    String providerPriceId,
    long providerQuantity,
    long billingPolicyVersion,
    String billingPolicyDigest,
    boolean livemode,
    Instant capturedAt,
    Instant expiresAt) {
  public AccountHostingFirstInvoicePayment {
    capturedAt = Objects.requireNonNull(capturedAt).truncatedTo(ChronoUnit.MICROS);
    expiresAt = Objects.requireNonNull(expiresAt).truncatedTo(ChronoUnit.MICROS);
    for (UUID id :
        new UUID[] {
          subscriptionId, creationRequestId, tenantId, ownerAccountId, paymentInstrumentId
        }) {
      AccountBillingCustomerRecord.requireUuid(id);
    }
    requireId(providerSubscriptionId, "sub_");
    requireId(providerCustomerId, "cus_");
    requireId(providerPaymentMethodId, "pm_");
    requireId(invoiceId, "in_");
    requireId(paymentIntentId, "pi_");
    requireId(providerPriceId, "price_");
    if (providerQuantity < 1
        || instrumentVersion < 1
        || billingPolicyVersion < 1
        || amount <= 0
        || currency == null
        || !currency.matches("[a-z]{3}")
        || quoteDigest == null
        || !quoteDigest.matches("sha256:[0-9a-f]{64}")
        || billingPolicyDigest == null
        || !billingPolicyDigest.matches("sha256:[0-9a-f]{64}")
        || !expiresAt.isAfter(capturedAt)) {
      throw new IllegalArgumentException(
          "Exact first-invoice commercial and expiry binding required");
    }
  }

  public String digest() {
    Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("schemaVersion", "account-hosting-first-invoice/v1");
    fields.put("subscriptionId", subscriptionId.toString());
    fields.put("creationRequestId", creationRequestId.toString());
    fields.put("tenantId", tenantId.toString());
    fields.put("ownerAccountId", ownerAccountId.toString());
    fields.put("paymentInstrumentId", paymentInstrumentId.toString());
    fields.put("instrumentVersion", Long.toString(instrumentVersion));
    fields.put("providerSubscriptionId", providerSubscriptionId);
    fields.put("providerCustomerId", providerCustomerId);
    fields.put("providerPaymentMethodId", providerPaymentMethodId);
    fields.put("invoiceId", invoiceId);
    fields.put("paymentIntentId", paymentIntentId);
    fields.put("amount", Long.toString(amount));
    fields.put("currency", currency);
    fields.put("quoteDigest", quoteDigest);
    fields.put("providerPriceId", providerPriceId);
    fields.put("providerQuantity", Long.toString(providerQuantity));
    fields.put("billingPolicyVersion", Long.toString(billingPolicyVersion));
    fields.put("billingPolicyDigest", billingPolicyDigest);
    fields.put("livemode", livemode);
    fields.put("capturedAt", capturedAt.toString());
    fields.put("expiresAt", expiresAt.toString());
    return AccountHostingSubscriptionLifecycleEvidence.digest(
        AccountHostingSubscriptionLifecycleEvidence.encode(fields));
  }

  private static void requireId(String value, String prefix) {
    if (value == null || !value.matches(prefix + "[A-Za-z0-9]{1,251}")) {
      throw new IllegalArgumentException("Exact provider identity required");
    }
  }
}
