package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerSelector.LocalIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationPurpose;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AccountJwtReadinessProbeOwnerServiceTest {
  private static final Binding BINDING =
      new Binding(
          "prod", "cluster-a", "firemud", CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final TrustFence TRUST =
      new TrustFence(
          "11111111-1111-4111-8111-111111111111",
          "22222222-2222-4222-8222-222222222222",
          "a".repeat(64),
          "trust-r1");

  @Test
  void selectsOwnerContextThenObservesInventoryBeforeOpeningTheOwnerReadTransaction() {
    List<String> order = new ArrayList<>();
    AccountJwtReadinessProbeRepository repository = mock(AccountJwtReadinessProbeRepository.class);
    AccountJwtValidatorInventorySource inventorySource =
        mock(AccountJwtValidatorInventorySource.class);
    InventorySnapshot snapshot = mock(InventorySnapshot.class);
    when(snapshot.namespace()).thenReturn(BINDING.namespace());
    AccountJwtReadinessProbeOwnerSelector selector = selector();
    ObservationContext context =
        new ObservationContext(
            ObservationPurpose.STRICT_READY, selector.rotationOperationId(), "9".repeat(64));
    when(repository.readCurrentInventoryObservationContext(
            eq(BINDING), eq(TRUST), eq(selector.rotationOperationId())))
        .thenAnswer(
            ignored -> {
              order.add("context");
              return context;
            });
    when(inventorySource.observe(context))
        .thenAnswer(
            ignored -> {
              assertThat(order).containsExactly("transaction", "context", "commit");
              order.add("inventory");
              return snapshot;
            });
    OwnerProbeEvidence evidence = mock(OwnerProbeEvidence.class);
    when(repository.readCurrentProbeOwner(
            eq(BINDING), eq(TRUST), eq(selector), eq(snapshot), eq(BINDING.namespace())))
        .thenAnswer(
            ignored -> {
              order.add("repository");
              assertThat(order)
                  .containsExactly(
                      "transaction", "context", "commit", "inventory", "transaction", "repository");
              return evidence;
            });
    TransactionTemplate transaction =
        new TransactionTemplate(new RecordingTransactionManager(order));
    AccountJwtReadinessProbeService service =
        new AccountJwtReadinessProbeService(
            repository, transaction, Clock.systemUTC(), inventorySource);

    assertThat(service.readCurrentProbeOwner(BINDING, TRUST, selector, BINDING.namespace()))
        .isSameAs(evidence);
    assertThat(order)
        .containsExactly(
            "transaction", "context", "commit", "inventory", "transaction", "repository", "commit");
    verify(repository)
        .readCurrentInventoryObservationContext(BINDING, TRUST, selector.rotationOperationId());
    verify(inventorySource).observe(context);
    verify(repository)
        .readCurrentProbeOwner(BINDING, TRUST, selector, snapshot, BINDING.namespace());
  }

  private static AccountJwtReadinessProbeOwnerSelector selector() {
    return new AccountJwtReadinessProbeOwnerSelector(
        UUID.fromString("33333333-3333-4333-8333-333333333333"),
        "b".repeat(64),
        "c".repeat(64),
        2,
        1,
        "game-session-service",
        "game-session-account-delegation",
        "account-service",
        ProbeKind.REPRESENTATIVE,
        ProbeExpectation.ACCEPT,
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        2,
        "1",
        "pending-key",
        Optional.empty(),
        20_100,
        20_300,
        20_300,
        "d".repeat(64),
        new LocalIdentity(
            "game-session-service",
            "55555555-5555-4555-8555-555555555555",
            "66666666-6666-4666-8666-666666666666",
            "10.0.0.1",
            URI.create("grpcs://10.0.0.1:9443").toString(),
            "spiffe://firemud/ns/firemud/sa/game-session-jwt-validator",
            "registry.example/validator@sha256:" + "e".repeat(64),
            "f".repeat(64),
            "a".repeat(64),
            "gs-r1",
            "9".repeat(64),
            "8".repeat(64)));
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final List<String> order;

    private RecordingTransactionManager(List<String> order) {
      this.order = order;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      order.add("transaction");
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      order.add("commit");
    }

    @Override
    public void rollback(TransactionStatus status) {
      throw new AssertionError("Unexpected transaction rollback");
    }
  }
}
