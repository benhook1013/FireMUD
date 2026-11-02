package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository.Attachment;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.HistoricalAttachment;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.ObservedAttachment;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.PendingObservation;
import net.firedevops.firemud.accountservice.service.AccountPaymentInstrumentAttachmentService.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled SDK transport and mocked SQL owners; does not establish physical durability. */
class AccountPaymentInstrumentAttachmentServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID CUSTOMER_OPERATION = UUID.randomUUID();
  private static final UUID CUSTOMER_KEY = UUID.randomUUID();
  private final AccountBillingCustomerRepository customers =
      mock(AccountBillingCustomerRepository.class);
  private final AccountPaymentInstrumentRepository instruments =
      mock(AccountPaymentInstrumentRepository.class);
  private final LocalTransactions transactions = new LocalTransactions();
  private final MutableClock clock = new MutableClock();
  private final Transport transport = new Transport();
  private final Request request =
      new Request(UUID.randomUUID(), UUID.randomUUID(), ACCOUNT, "pm_saved");
  private final AccountBillingCustomerRecord customer =
      new AccountBillingCustomerRecord(
          ACCOUNT,
          CUSTOMER_OPERATION,
          CUSTOMER_KEY,
          2,
          AccountBillingCustomerRecord.State.CONFIRMED,
          "cus_owner",
          UUID.randomUUID(),
          AccountBillingCustomerRecord.Observation.CONFIRMED,
          "sha256:" + "a".repeat(64),
          NOW,
          NOW);
  private AccountPaymentInstrumentRecord retained;
  private AccountPaymentInstrumentRecord current;

  @AfterEach
  void clearAmbient() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  private AccountPaymentInstrumentAttachmentService service() {
    return new AccountPaymentInstrumentAttachmentService(
        customers,
        instruments,
        new StripeBillingPrerequisiteObserver(new com.stripe.StripeClient(transport), clock, false),
        transactions,
        clock,
        Duration.ofSeconds(10));
  }

  private void source() {
    when(customers.findByAccountForUpdate(ACCOUNT)).thenReturn(Optional.of(customer));
    when(instruments.findByOwnerForUpdate(ACCOUNT, request.instrumentUuid()))
        .thenAnswer(call -> Optional.ofNullable(current));
    when(instruments.findAttachmentByRequestId(request.requestId()))
        .thenAnswer(call -> Optional.ofNullable(retained));
    when(instruments.retainAttachment(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(transactions.commits).isEqualTo(1);
              Attachment a = call.getArgument(0);
              retained =
                  new AccountPaymentInstrumentRecord(
                      a.instrumentUuid(),
                      a.accountUuid(),
                      a.customerProvisioningRequestId(),
                      a.providerCustomerId(),
                      a.providerPaymentMethodId(),
                      a.requestId(),
                      1,
                      a.state(),
                      a.display(),
                      a.requestId(),
                      a.evidenceDigest(),
                      NOW,
                      NOW);
              current = retained;
              return retained;
            });
  }

  @Test
  void actualSdkFactsAreRetainedBetweenSeparateTransactionsAndReadBackAfterCommit() {
    source();
    var result = (ObservedAttachment) service().retainSavedMethod(request);
    assertThat(transport.paths)
        .containsExactly("/v1/customers/cus_owner", "/v1/payment_methods/pm_saved");
    assertThat(result.receipt()).isEqualTo(retained);
    assertThat(result.current()).isEqualTo(current);
    assertThat(result.evidence().display()).isEqualTo(retained.display());
    assertThat(result.evidence().evidenceDigest()).isEqualTo(retained.evidenceDigest());
    assertThat(result.evidence().accountUuid()).isEqualTo(ACCOUNT);
    assertThat(retained.state()).isEqualTo(State.ATTACHABLE);
    assertThat(transactions.commits).isEqualTo(3);
    assertThat(ObservedAttachment.class.getConstructors()).isEmpty();
    verify(instruments, org.mockito.Mockito.times(3))
        .findAttachmentByRequestId(request.requestId());
  }

  @Test
  void historicalRetryPreservesOriginalReceiptAndLaterUnavailableVersionWithoutSdkReads() {
    source();
    service().retainSavedMethod(request);
    var original = retained;
    current =
        new AccountPaymentInstrumentRecord(
            retained.instrumentUuid(),
            ACCOUNT,
            CUSTOMER_OPERATION,
            "cus_owner",
            "pm_saved",
            request.requestId(),
            2,
            State.UNAVAILABLE,
            retained.display(),
            UUID.randomUUID(),
            "sha256:" + "b".repeat(64),
            NOW,
            NOW.plusSeconds(1));
    transport.paths.clear();
    var result = (HistoricalAttachment) service().retainSavedMethod(request);
    assertThat(result.receipt()).isEqualTo(original);
    assertThat(result.current().rowVersion()).isEqualTo(2);
    assertThat(result.current().state()).isEqualTo(State.UNAVAILABLE);
    assertThat(transport.paths).isEmpty();
    verify(instruments).retainAttachment(any());
  }

  @Test
  void receiptCommittedDuringSdkReadReturnsHistoricalOriginalAndLaterAvailability() {
    source();
    var first = (ObservedAttachment) service().retainSavedMethod(request);
    var original = first.receipt();
    var later =
        new AccountPaymentInstrumentRecord(
            original.instrumentUuid(),
            ACCOUNT,
            CUSTOMER_OPERATION,
            "cus_owner",
            "pm_saved",
            request.requestId(),
            2,
            State.UNAVAILABLE,
            original.display(),
            UUID.randomUUID(),
            "sha256:" + "b".repeat(64),
            NOW,
            NOW.plusSeconds(1));
    retained = null;
    current = null;
    transport.paths.clear();
    transport.lastFour = "4444";
    when(customers.findByAccountForUpdate(ACCOUNT))
        .thenAnswer(
            call -> {
              if (transport.paths.size() == 2) {
                // Another invocation committed its original receipt and a later availability
                // version
                // after this invocation's preparation but before final owner-lock acquisition.
                retained = original;
                current = later;
              }
              return Optional.of(customer);
            });
    var replay = (HistoricalAttachment) service().retainSavedMethod(request);
    assertThat(replay.receipt()).isEqualTo(original);
    assertThat(replay.receipt().display().lastFour()).isEqualTo("4242");
    assertThat(replay.current()).isEqualTo(later);
    assertThat(transport.paths).hasSize(2);
    verify(instruments).retainAttachment(any());
  }

  @Test
  void changedRequestSelectorCannotReplayOrRetrieveAnotherMethod() {
    source();
    service().retainSavedMethod(request);
    transport.paths.clear();
    var changed = new Request(request.requestId(), request.instrumentUuid(), ACCOUNT, "pm_other");
    assertThatThrownBy(() -> service().retainSavedMethod(changed))
        .hasMessageContaining("changed binding");
    assertThat(transport.paths).isEmpty();
  }

  @Test
  void wrongProviderCustomerAndFailedSdkReadNeverRetainAndNeverMeanAbsence() {
    source();
    transport.methodCustomer = "cus_other";
    var invalid = (PendingObservation) service().retainSavedMethod(request);
    assertThat(invalid.observation())
        .isInstanceOf(StripeBillingPrerequisiteObserver.InvalidObservation.class);
    transport.methodCustomer = "cus_owner";
    transport.failure = mock(StripeException.class);
    var unresolved = (PendingObservation) service().retainSavedMethod(request);
    assertThat(unresolved.observation())
        .isInstanceOf(StripeBillingPrerequisiteObserver.UnresolvedObservation.class);
    assertThat(
            ((StripeBillingPrerequisiteObserver.UnresolvedObservation) unresolved.observation())
                .providerFailure())
        .isSameAs(transport.failure);
    verify(instruments, never()).retainAttachment(any());
  }

  @Test
  void slowObservationAndPostObservationLockDelayFailFreshnessBeforeRetention() {
    source();
    transport.methodDelay = Duration.ofSeconds(11);
    assertThatThrownBy(() -> service().retainSavedMethod(request)).hasMessageContaining("stale");
    verify(instruments, never()).retainAttachment(any());
  }

  @Test
  void lockWaitAfterSdkObservationAlsoCountsAgainstFreshness() {
    source();
    when(customers.findByAccountForUpdate(ACCOUNT))
        .thenAnswer(
            call -> {
              if (!transport.paths.isEmpty()) {
                clock.now = NOW.plusSeconds(11);
              }
              return Optional.of(customer);
            });
    assertThatThrownBy(() -> service().retainSavedMethod(request)).hasMessageContaining("stale");
    verify(instruments, never()).retainAttachment(any());
  }

  @Test
  void customerSourceChangeCannotReturnPositiveEvidence() {
    source();
    when(customers.findByAccountForUpdate(ACCOUNT))
        .thenReturn(Optional.of(customer), Optional.empty());
    assertThatThrownBy(() -> service().retainSavedMethod(request))
        .hasMessageContaining("confirmed Account customer");
    verify(instruments, never()).retainAttachment(any());
  }

  @Test
  void independentlyReadReceiptMustExistAfterWriteCommit() {
    source();
    when(instruments.findAttachmentByRequestId(request.requestId())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service().retainSavedMethod(request))
        .hasMessageContaining("receipt disappeared");
    assertThat(transactions.commits).isEqualTo(2);
    assertThat(retained).isNotNull();
  }

  @Test
  void ambientTransactionsRefuseBeforeAnyOwnerOrSdkAccess() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service().retainSavedMethod(request))
        .hasMessageContaining("ambient SQL");
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service().retainSavedMethod(request))
        .hasMessageContaining("ambient SQL");
    verifyNoInteractions(customers, instruments);
    assertThat(transport.paths).isEmpty();
  }

  private final class Transport implements StripeResponseGetter {
    private final List<String> paths = new ArrayList<>();
    private String methodCustomer = "cus_owner";
    private String lastFour = "4242";
    private StripeException failure;
    private Duration methodDelay = Duration.ZERO;

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode apiMode)
        throws StripeException {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
      assertThat(method).isEqualTo(RequestMethod.GET);
      assertThat(params == null || params.isEmpty()).isTrue();
      paths.add(path);
      if (failure != null) {
        throw failure;
      }
      String response;
      if (path.equals("/v1/customers/cus_owner")) {
        response =
            ("{\"id\":\"cus_owner\",\"object\":\"customer\",\"deleted\":false,\"livemode\":false,%n"
                    + " \"metadata\":{\"firemud_metadata_schema_version\":\"1\",%n"
                    + "  \"firemud_operation_type\":\"customer_provisioning\",%n"
                    + "  \"firemud_operation_id\":\"%s\",\"firemud_account_id\":\"%s\"}}%n")
                .formatted(CUSTOMER_OPERATION, ACCOUNT);
      } else if (path.equals("/v1/payment_methods/pm_saved")) {
        clock.now = clock.now.plus(methodDelay);
        response =
            ("{\"id\":\"pm_saved\",\"object\":\"payment_method\",\"livemode\":false,\"type\":\"card\",%n"
                    + " \"customer\":\"%s\",\"card\":{\"brand\":\"visa\",\"last4\":\"%s\",\"exp_month\":12,\"exp_year\":2030}}%n")
                .formatted(methodCustomer, lastFour);
      } else {
        throw new AssertionError("Unexpected SDK request " + path);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress address,
        RequestMethod method,
        String path,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode apiMode) {
      throw new AssertionError("No stream or mutation allowed");
    }
  }

  private static final class MutableClock extends Clock {
    private Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    private int commits;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
