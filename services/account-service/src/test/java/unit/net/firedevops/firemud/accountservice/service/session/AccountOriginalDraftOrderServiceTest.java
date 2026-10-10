package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.time.OffsetDateTime;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.accountservice.hostedterms.AccountHostedTermsService;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import org.junit.jupiter.api.Test;

class AccountOriginalDraftOrderServiceTest {
  @Test
  void heldReplaySkipsCreatorAndEnvironmentAndUnavailableReadCannotFallThrough() {
    var actors = mock(AccountControlUiActorService.class);
    var service = new AccountOriginalDraftOrderService(actors, "test");
    var original = AccountOriginalDraftOrderGrpcServiceTest.original();
    java.util.function.Supplier<AccountHostedTermsService.CapturedEnvironmentBoundary> capture =
        mock(java.util.function.Supplier.class);
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var prior = context.attach();
    try {
      when(actors.readHeldOriginalDraft(original)).thenReturn(true);
      service.claimWithEnvironmentCapture("expired.original.credential", original, capture);
      verifyNoInteractions(capture);
      org.mockito.Mockito.verify(actors, org.mockito.Mockito.never())
          .claimOriginalDraft(
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any(),
              org.mockito.ArgumentMatchers.any());
      when(actors.readHeldOriginalDraft(original))
          .thenThrow(new IllegalStateException("ambiguous"));
      assertThatThrownBy(
              () ->
                  service.claimWithEnvironmentCapture(
                      "expired.original.credential", original, capture))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(capture);
    } finally {
      context.detach(prior);
    }
  }

  @Test
  void exactOwnerOrderIsRequiredWithUnchangedCreatorAndEnvironment() {
    var actors = mock(AccountControlUiActorService.class);
    var service = new AccountOriginalDraftOrderService(actors, "test");
    var original = AccountOriginalDraftOrderGrpcServiceTest.original();
    var environment = mock(AccountHostedTermsService.CapturedEnvironmentBoundary.class);
    var context =
        Context.current()
            .withValue(
                GrpcPeerIdentity.CONTEXT_KEY,
                GrpcPeerIdentity.parseUri("spiffe://firemud/ns/test/sa/game-design-service")
                    .orElseThrow());
    var prior = context.attach();
    try {
      var now = OffsetDateTime.now();
      when(actors.claimOriginalDraft("unchanged.original.credential", original, environment))
          .thenReturn(
              new DraftAuthorizationFenceRepository.FenceSnapshot(
                  DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER,
                  original.canonicalBytes(),
                  now,
                  now));
      service.claim("unchanged.original.credential", original, environment);
      verify(actors).claimOriginalDraft("unchanged.original.credential", original, environment);
      for (var ordering :
          java.util.List.of(
              DraftAuthorizationFenceRepository.Ordering.RESERVED,
              DraftAuthorizationFenceRepository.Ordering.REVOKE_ORDER)) {
        when(actors.claimOriginalDraft("unchanged.original.credential", original, environment))
            .thenReturn(
                new DraftAuthorizationFenceRepository.FenceSnapshot(
                    ordering, original.canonicalBytes(), now, now));
        assertThatThrownBy(
                () -> service.claim("unchanged.original.credential", original, environment))
            .isInstanceOf(IllegalStateException.class);
      }
      when(actors.claimOriginalDraft("unchanged.original.credential", original, environment))
          .thenReturn(
              new DraftAuthorizationFenceRepository.FenceSnapshot(
                  DraftAuthorizationFenceRepository.Ordering.COMMIT_ORDER,
                  AccountOriginalDraftOrderGrpcServiceTest.original().canonicalBytes(),
                  now,
                  now));
      assertThatThrownBy(
              () -> service.claim("unchanged.original.credential", original, environment))
          .isInstanceOf(IllegalStateException.class);
    } finally {
      context.detach(prior);
    }
  }

  @Test
  void unknownCallerCannotReachOriginalCreatorOwner() {
    var actors = mock(AccountControlUiActorService.class);
    assertThatThrownBy(
            () -> new AccountOriginalDraftOrderService(actors, "test").claim(null, null, null))
        .isInstanceOf(io.grpc.StatusRuntimeException.class);
    verifyNoInteractions(actors);
  }
}
