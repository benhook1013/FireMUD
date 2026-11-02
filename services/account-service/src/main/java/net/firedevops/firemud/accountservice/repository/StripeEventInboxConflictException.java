package net.firedevops.firemud.accountservice.repository;

import java.util.Objects;

/** A provider event ID already exists with different immutable, signature-verified evidence. */
public final class StripeEventInboxConflictException extends IllegalStateException {
  private final String providerEventId;

  public StripeEventInboxConflictException(String providerEventId) {
    super("Stripe event ID was reused with conflicting verified evidence");
    this.providerEventId = Objects.requireNonNull(providerEventId, "Stripe event ID is required");
  }

  public String providerEventId() {
    return providerEventId;
  }
}
