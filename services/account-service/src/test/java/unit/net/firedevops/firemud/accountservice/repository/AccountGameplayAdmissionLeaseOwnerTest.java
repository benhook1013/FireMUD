package net.firedevops.firemud.accountservice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import net.firedevops.firemud.account.v1.AbortGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.AcquireGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.FinalizeGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.account.v1.ReadGameplayAdmissionLeaseRequest;
import net.firedevops.firemud.accountservice.client.CanonicalGameplayAdmissionDecisionReadClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AccountGameplayAdmissionLeaseOwnerTest {
  private static final String NAMESPACE = "firemud";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);

  private final AccountGameplayAdmissionCallerVerifier callerVerifier =
      mock(AccountGameplayAdmissionCallerVerifier.class);
  private final CanonicalGameplayAdmissionDecisionReadClient decisionClient =
      mock(CanonicalGameplayAdmissionDecisionReadClient.class);
  private final DataSource dataSource = mock(DataSource.class);
  private final AccountGameplayAdmissionLeaseOwner owner =
      new AccountGameplayAdmissionLeaseOwner(
          callerVerifier, decisionClient, NAMESPACE, dataSource, CLOCK);

  @AfterEach
  void clearContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void everyPurposeRejectsMissingPeerBeforeParsingOrOwnerAccess() {
    withPeer(
        null,
        () -> {
          assertStatus(
              Status.Code.UNAUTHENTICATED,
              () -> owner.acquire(AcquireGameplayAdmissionLeaseRequest.getDefaultInstance()));
          assertStatus(
              Status.Code.UNAUTHENTICATED,
              () ->
                  owner.finalizeLease(FinalizeGameplayAdmissionLeaseRequest.getDefaultInstance()));
          assertStatus(
              Status.Code.UNAUTHENTICATED,
              () -> owner.read(ReadGameplayAdmissionLeaseRequest.getDefaultInstance()));
          assertStatus(
              Status.Code.UNAUTHENTICATED,
              () -> owner.abort(AbortGameplayAdmissionLeaseRequest.getDefaultInstance()));
        });

    verifyNoInteractions(callerVerifier, decisionClient, dataSource);
  }

  @Test
  void everyPurposeRejectsWrongWorkloadBeforeParsingOrOwnerAccess() {
    GrpcPeerIdentity wrongPeer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/firemud/sa/account-service").orElseThrow();
    withPeer(
        wrongPeer,
        () -> {
          assertStatus(
              Status.Code.PERMISSION_DENIED,
              () -> owner.acquire(AcquireGameplayAdmissionLeaseRequest.getDefaultInstance()));
          assertStatus(
              Status.Code.PERMISSION_DENIED,
              () ->
                  owner.finalizeLease(FinalizeGameplayAdmissionLeaseRequest.getDefaultInstance()));
          assertStatus(
              Status.Code.PERMISSION_DENIED,
              () -> owner.read(ReadGameplayAdmissionLeaseRequest.getDefaultInstance()));
          assertStatus(
              Status.Code.PERMISSION_DENIED,
              () -> owner.abort(AbortGameplayAdmissionLeaseRequest.getDefaultInstance()));
        });

    verifyNoInteractions(callerVerifier, decisionClient, dataSource);
  }

  @Test
  void ambientAccountTransactionIsRejectedBeforeCredentialOrRemoteReads() {
    GrpcPeerIdentity peer =
        GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service")
            .orElseThrow();
    TransactionSynchronizationManager.setActualTransactionActive(true);

    withPeer(
        peer,
        () ->
            assertStatus(
                Status.Code.FAILED_PRECONDITION,
                () -> owner.acquire(AcquireGameplayAdmissionLeaseRequest.getDefaultInstance())));

    verifyNoInteractions(callerVerifier, decisionClient, dataSource);
  }

  private static void assertStatus(Status.Code expected, Runnable invocation) {
    StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, invocation::run);
    assertEquals(expected, failure.getStatus().getCode());
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable invocation) {
    Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer).run(invocation);
  }
}
