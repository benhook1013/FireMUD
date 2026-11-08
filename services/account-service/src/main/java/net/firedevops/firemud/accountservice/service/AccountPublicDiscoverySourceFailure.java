package net.firedevops.firemud.accountservice.service;

import io.grpc.StatusRuntimeException;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient.ClientUnavailableException;

/** Availability classification only for the canonical Game Session public-discovery source. */
final class AccountPublicDiscoverySourceFailure {
  private AccountPublicDiscoverySourceFailure() {}

  static boolean isUnavailable(RuntimeException failure) {
    if (failure instanceof ClientUnavailableException) return true;
    if (!(failure instanceof StatusRuntimeException transport)) return false;
    return switch (transport.getStatus().getCode()) {
      case UNAVAILABLE, DEADLINE_EXCEEDED, CANCELLED, ABORTED -> true;
      default -> false;
    };
  }
}
