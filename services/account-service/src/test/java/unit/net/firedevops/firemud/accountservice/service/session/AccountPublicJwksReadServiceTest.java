package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.accountservice.config.AccountPublicJwksReadWorkloadGuard;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec;
import net.firedevops.firemud.common.security.AccountPublicJwksReadCodec.ReadResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Owner orchestration proof; the source edge has its own receipt/ConfigMap custody tests. */
class AccountPublicJwksReadServiceTest {
  private static final AccountPublicJwksCache.SourceIdentity PIN =
      new AccountPublicJwksCache.SourceIdentity(
          "prod",
          "cluster-a",
          "11111111-1111-4111-8111-111111111111",
          "firemud-prod",
          "22222222-2222-4222-8222-222222222222",
          "33333333-3333-4333-8333-333333333333",
          "api-7",
          "https://kubernetes.example:6443",
          "a".repeat(64));
  private final AccountJwtJwksTrustedSource source = mock(AccountJwtJwksTrustedSource.class);
  private final AccountPublicJwksReadWorkloadGuard guard =
      mock(AccountPublicJwksReadWorkloadGuard.class);
  private final AccountPublicJwksReadWorkloadGuard.ProtectedCaller caller =
      mock(AccountPublicJwksReadWorkloadGuard.ProtectedCaller.class);
  private final AccountPublicJwksReadService owner =
      new AccountPublicJwksReadService(source, guard);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void returnsExactCurrentSnapshotBetweenProtectedGuardReadsWithNoReadinessPlan() {
    when(guard.requireCaller()).thenReturn(caller);
    when(source.sourceIdentity()).thenReturn(PIN);
    when(source.currentPublicRead())
        .thenReturn(new ReadResult(PIN, new byte[] {1, 2, 3}, false, false));
    var response = owner.read(AccountPublicJwksReadCodec.request());
    assertThat(AccountPublicJwksReadCodec.snapshot(response).sourceIdentity()).isEqualTo(PIN);
    assertThat(response.getPublicJwks().toByteArray())
        .containsExactly((byte) 1, (byte) 2, (byte) 3);
    var order = inOrder(guard, source, caller);
    order.verify(guard).requireCaller();
    order.verify(source).currentPublicRead();
    order.verify(caller).requireSource(PIN);
    order.verify(source).sourceIdentity();
    order.verify(guard).requireCaller();
  }

  @Test
  void authenticatesPreparedDenyAndReturnsNoKeysOrRecoveryAuthority() {
    when(guard.requireCaller()).thenReturn(caller);
    when(source.sourceIdentity()).thenReturn(PIN);
    var deny = new ReadResult(PIN, new byte[0], true, false);
    when(source.currentPublicRead()).thenReturn(deny);
    var result =
        AccountPublicJwksReadCodec.result(owner.read(AccountPublicJwksReadCodec.request()));
    assertThat(result.sourceIdentity()).isEqualTo(PIN);
    assertThat(result.preparedDeny()).isTrue();
    assertThat(result.jwksBytes()).isEmpty();
    assertThat(result.committedRecoveryEligible()).isFalse();
    var order = inOrder(guard, source, caller);
    order.verify(guard).requireCaller();
    order.verify(source).currentPublicRead();
    order.verify(caller).requireSource(PIN);
    order.verify(source).sourceIdentity();
    order.verify(guard).requireCaller();
  }

  @Test
  void peerDenialPrecedesParsingAndOwnerReads() {
    when(guard.requireCaller())
        .thenThrow(new AccountPublicJwksReadWorkloadGuard.ReadDeniedException());
    assertThatThrownBy(() -> owner.read(GetCurrentAccountPublicJwksRequest.getDefaultInstance()))
        .isInstanceOf(AccountPublicJwksReadWorkloadGuard.ReadDeniedException.class);
    verifyNoInteractions(source);
  }

  @Test
  void malformedRequestAndAmbientTransactionNeverReadOwnerSource() {
    when(guard.requireCaller()).thenReturn(caller);
    assertThatThrownBy(() -> owner.read(GetCurrentAccountPublicJwksRequest.getDefaultInstance()))
        .isInstanceOf(AccountPublicJwksReadCodec.InvalidFrameException.class);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    verifyNoInteractions(source);
  }

  @Test
  void rejectsProducerBytesAsPublicationFailureAfterAcceptingRequest() {
    when(guard.requireCaller()).thenReturn(caller);
    when(source.sourceIdentity()).thenReturn(PIN);
    for (byte[] bytes :
        new byte[][] {new byte[0], new byte[AccountPublicJwksCache.MAX_JWKS_BYTES + 1]}) {
      var invalid = mock(ReadResult.class);
      when(invalid.sourceIdentity()).thenReturn(PIN);
      when(invalid.jwksBytes()).thenReturn(bytes);
      when(source.currentPublicRead()).thenReturn(invalid);
      assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
          .isExactlyInstanceOf(AccountPublicJwksCache.InvalidJwksException.class)
          .hasNoCause();
    }
    when(source.currentPublicRead()).thenReturn(null);
    assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
        .isExactlyInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
  }

  @Test
  void preservesUnclassifiedHandlerFaultForReceiverAvailabilityMapping() {
    when(guard.requireCaller()).thenReturn(caller);
    var fault = new IllegalStateException("unclassified internal fault");
    when(source.currentPublicRead()).thenThrow(fault);
    assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request())).isSameAs(fault);
  }

  @Test
  void refusesChangedSourceOrProtectedGuardAndPreservesTemporaryOutageType() {
    when(guard.requireCaller()).thenReturn(caller);
    when(source.sourceIdentity()).thenReturn(PIN);
    when(source.currentPublicRead())
        .thenThrow(new AccountPublicJwksCache.SourceUnavailableException());
    assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
        .isInstanceOf(AccountPublicJwksCache.SourceUnavailableException.class);
    var changed =
        new AccountPublicJwksCache.SourceIdentity(
            PIN.environmentId(),
            PIN.clusterId(),
            PIN.clusterIncarnationUid(),
            PIN.namespace(),
            PIN.namespaceUid(),
            "44444444-4444-4444-8444-444444444444",
            PIN.bindingRevision(),
            PIN.apiServerOrigin(),
            PIN.servingCaSha256());
    doReturn(new ReadResult(changed, new byte[] {1}, false, false))
        .when(source)
        .currentPublicRead();
    assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
    when(source.currentPublicRead()).thenReturn(new ReadResult(PIN, new byte[] {1}, false, false));
    when(guard.requireCaller())
        .thenReturn(caller, mock(AccountPublicJwksReadWorkloadGuard.ProtectedCaller.class));
    assertThatThrownBy(() -> owner.read(AccountPublicJwksReadCodec.request()))
        .isInstanceOf(AccountPublicJwksCache.InvalidJwksException.class);
  }
}
