package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.stripe.exception.StripeException;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource.RequestMethod;
import com.stripe.net.BaseAddress;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.client.StripeBillingPrerequisiteObserver;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRecord;
import net.firedevops.firemud.accountservice.repository.AccountPaymentInstrumentRepository;
import net.firedevops.firemud.accountservice.service.AccountBillingPrerequisiteConfirmationService.ConfirmationRequest;
import net.firedevops.firemud.accountservice.service.AccountBillingPrerequisiteConfirmationService.ConfirmedConfirmation;
import net.firedevops.firemud.accountservice.service.AccountBillingPrerequisiteConfirmationService.ReplayedConfirmation;
import net.firedevops.firemud.accountservice.service.AccountBillingPrerequisiteConfirmationService.Result;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual PostgreSQL owner composition plus controlled responses deserialized by StripeClient. */
@Testcontainers(disabledWithoutDocker = true)
class AccountBillingPrerequisiteConfirmationPostgresIntegrationTest {
  private static final String PROVIDER_CUSTOMER_ID = "cus_prerequisite";
  private static final String PROVIDER_PAYMENT_METHOD_ID = "pm_prerequisite";
  private static final String EVIDENCE_DIGEST = "sha256:" + "a".repeat(64);
  private static final Instant OBSERVED_AT = Instant.parse("2026-11-01T00:00:00Z");
  private static final Duration MAX_OBSERVATION_AGE = Duration.ofSeconds(10);

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void actualSdkReadsConfirmExactCustomerAndExplicitInstrumentThroughRealRepositories() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    ControlledStripeTransport transport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));

    AccountBillingPrerequisiteConfirmationService.ConfirmedConfirmation confirmed =
        (AccountBillingPrerequisiteConfirmationService.ConfirmedConfirmation)
            service(context, transport).confirm(request);

    assertThat(transport.callCount()).isEqualTo(2);
    assertThat(transport.methods()).containsExactly(RequestMethod.GET, RequestMethod.GET);
    assertThat(transport.paths())
        .containsExactly(
            "/v1/customers/" + PROVIDER_CUSTOMER_ID,
            "/v1/payment_methods/" + PROVIDER_PAYMENT_METHOD_ID);
    assertThat(confirmed.customer().state())
        .isEqualTo(AccountBillingCustomerRecord.State.CONFIRMED);
    assertThat(confirmed.customer().accountUuid()).isEqualTo(context.account());
    assertThat(confirmed.customer().customerProvisioningRequestId())
        .isEqualTo(intent.customerProvisioningRequestId());
    assertThat(confirmed.customer().providerCustomerId()).isEqualTo(PROVIDER_CUSTOMER_ID);
    assertThat(confirmed.instrument().accountUuid()).isEqualTo(context.account());
    assertThat(confirmed.instrument().instrumentUuid()).isEqualTo(request.instrumentUuid());
    assertThat(confirmed.instrument().customerProvisioningRequestId())
        .isEqualTo(intent.customerProvisioningRequestId());
    assertThat(confirmed.instrument().providerCustomerId()).isEqualTo(PROVIDER_CUSTOMER_ID);
    assertThat(confirmed.instrument().providerPaymentMethodId())
        .isEqualTo(PROVIDER_PAYMENT_METHOD_ID);
    assertThat(confirmed.instrument().display().brand()).isEqualTo("visa");
    assertThat(confirmed.instrument().display().lastFour()).isEqualTo("4242");
    assertThat(confirmed.instrument().display().expiryMonth()).isEqualTo(12);
    assertThat(confirmed.instrument().display().expiryYear()).isEqualTo(2030);
    assertThat(confirmed.evidenceDigest()).matches("sha256:[0-9a-f]{64}");
    assertThat(confirmed.readStartedAt()).isEqualTo(OBSERVED_AT);
    assertThat(confirmed.observedAt()).isEqualTo(OBSERVED_AT);
    assertThat(context.customers().readHistory(context.account()))
        .extracting(AccountBillingCustomerRecord::observation)
        .containsExactly(Observation.NONE, Observation.CONFIRMED);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .containsExactly(confirmed.instrument());
  }

  @Test
  void concurrentExactConfirmationsWithChangedDisplayReplayTheWinningReceipts() throws Exception {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    CountDownLatch paymentMethodReadsReady = new CountDownLatch(2);
    CountDownLatch releasePaymentMethodReads = new CountDownLatch(1);
    Runnable waitForBothPaymentMethodReads =
        blockAtPaymentMethodRead(paymentMethodReadsReady, releasePaymentMethodReads);
    ControlledStripeTransport firstTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID)
                .replace("4242", "1111"),
            waitForBothPaymentMethodReads);
    ControlledStripeTransport secondTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID)
                .replace("4242", "8888"),
            waitForBothPaymentMethodReads);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<Result> first =
          executor.submit(() -> service(context, firstTransport).confirm(request));
      Future<Result> second =
          executor.submit(() -> service(context, secondTransport).confirm(request));

      assertThat(paymentMethodReadsReady.await(10, TimeUnit.SECONDS))
          .as("both requests pass preflight and finish the customer read before either card read")
          .isTrue();
      releasePaymentMethodReads.countDown();

      Result firstResult = first.get(10, TimeUnit.SECONDS);
      Result secondResult = second.get(10, TimeUnit.SECONDS);
      assertThat(firstResult instanceof ConfirmedConfirmation)
          .isNotEqualTo(secondResult instanceof ConfirmedConfirmation);
      assertThat(firstResult instanceof ReplayedConfirmation)
          .isNotEqualTo(secondResult instanceof ReplayedConfirmation);

      ConfirmedConfirmation confirmed =
          (ConfirmedConfirmation)
              (firstResult instanceof ConfirmedConfirmation ? firstResult : secondResult);
      ReplayedConfirmation replayed =
          (ReplayedConfirmation)
              (firstResult instanceof ReplayedConfirmation ? firstResult : secondResult);

      assertThat(replayed.customerReceipt()).isEqualTo(confirmed.customer());
      assertThat(replayed.instrumentReceipt()).isEqualTo(confirmed.instrument());
      assertThat(replayed.evidenceDigest()).isEqualTo(confirmed.evidenceDigest());
      assertThat(confirmed.customer().evidenceDigest()).isEqualTo(confirmed.evidenceDigest());
      assertThat(confirmed.instrument().evidenceDigest()).isEqualTo(confirmed.evidenceDigest());
      assertThat(confirmed.instrument().display().lastFour()).isIn("1111", "8888");

      assertThat(firstTransport.callCount()).isEqualTo(2);
      assertThat(secondTransport.callCount()).isEqualTo(2);
      assertThat(context.customers().readHistory(context.account()))
          .containsExactly(intent, confirmed.customer());
      assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
          .containsExactly(confirmed.instrument());
      assertThat(context.instruments().findByOwner(context.account(), request.instrumentUuid()))
          .contains(confirmed.instrument());
    } finally {
      releasePaymentMethodReads.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
          .as("concurrent confirmation workers finish during cleanup")
          .isTrue();
    }
  }

  @Test
  void staleProviderObservationAtCommitDoesNotWriteConfirmationEvidence() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    ControlledClock ownerClock = new ControlledClock(OBSERVED_AT);
    ControlledStripeTransport transport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID),
            () -> ownerClock.set(OBSERVED_AT.plus(MAX_OBSERVATION_AGE).plusNanos(1)));
    var service =
        service(
            context,
            transport,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC),
            ownerClock,
            MAX_OBSERVATION_AGE);

    assertThatThrownBy(() -> service.confirm(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Provider observation is stale or from the future");

    assertThat(transport.callCount()).isEqualTo(2);
    assertNoConfirmationWrites(context, request, intent);
  }

  @Test
  void futureProviderObservationAtCommitDoesNotWriteConfirmationEvidence() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    ControlledClock providerClock = new ControlledClock(OBSERVED_AT);
    ControlledStripeTransport transport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID),
            () -> providerClock.set(OBSERVED_AT.plusNanos(1)));
    var service =
        service(
            context,
            transport,
            providerClock,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC),
            MAX_OBSERVATION_AGE);

    assertThatThrownBy(() -> service.confirm(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Provider observation is stale or from the future");

    assertThat(transport.callCount()).isEqualTo(2);
    assertNoConfirmationWrites(context, request, intent);
  }

  @Test
  void concurrentHistoricalReplayReturnsOriginalReceiptsAfterObservationExpires() throws Exception {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    Clock providerClock = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
    ControlledClock ownerClock = new ControlledClock(OBSERVED_AT);
    CountDownLatch paymentMethodReadsReady = new CountDownLatch(2);
    CountDownLatch releaseFirstPaymentMethodRead = new CountDownLatch(1);
    CountDownLatch releaseSecondPaymentMethodRead = new CountDownLatch(1);
    ControlledStripeTransport firstTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID),
            blockAtPaymentMethodRead(paymentMethodReadsReady, releaseFirstPaymentMethodRead));
    ControlledStripeTransport secondTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID),
            blockAtPaymentMethodRead(paymentMethodReadsReady, releaseSecondPaymentMethodRead));
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<Result> first =
          executor.submit(
              () ->
                  service(context, firstTransport, providerClock, ownerClock, MAX_OBSERVATION_AGE)
                      .confirm(request));
      Future<Result> second =
          executor.submit(
              () ->
                  service(context, secondTransport, providerClock, ownerClock, MAX_OBSERVATION_AGE)
                      .confirm(request));

      assertThat(paymentMethodReadsReady.await(10, TimeUnit.SECONDS))
          .as("both exact retries pass preflight before either provider read completes")
          .isTrue();
      releaseFirstPaymentMethodRead.countDown();
      ConfirmedConfirmation confirmed = (ConfirmedConfirmation) first.get(10, TimeUnit.SECONDS);
      List<AccountBillingCustomerRecord> customerHistory =
          context.customers().readHistory(context.account());
      List<AccountPaymentInstrumentRecord> instrumentHistory =
          context.instruments().readHistory(context.account(), request.instrumentUuid());

      ownerClock.set(OBSERVED_AT.plus(MAX_OBSERVATION_AGE).plusNanos(1));
      releaseSecondPaymentMethodRead.countDown();
      ReplayedConfirmation replayed = (ReplayedConfirmation) second.get(10, TimeUnit.SECONDS);

      assertThat(replayed.customerReceipt()).isEqualTo(confirmed.customer());
      assertThat(replayed.instrumentReceipt()).isEqualTo(confirmed.instrument());
      assertThat(replayed.instrumentReceipt().display())
          .isEqualTo(confirmed.instrument().display());
      assertThat(replayed.evidenceDigest()).isEqualTo(confirmed.evidenceDigest());
      assertThat(context.customers().readHistory(context.account()))
          .containsExactlyElementsOf(customerHistory);
      assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
          .containsExactlyElementsOf(instrumentHistory);
      assertThat(firstTransport.callCount()).isEqualTo(2);
      assertThat(secondTransport.callCount()).isEqualTo(2);
    } finally {
      releaseFirstPaymentMethodRead.countDown();
      releaseSecondPaymentMethodRead.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
          .as("concurrent confirmation workers finish during cleanup")
          .isTrue();
    }
  }

  @Test
  void exactProviderObservationReplayRetainsOriginalCustomerAndInstrumentReceipts() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    AccountBillingCustomerRecord advancedIntent =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        context
                            .customers()
                            .recordObservation(
                                context.account(),
                                intent.customerProvisioningRequestId(),
                                intent.rowVersion(),
                                UUID.randomUUID(),
                                Observation.AMBIGUOUS,
                                null,
                                EVIDENCE_DIGEST)));
    ConfirmationRequest request = request(context, advancedIntent);
    var firstTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), advancedIntent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));
    var first =
        (AccountBillingPrerequisiteConfirmationService.ConfirmedConfirmation)
            service(context, firstTransport).confirm(request);
    assertThat(first.customer().rowVersion()).isEqualTo(advancedIntent.rowVersion() + 1);
    AccountPaymentInstrumentRecord advancedInstrument =
        Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        context
                            .instruments()
                            .recordAvailability(
                                new AccountPaymentInstrumentRepository.AvailabilityObservation(
                                    UUID.randomUUID(),
                                    request.instrumentUuid(),
                                    context.account(),
                                    first.instrument().rowVersion(),
                                    AccountPaymentInstrumentRecord.State.UNAVAILABLE,
                                    first.instrument().display(),
                                    "sha256:" + "b".repeat(64)))));
    assertThat(advancedInstrument.rowVersion()).isEqualTo(2);
    assertThat(advancedInstrument.state())
        .isEqualTo(AccountPaymentInstrumentRecord.State.UNAVAILABLE);
    List<AccountBillingCustomerRecord> customerHistoryBeforeReplay =
        context.customers().readHistory(context.account());

    var changedProviderTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), advancedIntent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID)
                .replace("4242", "8888"));

    var changedProviderReplay =
        (AccountBillingPrerequisiteConfirmationService.ReplayedConfirmation)
            service(context, changedProviderTransport).confirm(request);

    assertThat(changedProviderTransport.callCount()).isZero();
    assertThat(changedProviderReplay.disposition())
        .isEqualTo(
            AccountBillingPrerequisiteConfirmationService.ReplayDisposition
                .EXACT_RETAINED_ORIGINAL);
    assertThat(changedProviderReplay.customerReceipt()).isEqualTo(first.customer());
    assertThat(changedProviderReplay.instrumentReceipt()).isEqualTo(first.instrument());
    assertThat(changedProviderReplay.evidenceDigest()).isEqualTo(first.evidenceDigest());
    assertThat(changedProviderReplay.instrumentReceipt().state())
        .isEqualTo(AccountPaymentInstrumentRecord.State.ATTACHABLE);
    assertThat(context.instruments().findByOwner(context.account(), request.instrumentUuid()))
        .contains(advancedInstrument);
    assertThat(context.customers().readHistory(context.account()))
        .containsExactlyElementsOf(customerHistoryBeforeReplay);

    StripeException providerUnavailable = mock(StripeException.class);
    ControlledStripeTransport unavailableTransport =
        ControlledStripeTransport.failOn(
            "/v1/customers/" + PROVIDER_CUSTOMER_ID,
            providerUnavailable,
            customerResponse(context.account(), advancedIntent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));
    var unavailableReplay =
        (AccountBillingPrerequisiteConfirmationService.ReplayedConfirmation)
            service(context, unavailableTransport).confirm(request);

    assertThat(unavailableTransport.callCount()).isZero();
    assertThat(unavailableReplay.customerReceipt()).isEqualTo(first.customer());
    assertThat(unavailableReplay.instrumentReceipt()).isEqualTo(first.instrument());
    assertThat(context.instruments().findByOwner(context.account(), request.instrumentUuid()))
        .contains(advancedInstrument);
    assertThat(context.customers().readHistory(context.account()))
        .containsExactlyElementsOf(customerHistoryBeforeReplay);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .containsExactly(first.instrument(), advancedInstrument);
  }

  @Test
  void providerReadErrorStaysUnresolvedAndDoesNotConfirmOrCreateInstrument() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    StripeException providerFailure = mock(StripeException.class);
    ControlledStripeTransport transport =
        ControlledStripeTransport.failOn(
            "/v1/customers/" + PROVIDER_CUSTOMER_ID,
            providerFailure,
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));

    var result = service(context, transport).confirm(request);

    var unresolved = (AccountBillingPrerequisiteConfirmationService.UnresolvedConfirmation) result;
    assertThat(unresolved.observation().providerFailure()).isSameAs(providerFailure);
    assertThat(unresolved.observation().retryable()).isTrue();
    assertThat(transport.callCount()).isEqualTo(1);
    assertThat(context.customers().readHistory(context.account())).containsExactly(intent);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .isEmpty();
  }

  @Test
  void changedCustomerVersionDuringRemoteReadsFailsCommitAndLeavesInstrumentUncreated() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    UUID concurrentObservationId = UUID.randomUUID();
    Runnable advanceCurrentVersion =
        () ->
            context
                .transaction()
                .execute(
                    status ->
                        context
                            .customers()
                            .recordObservation(
                                context.account(),
                                intent.customerProvisioningRequestId(),
                                intent.rowVersion(),
                                concurrentObservationId,
                                Observation.AMBIGUOUS,
                                null,
                                EVIDENCE_DIGEST));
    ControlledStripeTransport transport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID),
            advanceCurrentVersion);

    assertThatThrownBy(() -> service(context, transport).confirm(request))
        .isInstanceOf(AccountBillingPrerequisiteConfirmationService.ConflictException.class);

    assertThat(transport.callCount()).isEqualTo(2);
    AccountBillingCustomerRecord current =
        context.customers().findByAccount(context.account()).orElseThrow();
    assertThat(current.rowVersion()).isEqualTo(intent.rowVersion() + 1);
    assertThat(current.observation()).isEqualTo(Observation.AMBIGUOUS);
    assertThat(context.instruments().findByOwner(context.account(), request.instrumentUuid()))
        .isEmpty();
    assertThat(context.customers().readHistory(context.account()))
        .extracting(AccountBillingCustomerRecord::observation)
        .containsExactly(Observation.NONE, Observation.AMBIGUOUS);
  }

  @Test
  void incompleteOriginalCustomerReceiptCannotBeOptimisticallyReplayed() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    context
        .transaction()
        .execute(
            status ->
                context
                    .customers()
                    .recordObservation(
                        context.account(),
                        intent.customerProvisioningRequestId(),
                        intent.rowVersion(),
                        request.customerObservationRequestId(),
                        Observation.CONFIRMED,
                        PROVIDER_CUSTOMER_ID,
                        EVIDENCE_DIGEST));
    ControlledStripeTransport transport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));

    assertThatThrownBy(() -> service(context, transport).confirm(request))
        .isInstanceOf(AccountBillingPrerequisiteConfirmationService.ConflictException.class);

    assertThat(transport.callCount()).isZero();
    assertThat(context.customers().readHistory(context.account())).hasSize(2);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .isEmpty();
  }

  @Test
  void rolledBackCustomerAndAttachmentWritesDoNotCreateAReplayReceipt() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest request = request(context, intent);
    context
        .transaction()
        .executeWithoutResult(
            status -> {
              context
                  .customers()
                  .recordObservation(
                      context.account(),
                      intent.customerProvisioningRequestId(),
                      intent.rowVersion(),
                      request.customerObservationRequestId(),
                      Observation.CONFIRMED,
                      PROVIDER_CUSTOMER_ID,
                      EVIDENCE_DIGEST);
              context
                  .instruments()
                  .retainAttachment(
                      new AccountPaymentInstrumentRepository.Attachment(
                          request.attachmentRequestId(),
                          request.instrumentUuid(),
                          request.accountUuid(),
                          request.customerProvisioningRequestId(),
                          PROVIDER_CUSTOMER_ID,
                          PROVIDER_PAYMENT_METHOD_ID,
                          AccountPaymentInstrumentRecord.State.ATTACHABLE,
                          new AccountPaymentInstrumentRecord.DisplayMetadata(
                              "visa", "4242", 12, 2030),
                          EVIDENCE_DIGEST));
              status.setRollbackOnly();
            });

    assertThat(context.customers().findByAccount(context.account())).contains(intent);
    assertThat(context.customers().readHistory(context.account())).containsExactly(intent);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .isEmpty();

    StripeException providerFailure = mock(StripeException.class);
    ControlledStripeTransport transport =
        ControlledStripeTransport.failOn(
            "/v1/customers/" + PROVIDER_CUSTOMER_ID,
            providerFailure,
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));
    var result = service(context, transport).confirm(request);

    assertThat(result)
        .isInstanceOf(AccountBillingPrerequisiteConfirmationService.UnresolvedConfirmation.class);
    assertThat(transport.callCount()).isEqualTo(1);
    assertThat(context.customers().readHistory(context.account())).containsExactly(intent);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .isEmpty();
  }

  @Test
  void changedReplayTupleFieldsConflictBeforeAnyProviderRead() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest original = request(context, intent);
    var originalTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));
    service(context, originalTransport).confirm(original);
    var originalCustomerHistory = context.customers().readHistory(context.account());
    var originalInstrumentHistory =
        context.instruments().readHistory(context.account(), original.instrumentUuid());

    List<ConfirmationRequest> changedRequests =
        List.of(
            copyRequest(
                original,
                UUID.randomUUID(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                UUID.randomUUID(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                UUID.randomUUID(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion() + 1,
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                UUID.randomUUID(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                "cus_changed",
                original.instrumentUuid(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                UUID.randomUUID(),
                original.attachmentRequestId(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                UUID.randomUUID(),
                original.providerPaymentMethodIdHint()),
            copyRequest(
                original,
                original.accountUuid(),
                original.customerProvisioningRequestId(),
                original.providerIdempotencyId(),
                original.expectedCustomerVersion(),
                original.customerObservationRequestId(),
                original.providerCustomerIdHint(),
                original.instrumentUuid(),
                original.attachmentRequestId(),
                "pm_changed"));

    for (ConfirmationRequest changed : changedRequests) {
      ControlledStripeTransport transport =
          new ControlledStripeTransport(
              customerResponse(context.account(), intent.customerProvisioningRequestId()),
              paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));
      assertThatThrownBy(() -> service(context, transport).confirm(changed))
          .isInstanceOf(AccountBillingPrerequisiteConfirmationService.ConflictException.class);
      assertThat(transport.callCount()).isZero();
      assertThat(context.customers().readHistory(context.account()))
          .isEqualTo(originalCustomerHistory);
      assertThat(context.instruments().readHistory(context.account(), original.instrumentUuid()))
          .isEqualTo(originalInstrumentHistory);
    }
  }

  @Test
  void expectedCustomerVersionCannotOverflowOriginalReceiptVersionCheck() {
    assertThatThrownBy(
            () ->
                new ConfirmationRequest(
                    UUID.fromString("8a0f13b5-2189-4085-9aa9-bebd30e1d001"),
                    UUID.fromString("8a0f13b5-2189-4085-9aa9-bebd30e1d002"),
                    UUID.fromString("8a0f13b5-2189-4085-9aa9-bebd30e1d003"),
                    Long.MAX_VALUE,
                    UUID.fromString("8a0f13b5-2189-4085-9aa9-bebd30e1d004"),
                    PROVIDER_CUSTOMER_ID,
                    UUID.fromString("8a0f13b5-2189-4085-9aa9-bebd30e1d005"),
                    UUID.fromString("8a0f13b5-2189-4085-9aa9-bebd30e1d006"),
                    PROVIDER_PAYMENT_METHOD_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void changedOriginalCustomerOperationFailsBeforeAnyProviderRead() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest original = request(context, intent);
    ConfirmationRequest changedOperation =
        new ConfirmationRequest(
            original.accountUuid(),
            UUID.randomUUID(),
            original.providerIdempotencyId(),
            original.expectedCustomerVersion(),
            original.customerObservationRequestId(),
            original.providerCustomerIdHint(),
            original.instrumentUuid(),
            original.attachmentRequestId(),
            original.providerPaymentMethodIdHint());
    ControlledStripeTransport transport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));

    assertThatThrownBy(() -> service(context, transport).confirm(changedOperation))
        .isInstanceOf(AccountBillingPrerequisiteConfirmationService.ConflictException.class);

    assertThat(transport.callCount()).isZero();
    assertThat(context.customers().readHistory(context.account())).containsExactly(intent);
    assertThat(context.instruments().readHistory(context.account(), original.instrumentUuid()))
        .isEmpty();
  }

  @Test
  void providerObservationIdentityConflictCannotOverwritePriorConfirmationOrInstrument() {
    Context context = context();
    AccountBillingCustomerRecord intent = intent(context);
    ConfirmationRequest original = request(context, intent);
    var initialTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse(PROVIDER_PAYMENT_METHOD_ID, PROVIDER_CUSTOMER_ID));
    var initial =
        (AccountBillingPrerequisiteConfirmationService.ConfirmedConfirmation)
            service(context, initialTransport).confirm(original);
    ConfirmationRequest changedPaymentMethod =
        new ConfirmationRequest(
            original.accountUuid(),
            original.customerProvisioningRequestId(),
            original.providerIdempotencyId(),
            original.expectedCustomerVersion(),
            original.customerObservationRequestId(),
            original.providerCustomerIdHint(),
            original.instrumentUuid(),
            original.attachmentRequestId(),
            "pm_changed");
    ControlledStripeTransport changedTransport =
        new ControlledStripeTransport(
            customerResponse(context.account(), intent.customerProvisioningRequestId()),
            paymentMethodResponse("pm_changed", PROVIDER_CUSTOMER_ID));

    assertThatThrownBy(() -> service(context, changedTransport).confirm(changedPaymentMethod))
        .isInstanceOf(AccountBillingPrerequisiteConfirmationService.ConflictException.class);

    assertThat(changedTransport.callCount()).isZero();
    assertThat(context.customers().findByAccount(context.account())).contains(initial.customer());
    assertThat(context.customers().readHistory(context.account())).hasSize(2);
    assertThat(context.instruments().findByOwner(context.account(), original.instrumentUuid()))
        .contains(initial.instrument());
    assertThat(context.instruments().readHistory(context.account(), original.instrumentUuid()))
        .containsExactly(initial.instrument());
  }

  private static AccountBillingPrerequisiteConfirmationService service(
      Context context, ControlledStripeTransport transport) {
    Clock clock = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
    return service(context, transport, clock, clock, MAX_OBSERVATION_AGE);
  }

  private static AccountBillingPrerequisiteConfirmationService service(
      Context context,
      ControlledStripeTransport transport,
      Clock providerClock,
      Clock ownerClock,
      Duration maximumObservationAge) {
    var observer =
        new StripeBillingPrerequisiteObserver(
            new com.stripe.StripeClient(transport), providerClock, false);
    return new AccountBillingPrerequisiteConfirmationService(
        observer,
        context.customers(),
        context.instruments(),
        new DataSourceTransactionManager(context.source()),
        ownerClock,
        maximumObservationAge);
  }

  private static Runnable blockAtPaymentMethodRead(
      CountDownLatch paymentMethodReadsReady, CountDownLatch releasePaymentMethodRead) {
    return () -> {
      paymentMethodReadsReady.countDown();
      try {
        if (!releasePaymentMethodRead.await(10, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting to release Stripe payment-method read");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting to release Stripe read", interrupted);
      }
    };
  }

  private static void assertNoConfirmationWrites(
      Context context, ConfirmationRequest request, AccountBillingCustomerRecord intent) {
    assertThat(context.customers().readHistory(context.account())).containsExactly(intent);
    assertThat(context.instruments().readHistory(context.account(), request.instrumentUuid()))
        .isEmpty();
    assertThat(context.instruments().findByOwner(context.account(), request.instrumentUuid()))
        .isEmpty();
  }

  private static ConfirmationRequest request(Context context, AccountBillingCustomerRecord intent) {
    return new ConfirmationRequest(
        context.account(),
        intent.customerProvisioningRequestId(),
        intent.providerIdempotencyId(),
        intent.rowVersion(),
        UUID.randomUUID(),
        PROVIDER_CUSTOMER_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        PROVIDER_PAYMENT_METHOD_ID);
  }

  private static ConfirmationRequest copyRequest(
      ConfirmationRequest original,
      UUID accountUuid,
      UUID customerProvisioningRequestId,
      UUID providerIdempotencyId,
      long expectedCustomerVersion,
      UUID customerObservationRequestId,
      String providerCustomerIdHint,
      UUID instrumentUuid,
      UUID attachmentRequestId,
      String providerPaymentMethodIdHint) {
    return new ConfirmationRequest(
        accountUuid,
        customerProvisioningRequestId,
        providerIdempotencyId,
        expectedCustomerVersion,
        customerObservationRequestId,
        providerCustomerIdHint,
        instrumentUuid,
        attachmentRequestId,
        providerPaymentMethodIdHint);
  }

  private static AccountBillingCustomerRecord intent(Context context) {
    return Objects.requireNonNull(
            context
                .transaction()
                .execute(
                    status ->
                        context
                            .customers()
                            .retainIntent(context.account(), UUID.randomUUID(), UUID.randomUUID())))
        .customer();
  }

  @SuppressFBWarnings(
      value = "VA_FORMAT_STRING_USES_NEWLINE",
      justification =
          "This is a JSON test fixture whose text block intentionally retains LF characters independent of the host console line separator.")
  private static String customerResponse(UUID accountUuid, UUID customerOperationId) {
    return """
        {"id":"%s","object":"customer","deleted":false,"livemode":false,
         "metadata":{"firemud_metadata_schema_version":"1",
          "firemud_operation_type":"customer_provisioning",
          "firemud_operation_id":"%s","firemud_account_id":"%s"}}
        """
        .formatted(PROVIDER_CUSTOMER_ID, customerOperationId, accountUuid);
  }

  @SuppressFBWarnings(
      value = "VA_FORMAT_STRING_USES_NEWLINE",
      justification =
          "This is a JSON test fixture whose text block intentionally retains LF characters independent of the host console line separator.")
  private static String paymentMethodResponse(String methodId, String customerId) {
    return """
        {"id":"%s","object":"payment_method","type":"card","livemode":false,
         "customer":"%s","card":{"brand":"visa","last4":"4242",
          "exp_month":12,"exp_year":2030}}
        """
        .formatted(methodId, customerId);
  }

  private static FluentConfiguration configuration(Context context) {
    return Flyway.configure()
        .dataSource(context.source())
        .schemas(context.schema())
        .defaultSchema(context.schema())
        .placeholders(Map.of("serviceSchema", context.schema()))
        .locations("classpath:db/migration", "classpath:db/migration/saga");
  }

  private static Context context() {
    String schema = "billing_confirm_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    source.setSchema(schema);
    DSLContext setup = DSL.using(source, SQLDialect.POSTGRES);
    DSLContext dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(source));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(10);
    Context empty =
        new Context(
            source,
            schema,
            setup,
            transaction,
            new AccountBillingCustomerRepository(dsl),
            new AccountPaymentInstrumentRepository(dsl),
            null);
    configuration(empty).target("1027").load().migrate();
    UUID account =
        Objects.requireNonNull(
                setup.fetchOne(
                    "INSERT INTO accounts(username,email,password_hash) VALUES ('billing','billing@example.test','hash') RETURNING account_uuid"))
            .get(0, UUID.class);
    Context withAccount =
        new Context(
            source, schema, setup, transaction, empty.customers(), empty.instruments(), account);
    configuration(withAccount).target("1027.1").load().migrate();
    return withAccount;
  }

  private record Context(
      DriverManagerDataSource source,
      String schema,
      DSLContext setup,
      TransactionTemplate transaction,
      AccountBillingCustomerRepository customers,
      AccountPaymentInstrumentRepository instruments,
      UUID account) {}

  private static final class ControlledClock extends Clock {
    private final AtomicReference<Instant> current;
    private final ZoneId zone;

    private ControlledClock(Instant initial) {
      this(new AtomicReference<>(initial), ZoneOffset.UTC);
    }

    private ControlledClock(AtomicReference<Instant> current, ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    private void set(Instant instant) {
      current.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new ControlledClock(current, zone);
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }

  private static final class ControlledStripeTransport implements StripeResponseGetter {
    private final Map<String, String> responses;
    private final Runnable beforePaymentMethodResponse;
    private final String failurePath;
    private final StripeException failure;
    private int callCount;
    private final java.util.ArrayList<RequestMethod> methods = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> paths = new java.util.ArrayList<>();

    private ControlledStripeTransport(String customerResponse, String paymentMethodResponse) {
      this(customerResponse, paymentMethodResponse, null, null, null);
    }

    private ControlledStripeTransport(
        String customerResponse,
        String paymentMethodResponse,
        Runnable beforePaymentMethodResponse) {
      this(customerResponse, paymentMethodResponse, beforePaymentMethodResponse, null, null);
    }

    private ControlledStripeTransport(
        String customerResponse,
        String paymentMethodResponse,
        Runnable beforePaymentMethodResponse,
        String failurePath,
        StripeException failure) {
      this.responses =
          Map.of(
              "/v1/customers/" + PROVIDER_CUSTOMER_ID, customerResponse,
              "/v1/payment_methods/" + providerMethodId(paymentMethodResponse),
                  paymentMethodResponse);
      this.beforePaymentMethodResponse = beforePaymentMethodResponse;
      this.failurePath = failurePath;
      this.failure = failure;
    }

    private static ControlledStripeTransport failOn(
        String failurePath,
        StripeException failure,
        String customerResponse,
        String paymentMethodResponse) {
      return new ControlledStripeTransport(
          customerResponse, paymentMethodResponse, null, failurePath, failure);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends StripeObject> T request(
        BaseAddress baseAddress,
        RequestMethod requestMethod,
        String requestPath,
        Map<String, Object> params,
        Type type,
        RequestOptions options,
        ApiMode apiMode)
        throws StripeException {
      callCount++;
      methods.add(requestMethod);
      paths.add(requestPath);
      if (beforePaymentMethodResponse != null && requestPath.startsWith("/v1/payment_methods/")) {
        beforePaymentMethodResponse.run();
      }
      if (requestPath.equals(failurePath)) {
        throw failure;
      }
      String response = responses.get(requestPath);
      if (response == null) {
        throw new AssertionError("Unexpected Stripe SDK path: " + requestPath);
      }
      return (T) com.stripe.net.ApiResource.GSON.fromJson(response, type);
    }

    @Override
    public InputStream requestStream(
        BaseAddress baseAddress,
        RequestMethod requestMethod,
        String requestPath,
        Map<String, Object> params,
        RequestOptions options,
        ApiMode apiMode)
        throws StripeException {
      throw new AssertionError("Billing prerequisite reads must not stream");
    }

    private int callCount() {
      return callCount;
    }

    private List<RequestMethod> methods() {
      return List.copyOf(methods);
    }

    private List<String> paths() {
      return List.copyOf(paths);
    }

    private static String providerMethodId(String response) {
      String marker = "\"id\":\"";
      int start = response.indexOf(marker) + marker.length();
      int end = response.indexOf('"', start);
      return response.substring(start, end);
    }
  }
}
