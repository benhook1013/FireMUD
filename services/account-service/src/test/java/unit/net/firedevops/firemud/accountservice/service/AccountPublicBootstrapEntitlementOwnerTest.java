package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository;
import net.firedevops.firemud.accountservice.repository.AccountHostingSubscriptionCurrentReceipt;
import net.firedevops.firemud.accountservice.repository.AccountSubscriptionCurrentRecord;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository.SourceSelection;
import net.firedevops.firemud.accountservice.service.AccountHostingSubscriptionLifecycleService;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner;
import net.firedevops.firemud.accountservice.service.AccountPublicBootstrapEntitlementOwner.Evaluation;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Controlled owners exercise routing and final-transaction projection, not provider or SQL proof.
 */
class AccountPublicBootstrapEntitlementOwnerTest {
  private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID EVENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final String DIGEST = "sha256:" + "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void paidFactsKeepTheirActualObservationAndDeadlineWithoutReadingDemo() {
    Fixture f = new Fixture(SourceKind.PAID_HOSTING);
    var value = f.owner.evaluate(TENANT);
    assertThat(value.sourceKind()).isEqualTo(SourceKind.PAID_HOSTING);
    assertThat(value.gameplayAvailable()).isTrue();
    assertThat(value.allowPublicJoin()).isFalse();
    assertThat(value.entitlementVersion()).isEqualTo(3L);
    assertThat(value.evaluatedAt()).isEqualTo(NOW.minusSeconds(2));
    assertThat(value.validUntil()).isEqualTo(NOW.plusSeconds(4));
    verifyNoInteractions(f.demos);
    assertThat(Arrays.stream(Evaluation.class.getRecordComponents()).map(c -> c.getName()))
        .containsExactly(
            "canonicalTenantId",
            "sourceKind",
            "gameplayAvailable",
            "allowPublicJoin",
            "entitlementVersion",
            "tenantBillingSequence",
            "evaluatedAt",
            "validUntil");
  }

  @Test
  void demoRevalidationCannotRenewTheOriginalObservationDeadline() {
    Fixture f = new Fixture(SourceKind.NON_PAID_DEMO);
    when(f.demos.revalidate(f.demo))
        .thenAnswer(
            invocation -> {
              f.time.set(NOW.plusSeconds(7));
              return f.demo;
            });
    var value = f.owner.evaluate(TENANT);
    assertThat(value.sourceKind()).isEqualTo(SourceKind.NON_PAID_DEMO);
    assertThat(value.evaluatedAt()).isEqualTo(NOW);
    assertThat(value.validUntil()).isEqualTo(NOW.plusSeconds(15));
    verifyNoInteractions(f.hosting);
  }

  @Test
  void changedHeadOrUnavailableSelectionReturnsNoEvaluation() {
    Fixture f = new Fixture(SourceKind.PAID_HOSTING);
    Event changed = mock(Event.class);
    when(changed.tenantId()).thenReturn(TENANT);
    when(changed.eventId()).thenReturn(UUID.fromString("33333333-3333-4333-8333-333333333333"));
    when(f.sources.readCurrentSourceSelection(TENANT))
        .thenReturn(f.selection, new SourceSelection(SourceKind.PAID_HOSTING, changed));
    assertThatThrownBy(() -> f.owner.evaluate(TENANT))
        .isInstanceOf(AccountPublicBootstrapEntitlementOwner.UnavailableException.class);
    verifyNoInteractions(f.demos);
    when(f.sources.readCurrentSourceSelection(TENANT))
        .thenThrow(new IllegalStateException("unknown head"));
    assertThatThrownBy(() -> f.owner.evaluate(TENANT))
        .isInstanceOf(AccountPublicBootstrapEntitlementOwner.UnavailableException.class);
  }

  @Test
  void paidFailureNeverFallsBackToRetainedDemo() {
    Fixture f = new Fixture(SourceKind.PAID_HOSTING);
    doThrow(new IllegalStateException("provider unavailable"))
        .when(f.hosting)
        .readCurrentAndCapture(eq(TENANT), any());
    assertThatThrownBy(() -> f.owner.evaluate(TENANT))
        .isInstanceOf(AccountPublicBootstrapEntitlementOwner.UnavailableException.class);
    verifyNoInteractions(f.demos);
  }

  @Test
  void expiredDemoObservationAndFinalCommitFailureExposeNoValue() {
    Fixture f = new Fixture(SourceKind.NON_PAID_DEMO);
    when(f.demos.revalidate(f.demo))
        .thenAnswer(
            invocation -> {
              f.time.set(NOW.plusSeconds(15));
              return f.demo;
            });
    assertThatThrownBy(() -> f.owner.evaluate(TENANT))
        .isInstanceOf(AccountPublicBootstrapEntitlementOwner.UnavailableException.class);
    Fixture failedCommit = new Fixture(SourceKind.NON_PAID_DEMO);
    failedCommit.transactions.failFinalCommit = true;
    assertThatThrownBy(() -> failedCommit.owner.evaluate(TENANT))
        .isInstanceOf(AccountPublicBootstrapEntitlementOwner.UnavailableException.class);
    verifyNoInteractions(f.hosting, failedCommit.hosting);
  }

  @Test
  void sourceRefusalAndAmbientSqlFailWithoutUsingAnotherFamily() {
    Fixture f = new Fixture(SourceKind.NON_PAID_DEMO);
    when(f.demos.readCurrent(TENANT)).thenThrow(new IllegalStateException("stale demo"));
    assertThatThrownBy(() -> f.owner.evaluate(TENANT))
        .isInstanceOf(AccountPublicBootstrapEntitlementOwner.UnavailableException.class);
    verifyNoInteractions(f.hosting);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> f.owner.evaluate(TENANT)).hasMessageContaining("ambient SQL");
  }

  private static final class Fixture {
    private final AccountTenantAuthorityEventRepository sources =
        mock(AccountTenantAuthorityEventRepository.class);
    private final AccountHostingSubscriptionLifecycleService hosting =
        mock(AccountHostingSubscriptionLifecycleService.class);
    private final AccountDemoTenantEntitlementRepository demos =
        mock(AccountDemoTenantEntitlementRepository.class);
    private final DemoTenantEntitlementSnapshot demo = mock(DemoTenantEntitlementSnapshot.class);
    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final Transactions transactions = new Transactions();
    private final SourceSelection selection;
    private final AccountPublicBootstrapEntitlementOwner owner;

    private Fixture(SourceKind kind) {
      Event event = mock(Event.class);
      when(event.tenantId()).thenReturn(TENANT);
      when(event.eventId()).thenReturn(EVENT);
      when(event.eventDigest()).thenReturn(DIGEST);
      when(event.payload()).thenReturn(new byte[] {1, 2});
      when(event.tenantBillingSequence()).thenReturn(7L);
      when(event.tenantBillingEventId()).thenReturn(EVENT);
      when(event.tenantBillingEventDigest()).thenReturn(DIGEST);
      when(event.tenantAuthorityGeneration()).thenReturn(5L);
      when(event.tenantAuthoritySourceVersion()).thenReturn(5L);
      selection = new SourceSelection(kind, event);
      when(sources.readCurrentSourceSelection(TENANT)).thenReturn(selection);
      when(demo.canonicalTenantId()).thenReturn(TENANT);
      when(demo.tenantBillingSequence()).thenReturn(7L);
      when(demo.eventId()).thenReturn(EVENT);
      when(demo.eventDigest()).thenReturn(DIGEST);
      when(demo.tenantAuthorityEventId()).thenReturn(EVENT);
      when(demo.tenantAuthorityEventDigest()).thenReturn(DIGEST);
      when(demo.tenantAuthorityGeneration()).thenReturn(5L);
      when(demo.tenantAuthoritySourceVersion()).thenReturn(5L);
      when(demo.entitlementVersion()).thenReturn(3L);
      when(demo.gameplayAvailable()).thenReturn(true);
      when(demo.allowPublicJoin()).thenReturn(false);
      when(demos.readCurrent(TENANT)).thenReturn(demo);
      when(demos.revalidate(demo)).thenReturn(demo);
      AccountSubscriptionCurrentRecord current = mock(AccountSubscriptionCurrentRecord.class);
      when(current.tenantId()).thenReturn(TENANT);
      var receipt =
          new AccountHostingSubscriptionCurrentReceipt(
              current,
              EVENT,
              null,
              true,
              false,
              false,
              false,
              3L,
              7L,
              EVENT,
              DIGEST,
              event,
              "active",
              null,
              null,
              false,
              null,
              null,
              NOW.minusSeconds(2),
              NOW.plusSeconds(4));
      doAnswer(
              invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                Function<AccountHostingSubscriptionCurrentReceipt, Evaluation> capture =
                    invocation.getArgument(1);
                TransactionStatus status = transactions.getTransaction(definition());
                try {
                  Evaluation result = capture.apply(receipt);
                  transactions.commit(status);
                  return result;
                } catch (RuntimeException exception) {
                  transactions.rollback(status);
                  throw exception;
                }
              })
          .when(hosting)
          .readCurrentAndCapture(eq(TENANT), any());
      Clock clock = mock(Clock.class);
      when(clock.instant()).thenAnswer(invocation -> time.get());
      when(clock.getZone()).thenReturn(ZoneOffset.UTC);
      owner =
          new AccountPublicBootstrapEntitlementOwner(sources, hosting, demos, transactions, clock);
    }
  }

  private static TransactionDefinition definition() {
    var definition = new org.springframework.transaction.support.DefaultTransactionDefinition();
    definition.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    return definition;
  }

  private static final class Transactions implements PlatformTransactionManager {
    private int commits;
    private boolean failFinalCommit;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      TransactionDefinition actual =
          definition == null ? TransactionDefinition.withDefaults() : definition;
      assertThat(actual.getIsolationLevel())
          .isEqualTo(TransactionDefinition.ISOLATION_SERIALIZABLE);
      assertThat(actual.isReadOnly()).isFalse();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      if (++commits == 2 && failFinalCommit) throw new IllegalStateException("commit failed");
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
  }
}
