package net.firedevops.firemud.accountservice.repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable wallet binding and versioned provider-approved display/availability observation. */
public record AccountPaymentInstrumentRecord(
    UUID instrumentUuid,
    UUID accountUuid,
    UUID customerProvisioningRequestId,
    String providerCustomerId,
    String providerPaymentMethodId,
    UUID attachmentRequestId,
    long rowVersion,
    State state,
    DisplayMetadata display,
    UUID changeRequestId,
    String evidenceDigest,
    Instant createdAt,
    Instant updatedAt) {
  public AccountPaymentInstrumentRecord {
    AccountBillingCustomerRecord.requireUuid(instrumentUuid);
    AccountBillingCustomerRecord.requireUuid(accountUuid);
    AccountBillingCustomerRecord.requireUuid(customerProvisioningRequestId);
    AccountBillingCustomerRecord.requireProviderCustomer(providerCustomerId);
    requirePaymentMethod(providerPaymentMethodId);
    AccountBillingCustomerRecord.requireUuid(attachmentRequestId);
    AccountBillingCustomerRecord.requireUuid(changeRequestId);
    AccountBillingCustomerRecord.requireDigest(evidenceDigest);
    Objects.requireNonNull(state, "instrument state");
    Objects.requireNonNull(display, "approved display metadata");
    Objects.requireNonNull(createdAt, "creation time");
    Objects.requireNonNull(updatedAt, "observation time");
    if (rowVersion < 1) {
      throw new IllegalArgumentException("Instrument version must be positive");
    }
  }

  // No DETACHED state: there is no safe detach without the genuine reservation/reference owner.
  public enum State {
    ATTACHABLE,
    UNAVAILABLE
  }

  /**
   * Fixed safe display fields only; no PAN, security code, free-form payload or mutable default.
   */
  public record DisplayMetadata(
      String brand, String lastFour, Integer expiryMonth, Integer expiryYear) {
    public DisplayMetadata {
      if ((brand != null && !brand.matches("[a-z][a-z_]{0,31}"))
          || (lastFour != null && !lastFour.matches("[0-9]{4}"))
          || ((expiryMonth == null) != (expiryYear == null))
          || (expiryMonth != null && (expiryMonth < 1 || expiryMonth > 12))
          || (expiryYear != null && (expiryYear < 2000 || expiryYear > 9999))) {
        throw new IllegalArgumentException("Invalid provider-approved display metadata");
      }
    }
  }

  static void requirePaymentMethod(String value) {
    if (value == null || value.length() > 255 || !value.matches("pm_[A-Za-z0-9]+")) {
      throw new IllegalArgumentException("Stripe PaymentMethod reference is required");
    }
  }
}
