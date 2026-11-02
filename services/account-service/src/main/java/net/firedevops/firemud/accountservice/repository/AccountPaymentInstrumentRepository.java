package net.firedevops.firemud.accountservice.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.DisplayMetadata;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manually composed wallet storage. Provider evidence/owner authorization must be verified by the
 * future caller before SQL. No default, detach, reservation, provider dispatch or subscription API.
 */
public final class AccountPaymentInstrumentRepository {
  private static final String HISTORY_SELECT =
      "SELECT i.instrument_uuid, i.account_uuid, i.customer_provisioning_request_id, i.provider_customer_id, "
          + "i.provider_payment_method_id, i.attachment_request_id, h.row_version, h.state, h.display_brand, "
          + "h.display_last_four, h.display_expiry_month, h.display_expiry_year, h.request_id AS change_request_id, "
          + "h.evidence_digest, i.created_at, h.recorded_at AS updated_at "
          + "FROM account_payment_instrument_observations h JOIN payment_instrument i USING (instrument_uuid) ";
  private final DSLContext dsl;
  private final AccountBillingCustomerRepository customers;

  public AccountPaymentInstrumentRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.customers = new AccountBillingCustomerRepository(dsl);
  }

  public AccountPaymentInstrumentRecord retainAttachment(Attachment request) {
    requireWritableTransaction();
    Objects.requireNonNull(request, "attachment request");
    var customer =
        customers
            .findByAccountForUpdate(request.accountUuid())
            .orElseThrow(() -> new ConflictException("Confirmed Account customer is absent"));
    if (customer.state() != AccountBillingCustomerRecord.State.CONFIRMED
        || !customer.customerProvisioningRequestId().equals(request.customerProvisioningRequestId())
        || !Objects.equals(customer.providerCustomerId(), request.providerCustomerId())) {
      throw new ConflictException("Instrument does not bind the exact confirmed Account customer");
    }
    List<Record> matches =
        dsl.fetch(
            "SELECT * FROM payment_instrument WHERE instrument_uuid = ? OR attachment_request_id = ? OR provider_payment_method_id = ?",
            request.instrumentUuid(),
            request.requestId(),
            request.providerPaymentMethodId());
    if (!matches.isEmpty()) {
      if (matches.size() != 1) {
        throw new ConflictException(
            "Instrument identities resolve to conflicting retained bindings");
      }
      AccountPaymentInstrumentRecord current = decode(matches.getFirst());
      Record receipt = dsl.fetchOne(HISTORY_SELECT + "WHERE h.request_id = ?", request.requestId());
      if (receipt == null
          || !sameAttachment(decode(receipt), request)
          || !current.instrumentUuid().equals(request.instrumentUuid())) {
        throw new ConflictException(
            "Attachment request/provider identity was reused with changed input");
      }
      return decode(receipt);
    }
    try {
      int inserted =
          dsl.execute(
              "INSERT INTO payment_instrument (instrument_uuid, account_uuid, customer_provisioning_request_id, "
                  + "provider_customer_id, provider_payment_method_id, attachment_request_id, row_version, state, "
                  + "display_brand, display_last_four, display_expiry_month, display_expiry_year, change_request_id, evidence_digest) "
                  + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
              request.instrumentUuid(),
              request.accountUuid(),
              request.customerProvisioningRequestId(),
              request.providerCustomerId(),
              request.providerPaymentMethodId(),
              request.requestId(),
              request.state().name(),
              request.display().brand(),
              request.display().lastFour(),
              request.display().expiryMonth(),
              request.display().expiryYear(),
              request.requestId(),
              request.evidenceDigest());
      if (inserted != 1) {
        throw new ConflictException(
            "Instrument identity was concurrently retained for another binding");
      }
    } catch (IntegrityConstraintViolationException conflict) {
      throw new ConflictException(
          "Attachment conflicts with retained owner/request evidence", conflict);
    }
    return findByOwner(request.accountUuid(), request.instrumentUuid()).orElseThrow();
  }

  public Optional<AccountPaymentInstrumentRecord> findByOwner(
      UUID accountUuid, UUID instrumentUuid) {
    AccountBillingCustomerRecord.requireUuid(accountUuid);
    AccountBillingCustomerRecord.requireUuid(instrumentUuid);
    Record row =
        dsl.fetchOne(
            "SELECT * FROM payment_instrument WHERE account_uuid = ? AND instrument_uuid = ?",
            accountUuid,
            instrumentUuid);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  /**
   * Locks Account, customer, then instrument. The genuine future subscription owner must validate
   * this exact version and attachable state while committing its own reservation/claim. This read
   * itself reserves nothing, authenticates nobody, and does not permit a provider call.
   */
  public Optional<AccountPaymentInstrumentRecord> findByOwnerForUpdate(
      UUID accountUuid, UUID instrumentUuid) {
    requireWritableTransaction();
    AccountBillingCustomerRecord.requireUuid(accountUuid);
    AccountBillingCustomerRecord.requireUuid(instrumentUuid);
    if (customers.findByAccountForUpdate(accountUuid).isEmpty()) {
      return Optional.empty();
    }
    Record row =
        dsl.fetchOne(
            "SELECT * FROM payment_instrument WHERE account_uuid = ? AND instrument_uuid = ? FOR UPDATE",
            accountUuid,
            instrumentUuid);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  /** Provider-approved availability/display observation only; never erasure or detach. */
  public AccountPaymentInstrumentRecord recordAvailability(AvailabilityObservation request) {
    requireWritableTransaction();
    Objects.requireNonNull(request, "instrument observation");
    AccountPaymentInstrumentRecord current =
        findByOwnerForUpdate(request.accountUuid(), request.instrumentUuid())
            .orElseThrow(
                () -> new ConflictException("Instrument does not belong to the requested Account"));
    Record receipt = dsl.fetchOne(HISTORY_SELECT + "WHERE h.request_id = ?", request.requestId());
    if (receipt != null) {
      AccountPaymentInstrumentRecord original = decode(receipt);
      if (!original.accountUuid().equals(request.accountUuid())
          || !original.instrumentUuid().equals(request.instrumentUuid())
          || original.rowVersion() != request.expectedVersion() + 1
          || original.state() != request.state()
          || !original.display().equals(request.display())
          || !original.evidenceDigest().equals(request.evidenceDigest())) {
        throw new ConflictException("Instrument observation request was reused with changed input");
      }
      return original;
    }
    if (current.rowVersion() != request.expectedVersion()) {
      throw new StaleVersionException();
    }
    try {
      int changed =
          dsl.execute(
              "UPDATE payment_instrument SET row_version = row_version + 1, state = ?, display_brand = ?, "
                  + "display_last_four = ?, display_expiry_month = ?, display_expiry_year = ?, change_request_id = ?, evidence_digest = ? "
                  + "WHERE account_uuid = ? AND instrument_uuid = ? AND row_version = ?",
              request.state().name(),
              request.display().brand(),
              request.display().lastFour(),
              request.display().expiryMonth(),
              request.display().expiryYear(),
              request.requestId(),
              request.evidenceDigest(),
              request.accountUuid(),
              request.instrumentUuid(),
              request.expectedVersion());
      if (changed != 1) {
        throw new StaleVersionException();
      }
    } catch (IntegrityConstraintViolationException conflict) {
      // Caller must roll back the rejected SQL transaction before any retry/reconciliation.
      throw new ConflictException(
          "Instrument observation conflicts with retained request evidence", conflict);
    }
    return findByOwner(request.accountUuid(), request.instrumentUuid()).orElseThrow();
  }

  /**
   * Reads one immutable original attachment receipt by its globally unique request ID. The caller
   * must hold Account, customer, and the selected instrument locks in a writable transaction.
   */
  public Optional<AccountPaymentInstrumentRecord> findAttachmentByRequestId(UUID requestId) {
    requireWritableTransaction();
    AccountBillingCustomerRecord.requireUuid(requestId);
    Record row = dsl.fetchOne(HISTORY_SELECT + "WHERE h.request_id = ?", requestId);
    return row == null ? Optional.empty() : Optional.of(decode(row));
  }

  public List<AccountPaymentInstrumentRecord> readHistory(UUID accountUuid, UUID instrumentUuid) {
    AccountBillingCustomerRecord.requireUuid(accountUuid);
    AccountBillingCustomerRecord.requireUuid(instrumentUuid);
    return dsl.fetch(
            HISTORY_SELECT
                + "WHERE i.account_uuid = ? AND h.instrument_uuid = ? ORDER BY h.row_version",
            accountUuid,
            instrumentUuid)
        .map(AccountPaymentInstrumentRepository::decode);
  }

  private static boolean sameAttachment(
      AccountPaymentInstrumentRecord retained, Attachment request) {
    return retained.rowVersion() == 1
        && retained.instrumentUuid().equals(request.instrumentUuid())
        && retained.accountUuid().equals(request.accountUuid())
        && retained.customerProvisioningRequestId().equals(request.customerProvisioningRequestId())
        && retained.providerCustomerId().equals(request.providerCustomerId())
        && retained.providerPaymentMethodId().equals(request.providerPaymentMethodId())
        && retained.attachmentRequestId().equals(request.requestId())
        && retained.state() == request.state()
        && retained.display().equals(request.display())
        && retained.evidenceDigest().equals(request.evidenceDigest());
  }

  private static AccountPaymentInstrumentRecord decode(Record row) {
    return new AccountPaymentInstrumentRecord(
        row.get("instrument_uuid", UUID.class),
        row.get("account_uuid", UUID.class),
        row.get("customer_provisioning_request_id", UUID.class),
        row.get("provider_customer_id", String.class),
        row.get("provider_payment_method_id", String.class),
        row.get("attachment_request_id", UUID.class),
        row.get("row_version", Long.class),
        State.valueOf(row.get("state", String.class)),
        new DisplayMetadata(
            row.get("display_brand", String.class),
            row.get("display_last_four", String.class),
            row.get("display_expiry_month", Integer.class),
            row.get("display_expiry_year", Integer.class)),
        row.get("change_request_id", UUID.class),
        row.get("evidence_digest", String.class),
        row.get("created_at", OffsetDateTime.class).toInstant(),
        row.get("updated_at", OffsetDateTime.class).toInstant());
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Instrument storage requires an active writable Account transaction");
    }
  }

  public record Attachment(
      UUID requestId,
      UUID instrumentUuid,
      UUID accountUuid,
      UUID customerProvisioningRequestId,
      String providerCustomerId,
      String providerPaymentMethodId,
      State state,
      DisplayMetadata display,
      String evidenceDigest) {
    public Attachment {
      AccountBillingCustomerRecord.requireUuid(requestId);
      AccountBillingCustomerRecord.requireUuid(instrumentUuid);
      AccountBillingCustomerRecord.requireUuid(accountUuid);
      AccountBillingCustomerRecord.requireUuid(customerProvisioningRequestId);
      AccountBillingCustomerRecord.requireProviderCustomer(providerCustomerId);
      AccountPaymentInstrumentRecord.requirePaymentMethod(providerPaymentMethodId);
      AccountBillingCustomerRecord.requireDigest(evidenceDigest);
      Objects.requireNonNull(state, "instrument state");
      Objects.requireNonNull(display, "approved display metadata");
    }
  }

  public record AvailabilityObservation(
      UUID requestId,
      UUID instrumentUuid,
      UUID accountUuid,
      long expectedVersion,
      State state,
      DisplayMetadata display,
      String evidenceDigest) {
    public AvailabilityObservation {
      AccountBillingCustomerRecord.requireUuid(requestId);
      AccountBillingCustomerRecord.requireUuid(instrumentUuid);
      AccountBillingCustomerRecord.requireUuid(accountUuid);
      AccountBillingCustomerRecord.requireDigest(evidenceDigest);
      Objects.requireNonNull(state, "instrument state");
      Objects.requireNonNull(display, "approved display metadata");
      if (expectedVersion < 1 || expectedVersion == Long.MAX_VALUE) {
        throw new IllegalArgumentException(
            "Instrument expected version must be positive and advanceable");
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

  public static final class StaleVersionException extends IllegalStateException {
    public StaleVersionException() {
      super("Instrument row-version fence rejected");
    }
  }
}
