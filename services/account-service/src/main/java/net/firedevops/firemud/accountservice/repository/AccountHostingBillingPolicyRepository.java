package net.firedevops.firemud.accountservice.repository;

import java.util.Map;
import java.util.Objects;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit versioned commercial policy. No client amount, Stripe balance or tax fallback. */
public final class AccountHostingBillingPolicyRepository {
  private final DSLContext dsl;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Private injected transactional DSLContext is retained for the Account transaction boundary and never exposed.")
  public AccountHostingBillingPolicyRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  public Policy requireCurrent(AccountHostingPlanQuote quote) {
    requireTransaction();
    var row =
        dsl.fetchOne(
            "SELECT v.* FROM account_hosting_billing_policy_current c "
                + "JOIN account_hosting_billing_policy_values v USING (provider_price_id, policy_version) "
                + "WHERE c.provider_price_id = ? FOR SHARE OF c",
            quote.providerPriceId());
    if (row == null)
      throw new IllegalStateException("Explicit current hosting billing policy unavailable");
    var result =
        new Policy(
            row.get("provider_price_id", String.class),
            row.get("policy_version", Long.class),
            row.get("quantity", Long.class),
            row.get("amount", Long.class),
            row.get("currency", String.class));
    if (result.quantity() != quote.providerQuantity())
      throw new IllegalStateException("Hosting policy quantity mismatch");
    return result;
  }

  public void requireUnchanged(
      AccountHostingFirstInvoicePayment invoice, AccountHostingPlanQuote quote) {
    Policy current = requireCurrent(quote);
    if (!current.providerPriceId().equals(invoice.providerPriceId())
        || current.quantity() != invoice.providerQuantity()
        || current.version() != invoice.billingPolicyVersion()
        || !current.digest().equals(invoice.billingPolicyDigest())
        || current.amount() != invoice.amount()
        || !current.currency().equals(invoice.currency())
        || !quote.digest().equals(invoice.quoteDigest())) {
      throw new IllegalStateException(
          "Hosting billing policy changed; first-invoice checkout refused");
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Account owner transaction required");
  }

  public record Policy(
      String providerPriceId, long version, long quantity, long amount, String currency) {
    public Policy {
      if (providerPriceId == null
          || !providerPriceId.matches("price_[A-Za-z0-9]+")
          || version < 1
          || quantity < 1
          || amount < 1
          || currency == null
          || !currency.matches("[a-z]{3}")) {
        throw new IllegalArgumentException("Explicit positive hosting commercial policy required");
      }
    }

    public String digest() {
      return AccountHostingSubscriptionLifecycleEvidence.digest(
          AccountHostingSubscriptionLifecycleEvidence.encode(
              Map.of(
                  "schemaVersion",
                  "account-hosting-billing-policy/v1",
                  "providerPriceId",
                  providerPriceId,
                  "version",
                  Long.toString(version),
                  "quantity",
                  Long.toString(quantity),
                  "amount",
                  Long.toString(amount),
                  "currency",
                  currency)));
    }
  }
}
