package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetCurrentReadinessProbeOwnerRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessProbeOwnerWorkloadGuard.AuthenticatedCaller;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ExpectedPod;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.OwnerProbeEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.SignerFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerSelector.LocalIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.PodTarget;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ReceiverUnavailableException;
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

  @Test
  void ownerBoundaryReturnsExactRetainedIssuedEvidenceThroughTheRealProbeServiceAndMapper() {
    OwnerFixture fixture = new OwnerFixture("firemud");

    var response = fixture.owner.getCurrentReadinessProbeOwner(fixture.request);

    assertThat(response.getOwnerRecordFound()).isTrue();
    assertThat(response.getCurrentCoordinates())
        .isEqualTo(fixture.request.getExpectedCoordinates());
    assertThat(response.getCompactTokenSha256()).isEqualTo("d".repeat(64));
    assertThat(response.getCurrentPodTarget().getPodUid())
        .isEqualTo(fixture.request.getProtectedLocalIdentity().getPodUid());
    assertThat(response.getCurrentPodTarget().getInventoryBindingRevision())
        .isEqualTo("inventory-r1");
    verify(fixture.repository)
        .readCurrentProbeOwner(
            fixture.binding.accountBinding(),
            fixture.trust,
            fixture.selector,
            fixture.inventory,
            "firemud");
  }

  @Test
  void ownerBoundaryRejectsProtectedBindingChangeAndMissingBindingAfterTheOwnerRead() {
    OwnerFixture fixture = new OwnerFixture("firemud");
    when(fixture.materializerBinding.current())
        .thenReturn(Optional.of(protectedBinding("trust-r2")));
    assertThatThrownBy(() -> fixture.owner.getCurrentReadinessProbeOwner(fixture.request))
        .isInstanceOf(ReceiverUnavailableException.class);

    when(fixture.materializerBinding.current()).thenReturn(Optional.empty());
    assertThatThrownBy(() -> fixture.owner.getCurrentReadinessProbeOwner(fixture.request))
        .isInstanceOf(ReceiverUnavailableException.class);
  }

  @Test
  void ownerBoundaryRejectsCrossNamespaceTargetEvenWithAnIssuedOwnerRow() {
    OwnerFixture fixture = new OwnerFixture("other");

    assertThatThrownBy(() -> fixture.owner.getCurrentReadinessProbeOwner(fixture.request))
        .isInstanceOf(ReceiverUnavailableException.class);
  }

  /**
   * Retained immutable owner records are controlled inputs, not physical PostgreSQL evidence.
   * Authentication and Kubernetes reads are fixtures; owner/probe services and wire mapping are
   * real.
   */
  private static final class OwnerFixture {
    private final GetCurrentReadinessProbeOwnerRequest request =
        AccountJwtReadinessProbeOwnerProtoMapperTest.request().build();
    private final AccountJwtReadinessProbeOwnerSelector selector =
        new AccountJwtReadinessProbeOwnerProtoMapper().parse(request);
    private final AccountJwtSignerMaterializerTrustBinding.Binding binding =
        protectedBinding("trust-r1");
    private final TrustFence trust =
        new TrustFence(
            binding.expectedClusterIncarnationUid(),
            binding.expectedNamespaceUid(),
            binding.bindingDigest(),
            binding.configRevision());
    private final AccountJwtSignerMaterializerTrustBinding materializerBinding =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    private final AccountJwtReadinessProbeRepository repository =
        mock(AccountJwtReadinessProbeRepository.class);
    private final InventorySnapshot inventory = mock(InventorySnapshot.class);
    private final AccountJwtReadinessProbeOwnerService owner;

    private OwnerFixture(String targetNamespace) {
      var entry =
          new ProbeEntry(
              selector.rotationOperationId(),
              selector.planDigest(),
              selector.validatorId(),
              selector.tokenProfile(),
              selector.audience(),
              selector.probeKind(),
              selector.jti(),
              selector.targetGeneration(),
              selector.targetKid(),
              selector.expectedActive(),
              selector.registryVersion(),
              selector.entryVersion(),
              selector.issuedAtEpochSecond(),
              selector.expiresAtEpochSecond(),
              ProbeState.ISSUED,
              Optional.empty(),
              Optional.of(selector.issuedAtEpochSecond()),
              Optional.of(selector.compactTokenSha256()),
              Optional.empty());
      var plan =
          new ReadinessProbePlan(
              selector.rotationOperationId(),
              binding.accountBinding(),
              trust,
              selector.operationDigest(),
              "1".repeat(64),
              "2".repeat(64),
              2,
              selector.targetGeneration(),
              selector.targetKid(),
              "3".repeat(64),
              new SignerFence(Optional.empty(), Optional.empty()),
              "4".repeat(64),
              "5".repeat(64),
              "6".repeat(64),
              "{}",
              selector.localIdentity().applicabilityMatrixDigest(),
              true,
              300,
              selector.issuedAtEpochSecond() - 300,
              selector.issuedAtEpochSecond(),
              selector.planExpiresAtEpochSecond(),
              selector.planVersion(),
              selector.planDigest(),
              List.of(entry),
              Optional.of("7".repeat(64)));
      var local = selector.localIdentity();
      var target =
          new PodTarget(
              "7".repeat(64),
              "prod",
              "cluster-a",
              binding.expectedClusterIncarnationUid(),
              targetNamespace,
              binding.expectedNamespaceUid(),
              "api-r1",
              "8".repeat(64),
              "inventory-r1",
              "9".repeat(64),
              selector.validatorId(),
              local.deploymentUid(),
              local.podUid(),
              local.podIp(),
              local.image(),
              local.verifierConfigSha256(),
              local.applicabilityMatrixDigest(),
              selector.expectedOutcome(),
              Optional.of(URI.create(local.directPodEndpoint())),
              Optional.of(local.canonicalServiceUri()),
              Optional.of(local.serverLeafSpkiSha256()));
      var expectedPod =
          new ExpectedPod(
              selector.rotationOperationId(),
              selector.planDigest(),
              selector.validatorId(),
              selector.tokenProfile(),
              selector.audience(),
              selector.probeKind(),
              selector.jti(),
              target);
      var evidence = new OwnerProbeEvidence(plan, entry, expectedPod);
      var context =
          new ObservationContext(
              ObservationPurpose.STRICT_READY,
              selector.rotationOperationId(),
              selector.operationDigest());
      var inventorySource = mock(AccountJwtValidatorInventorySource.class);
      when(inventory.namespace()).thenReturn("firemud");
      when(repository.readCurrentInventoryObservationContext(
              binding.accountBinding(), trust, selector.rotationOperationId()))
          .thenReturn(context);
      when(inventorySource.observe(context)).thenReturn(inventory);
      when(repository.readCurrentProbeOwner(
              binding.accountBinding(), trust, selector, inventory, "firemud"))
          .thenReturn(evidence);
      var probe =
          new AccountJwtReadinessProbeService(
              repository,
              new TransactionTemplate(new RecordingTransactionManager(new ArrayList<>())),
              Clock.fixed(
                  Instant.ofEpochSecond(selector.issuedAtEpochSecond() + 1), ZoneOffset.UTC),
              inventorySource);
      var guard = mock(AccountJwtReadinessProbeOwnerWorkloadGuard.class);
      var caller = mock(AuthenticatedCaller.class);
      when(caller.accountBinding()).thenReturn(binding);
      when(guard.requireGameSessionOwnerReadCaller()).thenReturn(caller);
      when(materializerBinding.current()).thenReturn(Optional.of(binding));
      owner =
          new AccountJwtReadinessProbeOwnerService(
              probe, materializerBinding, guard, new AccountJwtReadinessProbeOwnerProtoMapper());
    }
  }

  private static AccountJwtSignerMaterializerTrustBinding.Binding protectedBinding(
      String revision) {
    String clusterUid = "11111111-1111-4111-8111-111111111111";
    String namespaceUid = "22222222-2222-4222-8222-222222222222";
    String peer = "spiffe://firemud/ns/firemud/sa/jwt-signer-materializer";
    var pins = List.of("a".repeat(64));
    String digest =
        AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
            revision, "prod", "cluster-a", "firemud", clusterUid, namespaceUid, peer, pins);
    return new AccountJwtSignerMaterializerTrustBinding.Binding(
        "prod", "cluster-a", "firemud", clusterUid, namespaceUid, peer, pins, revision, digest);
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
