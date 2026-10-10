package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountJwtReadinessProbeRepositoryTest {
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
  void requiresAnOwningAccountTransactionBeforeReadingOtherAuthorities() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository desired =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtJwksPublicationRepository publication =
        mock(AccountJwtJwksPublicationRepository.class);
    AccountJwtReadinessProbeRepository repository =
        new AccountJwtReadinessProbeRepository(dsl, desired, publication);

    assertThatThrownBy(() -> repository.planCurrent(BINDING, TRUST, Instant.ofEpochSecond(10_000)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owning Account transaction");

    verifyNoInteractions(dsl, desired, publication);
  }

  @Test
  void promotionProofReadsRequireWritableOwnerTransactionForLockedEvidence() {
    DSLContext dsl = mock(DSLContext.class);
    AccountJwtSignerDesiredStateRepository desired =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtJwksPublicationRepository publication =
        mock(AccountJwtJwksPublicationRepository.class);
    AccountJwtReadinessProbeRepository repository =
        new AccountJwtReadinessProbeRepository(dsl, desired, publication);
    UUID operationId = UUID.randomUUID();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    try {
      assertThatThrownBy(
              () -> repository.readCurrentPromotionPrerequisites(BINDING, TRUST, operationId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("A writable Account transaction is required");
      assertThatThrownBy(() -> repository.readPromotionProof(BINDING, TRUST, operationId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("A writable Account transaction is required");
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    verifyNoInteractions(dsl, desired, publication);
  }

  @Test
  void matrixIsCanonicalExplicitAndStillMarkedIncomplete() {
    String matrix = AccountJwtReadinessProbeRepository.applicabilityMatrixJson();

    assertThat(matrix)
        .isEqualTo(
            "{\"inventoryStatus\":\"PARTIAL_UNCONFIRMED\",\"schemaVersion\":1,"
                + "\"validators\":[{\"applicableProfiles\":[{\"audience\":\"control-ui\","
                + "\"tokenProfile\":\"control-ui\"},{\"audience\":\"player-bootstrap\","
                + "\"tokenProfile\":\"player-bootstrap\"},{\"audience\":\"account-service\","
                + "\"tokenProfile\":\"game-session-account-delegation\"}],"
                + "\"validatorId\":\"account-service\"}]}");
    assertThat(matrix).doesNotContain("credential-authenticate-account");
  }

  @Test
  void firstInstallFenceHasExactAbsentActiveAndPublishedPair() {
    AccountJwtReadinessProbeRepository.SignerFence fence =
        new AccountJwtReadinessProbeRepository.SignerFence(Optional.empty(), Optional.empty());

    assertThat(fence.durableActive()).isEmpty();
    assertThat(fence.publishedActive()).isEmpty();
    assertThatThrownBy(
            () ->
                new AccountJwtReadinessProbeRepository.SignerFence(
                    Optional.empty(),
                    Optional.of(
                        new AccountJwtSignerDesiredStateRepository.ActiveSigner("1", "old-key"))))
        .isInstanceOf(AccountJwtReadinessProbeRepository.QuarantinedStateException.class);
  }

  @Test
  void plannedFirstInstallEntryCarriesAnAbsentPreviousActiveFence() {
    var entry =
        new AccountJwtReadinessProbeRepository.ProbeEntry(
            UUID.randomUUID(),
            "a".repeat(64),
            "account-service",
            "game-session-account-delegation",
            "account-service",
            net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle
                .ProbeKind.REPRESENTATIVE,
            UUID.randomUUID(),
            "1",
            "first-key",
            Optional.empty(),
            1,
            1,
            20_000,
            20_300,
            AccountJwtReadinessProbeRepository.ProbeState.PLANNED,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    assertThat(entry.expectedActive()).isEmpty();
    assertThat(entry.state()).isEqualTo(AccountJwtReadinessProbeRepository.ProbeState.PLANNED);
  }
}
