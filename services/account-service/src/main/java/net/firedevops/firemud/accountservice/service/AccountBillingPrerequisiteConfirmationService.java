package net.firedevops.firemud.accountservice.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver.ExpectedBinding;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver.InvalidObservation;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver.ObservedBillingPrerequisites;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver.UnresolvedObservation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.Attachment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Manually composed, unregistered owner of Stripe billing-prerequisite confirmation. It first
 * checks exact original receipts/current intent under short ordered locks. A new intent reads the
 * real provider objects after those locks are released, then revalidates the original
 * account/customer intent before retaining only the observer's private-construction evidence
 * through the existing Account repositories.
 *
 * <p>This service does not authorize an actor, perform recent reauthentication, create/confirm a
 * SetupIntent, collect payment, create/attach/detach/default a provider resource, provision a
 * subscription, apply lifecycle or entitlement state, or expose a public API. Repository DTOs and
 * caller-supplied IDs are not accepted as provider-confirmation evidence.
 */
public final class AccountBillingPrerequisiteConfirmationService {
  private final StripeBillingPrerequisiteObserver observer;
  private final AccountBillingCustomerRepository customers;
  private final AccountPaymentInstrumentRepository instruments;
  private final TransactionTemplate transaction;

  public AccountBillingPrerequisiteConfirmationService(
      StripeBillingPrerequisiteObserver observer,
      AccountBillingCustomerRepository customers,
      AccountPaymentInstrumentRepository instruments,
      PlatformTransactionManager transactionManager) {
    this.observer = Objects.requireNonNull(observer, "Stripe observer is required");
    this.customers = Objects.requireNonNull(customers, "customer repository is required");
    this.instruments = Objects.requireNonNull(instruments, "instrument repository is required");
    this.transaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transaction manager is required"));
    this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /**
   * Confirms one exact durable customer operation and explicit wallet instrument identity. Provider
   * exceptions and invalid observations return non-positive result variants and perform no SQL
   * writes. Stale or conflicting local intent fails closed with {@link ConflictException}.
   */
  public Result confirm(ConfirmationRequest request) {
    Objects.requireNonNull(request, "confirmation request is required");
    rejectAmbientTransaction();

    Preflight preflight = executeLocalTransaction(status -> inspectOriginalOrCurrent(request));
    if (preflight == null) {
      throw new IllegalStateException("Billing prerequisite preflight returned no result");
    }
    if (preflight instanceof OriginalReplay replay) {
      return new ReplayedConfirmation(replay.customer(), replay.instrument());
    }
    if (!(preflight instanceof NewIntent)) {
      throw new IllegalStateException("Billing prerequisite preflight returned an unknown result");
    }

    ExpectedBinding expected =
        new ExpectedBinding(
            request.accountUuid(),
            request.customerProvisioningRequestId(),
            request.providerIdempotencyId(),
            request.providerCustomerIdHint(),
            request.providerPaymentMethodIdHint());
    StripeBillingPrerequisiteObserver.Result providerObservation = observer.observe(expected);
    if (providerObservation instanceof InvalidObservation invalid) {
      return new InvalidConfirmation(
          invalid.reason(), invalid.readStartedAt(), invalid.observedAt());
    }
    if (providerObservation instanceof UnresolvedObservation unresolved) {
      return new UnresolvedConfirmation(unresolved);
    }
    if (!(providerObservation instanceof ObservedBillingPrerequisites observed)) {
      throw new IllegalStateException("Stripe observer returned an unsupported result type");
    }

    CommittedRecords committed = executeLocalTransaction(status -> commit(request, observed));
    if (committed == null) {
      throw new IllegalStateException("Billing prerequisite transaction returned no records");
    }
    if (committed.replayed()) {
      return new ReplayedConfirmation(committed.customer(), committed.instrument());
    }
    return new ConfirmedConfirmation(
        committed.customer(),
        committed.instrument(),
        observed.evidenceDigest(),
        observed.readStartedAt(),
        observed.observedAt());
  }

  private <T> T executeLocalTransaction(TransactionCallback<T> action) {
    try {
      return transaction.execute(action);
    } catch (AccountBillingCustomerRepository.ConflictException
        | AccountBillingCustomerRepository.StaleVersionException
        | AccountPaymentInstrumentRepository.ConflictException
        | AccountPaymentInstrumentRepository.StaleVersionException failure) {
      throw new ConflictException(failure.getMessage(), failure);
    }
  }

  /** Lock and classify before the provider call; an exact retained result is returned directly. */
  private Preflight inspectOriginalOrCurrent(ConfirmationRequest request) {
    AccountBillingCustomerRecord currentCustomer =
        customers
            .findByAccountForUpdate(request.accountUuid())
            .orElseThrow(() -> new ConflictException("Durable Account customer intent is absent"));
    requireCustomerOperationIdentity(currentCustomer, request);

    Optional<AccountPaymentInstrumentRecord> currentInstrument =
        instruments.findByOwnerForUpdate(request.accountUuid(), request.instrumentUuid());
    Optional<AccountBillingCustomerRecord> originalCustomer =
        customers.findObservationByRequestId(request.customerObservationRequestId());
    Optional<AccountPaymentInstrumentRecord> originalInstrument =
        instruments.findAttachmentByRequestId(request.attachmentRequestId());

    return classifyOriginalOrNewIntent(
        currentCustomer, currentInstrument, originalCustomer, originalInstrument, request);
  }

  private CommittedRecords commit(
      ConfirmationRequest request, ObservedBillingPrerequisites observed) {
    AccountBillingCustomerRecord current =
        customers
            .findByAccountForUpdate(request.accountUuid())
            .orElseThrow(
                () -> new ConflictException("Durable Account customer intent disappeared"));
    requireCustomerOperationIdentity(current, request);
    Optional<AccountPaymentInstrumentRecord> currentInstrument =
        instruments.findByOwnerForUpdate(request.accountUuid(), request.instrumentUuid());
    Optional<AccountBillingCustomerRecord> originalCustomer =
        customers.findObservationByRequestId(request.customerObservationRequestId());
    Optional<AccountPaymentInstrumentRecord> originalInstrument =
        instruments.findAttachmentByRequestId(request.attachmentRequestId());
    Preflight preflight =
        classifyOriginalOrNewIntent(
            current, currentInstrument, originalCustomer, originalInstrument, request);
    if (preflight instanceof OriginalReplay replay) {
      return new CommittedRecords(replay.customer(), replay.instrument(), true);
    }
    if (!(preflight instanceof NewIntent)) {
      throw new IllegalStateException("Billing prerequisite commit returned an unknown preflight");
    }
    if (!observed.accountUuid().equals(current.accountUuid())
        || !observed.customerProvisioningRequestId().equals(current.customerProvisioningRequestId())
        || !observed.providerCustomerId().equals(request.providerCustomerIdHint())
        || !observed.providerPaymentMethodId().equals(request.providerPaymentMethodIdHint())) {
      throw new ConflictException(
          "Provider evidence changed the original Account/customer binding");
    }

    AccountBillingCustomerRecord confirmed =
        customers.recordObservation(
            request.accountUuid(),
            request.customerProvisioningRequestId(),
            request.expectedCustomerVersion(),
            request.customerObservationRequestId(),
            Observation.CONFIRMED,
            observed.providerCustomerId(),
            observed.evidenceDigest());
    if (confirmed.state() != State.CONFIRMED
        || !confirmed.accountUuid().equals(request.accountUuid())
        || !confirmed
            .customerProvisioningRequestId()
            .equals(request.customerProvisioningRequestId())
        || !confirmed.providerIdempotencyId().equals(request.providerIdempotencyId())
        || !observed.providerCustomerId().equals(confirmed.providerCustomerId())) {
      throw new ConflictException("Retained customer does not match actual provider evidence");
    }

    Attachment attachment =
        new Attachment(
            request.attachmentRequestId(),
            request.instrumentUuid(),
            request.accountUuid(),
            request.customerProvisioningRequestId(),
            observed.providerCustomerId(),
            observed.providerPaymentMethodId(),
            AccountPaymentInstrumentRecord.State.ATTACHABLE,
            observed.display(),
            observed.evidenceDigest());
    AccountPaymentInstrumentRecord retained = instruments.retainAttachment(attachment);
    requireSameInstrumentIdentity(retained, request, observed.providerCustomerId());
    if (retained.state() != AccountPaymentInstrumentRecord.State.ATTACHABLE
        || !retained.display().equals(observed.display())
        || !retained.evidenceDigest().equals(observed.evidenceDigest())) {
      throw new ConflictException("Retained wallet evidence conflicts with current Stripe reads");
    }
    return new CommittedRecords(confirmed, retained, false);
  }

  private static Preflight classifyOriginalOrNewIntent(
      AccountBillingCustomerRecord currentCustomer,
      Optional<AccountPaymentInstrumentRecord> currentInstrument,
      Optional<AccountBillingCustomerRecord> originalCustomer,
      Optional<AccountPaymentInstrumentRecord> originalInstrument,
      ConfirmationRequest request) {
    boolean hasCustomerReceipt = originalCustomer.isPresent();
    boolean hasInstrumentReceipt = originalInstrument.isPresent();
    if (hasCustomerReceipt || hasInstrumentReceipt) {
      if (!hasCustomerReceipt || !hasInstrumentReceipt) {
        throw new ConflictException("Original customer/instrument receipts are incomplete");
      }
      AccountBillingCustomerRecord customerReceipt = originalCustomer.orElseThrow();
      AccountPaymentInstrumentRecord instrumentReceipt = originalInstrument.orElseThrow();
      validateOriginalReceipts(customerReceipt, instrumentReceipt, request);
      AccountPaymentInstrumentRecord current =
          currentInstrument.orElseThrow(
              () -> new ConflictException("Original instrument owner row is absent"));
      requireSameInstrumentIdentity(current, request, customerReceipt.providerCustomerId());
      return new OriginalReplay(customerReceipt, instrumentReceipt);
    }

    validateNewIntentVersion(currentCustomer, request);
    if (currentInstrument.isPresent()) {
      throw new ConflictException("Explicit instrument UUID already has a retained owner row");
    }
    return NewIntent.INSTANCE;
  }

  private static void validateOriginalReceipts(
      AccountBillingCustomerRecord customer,
      AccountPaymentInstrumentRecord instrument,
      ConfirmationRequest request) {
    if (!customer.accountUuid().equals(request.accountUuid())
        || !customer.customerProvisioningRequestId().equals(request.customerProvisioningRequestId())
        || !customer.providerIdempotencyId().equals(request.providerIdempotencyId())
        || customer.rowVersion() != request.expectedCustomerVersion() + 1
        || customer.state() != State.CONFIRMED
        || customer.observation() != Observation.CONFIRMED
        || !customer.changeRequestId().equals(request.customerObservationRequestId())
        || !Objects.equals(customer.providerCustomerId(), request.providerCustomerIdHint())) {
      throw new ConflictException("Original customer observation receipt does not match request");
    }
    if (instrument.rowVersion() != 1
        || !instrument.instrumentUuid().equals(request.instrumentUuid())
        || !instrument.accountUuid().equals(request.accountUuid())
        || !instrument
            .customerProvisioningRequestId()
            .equals(request.customerProvisioningRequestId())
        || !instrument.providerCustomerId().equals(request.providerCustomerIdHint())
        || !instrument.providerPaymentMethodId().equals(request.providerPaymentMethodIdHint())
        || !instrument.attachmentRequestId().equals(request.attachmentRequestId())
        || !instrument.changeRequestId().equals(request.attachmentRequestId())
        || instrument.state() != AccountPaymentInstrumentRecord.State.ATTACHABLE
        || !instrument.evidenceDigest().equals(customer.evidenceDigest())) {
      throw new ConflictException("Original instrument attachment receipt does not match request");
    }
  }

  private static void requireCustomerOperationIdentity(
      AccountBillingCustomerRecord retained, ConfirmationRequest request) {
    if (!retained.accountUuid().equals(request.accountUuid())
        || !retained.customerProvisioningRequestId().equals(request.customerProvisioningRequestId())
        || !retained.providerIdempotencyId().equals(request.providerIdempotencyId())) {
      throw new ConflictException("Original Account/customer operation binding changed");
    }
  }

  private static void validateNewIntentVersion(
      AccountBillingCustomerRecord retained, ConfirmationRequest request) {
    boolean currentIntentVersion =
        retained.rowVersion() == request.expectedCustomerVersion()
            && (retained.state() == State.UNCONFIRMED || retained.state() == State.RECONCILING)
            && retained.providerCustomerId() == null;
    if (!currentIntentVersion) {
      throw new ConflictException("Customer intent/version changed before provider confirmation");
    }
  }

  private static void requireSameInstrumentIdentity(
      AccountPaymentInstrumentRecord retained,
      ConfirmationRequest request,
      String providerCustomerId) {
    if (!retained.instrumentUuid().equals(request.instrumentUuid())
        || !retained.accountUuid().equals(request.accountUuid())
        || !retained.customerProvisioningRequestId().equals(request.customerProvisioningRequestId())
        || !retained.providerCustomerId().equals(providerCustomerId)
        || !retained.providerPaymentMethodId().equals(request.providerPaymentMethodIdHint())
        || !retained.attachmentRequestId().equals(request.attachmentRequestId())) {
      throw new ConflictException("Explicit Account wallet instrument binding changed");
    }
  }

  private static void rejectAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Stripe billing prerequisite reads cannot run inside an ambient transaction or synchronization");
    }
  }

  private record CommittedRecords(
      AccountBillingCustomerRecord customer,
      AccountPaymentInstrumentRecord instrument,
      boolean replayed) {}

  private sealed interface Preflight permits NewIntent, OriginalReplay {}

  private enum NewIntent implements Preflight {
    INSTANCE
  }

  private record OriginalReplay(
      AccountBillingCustomerRecord customer, AccountPaymentInstrumentRecord instrument)
      implements Preflight {}

  public sealed interface Result
      permits ConfirmedConfirmation,
          ReplayedConfirmation,
          InvalidConfirmation,
          UnresolvedConfirmation {}

  /**
   * Exact historical receipts replayed without a provider read. These records are not a claim that
   * their original provider or instrument state is current or attachable now.
   */
  public static final class ReplayedConfirmation implements Result {
    private final AccountBillingCustomerRecord customerReceipt;
    private final AccountPaymentInstrumentRecord instrumentReceipt;

    private ReplayedConfirmation(
        AccountBillingCustomerRecord customer, AccountPaymentInstrumentRecord instrument) {
      this.customerReceipt = customer;
      this.instrumentReceipt = instrument;
    }

    public AccountBillingCustomerRecord customerReceipt() {
      return customerReceipt;
    }

    public AccountPaymentInstrumentRecord instrumentReceipt() {
      return instrumentReceipt;
    }

    public String evidenceDigest() {
      return customerReceipt.evidenceDigest();
    }

    public ReplayDisposition disposition() {
      return ReplayDisposition.EXACT_RETAINED_ORIGINAL;
    }
  }

  public enum ReplayDisposition {
    EXACT_RETAINED_ORIGINAL
  }

  /**
   * A persisted, exact provider-confirmed customer and explicit instrument; private construction.
   */
  public static final class ConfirmedConfirmation implements Result {
    private final AccountBillingCustomerRecord customer;
    private final AccountPaymentInstrumentRecord instrument;
    private final String evidenceDigest;
    private final Instant readStartedAt;
    private final Instant observedAt;

    private ConfirmedConfirmation(
        AccountBillingCustomerRecord customer,
        AccountPaymentInstrumentRecord instrument,
        String evidenceDigest,
        Instant readStartedAt,
        Instant observedAt) {
      this.customer = customer;
      this.instrument = instrument;
      this.evidenceDigest = evidenceDigest;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
    }

    public AccountBillingCustomerRecord customer() {
      return customer;
    }

    public AccountPaymentInstrumentRecord instrument() {
      return instrument;
    }

    public String evidenceDigest() {
      return evidenceDigest;
    }

    public Instant readStartedAt() {
      return readStartedAt;
    }

    public Instant observedAt() {
      return observedAt;
    }
  }

  public static final class InvalidConfirmation implements Result {
    private final StripeBillingPrerequisiteObserver.InvalidReason reason;
    private final Instant readStartedAt;
    private final Instant observedAt;

    private InvalidConfirmation(
        StripeBillingPrerequisiteObserver.InvalidReason reason,
        Instant readStartedAt,
        Instant observedAt) {
      this.reason = reason;
      this.readStartedAt = readStartedAt;
      this.observedAt = observedAt;
    }

    public StripeBillingPrerequisiteObserver.InvalidReason reason() {
      return reason;
    }

    public Instant readStartedAt() {
      return readStartedAt;
    }

    public Instant observedAt() {
      return observedAt;
    }
  }

  public static final class UnresolvedConfirmation implements Result {
    private final UnresolvedObservation observation;

    private UnresolvedConfirmation(UnresolvedObservation observation) {
      this.observation = observation;
    }

    public UnresolvedObservation observation() {
      return observation;
    }
  }

  /** Exact existing customer-operation and explicit wallet identity; contains no provider proof. */
  public record ConfirmationRequest(
      UUID accountUuid,
      UUID customerProvisioningRequestId,
      UUID providerIdempotencyId,
      long expectedCustomerVersion,
      UUID customerObservationRequestId,
      String providerCustomerIdHint,
      UUID instrumentUuid,
      UUID attachmentRequestId,
      String providerPaymentMethodIdHint) {
    public ConfirmationRequest {
      requireUuid(accountUuid, "Account UUID");
      requireUuid(customerProvisioningRequestId, "customer provisioning request ID");
      requireUuid(providerIdempotencyId, "customer provider idempotency ID");
      requireUuid(customerObservationRequestId, "customer observation request ID");
      requireUuid(instrumentUuid, "payment-instrument UUID");
      requireUuid(attachmentRequestId, "instrument attachment request ID");
      if (customerProvisioningRequestId.equals(providerIdempotencyId)
          || customerObservationRequestId.equals(customerProvisioningRequestId)
          || customerObservationRequestId.equals(providerIdempotencyId)
          || attachmentRequestId.equals(customerProvisioningRequestId)
          || attachmentRequestId.equals(providerIdempotencyId)
          || attachmentRequestId.equals(customerObservationRequestId)) {
        throw new IllegalArgumentException("Billing prerequisite identities must be disjoint");
      }
      if (expectedCustomerVersion < 1 || expectedCustomerVersion == Long.MAX_VALUE) {
        throw new IllegalArgumentException(
            "Expected customer version must be positive and advanceable");
      }
      if (providerCustomerIdHint == null || !providerCustomerIdHint.matches("cus_[A-Za-z0-9]+")) {
        throw new IllegalArgumentException("Stripe Customer lookup hint is invalid");
      }
      if (providerPaymentMethodIdHint == null
          || !providerPaymentMethodIdHint.matches("pm_[A-Za-z0-9]+")) {
        throw new IllegalArgumentException("Stripe PaymentMethod lookup hint is invalid");
      }
    }

    private static void requireUuid(UUID value, String label) {
      Objects.requireNonNull(value, label + " is required");
      if (value.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException(label + " must not be nil");
      }
    }
  }

  public static final class ConflictException extends IllegalStateException {
    public ConflictException(String message) {
      super(message);
    }

    public ConflictException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
