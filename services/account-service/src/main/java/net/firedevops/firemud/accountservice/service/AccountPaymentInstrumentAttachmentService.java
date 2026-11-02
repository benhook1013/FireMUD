package net.firedevops.firemud.accountservice.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver.ExpectedBinding;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver.ObservedBillingPrerequisites;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.Attachment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed saved-method observation/retention source, deliberately unregistered.
 *
 * <p>Selectors confer no actor authorization. Protected actor/recent-reauth admission, transport,
 * SetupIntent collection, provider attach/detach lifecycle, reservation and subscription selection
 * remain absent. This component only retains an already attached method independently retrieved by
 * the instance SDK against an existing confirmed Account customer. It never selects defaults or
 * issues a provider mutation. Freshness policy and expected Stripe mode belong to internal
 * composition, not request input. A retained ATTACHABLE observation is not a future provider claim.
 */
public final class AccountPaymentInstrumentAttachmentService {
  private final AccountBillingCustomerRepository customers;
  private final AccountPaymentInstrumentRepository instruments;
  private final StripeBillingPrerequisiteObserver observer;
  private final Clock clock;
  private final Duration maximumObservationAge;
  private final TransactionTemplate transaction;

  public AccountPaymentInstrumentAttachmentService(
      AccountBillingCustomerRepository customers,
      AccountPaymentInstrumentRepository instruments,
      StripeBillingPrerequisiteObserver observer,
      PlatformTransactionManager transactions,
      Clock clock,
      Duration maximumObservationAge) {
    this.customers = Objects.requireNonNull(customers);
    this.instruments = Objects.requireNonNull(instruments);
    this.observer = Objects.requireNonNull(observer);
    this.clock = Objects.requireNonNull(clock);
    this.maximumObservationAge = Objects.requireNonNull(maximumObservationAge);
    if (maximumObservationAge.isZero() || maximumObservationAge.isNegative()) {
      throw new IllegalArgumentException(
          "Positive internal observation freshness policy is required");
    }
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Internal source operation only; callers must not expose it as an authorization/admission API.
   */
  public Result retainSavedMethod(Request request) {
    Objects.requireNonNull(request, "explicit saved-method selector is required");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Saved-method observation cannot span an ambient SQL transaction");
    }
    Prepared prepared =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  AccountBillingCustomerRecord customer = lockCustomer(request.accountUuid());
                  instruments.findByOwnerForUpdate(request.accountUuid(), request.instrumentUuid());
                  AccountPaymentInstrumentRecord receipt =
                      instruments.findAttachmentByRequestId(request.requestId()).orElse(null);
                  if (receipt != null) {
                    requireReceiptBinding(receipt, request, customer);
                  }
                  return new Prepared(customer, receipt);
                }));
    if (prepared.receipt() != null) {
      RetainedBinding retained = readCommitted(request, prepared.customer(), prepared.receipt());
      return new HistoricalAttachment(retained.receipt(), retained.current());
    }

    // Both typed SDK retrievals occur after releasing every Account transaction/owner lock.
    StripeBillingPrerequisiteObserver.Result result =
        observer.observe(
            new ExpectedBinding(
                prepared.customer().accountUuid(),
                prepared.customer().customerProvisioningRequestId(),
                prepared.customer().providerIdempotencyId(),
                prepared.customer().providerCustomerId(),
                request.providerPaymentMethodId()));
    if (!(result instanceof ObservedBillingPrerequisites observed)) {
      return new PendingObservation(result);
    }
    CommitResult committed =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  AccountBillingCustomerRecord current = lockCustomer(request.accountUuid());
                  if (!current.equals(prepared.customer())) {
                    throw new IllegalStateException(
                        "Confirmed customer changed during provider observation");
                  }
                  instruments.findByOwnerForUpdate(request.accountUuid(), request.instrumentUuid());
                  AccountPaymentInstrumentRecord original =
                      instruments.findAttachmentByRequestId(request.requestId()).orElse(null);
                  if (original != null) {
                    requireReceiptBinding(original, request, current);
                    return new CommitResult(original, false);
                  }
                  requireFresh(observed);
                  if (!current.accountUuid().equals(observed.accountUuid())
                      || !current
                          .customerProvisioningRequestId()
                          .equals(observed.customerProvisioningRequestId())
                      || !current.providerCustomerId().equals(observed.providerCustomerId())
                      || !request
                          .providerPaymentMethodId()
                          .equals(observed.providerPaymentMethodId())) {
                    throw new IllegalStateException(
                        "Provider observation changed the selected owner/customer/method");
                  }
                  AccountPaymentInstrumentRecord receipt =
                      instruments.retainAttachment(
                          new Attachment(
                              request.requestId(),
                              request.instrumentUuid(),
                              observed.accountUuid(),
                              observed.customerProvisioningRequestId(),
                              observed.providerCustomerId(),
                              observed.providerPaymentMethodId(),
                              AccountPaymentInstrumentRecord.State.ATTACHABLE,
                              observed.display(),
                              observed.evidenceDigest()));
                  requireReceiptBinding(receipt, request, current);
                  if (receipt.state() != AccountPaymentInstrumentRecord.State.ATTACHABLE
                      || !receipt.display().equals(observed.display())
                      || !receipt.evidenceDigest().equals(observed.evidenceDigest())) {
                    throw new IllegalStateException(
                        "Retained attachment differs from independently observed facts");
                  }
                  return new CommitResult(receipt, true);
                }));
    RetainedBinding retained = readCommitted(request, prepared.customer(), committed.receipt());
    if (!committed.newlyRecorded()) {
      return new HistoricalAttachment(retained.receipt(), retained.current());
    }
    return new ObservedAttachment(retained.receipt(), retained.current(), observed);
  }

  private RetainedBinding readCommitted(
      Request request,
      AccountBillingCustomerRecord expected,
      AccountPaymentInstrumentRecord committed) {
    return Objects.requireNonNull(
        transaction.execute(
            status -> {
              AccountBillingCustomerRecord customer = lockCustomer(request.accountUuid());
              if (!customer.equals(expected)) {
                throw new IllegalStateException("Committed attachment customer readback changed");
              }
              AccountPaymentInstrumentRecord current =
                  instruments
                      .findByOwnerForUpdate(request.accountUuid(), request.instrumentUuid())
                      .orElseThrow(
                          () -> new IllegalStateException("Committed instrument disappeared"));
              AccountPaymentInstrumentRecord receipt =
                  instruments
                      .findAttachmentByRequestId(request.requestId())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Committed attachment receipt disappeared"));
              requireReceiptBinding(receipt, request, customer);
              if (!receipt.equals(committed)
                  || !current.instrumentUuid().equals(receipt.instrumentUuid())
                  || !current.accountUuid().equals(receipt.accountUuid())
                  || !current
                      .customerProvisioningRequestId()
                      .equals(receipt.customerProvisioningRequestId())
                  || !current.providerCustomerId().equals(receipt.providerCustomerId())
                  || !current.providerPaymentMethodId().equals(receipt.providerPaymentMethodId())
                  || !current.attachmentRequestId().equals(receipt.attachmentRequestId())
                  || current.rowVersion() < receipt.rowVersion()
                  || (current.rowVersion() == receipt.rowVersion() && !current.equals(receipt))) {
                throw new IllegalStateException(
                    "Committed historical receipt/current binding readback mismatch");
              }
              // Later availability versions are preserved; replay cannot restore the original
              // state.
              return new RetainedBinding(receipt, current);
            }));
  }

  private AccountBillingCustomerRecord lockCustomer(UUID accountUuid) {
    AccountBillingCustomerRecord customer =
        customers
            .findByAccountForUpdate(accountUuid)
            .orElseThrow(
                () -> new IllegalStateException("Existing confirmed Account customer is required"));
    if (!customer.accountUuid().equals(accountUuid)
        || customer.state() != AccountBillingCustomerRecord.State.CONFIRMED) {
      throw new IllegalStateException("Exact confirmed Account customer is required");
    }
    return customer;
  }

  private void requireFresh(ObservedBillingPrerequisites observed) {
    Instant now = clock.instant();
    if (observed.readStartedAt().isAfter(observed.observedAt())
        || observed.observedAt().isAfter(now)
        || observed.readStartedAt().isBefore(now.minus(maximumObservationAge))) {
      throw new IllegalStateException("Provider observation is stale or from the future");
    }
  }

  private static void requireReceiptBinding(
      AccountPaymentInstrumentRecord receipt,
      Request request,
      AccountBillingCustomerRecord customer) {
    if (receipt.rowVersion() != 1
        || !receipt.instrumentUuid().equals(request.instrumentUuid())
        || !receipt.accountUuid().equals(request.accountUuid())
        || !receipt.customerProvisioningRequestId().equals(customer.customerProvisioningRequestId())
        || !receipt.providerCustomerId().equals(customer.providerCustomerId())
        || !receipt.providerPaymentMethodId().equals(request.providerPaymentMethodId())
        || !receipt.attachmentRequestId().equals(request.requestId())
        || !receipt.changeRequestId().equals(request.requestId())) {
      throw new IllegalStateException("Stable attachment request was reused with changed binding");
    }
  }

  /** Only immutable identity and explicit method selection; never caller-supplied evidence. */
  public record Request(
      UUID requestId, UUID instrumentUuid, UUID accountUuid, String providerPaymentMethodId) {
    public Request {
      requireUuid(requestId);
      requireUuid(instrumentUuid);
      requireUuid(accountUuid);
      if (providerPaymentMethodId == null
          || providerPaymentMethodId.length() > 255
          || !providerPaymentMethodId.matches("pm_[A-Za-z0-9]+")) {
        throw new IllegalArgumentException("Explicit Stripe PaymentMethod selector is required");
      }
    }

    private static void requireUuid(UUID value) {
      if (value == null || value.equals(new UUID(0, 0))) {
        throw new IllegalArgumentException("Non-nil stable attachment identity is required");
      }
    }
  }

  private record Prepared(
      AccountBillingCustomerRecord customer, AccountPaymentInstrumentRecord receipt) {}

  private record CommitResult(AccountPaymentInstrumentRecord receipt, boolean newlyRecorded) {}

  private record RetainedBinding(
      AccountPaymentInstrumentRecord receipt, AccountPaymentInstrumentRecord current) {}

  public sealed interface Result
      permits ObservedAttachment, HistoricalAttachment, PendingObservation {}

  /** Unresolved/invalid SDK results cannot produce a wallet row or prove provider absence. */
  public record PendingObservation(StripeBillingPrerequisiteObserver.Result observation)
      implements Result {}

  /** Exact original receipt plus current binding, without a fresh provider-observation claim. */
  public static final class HistoricalAttachment implements Result {
    private final AccountPaymentInstrumentRecord receipt;
    private final AccountPaymentInstrumentRecord current;

    private HistoricalAttachment(
        AccountPaymentInstrumentRecord receipt, AccountPaymentInstrumentRecord current) {
      this.receipt = receipt;
      this.current = current;
    }

    public AccountPaymentInstrumentRecord receipt() {
      return receipt;
    }

    public AccountPaymentInstrumentRecord current() {
      return current;
    }
  }

  /** Private positive construction follows both SDK reads, commit, and independent readback. */
  public static final class ObservedAttachment implements Result {
    private final AccountPaymentInstrumentRecord receipt;
    private final AccountPaymentInstrumentRecord current;
    private final ObservedBillingPrerequisites evidence;

    private ObservedAttachment(
        AccountPaymentInstrumentRecord receipt,
        AccountPaymentInstrumentRecord current,
        ObservedBillingPrerequisites evidence) {
      this.receipt = receipt;
      this.current = current;
      this.evidence = evidence;
    }

    public AccountPaymentInstrumentRecord receipt() {
      return receipt;
    }

    public AccountPaymentInstrumentRecord current() {
      return current;
    }

    public ObservedBillingPrerequisites evidence() {
      return evidence;
    }
  }
}
