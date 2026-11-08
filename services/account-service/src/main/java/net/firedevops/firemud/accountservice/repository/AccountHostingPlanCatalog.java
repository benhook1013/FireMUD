package net.firedevops.firemud.accountservice.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Manually composed immutable operator/server configuration. No live default plans, environment
 * discovery, provider lookup, bean registration or actor authority. Replace the catalog explicitly
 * for new requests; retained subscription operations must never be repriced from this catalog.
 */
public final class AccountHostingPlanCatalog {
  private final Map<String, AccountHostingPlanQuote> plans;

  public AccountHostingPlanCatalog(Collection<ConfiguredPlan> configuration) {
    if (configuration == null) {
      throw new IllegalArgumentException("Explicit hosting-plan configuration is required");
    }
    Map<String, AccountHostingPlanQuote> resolved = new HashMap<>();
    for (ConfiguredPlan entry : configuration) {
      if (entry == null) {
        throw new IllegalArgumentException("Hosting-plan configuration is incomplete");
      }
      var quote =
          new AccountHostingPlanQuote(
              entry.planCode(),
              entry.providerPriceId(),
              integral(entry.providerQuantity()),
              integral(entry.maxActiveSessions()),
              integral(entry.maxConcurrentGameInstances()),
              integral(entry.maxStorageBytes()));
      quote.payload();
      if (resolved.putIfAbsent(quote.planCode(), quote) != null) {
        throw new IllegalArgumentException(
            "Ambiguous hosting-plan configuration: duplicate exact plan code");
      }
    }
    plans = Map.copyOf(resolved);
  }

  public AccountHostingPlanQuote resolve(String normalizedPlanCode) {
    AccountHostingPlanQuote.requirePlanCode(normalizedPlanCode);
    var quote = plans.get(normalizedPlanCode);
    if (quote == null) {
      throw new PlanUnavailableException(
          "Explicit hosting-plan configuration is absent for the requested plan");
    }
    return quote;
  }

  private static long integral(String value) {
    if (value == null || value.length() > 19 || !value.matches("0|[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "Explicit bounded unsigned integral plan value is required");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException overflow) {
      throw new IllegalArgumentException(
          "Hosting-plan value exceeds signed BIGINT range", overflow);
    }
  }

  /**
   * Exact configuration values only. Strings permit rejecting incomplete/fractional/overflow input.
   */
  public record ConfiguredPlan(
      String planCode,
      String providerPriceId,
      String providerQuantity,
      String maxActiveSessions,
      String maxConcurrentGameInstances,
      String maxStorageBytes) {}

  public static final class PlanUnavailableException extends IllegalStateException {
    public PlanUnavailableException(String message) {
      super(message);
    }
  }
}
