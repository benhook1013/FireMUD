package net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredentialRequest;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountOriginalNewBoundGameplayCredentialOwnerTest {
  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void bothIssueApisRefuseAmbientTransactionsBeforeCallerVerificationOrSourceReads() {
    DataSource dataSource = mock(DataSource.class);
    AccountGameplayAdmissionCallerVerifier callerVerifier = mock();
    AccountGameplayDelegationSigner signer = mock();
    AccountGameplayDelegationTokenRegistry tokenRegistry = mock();
    AccountResponseEnvelopeCryptography responseCryptography = mock();
    var owner =
        new AccountOriginalNewBoundGameplayCredentialOwner(
            dataSource,
            "test",
            callerVerifier,
            signer,
            tokenRegistry,
            responseCryptography,
            Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC));
    var peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-session-service").orElseThrow();
    OriginalNewCredentialRequest request = mock();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer)
        .run(
            () -> {
              assertThatThrownBy(() -> owner.issue("unlogged-test-token", null))
                  .isInstanceOf(StatusRuntimeException.class)
                  .satisfies(
                      failure ->
                          assertThat(Status.fromThrowable(failure).getCode())
                              .isEqualTo(Status.Code.FAILED_PRECONDITION));
              assertThatThrownBy(() -> owner.issue(request))
                  .isInstanceOf(StatusRuntimeException.class)
                  .satisfies(
                      failure ->
                          assertThat(Status.fromThrowable(failure).getCode())
                              .isEqualTo(Status.Code.FAILED_PRECONDITION));
            });
    verifyNoInteractions(dataSource, callerVerifier, signer, tokenRegistry, responseCryptography);
  }

  @Test
  void finalConfirmationFailsClosedUntilProtectedGsReadbackIsWired() {
    DataSource dataSource = mock(DataSource.class);
    AccountGameplayAdmissionCallerVerifier callerVerifier =
        mock(AccountGameplayAdmissionCallerVerifier.class);
    AccountGameplayDelegationSigner signer = mock(AccountGameplayDelegationSigner.class);
    AccountGameplayDelegationTokenRegistry tokenRegistry =
        mock(AccountGameplayDelegationTokenRegistry.class);
    AccountResponseEnvelopeCryptography responseCryptography =
        mock(AccountResponseEnvelopeCryptography.class);
    var owner =
        new AccountOriginalNewBoundGameplayCredentialOwner(
            dataSource,
            "test",
            callerVerifier,
            signer,
            tokenRegistry,
            responseCryptography,
            Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC));

    assertThatThrownBy(() -> owner.confirmOriginalFinalAdmission("unlogged-test-token", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Original-new bound gameplay credential is unavailable");
    verifyNoInteractions(dataSource, callerVerifier, signer, tokenRegistry, responseCryptography);
  }
}
