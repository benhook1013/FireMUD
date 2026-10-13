package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.account.v1.IssueReadinessProbesRequest;
import net.firedevops.firemud.account.v1.ValidateReadinessProbeRequest;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtReadinessTrustBinding.PeerIdentity;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.DeliveryClaim;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.SignerFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.VerificationReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.security.AccountJwtReadinessTlsInterceptor;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AccountJwtReadinessTransportOwnerTest {
  private static final Instant NOW = Instant.parse("2030-06-01T00:10:00Z");
  private static final UUID OPERATION_ID = UUID.randomUUID();
  private static final UUID JTI = UUID.randomUUID();
  private static final String PLAN_DIGEST = "a".repeat(64);
  private static final String TOKEN_HASH = "c".repeat(64);
  private static final String COMPACT_JWT = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.signature";
  private static final String VALIDATOR_URI =
      "spiffe://firemud/ns/firemud-prod/sa/account-jwt-readiness-harness";

  @Test
  void unknownOrMissingSchemaIsRejectedBeforeAnyOwnerOrRepositoryAccess() {
    Fixture fixture = new Fixture(false);
    IssueReadinessProbesRequest unknown =
        IssueReadinessProbesRequest.newBuilder()
            .setSchemaVersion(1)
            .mergeUnknownFields(unknownField())
            .build();
    IssueReadinessProbesRequest missing = IssueReadinessProbesRequest.getDefaultInstance();
    ValidateReadinessProbeRequest unknownValidation =
        ValidateReadinessProbeRequest.newBuilder()
            .setSchemaVersion(1)
            .mergeUnknownFields(unknownField())
            .build();
    ValidateReadinessProbeRequest missingValidation =
        ValidateReadinessProbeRequest.getDefaultInstance();

    assertThatThrownBy(() -> fixture.owner.issue(unknown, fixture.caller))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    assertThatThrownBy(() -> fixture.owner.issue(missing, fixture.caller))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    assertThatThrownBy(() -> fixture.owner.validate(unknownValidation, fixture.caller))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    assertThatThrownBy(() -> fixture.owner.validate(missingValidation, fixture.caller))
        .isInstanceOf(AccountJwtReadinessValidationService.InvalidReadinessRequestException.class);
    verifyNoInteractions(fixture.repository, fixture.producer, fixture.validation);
  }

  @Test
  void claimCommitsBeforeSigningAndResponseContainsOnlyReadBackIssuedProbes() {
    Fixture fixture = new Fixture(false);
    fixture.stubCurrentBindings();
    ReadinessProbePlan plan = fixture.plan();
    ProbeEntry planned = fixture.entry(ProbeState.PLANNED, 1, Optional.empty());
    ProbeEntry issued = fixture.entry(ProbeState.ISSUED, 3, Optional.of(TOKEN_HASH));
    when(plan.entries()).thenReturn(List.of(planned));
    when(fixture.producer.planCurrent(fixture.accountBinding, fixture.trustFence)).thenReturn(plan);
    DeliveryClaim claim = mock(DeliveryClaim.class);
    when(fixture.repository.claimSingleDelivery(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(plan),
            eq(fixture.readinessBinding),
            eq(fixture.peer),
            any(Instant.class)))
        .thenReturn(claim);

    var metadata =
        new AccountJwtReadinessProbeService.DeliveryMetadata(
            OPERATION_ID,
            PLAN_DIGEST,
            1,
            "account-service",
            "account-jwt-readiness-canary",
            "firemud-account-jwt-readiness",
            ProbeKind.CANARY,
            JTI,
            "2",
            "pending-key-2",
            Optional.empty(),
            Optional.empty(),
            1,
            3,
            20_300,
            20_600,
            TOKEN_HASH);
    doAnswer(
            invocation -> {
              assertThat(fixture.transactions.committed).isTrue();
              AccountJwtReadinessProbeService.OwnerInternalProbeDelivery delivery =
                  invocation.getArgument(4);
              delivery.deliver(metadata, COMPACT_JWT.getBytes(StandardCharsets.US_ASCII));
              return List.of(issued);
            })
        .when(fixture.producer)
        .issueCurrentPlan(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(OPERATION_ID),
            eq(claim),
            any(AccountJwtReadinessProbeService.OwnerInternalProbeDelivery.class));

    var response = fixture.owner.issue(validIssueRequest(), fixture.caller);

    assertThat(response.getSchemaVersion()).isEqualTo(1);
    assertThat(response.getRotationOperationId()).isEqualTo(OPERATION_ID.toString());
    assertThat(response.getPlanDigest()).isEqualTo(PLAN_DIGEST);
    assertThat(response.getValidatorInventoryComplete()).isFalse();
    assertThat(response.getProbesCount()).isEqualTo(1);
    assertThat(response.getProbes(0).getJti()).isEqualTo(JTI.toString());
    assertThat(response.getProbes(0).getCompactTokenSha256()).isEqualTo(TOKEN_HASH);
    assertThat(response.getProbes(0).getCompactJwt()).isEqualTo(COMPACT_JWT);
    verify(fixture.repository)
        .claimSingleDelivery(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(plan),
            eq(fixture.readinessBinding),
            eq(fixture.peer),
            any(Instant.class));
    verify(fixture.producer)
        .issueCurrentPlan(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(OPERATION_ID),
            eq(claim),
            any(AccountJwtReadinessProbeService.OwnerInternalProbeDelivery.class));
    verifyNoMoreInteractions(fixture.repository);
  }

  @Test
  void uncertainClaimCommitNeverInvokesSignerOrCreatesAnotherAttempt() {
    Fixture fixture = new Fixture(true);
    fixture.stubCurrentBindings();
    ReadinessProbePlan plan = fixture.plan();
    when(fixture.producer.planCurrent(fixture.accountBinding, fixture.trustFence)).thenReturn(plan);
    DeliveryClaim claim = mock(DeliveryClaim.class);
    when(fixture.repository.claimSingleDelivery(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(plan),
            eq(fixture.readinessBinding),
            eq(fixture.peer),
            any(Instant.class)))
        .thenReturn(claim);

    assertThatThrownBy(() -> fixture.owner.issue(validIssueRequest(), fixture.caller))
        .isInstanceOf(IllegalStateException.class);

    verify(fixture.producer).planCurrent(fixture.accountBinding, fixture.trustFence);
    verify(fixture.producer, never())
        .issueCurrentPlan(
            any(),
            any(),
            any(),
            any(),
            any(AccountJwtReadinessProbeService.OwnerInternalProbeDelivery.class));
    verify(fixture.repository)
        .claimSingleDelivery(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(plan),
            eq(fixture.readinessBinding),
            eq(fixture.peer),
            any(Instant.class));
  }

  @Test
  void claimReadbackFailureNeverInvokesSigner() {
    Fixture fixture = new Fixture(false);
    fixture.stubCurrentBindings();
    ReadinessProbePlan plan = fixture.plan();
    when(fixture.producer.planCurrent(fixture.accountBinding, fixture.trustFence)).thenReturn(plan);
    when(fixture.repository.claimSingleDelivery(
            eq(fixture.accountBinding),
            eq(fixture.trustFence),
            eq(plan),
            eq(fixture.readinessBinding),
            eq(fixture.peer),
            any(Instant.class)))
        .thenThrow(new AccountJwtReadinessProbeRepository.StorageUnavailableException("readback"));

    assertThatThrownBy(() -> fixture.owner.issue(validIssueRequest(), fixture.caller))
        .isInstanceOf(AccountJwtReadinessProbeRepository.StorageUnavailableException.class);
    verify(fixture.producer, never())
        .issueCurrentPlan(
            any(),
            any(),
            any(),
            any(),
            any(AccountJwtReadinessProbeService.OwnerInternalProbeDelivery.class));
  }

  @Test
  void validateReturnsTheExactProductionReceiptWithoutInventingReadinessEvidence() {
    Fixture fixture = new Fixture(false);
    VerificationReceipt receipt =
        new VerificationReceipt(
            1,
            3,
            4,
            20_400,
            "d".repeat(64),
            "pending-key-2",
            "account-validator-instance-7",
            "e".repeat(64),
            "revision-7",
            VALIDATOR_URI,
            "f".repeat(64));
    ValidateReadinessProbeRequest request =
        ValidateReadinessProbeRequest.newBuilder()
            .setSchemaVersion(1)
            .setRotationOperationId(OPERATION_ID.toString())
            .setValidatorId("account-service")
            .setTokenProfile("account-jwt-readiness-canary")
            .setAudience("firemud-account-jwt-readiness")
            .setProbeKind("CANARY")
            .setJti(JTI.toString())
            .setCompactJwt(COMPACT_JWT)
            .build();
    when(fixture.validation.validate(request, fixture.caller)).thenReturn(receipt);

    var response = fixture.owner.validate(request, fixture.caller);

    assertThat(response.getResult())
        .isEqualTo(
            net.firedevops.firemud.account.v1.ValidateReadinessProbeResponse.Result
                .NON_AUTHORIZING_VERIFICATION_RECORDED);
    assertThat(response.getReceiptVersion()).isEqualTo(receipt.receiptVersion());
    assertThat(response.getReceiptSha256()).isEqualTo(receipt.receiptSha256());
    assertThat(response.getObservedAtEpochSeconds()).isEqualTo(receipt.observedAtEpochSecond());
    assertThat(response.getValidatorInstanceId()).isEqualTo(receipt.validatorInstanceId());
    verify(fixture.validation).validate(request, fixture.caller);
    verifyNoInteractions(fixture.repository, fixture.producer);
  }

  private static IssueReadinessProbesRequest validIssueRequest() {
    return IssueReadinessProbesRequest.newBuilder().setSchemaVersion(1).build();
  }

  private static UnknownFieldSet unknownField() {
    return UnknownFieldSet.newBuilder()
        .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }

  private static final class Fixture {
    private final AccountJwtReadinessProbeRepository repository =
        mock(AccountJwtReadinessProbeRepository.class);
    private final AccountJwtReadinessProbeService producer =
        mock(AccountJwtReadinessProbeService.class);
    private final AccountJwtReadinessValidationService validation =
        mock(AccountJwtReadinessValidationService.class);
    private final AccountJwtReadinessTlsInterceptor.AuthenticatedCaller caller =
        mock(AccountJwtReadinessTlsInterceptor.AuthenticatedCaller.class);
    private final AccountJwtReadinessTrustBinding.Binding readinessBinding =
        mock(AccountJwtReadinessTrustBinding.Binding.class);
    private final AccountJwtSignerMaterializerTrustBinding.Binding signerBinding =
        mock(AccountJwtSignerMaterializerTrustBinding.Binding.class);
    private final AccountJwtSignerDesiredStateRepository.Binding accountBinding =
        new AccountJwtSignerDesiredStateRepository.Binding(
            "prod",
            "prod-cluster-1",
            "firemud-prod",
            AccountJwtSignerDesiredStateRepository.CustodyMode
                .INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    private final TrustFence trustFence =
        new TrustFence(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "b".repeat(64),
            "revision-7");
    private final PeerIdentity peer = new PeerIdentity(VALIDATOR_URI, "f".repeat(64));
    private final RecordingTransactionManager transactions;
    private final AccountJwtReadinessTransportOwner owner;

    private Fixture(boolean failCommit) {
      transactions = new RecordingTransactionManager(failCommit);
      owner =
          new AccountJwtReadinessTransportOwner(
              repository,
              producer,
              validation,
              new TransactionTemplate(transactions),
              Clock.fixed(NOW, ZoneOffset.UTC));
      when(caller.peer()).thenReturn(peer);
      when(validation.requireAuthenticatedBinding(caller)).thenReturn(readinessBinding);
      when(validation.requireCurrentSignerBinding(readinessBinding)).thenReturn(signerBinding);
      when(signerBinding.accountBinding()).thenReturn(accountBinding);
      when(signerBinding.expectedClusterIncarnationUid())
          .thenReturn("11111111-1111-4111-8111-111111111111");
      when(signerBinding.expectedNamespaceUid()).thenReturn("22222222-2222-4222-8222-222222222222");
      when(signerBinding.bindingDigest()).thenReturn("b".repeat(64));
      when(signerBinding.configRevision()).thenReturn("revision-7");
    }

    private void stubCurrentBindings() {
      org.mockito.Mockito.doNothing()
          .when(validation)
          .requireBindingsUnchanged(readinessBinding, signerBinding, caller);
    }

    private ReadinessProbePlan plan() {
      ReadinessProbePlan plan = mock(ReadinessProbePlan.class);
      when(plan.operationId()).thenReturn(OPERATION_ID);
      when(plan.planDigest()).thenReturn(PLAN_DIGEST);
      when(plan.planVersion()).thenReturn(1);
      when(plan.plannedAtEpochSecond()).thenReturn(20_000L);
      when(plan.notBeforeEpochSecond()).thenReturn(20_300L);
      when(plan.expiresAtEpochSecond()).thenReturn(20_600L);
      when(plan.targetGeneration()).thenReturn("2");
      when(plan.targetKid()).thenReturn("pending-key-2");
      when(plan.validatorInventoryComplete()).thenReturn(false);
      when(plan.expectedFence()).thenReturn(new SignerFence(Optional.empty(), Optional.empty()));
      ProbeEntry plannedEntry = entry(ProbeState.PLANNED, 1, Optional.empty());
      when(plan.entries()).thenReturn(List.of(plannedEntry));
      return plan;
    }

    private ProbeEntry entry(ProbeState state, long version, Optional<String> tokenHash) {
      ProbeEntry entry = mock(ProbeEntry.class);
      when(entry.rotationOperationId()).thenReturn(OPERATION_ID);
      when(entry.planDigest()).thenReturn(PLAN_DIGEST);
      when(entry.validatorId()).thenReturn("account-service");
      when(entry.tokenProfile()).thenReturn("account-jwt-readiness-canary");
      when(entry.audience()).thenReturn("firemud-account-jwt-readiness");
      when(entry.probeKind()).thenReturn(ProbeKind.CANARY);
      when(entry.jti()).thenReturn(JTI);
      when(entry.targetGeneration()).thenReturn("2");
      when(entry.targetKid()).thenReturn("pending-key-2");
      when(entry.expectedActive()).thenReturn(Optional.empty());
      when(entry.registryVersion()).thenReturn(1);
      when(entry.entryVersion()).thenReturn(version);
      when(entry.plannedIssuedAtEpochSecond()).thenReturn(20_300L);
      when(entry.expiresAtEpochSecond()).thenReturn(20_600L);
      when(entry.state()).thenReturn(state);
      when(entry.compactTokenSha256()).thenReturn(tokenHash);
      return entry;
    }
  }

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final boolean failCommit;
    private final AtomicBoolean committed = new AtomicBoolean();

    private RecordingTransactionManager(boolean failCommit) {
      this.failCommit = failCommit;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      if (failCommit) {
        throw new IllegalStateException("Commit outcome is uncertain");
      }
      committed.set(true);
    }

    @Override
    public void rollback(TransactionStatus status) {}
  }
}
