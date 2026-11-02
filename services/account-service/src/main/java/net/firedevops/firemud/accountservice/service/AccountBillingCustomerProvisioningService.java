package net.firedevops.firemud.accountservice.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeBillingCustomerProvisioner;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository.DispatchClaim;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository.DispatchClaimResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Held, manually composed owner component consuming an existing genuine Account customer intent.
 * IDs select that durable owner; they confer no caller authorization and cannot retain new intent.
 * Production wiring, authenticated admission, retry scheduling/budgets and operator recovery remain
 * held. A committed first-dispatch claim precedes all provider IO. Existing claims reconcile only;
 * a crash before actual dispatch may require operator resolution rather than automatic recreation.
 * No instrument, subscription, payment, entitlement, route or issuer is produced here.
 */
public final class AccountBillingCustomerProvisioningService {
  private final AccountBillingCustomerRepository customers;
  private final StripeBillingCustomerProvisioner provider;
  private final TransactionTemplate transaction;

  public AccountBillingCustomerProvisioningService(
      AccountBillingCustomerRepository customers,
      StripeBillingCustomerProvisioner provider,
      PlatformTransactionManager transactions) {
    this.customers = Objects.requireNonNull(customers);
    this.provider = Objects.requireNonNull(provider);
    transaction = new TransactionTemplate(Objects.requireNonNull(transactions));
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public Result provision(UUID accountUuid, UUID customerProvisioningRequestId) {
    requireUuid(accountUuid);
    requireUuid(customerProvisioningRequestId);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "Customer provisioning cannot span an ambient SQL transaction");
    }
    Prepared prepared =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  AccountBillingCustomerRecord current =
                      lock(accountUuid, customerProvisioningRequestId);
                  if (current.state() == State.CONFIRMED) {
                    return new Prepared(current, null);
                  }
                  return new Prepared(
                      current, customers.claimFirstProviderDispatch(current, UUID.randomUUID()));
                }));
    if (prepared.dispatch() == null) {
      return new RetainedCustomer(prepared.intent());
    }
    // Independently verify the committed claim and original durable intent before any provider IO.
    AccountBillingCustomerRecord dispatchReadback =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  AccountBillingCustomerRecord current =
                      lock(accountUuid, customerProvisioningRequestId);
                  DispatchClaim retained =
                      customers
                          .findDispatchClaim(accountUuid)
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Committed dispatch claim disappeared"));
                  if (!retained.equals(prepared.dispatch().claim())
                      || !current
                          .providerIdempotencyId()
                          .equals(prepared.intent().providerIdempotencyId())) {
                    throw new IllegalStateException("Committed customer dispatch binding changed");
                  }
                  return current;
                }));
    if (dispatchReadback.state() == State.CONFIRMED) {
      return new RetainedCustomer(dispatchReadback);
    }
    if (dispatchReadback.rowVersion() != prepared.intent().rowVersion()) {
      throw new AccountBillingCustomerRepository.StaleVersionException();
    }

    boolean initialDispatch =
        prepared.dispatch().inserted()
            && prepared.intent().state() == State.UNCONFIRMED
            && prepared.intent().rowVersion() == 1;
    StripeBillingCustomerProvisioner.Result observed =
        provider.reconcile(prepared.intent(), initialDispatch);
    if (!(observed instanceof StripeBillingCustomerProvisioner.Confirmed confirmed)) {
      return new PendingCustomer(observed);
    }
    UUID observationRequestId = UUID.randomUUID();
    CommitResult committed =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  AccountBillingCustomerRecord current =
                      lock(accountUuid, customerProvisioningRequestId);
                  if (!confirmed.accountUuid().equals(current.accountUuid())
                      || !confirmed
                          .customerProvisioningRequestId()
                          .equals(current.customerProvisioningRequestId())
                      || !confirmed
                          .providerIdempotencyId()
                          .equals(current.providerIdempotencyId())) {
                    throw new IllegalStateException(
                        "Provider evidence changed immutable customer operation");
                  }
                  if (current.state() == State.CONFIRMED) {
                    if (!confirmed.providerCustomerId().equals(current.providerCustomerId())) {
                      throw new IllegalStateException(
                          "Provider customer conflicts with retained confirmation");
                    }
                    return new CommitResult(current, false);
                  }
                  if (current.rowVersion() != prepared.intent().rowVersion()) {
                    throw new AccountBillingCustomerRepository.StaleVersionException();
                  }
                  AccountBillingCustomerRecord receipt =
                      customers.recordObservation(
                          accountUuid,
                          customerProvisioningRequestId,
                          current.rowVersion(),
                          observationRequestId,
                          Observation.CONFIRMED,
                          confirmed.providerCustomerId(),
                          confirmed.evidenceDigest());
                  if (!receipt.changeRequestId().equals(observationRequestId)
                      || receipt.rowVersion() != current.rowVersion() + 1
                      || !confirmed.evidenceDigest().equals(receipt.evidenceDigest())) {
                    throw new IllegalStateException(
                        "Customer confirmation receipt changed observation identity");
                  }
                  return new CommitResult(receipt, true);
                }));
    // The write transaction has actually committed. Read the exact immutable observation and
    // current owner afresh; its in-transaction return value alone is not durable readback proof.
    AccountBillingCustomerRecord retained =
        Objects.requireNonNull(
            transaction.execute(
                status -> {
                  AccountBillingCustomerRecord current =
                      lock(accountUuid, customerProvisioningRequestId);
                  AccountBillingCustomerRecord receipt =
                      customers
                          .findObservationByRequestId(committed.receipt().changeRequestId())
                          .orElseThrow(
                              () ->
                                  new IllegalStateException(
                                      "Committed customer observation disappeared"));
                  if (!receipt.equals(committed.receipt())
                      || !current.equals(receipt)
                      || receipt.state() != State.CONFIRMED
                      || receipt.observation() != Observation.CONFIRMED
                      || !confirmed.providerCustomerId().equals(receipt.providerCustomerId())
                      || !confirmed.accountUuid().equals(receipt.accountUuid())
                      || !confirmed
                          .customerProvisioningRequestId()
                          .equals(receipt.customerProvisioningRequestId())
                      || !confirmed.providerIdempotencyId().equals(receipt.providerIdempotencyId())
                      || (committed.newlyRecorded()
                          && (!observationRequestId.equals(receipt.changeRequestId())
                              || !confirmed.evidenceDigest().equals(receipt.evidenceDigest())))) {
                    throw new IllegalStateException(
                        "Committed customer confirmation readback mismatch");
                  }
                  return receipt;
                }));
    // A simultaneous confirmation keeps its own original observation identity and digest. It is
    // historical replay, not a new observation under this invocation's unused request identity.
    if (!committed.newlyRecorded()) {
      return new RetainedCustomer(retained);
    }
    return new ConfirmedCustomer(retained, confirmed);
  }

  private AccountBillingCustomerRecord lock(UUID account, UUID operation) {
    AccountBillingCustomerRecord current =
        customers
            .findByAccountForUpdate(account)
            .orElseThrow(
                () -> new IllegalStateException("Existing durable customer intent is required"));
    if (!current.accountUuid().equals(account)
        || !current.customerProvisioningRequestId().equals(operation)) {
      throw new IllegalStateException("Durable customer operation binding differs");
    }
    return current;
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.equals(new UUID(0, 0))) {
      throw new IllegalArgumentException("Non-nil durable customer identity is required");
    }
  }

  private record Prepared(AccountBillingCustomerRecord intent, DispatchClaimResult dispatch) {}

  private record CommitResult(AccountBillingCustomerRecord receipt, boolean newlyRecorded) {}

  public sealed interface Result permits ConfirmedCustomer, RetainedCustomer, PendingCustomer {}

  /** Historical local confirmation; does not claim a fresh provider read. */
  public record RetainedCustomer(AccountBillingCustomerRecord customer) implements Result {}

  /** Always nonterminal; an invalid or empty provider result cannot authorize a replacement. */
  public record PendingCustomer(StripeBillingCustomerProvisioner.Result observation)
      implements Result {}

  /** Private construction retains actual SDK readback evidence alongside its committed owner. */
  public static final class ConfirmedCustomer implements Result {
    private final AccountBillingCustomerRecord customer;
    private final StripeBillingCustomerProvisioner.Confirmed evidence;

    private ConfirmedCustomer(
        AccountBillingCustomerRecord customer,
        StripeBillingCustomerProvisioner.Confirmed evidence) {
      this.customer = customer;
      this.evidence = evidence;
    }

    public AccountBillingCustomerRecord customer() {
      return customer;
    }

    public StripeBillingCustomerProvisioner.Confirmed evidence() {
      return evidence;
    }
  }
}
