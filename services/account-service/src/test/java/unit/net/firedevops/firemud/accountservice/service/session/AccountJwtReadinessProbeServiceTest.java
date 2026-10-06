package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.DeliveryClaim;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeEntry;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ProbeState;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.ReadinessProbePlan;
import net.firedevops.firemud.accountservice.repository.AccountJwtReadinessProbeRepository.SignerFence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AccountJwtReadinessProbeServiceTest {
  private static final Binding BINDING =
      new Binding(
          "staging", "cluster-a", "firemud", CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
  private static final TrustFence TRUST =
      new TrustFence(
          "11111111-1111-4111-8111-111111111111",
          "22222222-2222-4222-8222-222222222222",
          "a".repeat(64),
          "trust-r1");

  @Test
  void exactIssuedRetryReadsOnlyStoredEvidenceAndDoesNotRemintOrRedeliver() {
    AccountJwtReadinessProbeRepository repository = mock(AccountJwtReadinessProbeRepository.class);
    UUID operationId = UUID.randomUUID();
    UUID jti = UUID.randomUUID();
    long issuedAt = 20_300;
    ProbeEntry issued =
        new ProbeEntry(
            operationId,
            "b".repeat(64),
            "account-service",
            "game-session-account-delegation",
            "account-service",
            AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
            jti,
            "1",
            "first-key",
            Optional.empty(),
            1,
            3,
            issuedAt,
            issuedAt + 180,
            ProbeState.ISSUED,
            Optional.empty(),
            Optional.of(issuedAt),
            Optional.of("c".repeat(64)),
            Optional.empty());
    ReadinessProbePlan plan =
        new ReadinessProbePlan(
            operationId,
            BINDING,
            TRUST,
            "d".repeat(64),
            "e".repeat(64),
            "f".repeat(64),
            2,
            "1",
            "first-key",
            "a".repeat(64),
            new SignerFence(Optional.empty(), Optional.empty()),
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "{}",
            "4".repeat(64),
            false,
            300,
            20_000,
            20_300,
            20_600,
            1,
            "5".repeat(64),
            List.of(issued));
    when(repository.readCurrentPlan(BINDING, TRUST, operationId)).thenReturn(plan);
    DeliveryClaim claim = deliveryClaim(plan);
    when(repository.requireCurrentDeliveryClaim(
            org.mockito.ArgumentMatchers.eq(BINDING),
            org.mockito.ArgumentMatchers.eq(TRUST),
            org.mockito.ArgumentMatchers.eq(plan),
            org.mockito.ArgumentMatchers.eq(claim),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(claim);
    AtomicInteger deliveries = new AtomicInteger();
    AccountJwtReadinessProbeService service =
        new AccountJwtReadinessProbeService(
            repository,
            new TransactionTemplate(new NoopTransactionManager()),
            Clock.fixed(Instant.ofEpochSecond(20_400), ZoneOffset.UTC),
            Path.of("/missing/private"),
            Path.of("pending.key"),
            Path.of("/missing/public"),
            Path.of("jwks.json"));

    List<ProbeEntry> result =
        service.issueCurrentPlan(
            BINDING, TRUST, operationId, claim, (metadata, jwt) -> deliveries.incrementAndGet());

    assertThat(result).containsExactly(issued);
    assertThat(result.get(0).compactTokenSha256()).contains("c".repeat(64));
    assertThat(deliveries).hasValue(0);
    verify(repository, org.mockito.Mockito.times(2)).readCurrentPlan(BINDING, TRUST, operationId);
    verify(repository)
        .requireCurrentDeliveryClaim(
            BINDING, TRUST, plan, claim, java.time.Instant.ofEpochSecond(20_400));
    verifyNoMoreInteractions(repository);
  }

  @Test
  void exactVerifiedRetryPreservesReceiptWithoutRemintOrRedelivery() {
    AccountJwtReadinessProbeRepository repository = mock(AccountJwtReadinessProbeRepository.class);
    UUID operationId = UUID.randomUUID();
    UUID jti = UUID.randomUUID();
    long issuedAt = 20_300;
    String tokenHash = "c".repeat(64);
    var receipt =
        new AccountJwtReadinessProbeRepository.VerificationReceipt(
            1,
            3,
            4,
            20_400,
            "6".repeat(64),
            "first-key",
            "account-validator-instance-7",
            "7".repeat(64),
            "readiness-r1",
            "spiffe://firemud/ns/firemud/sa/account-jwt-readiness-harness",
            "8".repeat(64));
    ProbeEntry verified =
        new ProbeEntry(
            operationId,
            "b".repeat(64),
            "account-service",
            "game-session-account-delegation",
            "account-service",
            AccountMountedJwtSignerBundle.ProbeKind.REPRESENTATIVE,
            jti,
            "1",
            "first-key",
            Optional.empty(),
            1,
            4,
            issuedAt,
            issuedAt + 180,
            ProbeState.VERIFIED,
            Optional.empty(),
            Optional.of(issuedAt),
            Optional.of(tokenHash),
            Optional.of(receipt));
    ReadinessProbePlan plan =
        new ReadinessProbePlan(
            operationId,
            BINDING,
            TRUST,
            "d".repeat(64),
            "e".repeat(64),
            "f".repeat(64),
            2,
            "1",
            "first-key",
            "a".repeat(64),
            new SignerFence(Optional.empty(), Optional.empty()),
            "1".repeat(64),
            "2".repeat(64),
            "3".repeat(64),
            "{}",
            "4".repeat(64),
            false,
            300,
            20_000,
            20_300,
            20_600,
            1,
            "5".repeat(64),
            List.of(verified));
    when(repository.readCurrentPlan(BINDING, TRUST, operationId)).thenReturn(plan);
    DeliveryClaim claim = deliveryClaim(plan);
    when(repository.requireCurrentDeliveryClaim(
            org.mockito.ArgumentMatchers.eq(BINDING),
            org.mockito.ArgumentMatchers.eq(TRUST),
            org.mockito.ArgumentMatchers.eq(plan),
            org.mockito.ArgumentMatchers.eq(claim),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(claim);
    AtomicInteger deliveries = new AtomicInteger();
    AccountJwtReadinessProbeService service =
        new AccountJwtReadinessProbeService(
            repository,
            new TransactionTemplate(new NoopTransactionManager()),
            Clock.fixed(Instant.ofEpochSecond(20_400), ZoneOffset.UTC),
            Path.of("/missing/private"),
            Path.of("pending.key"),
            Path.of("/missing/public"),
            Path.of("jwks.json"));

    List<ProbeEntry> result =
        service.issueCurrentPlan(
            BINDING, TRUST, operationId, claim, (metadata, jwt) -> deliveries.incrementAndGet());

    assertThat(result).containsExactly(verified);
    assertThat(result.get(0).verificationReceipt()).contains(receipt);
    assertThat(deliveries).hasValue(0);
    verify(repository, org.mockito.Mockito.times(2)).readCurrentPlan(BINDING, TRUST, operationId);
    verify(repository)
        .requireCurrentDeliveryClaim(
            BINDING, TRUST, plan, claim, java.time.Instant.ofEpochSecond(20_400));
    verifyNoMoreInteractions(repository);
  }

  private static final class NoopTransactionManager implements PlatformTransactionManager {
    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {}

    @Override
    public void rollback(TransactionStatus status) {}
  }

  private static DeliveryClaim deliveryClaim(ReadinessProbePlan plan) {
    return new DeliveryClaim(
        plan.operationId(),
        plan.planDigest(),
        TRUST.expectedClusterIncarnationUid(),
        TRUST.expectedNamespaceUid(),
        TRUST.bindingDigest(),
        TRUST.configRevision(),
        plan.targetGeneration(),
        plan.targetKid(),
        plan.expectedFence().durableActive(),
        "9".repeat(64),
        "readiness-r1",
        plan.expiresAtEpochSecond() + 1,
        "account-service",
        "account-validator-instance-1",
        "spiffe://firemud/ns/firemud/sa/account-jwt-readiness-harness",
        "8".repeat(64),
        plan.notBeforeEpochSecond(),
        plan.expiresAtEpochSecond(),
        "7".repeat(64));
  }
}
