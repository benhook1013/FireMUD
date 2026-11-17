package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalInitialAdmissionOwner;
import net.firedevops.firemud.gamesession.data.RunOwnedCanonicalLaunchPreparationOwner;
import net.firedevops.firemud.gamesession.v1.GetFixtureInitialAdmissionOwnerRequest;
import net.firedevops.firemud.gamesession.v1.GetFixtureInitialAdmissionOwnerResponse;
import org.junit.jupiter.api.Test;

class FixtureInitialAdmissionOwnerReadGrpcServiceTest {
  @Test
  void unauthenticatedPeerDeniedBeforeParsingOrOwnerRead() {
    var fixed = mock(RunOwnedCanonicalLaunchPreparationOwner.class);
    var owner = mock(RunOwnedCanonicalInitialAdmissionOwner.class);
    when(fixed.loadFixedVerifiedEvidenceForWorldPeer())
        .thenThrow(new AdminAuthorizationException("wrong namespace or leaf"));
    var observer = new Result();
    new FixtureInitialAdmissionOwnerReadGrpcService(fixed, owner)
        .getFixtureInitialAdmissionOwner(
            GetFixtureInitialAdmissionOwnerRequest.getDefaultInstance(), observer);
    assertThat(observer.errorCode).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(owner);
  }

  @Test
  void disabledOrChangedFixedReceiptDeniesBeforeOwnerRead() {
    var fixed = mock(RunOwnedCanonicalLaunchPreparationOwner.class);
    var owner = mock(RunOwnedCanonicalInitialAdmissionOwner.class);
    when(fixed.loadFixedVerifiedEvidenceForWorldPeer())
        .thenThrow(new IllegalStateException("fixed source unavailable"));
    var observer = new Result();
    new FixtureInitialAdmissionOwnerReadGrpcService(fixed, owner)
        .getFixtureInitialAdmissionOwner(
            GetFixtureInitialAdmissionOwnerRequest.getDefaultInstance(), observer);
    assertThat(observer.errorCode).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(owner);
  }

  @Test
  void originalFixtureOperationIsNotAReadCorrelationAndCannotReachTerminalOwner() {
    var fixed = mock(RunOwnedCanonicalLaunchPreparationOwner.class);
    var owner = mock(RunOwnedCanonicalInitialAdmissionOwner.class);
    var source =
        mock(
            net.firedevops.firemud.gamesession.data.RunOwnedInitialAdmissionLaunchEvidence.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var operation = java.util.UUID.randomUUID();
    when(fixed.loadFixedVerifiedEvidenceForWorldPeer()).thenReturn(source);
    when(source.fixtureCapability().operationId()).thenReturn(operation.toString());
    var selector =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.Request(
            "test",
            java.util.UUID.randomUUID(),
            "demo",
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            "SHARED",
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            2L,
            java.util.UUID.randomUUID().toString(),
            "a".repeat(64),
            net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold
                .InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            null);
    var fixture =
        new net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionHoldRequest(
            selector,
            new net.firedevops.firemud.common.world.WorldCanonicalInstanceFixtureActivationRequest(
                java.util.UUID.randomUUID(),
                selector.canonicalTenantId(),
                selector.canonicalGameInstanceId(),
                "sha256:" + "b".repeat(64),
                1L,
                0L));
    var identity =
        new net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity(
            selector, java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
    var lookup =
        new net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec.Lookup(
            operation, fixture, identity);
    var observer = new Result();
    new FixtureInitialAdmissionOwnerReadGrpcService(fixed, owner)
        .getFixtureInitialAdmissionOwner(
            net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec.toTerminalRequest(
                lookup),
            observer);
    assertThat(observer.errorCode).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  private static final class Result
      implements StreamObserver<GetFixtureInitialAdmissionOwnerResponse> {
    private Status.Code errorCode;

    @Override
    public void onNext(GetFixtureInitialAdmissionOwnerResponse response) {
      throw new AssertionError("Denied read produced evidence");
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Denied read completed");
    }
  }
}
