package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationEvidence;
import net.firedevops.firemud.accountservice.service.session.AccountRecentReauthenticationPolicy;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountHostingFirstInvoicePaymentRepositoryTest {
  private final DSLContext dsl = mock(DSLContext.class);
  private final AccountHostingFirstInvoicePaymentRepository payments =
      new AccountHostingFirstInvoicePaymentRepository(
          dsl, mock(AccountSubscriptionIntentRepository.class), Clock.systemUTC());

  @AfterEach
  void clearTransactions() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @Test
  void ownerReadRecoversOnlyTheExactPreRowIdentityEvenAfterExpiryWithoutMutation() {
    var f = new OwnerReferenceFixture();
    assertThat(f.read()).isEqualTo(f.original);
    verify(f.repository).requireOwner(f.invoice.subscriptionId(), f.invoice.ownerAccountId());
    verify(f.repository).requireAwaitingPayment(f.invoice, 3);
    verify(f.dsl)
        .fetchOne(
            "SELECT * FROM subscription_first_invoice_operation_fences WHERE subscription_id = ? FOR UPDATE",
            f.invoice.subscriptionId());
    verify(f.dsl, never()).execute(anyString(), any(Object[].class));
    f.fencePresent = false;
    assertThat(f.read()).isNull();

    f.fencePresent = true;
    f.confirmation = UUID.randomUUID();
    assertThat(f.read()).isNull();
  }

  @Test
  void inconsistentExistingFenceCannotLookLikeAnAbsentOriginalOperation() {
    for (var mismatch :
        Map.<String, Object>of(
                "subscription_id", UUID.randomUUID(),
                "account_uuid", UUID.randomUUID(),
                "tuple_digest", "sha256:" + "c".repeat(64),
                "expected_row_version", 4L,
                "invoice_expires_at", Instant.EPOCH.atOffset(java.time.ZoneOffset.UTC))
            .entrySet()) {
      var f = new OwnerReferenceFixture();
      f.fields.put(mismatch.getKey(), mismatch.getValue());
      assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    }
    var missingIdentity = new OwnerReferenceFixture();
    missingIdentity.fields.remove("request_id");
    assertThatThrownBy(missingIdentity::read).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void preRowReferenceRefusesAuthorizationDispatchOrCommandWithoutAConfirmation() {
    var f = new OwnerReferenceFixture();
    f.version = 1;
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    f.version = 0;
    f.dispatched = true;
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    f.dispatched = false;
    f.commandClaimed = true;
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
    f.fencePresent = false;
    assertThatThrownBy(f::read).isInstanceOf(IllegalStateException.class);
  }

  private static final class OwnerReferenceFixture {
    final DSLContext dsl = mock(DSLContext.class);
    final AccountHostingFirstInvoicePayment invoice = invoice("in_exact", "pi_exact", 2400, "usd");
    final UUID original = UUID.randomUUID();
    final Map<String, Object> fields = new HashMap<>();
    final AccountHostingFirstInvoicePaymentRepository repository;
    UUID confirmation;
    long version;
    boolean fencePresent = true;
    boolean dispatched;
    boolean commandClaimed;

    OwnerReferenceFixture() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      repository =
          spy(
              new AccountHostingFirstInvoicePaymentRepository(
                  dsl,
                  mock(AccountSubscriptionIntentRepository.class),
                  Clock.fixed(invoice.expiresAt().plusSeconds(1), java.time.ZoneOffset.UTC)));
      doReturn(invoice)
          .when(repository)
          .requireOwner(invoice.subscriptionId(), invoice.ownerAccountId());
      doReturn(3L)
          .when(repository)
          .ownerRowVersion(invoice.subscriptionId(), invoice.ownerAccountId());
      doAnswer(call -> confirmation)
          .when(repository)
          .confirmationRequestId(invoice.subscriptionId());
      doAnswer(call -> version).when(repository).authorizationVersion(invoice.subscriptionId());
      doNothing().when(repository).requireAwaitingPayment(invoice, 3);
      fields.put("subscription_id", invoice.subscriptionId());
      fields.put("request_id", original);
      fields.put("account_uuid", invoice.ownerAccountId());
      fields.put("tuple_digest", invoice.digest());
      fields.put("expected_row_version", 3L);
      fields.put("invoice_expires_at", invoice.expiresAt().atOffset(java.time.ZoneOffset.UTC));
      Record fence = RenewalFixture.row(fields);
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              call -> {
                String sql = call.getArgument(0);
                if (sql.contains("operation_fences")) return fencePresent ? fence : null;
                if (sql.contains("first_invoice_dispatches")) return dispatched ? fence : null;
                if (sql.contains("first_invoice_command_claims"))
                  return commandClaimed ? fence : null;
                throw new AssertionError(sql);
              });
    }

    UUID read() {
      return repository.preRowOriginalRequestId(invoice.subscriptionId(), invoice.ownerAccountId());
    }
  }

  @Test
  void immutableDigestBindsExistingProviderInvoiceIntentInstrumentPolicyAndExpiry() {
    var invoice = invoice("in_exact", "pi_exact", 2400, "usd");
    assertThat(invoice.digest()).isEqualTo(invoice("in_exact", "pi_exact", 2400, "usd").digest());
    assertThat(invoice.digest())
        .isNotEqualTo(invoice("in_other", "pi_exact", 2400, "usd").digest());
    assertThat(invoice.digest())
        .isNotEqualTo(invoice("in_exact", "pi_other", 2400, "usd").digest());
    assertThat(invoice.digest())
        .isNotEqualTo(invoice("in_exact", "pi_exact", 2500, "usd").digest());
    assertThat(invoice.digest())
        .isNotEqualTo(invoice("in_exact", "pi_exact", 2400, "eur").digest());
    assertThat(invoice.digest())
        .isNotEqualTo(
            invoice(
                    "in_exact",
                    "pi_exact",
                    2400,
                    "usd",
                    UUID.randomUUID(),
                    invoice.billingPolicyDigest(),
                    invoice.expiresAt())
                .digest())
        .isNotEqualTo(
            invoice(
                    "in_exact",
                    "pi_exact",
                    2400,
                    "usd",
                    invoice.paymentInstrumentId(),
                    "sha256:" + "c".repeat(64),
                    invoice.expiresAt())
                .digest())
        .isNotEqualTo(
            invoice(
                    "in_exact",
                    "pi_exact",
                    2400,
                    "usd",
                    invoice.paymentInstrumentId(),
                    invoice.billingPolicyDigest(),
                    invoice.expiresAt().minusSeconds(1))
                .digest());
  }

  @Test
  void invoiceDigestUsesPostgresTimestampPrecision() {
    var invoice = invoice("in_exact", "pi_exact", 2400, "usd");
    var subMicrosecond =
        invoice(
            invoice.invoiceId(),
            invoice.paymentIntentId(),
            invoice.amount(),
            invoice.currency(),
            invoice.paymentInstrumentId(),
            invoice.billingPolicyDigest(),
            invoice.capturedAt().plusNanos(900),
            invoice.expiresAt().plusNanos(900));
    assertThat(subMicrosecond).isEqualTo(invoice);
    assertThat(subMicrosecond.digest()).isEqualTo(invoice.digest());
    assertThatThrownBy(
            () ->
                invoice(
                    invoice.invoiceId(),
                    invoice.paymentIntentId(),
                    invoice.amount(),
                    invoice.currency(),
                    invoice.paymentInstrumentId(),
                    invoice.billingPolicyDigest(),
                    invoice.capturedAt(),
                    invoice.capturedAt().plusNanos(900)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingAndReadonlyTransactionsCannotReadOrMutateCheckout() {
    for (boolean readOnly : new boolean[] {false, true}) {
      TransactionSynchronizationManager.setActualTransactionActive(readOnly);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
      assertThatThrownBy(() -> payments.find(UUID.randomUUID()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> payments.retainAwaitingPayment(null, null, null))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> payments.retainSignedSuccess(null, null))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> payments.readDispatch(null, null))
          .isInstanceOf(IllegalStateException.class);
    }
    verifyNoInteractions(dsl);
  }

  @Test
  void onlyExactCurrentAwaitingPaymentSourcePermitsCommandPreparationOrSecretReadback() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    var invoice = invoice("in_exact", "pi_exact", 2400, "usd");
    Record row = mock(Record.class);
    when(dsl.fetchOne(
            AccountHostingFirstInvoicePaymentRepository.AWAITING_PAYMENT_SQL,
            invoice.subscriptionId(),
            invoice.creationRequestId()))
        .thenReturn(row);
    when(row.get("status", String.class)).thenReturn("provisioning");
    when(row.get("phase", String.class)).thenReturn("AWAITING_PAYMENT");
    when(row.get("row_version", Long.class)).thenReturn(3L);
    when(row.get("provider_subscription_id", String.class)).thenReturn("sub_exact");
    payments.requireAwaitingPayment(invoice, 3);
    for (String status : new String[] {"active", "trialing", "canceled"}) {
      when(row.get("status", String.class)).thenReturn(status);
      assertThatThrownBy(() -> payments.requireAwaitingPayment(invoice, 3))
          .isInstanceOf(IllegalStateException.class);
    }
    when(row.get("status", String.class)).thenReturn("provisioning");
    when(row.get("phase", String.class)).thenReturn("CONFIRMED");
    assertThatThrownBy(() -> payments.requireAwaitingPayment(invoice, 3))
        .isInstanceOf(IllegalStateException.class);
    when(row.get("phase", String.class)).thenReturn("AWAITING_PAYMENT");
    assertThatThrownBy(() -> payments.requireAwaitingPayment(invoice, 4))
        .isInstanceOf(IllegalStateException.class);
    when(row.get("provider_subscription_id", String.class)).thenReturn("sub_replacement");
    assertThatThrownBy(() -> payments.requireAwaitingPayment(invoice, 3))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void commercialZeroNegativeAmbiguousCurrencyAndNewProviderIdentifiersAreNotAccepted() {
    assertThatThrownBy(() -> invoice("in_exact", "pi_exact", 0, "usd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> invoice("in_exact", "pi_exact", -1, "usd"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> invoice("in_exact", "pi_exact", 2400, "USD"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> invoice("in_exact", "cs_newCheckout", 2400, "usd"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freshConsentRecoversCrashBeforeCommandClaimAndKeepsEveryOriginalIdentity() {
    var f = new RenewalFixture();
    var prepared = f.renew();
    assertThat(prepared.requestId()).isEqualTo(f.originalId);
    assertThat(prepared.providerIdempotencyId()).isEqualTo(f.providerId);
    assertThat(prepared.lifecycleRequestId()).isEqualTo(f.lifecycleId);
    assertThat(prepared.invoice()).isEqualTo(f.invoice);
    assertThat(prepared.authorizationVersion()).isEqualTo(2);
    var dispatch = f.repository.readDispatch(prepared, f.invoice.ownerAccountId());
    assertThat(dispatch.takeInitialCommandPermission()).isTrue();
    var claim = f.repository.claimCommand(dispatch, f.invoice.ownerAccountId());
    assertThat(claim).isNotNull();
    assertThat(claim.takePermission(prepared)).isFalse();
    assertThat(claim.takePermission(dispatch)).isTrue();
    assertThat(claim.takePermission(dispatch)).isFalse();
    assertThat(f.repository.claimCommand(dispatch, f.invoice.ownerAccountId())).isNull();
  }

  @Test
  void exactRenewalReplayIsReadOnlyAndChangedEvidenceOrVersionCannotRearmIt() {
    var f = new RenewalFixture();
    f.renew();
    assertThat(f.renew().takeInitialCommandPermission()).isFalse();
    when(f.evidence.evidenceId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
    when(f.evidence.evidenceId()).thenReturn(f.evidenceId);
    assertThatThrownBy(
            () ->
                f.repository.renewAuthorization(
                    f.invoice.ownerAccountId(),
                    f.originalId,
                    f.authorizationId,
                    f.invoice.subscriptionId(),
                    3,
                    2,
                    f.invoice.digest(),
                    f.evidence))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void changedConsentOriginalIdentityOrWrongPurposeCannotRenew() {
    var f = new RenewalFixture();
    assertThatThrownBy(
            () ->
                f.repository.renewAuthorization(
                    f.invoice.ownerAccountId(),
                    UUID.randomUUID(),
                    f.authorizationId,
                    f.invoice.subscriptionId(),
                    3,
                    1,
                    f.invoice.digest(),
                    f.evidence))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                f.repository.renewAuthorization(
                    f.invoice.ownerAccountId(),
                    f.originalId,
                    f.authorizationId,
                    f.invoice.subscriptionId(),
                    3,
                    1,
                    "sha256:" + "c".repeat(64),
                    f.evidence))
        .isInstanceOf(IllegalStateException.class);
    when(f.evidence.purpose()).thenReturn(AccountRecentReauthenticationPolicy.PURPOSE);
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
    when(f.evidence.purpose()).thenReturn(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
    when(f.evidence.creationOperationId()).thenReturn(f.originalId);
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
    when(f.evidence.creationOperationId()).thenReturn(f.authorizationId);
    when(f.evidence.accountId()).thenReturn(UUID.randomUUID());
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
    assertThat(f.authorizations).isEmpty();
  }

  @Test
  void exactExpiryStaleRowAndLosingAuthorizationFenceRefuse() {
    var f = new RenewalFixture();
    when(f.evidence.issuedAt()).thenReturn(RenewalFixture.NOW.minusSeconds(115));
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
    when(f.evidence.issuedAt()).thenReturn(RenewalFixture.NOW);
    assertThatThrownBy(
            () ->
                f.repository.renewAuthorization(
                    f.invoice.ownerAccountId(),
                    f.originalId,
                    f.authorizationId,
                    f.invoice.subscriptionId(),
                    4,
                    1,
                    f.invoice.digest(),
                    f.evidence))
        .isInstanceOf(IllegalStateException.class);
    f.latestVersion = 2;
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
    assertThat(f.authorizations).isEmpty();
  }

  @Test
  void laterRenewalCannotRepeatAnAlreadyClaimedOrAmbiguousCommand() {
    var f = new RenewalFixture();
    f.commandClaimed = true;
    var dispatch = f.repository.readDispatch(f.renew(), f.invoice.ownerAccountId());
    assertThat(f.repository.claimCommand(dispatch, f.invoice.ownerAccountId())).isNull();
    f.latestVersion = 3;
    assertThatThrownBy(() -> f.repository.readDispatch(dispatch, f.invoice.ownerAccountId()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> f.repository.claimCommand(dispatch, f.invoice.ownerAccountId()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void authorizationAndOriginalInvoiceExactDeadlinesCannotBeExtendedByRenewal() {
    var f = new RenewalFixture();
    var prepared = f.renew();
    when(f.clock.instant()).thenReturn(RenewalFixture.NOW.plusSeconds(115));
    assertThatThrownBy(() -> f.repository.readDispatch(prepared, f.invoice.ownerAccountId()))
        .isInstanceOf(IllegalStateException.class);
    when(f.clock.instant()).thenReturn(f.invoice.expiresAt());
    when(f.evidence.issuedAt()).thenReturn(f.invoice.expiresAt());
    assertThatThrownBy(f::renew).isInstanceOf(IllegalStateException.class);
  }

  /** Controlled SQL results exercise the owner methods, not physical PostgreSQL constraints. */
  private static final class RenewalFixture {
    static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    final UUID originalId = UUID.randomUUID(),
        authorizationId = UUID.randomUUID(),
        evidenceId = UUID.randomUUID(),
        providerId = UUID.randomUUID(),
        lifecycleId = UUID.randomUUID();
    final DSLContext dsl = mock(DSLContext.class);
    final Clock clock = mock(Clock.class);
    final AccountSubscriptionIntentRepository intents =
        mock(AccountSubscriptionIntentRepository.class);
    final AccountRecentReauthenticationEvidence evidence =
        mock(AccountRecentReauthenticationEvidence.class);
    final Map<UUID, Record> authorizations = new HashMap<>();
    final AccountHostingFirstInvoicePayment invoice;
    final AccountHostingFirstInvoicePaymentRepository repository;
    long latestVersion = 1;
    boolean commandClaimed;

    RenewalFixture() {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      var policy =
          new AccountHostingBillingPolicyRepository.Policy("price_exact", 1, 1, 2400, "usd");
      var base = invoice("in_exact", "pi_exact", 2400, "usd");
      invoice =
          new AccountHostingFirstInvoicePayment(
              base.subscriptionId(),
              base.creationRequestId(),
              base.tenantId(),
              base.ownerAccountId(),
              base.paymentInstrumentId(),
              base.instrumentVersion(),
              base.providerSubscriptionId(),
              base.providerCustomerId(),
              base.providerPaymentMethodId(),
              base.invoiceId(),
              base.paymentIntentId(),
              base.amount(),
              base.currency(),
              base.quoteDigest(),
              base.providerPriceId(),
              base.providerQuantity(),
              base.billingPolicyVersion(),
              policy.digest(),
              false,
              base.capturedAt(),
              base.expiresAt());
      repository = spy(new AccountHostingFirstInvoicePaymentRepository(dsl, intents, clock));
      when(clock.instant()).thenReturn(NOW);
      doReturn(invoice)
          .when(repository)
          .requireOwner(invoice.subscriptionId(), invoice.ownerAccountId());
      doNothing().when(repository).requireAwaitingPayment(invoice, 3);
      doNothing().when(repository).requireAwaitingPayment(invoice, 4);
      var creation = mock(AccountSubscriptionCreationRecord.class);
      var quote = mock(AccountHostingPlanQuote.class);
      when(quote.providerPriceId()).thenReturn("price_exact");
      when(quote.providerQuantity()).thenReturn(1L);
      when(quote.digest()).thenReturn(invoice.quoteDigest());
      when(creation.planQuote()).thenReturn(quote);
      when(intents.findCreation(invoice.creationRequestId())).thenReturn(Optional.of(creation));
      when(evidence.accountId()).thenReturn(invoice.ownerAccountId());
      when(evidence.evidenceId()).thenReturn(evidenceId);
      when(evidence.purpose()).thenReturn(AccountRecentReauthenticationPolicy.BILLING_PURPOSE);
      when(evidence.creationOperationId()).thenReturn(authorizationId);
      when(evidence.billingContext())
          .thenReturn(
              new AccountRecentReauthenticationEvidence.BillingContext(
                  invoice.ownerAccountId(),
                  invoice.subscriptionId(),
                  originalId,
                  invoice.digest(),
                  3L,
                  invoice.expiresAt(),
                  authorizationId,
                  1L,
                  AccountRecentReauthenticationEvidence.BillingAuthorizationPurpose
                      .FOLLOWUP_RENEWAL));
      when(evidence.issuedAt()).thenReturn(NOW);
      when(evidence.recentAgeSeconds()).thenReturn(120L);
      when(evidence.maxClockSkewSeconds()).thenReturn(5L);
      Record original =
          row(
              Map.of(
                  "request_id",
                  originalId,
                  "subscription_id",
                  invoice.subscriptionId(),
                  "account_uuid",
                  invoice.ownerAccountId(),
                  "tuple_digest",
                  invoice.digest(),
                  "expected_row_version",
                  3L,
                  "provider_idempotency_id",
                  providerId,
                  "lifecycle_request_id",
                  lifecycleId));
      Record commercial =
          row(
              Map.of(
                  "provider_price_id",
                  "price_exact",
                  "policy_version",
                  1L,
                  "quantity",
                  1L,
                  "amount",
                  2400L,
                  "currency",
                  "usd"));
      when(dsl.fetchOne(anyString(), any(Object[].class)))
          .thenAnswer(
              call -> {
                String sql = call.getArgument(0);
                Object[] values = (Object[]) call.getRawArguments()[1];
                if (sql.contains("account_hosting_billing_policy_current")) return commercial;
                if (sql.contains("FROM subscription_first_invoice_confirmations")) return original;
                if (sql.contains("subscription_first_invoice_operation_fences")) return null;
                if (sql.contains("COALESCE(MAX")) return row(Map.of("version", latestVersion));
                if (sql.contains("FROM subscription_first_invoice_authorizations"))
                  return authorizations.get(values[0]);
                throw new AssertionError(sql);
              });
      Record transaction = row(Map.of("id", 100L));
      when(dsl.fetchOne("SELECT txid_current() AS id")).thenReturn(transaction);
      doAnswer(
              call -> {
                String sql = call.getArgument(0);
                Object[] values = (Object[]) call.getRawArguments()[1];
                if (sql.startsWith("INSERT INTO subscription_first_invoice_authorizations")) {
                  authorizations.put(
                      (UUID) values[0],
                      row(
                          Map.of(
                              "authorization_request_id",
                              values[0],
                              "request_id",
                              values[1],
                              "subscription_id",
                              values[2],
                              "authorization_version",
                              values[3],
                              "reauthentication_evidence_id",
                              values[4],
                              "consented_at",
                              values[5],
                              "valid_until",
                              values[6],
                              "created_transaction_id",
                              99L)));
                  latestVersion = (Long) values[3];
                  return 1;
                }
                if (sql.startsWith("INSERT INTO subscription_first_invoice_command_claims")) {
                  if (commandClaimed) return 0;
                  commandClaimed = true;
                  return 1;
                }
                throw new AssertionError(sql);
              })
          .when(dsl)
          .execute(anyString(), any(Object[].class));
    }

    AccountHostingFirstInvoicePaymentRepository.ConfirmationDispatch renew() {
      return repository.renewAuthorization(
          invoice.ownerAccountId(),
          originalId,
          authorizationId,
          invoice.subscriptionId(),
          3,
          1,
          invoice.digest(),
          evidence);
    }

    @SuppressWarnings("unchecked")
    private static Record row(Map<String, Object> values) {
      Record row = mock(Record.class);
      when(row.get(anyString(), any(Class.class)))
          .thenAnswer(call -> values.get(call.getArgument(0)));
      when(row.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
      return row;
    }
  }

  private static AccountHostingFirstInvoicePayment invoice(
      String invoice, String intent, long amount, String currency) {
    return invoice(
        invoice,
        intent,
        amount,
        currency,
        UUID.fromString("d4bc0143-4bf9-48ac-86a7-88f422146cfa"),
        "sha256:" + "b".repeat(64),
        Instant.parse("2026-11-07T23:00:00Z"));
  }

  private static AccountHostingFirstInvoicePayment invoice(
      String invoice,
      String intent,
      long amount,
      String currency,
      UUID instrument,
      String billingPolicyDigest,
      Instant expiresAt) {
    return invoice(
        invoice,
        intent,
        amount,
        currency,
        instrument,
        billingPolicyDigest,
        Instant.parse("2026-11-07T00:00:00Z"),
        expiresAt);
  }

  private static AccountHostingFirstInvoicePayment invoice(
      String invoice,
      String intent,
      long amount,
      String currency,
      UUID instrument,
      String billingPolicyDigest,
      Instant capturedAt,
      Instant expiresAt) {
    UUID id = UUID.fromString("d4bc0143-4bf9-48ac-86a7-88f422146cfa");
    return new AccountHostingFirstInvoicePayment(
        id,
        id,
        id,
        id,
        instrument,
        1,
        "sub_exact",
        "cus_exact",
        "pm_exact",
        invoice,
        intent,
        amount,
        currency,
        "sha256:" + "a".repeat(64),
        "price_exact",
        1,
        1,
        billingPolicyDigest,
        false,
        capturedAt,
        expiresAt);
  }
}
