package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionRequest;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceEvidence;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding.SourceKind;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.gamesession.OriginalStartSessionLaunchReadEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Storage comparison and dispatch-integrity tests. The mocked typed request proves no producer
 * authority; PostgreSQL source exclusion and currentness SQL are covered by integration fixtures.
 */
class AccountStartSessionActivationProtectionRepositoryTest {
  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void lookupRequiresCallerOwnedWritableReadCommittedTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountStartSessionActivationProtectionRepository repository =
        new AccountStartSessionActivationProtectionRepository(dsl);

    assertThatThrownBy(() -> repository.findExact(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction required");

    verifyNoInteractions(dsl);
  }

  @Test
  void historicalResolutionAndSettlementRequireIndependentOwnerTransactionBeforeDatabaseAccess() {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountStartSessionActivationProtectionRepository(dsl);

    assertThatThrownBy(() -> repository.findHistoricalExact(UUID.randomUUID(), 7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction required");
    assertThatThrownBy(() -> repository.findSettlementExact(UUID.randomUUID(), 7L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction required");
    assertThatThrownBy(() -> repository.settleExact(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Writable Account READ_COMMITTED transaction required");

    verifyNoInteractions(dsl);
  }

  @Test
  void exactPrefixedCommonRequestDigestReachesCurrentnessSql() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountStartSessionActivationProtectionRepository(dsl);
    byte[] operationIdentity = "stable operation identity".getBytes(StandardCharsets.UTF_8);
    byte[] fullRequest = "full request retaining read UUIDs".getBytes(StandardCharsets.UTF_8);
    String requestDigest = "sha256:" + sha256(operationIdentity);
    AccountStartSessionActivationProtectionRequest request =
        mockedRequest(fullRequest, operationIdentity, requestDigest);

    repository.assertCurrent(request);

    verify(dsl)
        .execute(
            "SELECT account_ss_activation_assert_current(?, ?, ?, FALSE)",
            fullRequest,
            operationIdentity,
            requestDigest);
  }

  @Test
  void unprefixedDigestIsRejectedBeforeCurrentnessSql() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    var repository = new AccountStartSessionActivationProtectionRepository(dsl);
    byte[] operationIdentity = "stable operation identity".getBytes(StandardCharsets.UTF_8);
    AccountStartSessionActivationProtectionRequest request =
        mockedRequest(
            "full request".getBytes(StandardCharsets.UTF_8),
            operationIdentity,
            sha256(operationIdentity));

    assertThatThrownBy(() -> repository.assertCurrent(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with its immutable binding");

    verifyNoInteractions(dsl);
  }

  @Test
  void operationRetryUsesStableIdentityRatherThanReplacingFirstFullRequest() {
    byte[] firstFullRequest = "first read UUIDs".getBytes(StandardCharsets.UTF_8);
    byte[] retryFullRequest = "fresh read UUIDs".getBytes(StandardCharsets.UTF_8);
    byte[] stableOperationIdentity = "normalized operation".getBytes(StandardCharsets.UTF_8);

    assertThat(firstFullRequest).isNotEqualTo(retryFullRequest);
    AccountStartSessionActivationProtectionRepository.requireSameOperationIdentity(
        stableOperationIdentity, stableOperationIdentity.clone());
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRepository.requireSameOperationIdentity(
                    stableOperationIdentity, "changed activation".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with its immutable binding");
  }

  @Test
  void changedCaptureBindingConflictsBeforeAnyReceiptCanBeReturned() {
    AccountStartSessionActivationProtectionRepository.requireSameCaptureBinding(
        "original-request", 19L, 23L, "a".repeat(64), "original-request", 19L, 23L, "a".repeat(64));

    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRepository.requireSameCaptureBinding(
                    "original-request",
                    19L,
                    23L,
                    "a".repeat(64),
                    "original-request",
                    20L,
                    23L,
                    "b".repeat(64)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with its immutable binding");
  }

  @Test
  void sourceChildrenMustMatchTheCompleteCanonicalEvidenceVector() {
    SourceEvidence first = source("captured source bytes");
    SourceEvidence sameKeyChangedValue = source("substituted source bytes");

    AccountStartSessionActivationProtectionRepository.requireExactSources(
        List.of(first), List.of(first));
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRepository.requireExactSources(
                    List.of(first), List.of(sameKeyChangedValue)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with its immutable binding");
    assertThatThrownBy(
            () ->
                AccountStartSessionActivationProtectionRepository.requireExactSources(
                    List.of(first), List.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicts with its immutable binding");
  }

  private static SourceEvidence source(String value) {
    return new SourceEvidence(
        SourceKind.ACCOUNT,
        UUID.fromString("12d1a4d7-b8c9-4b36-853f-7e658d06d30f").toString(),
        "2",
        "17",
        null,
        null,
        value.getBytes(StandardCharsets.UTF_8));
  }

  private static AccountStartSessionActivationProtectionRequest mockedRequest(
      byte[] fullRequest, byte[] operationIdentity, String requestDigest) {
    var request = mock(AccountStartSessionActivationProtectionRequest.class);
    var launchEvidence = mock(OriginalStartSessionLaunchReadEvidence.Result.class);
    var launch = mock(CanonicalGameInstanceLaunchAssociationReadEvidence.Result.class);
    var activation = mock(WorldCanonicalInstanceActivation.Request.class);
    var preparing = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(request.gameSessionLaunchReadEvidence()).thenReturn(launchEvidence);
    when(launchEvidence.launch()).thenReturn(launch);
    when(launch.currentGameInstanceStatus()).thenReturn(CurrentGameInstanceStatus.STARTING);
    when(request.worldActivationRequest()).thenReturn(activation);
    when(activation.preparing()).thenReturn(preparing);
    when(preparing.lifecycleStatus()).thenReturn("PREPARING");
    when(request.operationIdentityBytes()).thenReturn(operationIdentity);
    when(request.requestDigest()).thenReturn(requestDigest);
    when(request.canonicalBytes()).thenReturn(fullRequest);
    return request;
  }

  private static String sha256(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
