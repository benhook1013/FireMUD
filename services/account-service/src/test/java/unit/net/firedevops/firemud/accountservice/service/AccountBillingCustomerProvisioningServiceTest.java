package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.model.Customer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.client.StripeBillingCustomerProvisioner;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.Observation;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRecord.State;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository.DispatchClaim;
import net.firedevops.firemud.accountservice.repository.AccountBillingCustomerRepository.DispatchClaimResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountBillingCustomerProvisioningServiceTest {
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID OPERATION = UUID.randomUUID();
  private static final UUID KEY = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private final AccountBillingCustomerRepository repository =
      mock(AccountBillingCustomerRepository.class);
  private final StripeBillingCustomerProvisioner provider =
      mock(StripeBillingCustomerProvisioner.class);
  private final LocalTransactions transactions = new LocalTransactions();
  private final AccountBillingCustomerProvisioningService service =
      new AccountBillingCustomerProvisioningService(repository, provider, transactions);

  @AfterEach
  void clearAmbientState() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void missingOrMismatchedOriginalIntentCannotReachProviderOrCreateIntent() {
    when(repository.findByAccountForUpdate(ACCOUNT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.provision(ACCOUNT, OPERATION))
        .hasMessageContaining("Existing durable");
    when(repository.findByAccountForUpdate(ACCOUNT)).thenReturn(Optional.of(intent(1)));
    assertThatThrownBy(() -> service.provision(ACCOUNT, UUID.randomUUID()))
        .hasMessageContaining("binding differs");
    verifyNoInteractions(provider);
    verify(repository, never()).retainIntent(any(), any(), any());
  }

  @Test
  void ambientTransactionAndSynchronizationRefuseBeforeAnyLocalOrProviderEffects() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.provision(ACCOUNT, OPERATION))
        .hasMessageContaining("ambient SQL");
    TransactionSynchronizationManager.setActualTransactionActive(false);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.provision(ACCOUNT, OPERATION))
        .hasMessageContaining("ambient SQL");
    verifyNoInteractions(repository, provider);
  }

  @Test
  void newClaimCommitsAndReadsBackBeforeProviderAndExistingClaimReconcilesOnly() {
    for (boolean inserted : new boolean[] {true, false}) {
      prepare(inserted);
      int commitsBefore = transactions.commits;
      when(provider.reconcile(any(), eq(inserted)))
          .thenAnswer(
              call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
                assertThat(transactions.commits).isEqualTo(commitsBefore + 2);
                return new StripeBillingCustomerProvisioner.Unresolved(null);
              });
      assertThat(service.provision(ACCOUNT, OPERATION))
          .isInstanceOf(AccountBillingCustomerProvisioningService.PendingCustomer.class);
    }
    verify(repository, never())
        .recordObservation(any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void priorAmbiguousObservationNeverGainsCreatePermissionFromNewClaim() {
    prepare(true);
    var reconciling =
        new AccountBillingCustomerRecord(
            ACCOUNT,
            OPERATION,
            KEY,
            2,
            State.RECONCILING,
            null,
            UUID.randomUUID(),
            Observation.AMBIGUOUS,
            "sha256:" + "b".repeat(64),
            NOW,
            NOW);
    doReturn(Optional.of(reconciling)).when(repository).findByAccountForUpdate(ACCOUNT);
    when(provider.reconcile(reconciling, false))
        .thenReturn(new StripeBillingCustomerProvisioner.Unresolved(null));
    assertThat(service.provision(ACCOUNT, OPERATION))
        .isInstanceOf(AccountBillingCustomerProvisioningService.PendingCustomer.class);
    verify(provider).reconcile(reconciling, false);
    verify(provider, never()).reconcile(any(), eq(true));
  }

  @Test
  void changedIntentAfterClaimReadbackStopsBeforeProvider() {
    prepare(true);
    var reconciling =
        new AccountBillingCustomerRecord(
            ACCOUNT,
            OPERATION,
            KEY,
            2,
            State.RECONCILING,
            null,
            UUID.randomUUID(),
            Observation.AMBIGUOUS,
            "sha256:" + "b".repeat(64),
            NOW,
            NOW);
    doReturn(Optional.of(intent(1)), Optional.of(reconciling))
        .when(repository)
        .findByAccountForUpdate(ACCOUNT);
    assertThatThrownBy(() -> service.provision(ACCOUNT, OPERATION))
        .isInstanceOf(AccountBillingCustomerRepository.StaleVersionException.class);
    verifyNoInteractions(provider);
  }

  @Test
  void changedCommittedClaimCannotReachProvider() {
    prepare(true);
    when(repository.findDispatchClaim(ACCOUNT))
        .thenReturn(
            Optional.of(new DispatchClaim(ACCOUNT, OPERATION, KEY, UUID.randomUUID(), NOW)));
    assertThatThrownBy(() -> service.provision(ACCOUNT, OPERATION))
        .hasMessageContaining("binding changed");
    verifyNoInteractions(provider);
  }

  @Test
  void independentlyReadProviderEvidenceAndCommittedHistoryAreRequiredForConfirmation()
      throws Exception {
    ConfirmationFixture fixture = confirmationFixture(false);
    var result =
        (AccountBillingCustomerProvisioningService.ConfirmedCustomer)
            fixture.service().provision(ACCOUNT, OPERATION);
    assertThat(result.customer().evidenceDigest()).isEqualTo(result.evidence().evidenceDigest());
    assertThat(result.customer().providerCustomerId()).isEqualTo("cus_exact");
    assertThat(transactions.commits).isEqualTo(4);
    verify(repository).findObservationByRequestId(result.customer().changeRequestId());
    assertThat(AccountBillingCustomerProvisioningService.ConfirmedCustomer.class.getConstructors())
        .isEmpty();
    verify(fixture.stripeCustomers(), never())
        .create(
            any(com.stripe.param.CustomerCreateParams.class),
            any(com.stripe.net.RequestOptions.class));
  }

  @Test
  void committedConfirmationReadbackRejectsChangedEvidence() throws Exception {
    ConfirmationFixture fixture = confirmationFixture(false);
    doAnswer(
            call -> {
              assertThat(transactions.commits).isEqualTo(3);
              AccountBillingCustomerRecord saved = fixture.receipt().get();
              return Optional.of(
                  new AccountBillingCustomerRecord(
                      ACCOUNT,
                      OPERATION,
                      KEY,
                      saved.rowVersion(),
                      State.CONFIRMED,
                      "cus_exact",
                      saved.changeRequestId(),
                      Observation.CONFIRMED,
                      "sha256:" + "f".repeat(64),
                      NOW,
                      NOW));
            })
        .when(repository)
        .findObservationByRequestId(any());
    assertThatThrownBy(() -> fixture.service().provision(ACCOUNT, OPERATION))
        .hasMessageContaining("readback mismatch");
    assertThat(transactions.commits).isEqualTo(3);
  }

  @Test
  void committedObservationMustAlsoMatchTheIndependentlyReadCurrentOwner() throws Exception {
    ConfirmationFixture fixture = confirmationFixture(false);
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            call -> {
              int read = reads.incrementAndGet();
              if (read < 4) {
                return Optional.of(intent(1));
              }
              assertThat(transactions.commits).isEqualTo(3);
              var saved = fixture.receipt().get();
              return Optional.of(
                  new AccountBillingCustomerRecord(
                      ACCOUNT,
                      OPERATION,
                      KEY,
                      2,
                      State.CONFIRMED,
                      "cus_wrong",
                      saved.changeRequestId(),
                      Observation.CONFIRMED,
                      saved.evidenceDigest(),
                      NOW,
                      NOW));
            })
        .when(repository)
        .findByAccountForUpdate(ACCOUNT);
    assertThatThrownBy(() -> fixture.service().provision(ACCOUNT, OPERATION))
        .hasMessageContaining("readback mismatch");
  }

  @Test
  void simultaneousConfirmationReplaysItsOwnOriginalIdentityAndDigestAfterReadback()
      throws Exception {
    ConfirmationFixture fixture = confirmationFixture(true);
    var original = fixture.receipt().get();
    var result =
        (AccountBillingCustomerProvisioningService.RetainedCustomer)
            fixture.service().provision(ACCOUNT, OPERATION);
    assertThat(result.customer()).isEqualTo(original);
    assertThat(transactions.commits).isEqualTo(4);
    verify(repository).findObservationByRequestId(original.changeRequestId());
    verify(repository, never())
        .recordObservation(any(), any(), anyLong(), any(), any(), any(), any());
  }

  private ConfirmationFixture confirmationFixture(boolean simultaneous) throws Exception {
    prepare(false);
    var receipt = new java.util.concurrent.atomic.AtomicReference<AccountBillingCustomerRecord>();
    if (simultaneous) {
      receipt.set(
          new AccountBillingCustomerRecord(
              ACCOUNT,
              OPERATION,
              KEY,
              2,
              State.CONFIRMED,
              "cus_exact",
              UUID.randomUUID(),
              Observation.CONFIRMED,
              "sha256:" + "a".repeat(64),
              NOW,
              NOW));
    }
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              int read = reads.incrementAndGet();
              return Optional.of(read <= 2 || receipt.get() == null ? intent(1) : receipt.get());
            })
        .when(repository)
        .findByAccountForUpdate(ACCOUNT);
    // Adapter is genuine; the Stripe transport is controlled. No public positive evidence factory.
    var stripe = mock(com.stripe.StripeClient.class);
    var stripeCustomers = mock(com.stripe.service.CustomerService.class);
    when(stripe.customers()).thenReturn(stripeCustomers);
    Customer customer = new Customer();
    customer.setObject("customer");
    customer.setId("cus_exact");
    customer.setLivemode(false);
    customer.setDeleted(false);
    customer.setMetadata(
        Map.of(
            "firemud_metadata_schema_version",
            "1",
            "firemud_operation_type",
            "customer_provisioning",
            "firemud_operation_id",
            OPERATION.toString(),
            "firemud_account_id",
            ACCOUNT.toString()));
    var search = new com.stripe.model.StripeSearchResult<Customer>();
    search.setHasMore(false);
    search.setData(java.util.List.of(customer));
    when(stripeCustomers.search(any(com.stripe.param.CustomerSearchParams.class)))
        .thenReturn(search);
    when(stripeCustomers.retrieve("cus_exact"))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return customer;
            });
    when(repository.recordObservation(
            eq(ACCOUNT),
            eq(OPERATION),
            eq(1L),
            any(),
            eq(Observation.CONFIRMED),
            eq("cus_exact"),
            anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(transactions.commits).isEqualTo(2);
              var saved =
                  new AccountBillingCustomerRecord(
                      ACCOUNT,
                      OPERATION,
                      KEY,
                      2,
                      State.CONFIRMED,
                      "cus_exact",
                      call.getArgument(3),
                      Observation.CONFIRMED,
                      call.getArgument(6),
                      NOW,
                      NOW);
              receipt.set(saved);
              return saved;
            });
    when(repository.findObservationByRequestId(any()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(transactions.commits).isEqualTo(3);
              assertThat(call.<UUID>getArgument(0)).isEqualTo(receipt.get().changeRequestId());
              return Optional.of(receipt.get());
            });
    var actual =
        new AccountBillingCustomerProvisioningService(
            repository,
            new StripeBillingCustomerProvisioner(stripe, Clock.fixed(NOW, ZoneOffset.UTC), false),
            transactions);
    return new ConfirmationFixture(actual, stripeCustomers, receipt);
  }

  private record ConfirmationFixture(
      AccountBillingCustomerProvisioningService service,
      com.stripe.service.CustomerService stripeCustomers,
      java.util.concurrent.atomic.AtomicReference<AccountBillingCustomerRecord> receipt) {}

  @Test
  void alreadyConfirmedReturnsHistoricalReceiptWithoutClaimOrProviderRead() {
    var confirmed =
        new AccountBillingCustomerRecord(
            ACCOUNT,
            OPERATION,
            KEY,
            2,
            State.CONFIRMED,
            "cus_exact",
            UUID.randomUUID(),
            Observation.CONFIRMED,
            "sha256:" + "a".repeat(64),
            NOW,
            NOW);
    when(repository.findByAccountForUpdate(ACCOUNT)).thenReturn(Optional.of(confirmed));
    var result =
        (AccountBillingCustomerProvisioningService.RetainedCustomer)
            service.provision(ACCOUNT, OPERATION);
    assertThat(result.customer()).isEqualTo(confirmed);
    verify(repository, never()).claimFirstProviderDispatch(any(), any());
    verifyNoInteractions(provider);
  }

  private void prepare(boolean inserted) {
    var claim = new DispatchClaim(ACCOUNT, OPERATION, KEY, UUID.randomUUID(), NOW);
    doAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              return Optional.of(intent(1));
            })
        .when(repository)
        .findByAccountForUpdate(ACCOUNT);
    when(repository.claimFirstProviderDispatch(any(), any()))
        .thenReturn(new DispatchClaimResult(claim, inserted));
    when(repository.findDispatchClaim(ACCOUNT)).thenReturn(Optional.of(claim));
  }

  private static AccountBillingCustomerRecord intent(long version) {
    return new AccountBillingCustomerRecord(
        ACCOUNT,
        OPERATION,
        KEY,
        version,
        State.UNCONFIRMED,
        null,
        OPERATION,
        Observation.NONE,
        null,
        NOW,
        NOW);
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
