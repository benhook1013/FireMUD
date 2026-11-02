package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed, unregistered pre-scope public discovery evaluation. Its facts do not
 * authenticate a caller, admit a player, reserve capacity, or constitute a runtime evidence bundle.
 */
public final class AccountPublicBootstrapEntitlementOwner {
  private final AccountTenantAuthorityEventRepository sources;
  private final AccountHostingSubscriptionLifecycleService hosting;
  private final AccountDemoTenantEntitlementRepository demos;
  private final TransactionTemplate transaction;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Private injected owner collaborators retain their transaction boundaries.")
  public AccountPublicBootstrapEntitlementOwner(
      AccountTenantAuthorityEventRepository sources,
      AccountHostingSubscriptionLifecycleService hosting,
      AccountDemoTenantEntitlementRepository demos,
      PlatformTransactionManager transactions,
      Clock clock) {
    this.sources = Objects.requireNonNull(sources);
    this.hosting = Objects.requireNonNull(hosting);
    this.demos = Objects.requireNonNull(demos);
    this.clock = Objects.requireNonNull(clock);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
  }

  public Evaluation evaluate(UUID tenantId) {
    if (tenantId == null || new UUID(0L, 0L).equals(tenantId)) {
      throw new IllegalArgumentException("Canonical non-nil tenant UUID is required");
    }
    requireNoAmbientTransaction();
    try {
      Prepared prepared =
          Objects.requireNonNull(
              transaction.execute(
                  status -> {
                    requireSourceTransaction();
                    SourceSelection selection = sources.readCurrentSourceSelection(tenantId);
                    Instant observedAt = now();
                    DemoTenantEntitlementSnapshot demo =
                        selection.kind() == SourceKind.NON_PAID_DEMO
                            ? demos.readCurrent(tenantId)
                            : null;
                    return new Prepared(selection, demo, observedAt);
                  }));
      Evaluation evaluation =
          switch (prepared.selection().kind()) {
            case PAID_HOSTING ->
                hosting.readCurrentAndCapture(
                    tenantId, receipt -> capturePaid(tenantId, prepared.selection(), receipt));
            case NON_PAID_DEMO ->
                Objects.requireNonNull(
                    transaction.execute(status -> captureDemo(tenantId, prepared)));
          };
      requireFresh(evaluation.evaluatedAt(), evaluation.validUntil());
      return evaluation;
    } catch (RuntimeException exception) {
      throw new UnavailableException(exception);
    }
  }

  private Evaluation capturePaid(
      UUID tenantId, SourceSelection selected, AccountHostingSubscriptionCurrentReceipt receipt) {
    requireSourceTransaction();
    requireSameHead(tenantId, selected);
    if (receipt == null
        || !tenantId.equals(receipt.current().tenantId())
        || !selected.matches(receipt.tenantAuthorityEvent())
        || receipt.tenantBillingSequence() != selected.checkpoint().tenantBillingSequence()
        || !receipt.billingEventId().equals(selected.checkpoint().tenantBillingEventId())
        || !receipt.billingEventDigest().equals(selected.checkpoint().tenantBillingEventDigest())) {
      throw new IllegalStateException("Paid discovery source differs from its selected head");
    }
    requireFresh(receipt.evaluatedAt(), receipt.validUntil());
    return new Evaluation(
        tenantId,
        SourceKind.PAID_HOSTING,
        receipt.gameplayAvailable(),
        receipt.allowPublicJoin(),
        receipt.entitlementVersion(),
        receipt.tenantBillingSequence(),
        receipt.evaluatedAt(),
        receipt.validUntil());
  }

  private Evaluation captureDemo(UUID tenantId, Prepared prepared) {
    requireSourceTransaction();
    requireSameHead(tenantId, prepared.selection());
    DemoTenantEntitlementSnapshot demo = demos.revalidate(prepared.demo());
    var event = prepared.selection().checkpoint();
    if (!tenantId.equals(demo.canonicalTenantId())
        || demo.tenantBillingSequence() != event.tenantBillingSequence()
        || !demo.eventId().equals(event.tenantBillingEventId())
        || !demo.eventDigest().equals(event.tenantBillingEventDigest())
        || !demo.tenantAuthorityEventId().equals(event.eventId())
        || !demo.tenantAuthorityEventDigest().equals(event.eventDigest())
        || demo.tenantAuthorityGeneration() != event.tenantAuthorityGeneration()
        || demo.tenantAuthoritySourceVersion() != event.tenantAuthoritySourceVersion()) {
      throw new IllegalStateException("Demo discovery source differs from its selected head");
    }
    // Demo has no provider period. Bound this observation from its initial capture, never
    // revalidation.
    Instant validUntil = prepared.observedAt().plusSeconds(15);
    requireFresh(prepared.observedAt(), validUntil);
    return new Evaluation(
        tenantId,
        SourceKind.NON_PAID_DEMO,
        demo.gameplayAvailable(),
        demo.allowPublicJoin(),
        demo.entitlementVersion(),
        demo.tenantBillingSequence(),
        prepared.observedAt(),
        validUntil);
  }

  private void requireSameHead(UUID tenantId, SourceSelection selected) {
    SourceSelection current = sources.readCurrentSourceSelection(tenantId);
    if (selected.kind() != current.kind() || !selected.matches(current.checkpoint())) {
      throw new IllegalStateException("Discovery source head changed after selection");
    }
  }

  private void requireFresh(Instant evaluatedAt, Instant validUntil) {
    Instant current = now();
    if (evaluatedAt == null
        || validUntil == null
        || current.isBefore(evaluatedAt)
        || !current.isBefore(validUntil)) {
      throw new IllegalStateException("Discovery source observation is no longer current");
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static void requireSourceTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException("Discovery capture requires a writable Account transaction");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Discovery source preparation refuses ambient SQL");
    }
  }

  private record Prepared(
      SourceSelection selection, DemoTenantEntitlementSnapshot demo, Instant observedAt) {}

  /** Nonadmitting discovery facts only; no caller, connect scope, target, or authority bundle. */
  public record Evaluation(
      UUID canonicalTenantId,
      SourceKind sourceKind,
      boolean gameplayAvailable,
      boolean allowPublicJoin,
      long entitlementVersion,
      long tenantBillingSequence,
      Instant evaluatedAt,
      Instant validUntil) {}

  public static final class UnavailableException extends IllegalStateException {
    public UnavailableException(Throwable cause) {
      super("ENTITLEMENT_UNAVAILABLE: current discovery source is unavailable", cause);
    }
  }
}
