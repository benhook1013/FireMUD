package net.firedevops.firemud.gamedesign.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantIdentity;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveLegacyGameTenantIdentityResponse;
import org.junit.jupiter.api.Test;

class TenantIdentityGrpcServiceTest {
  private static final String ACCOUNT_PEER = "spiffe://firemud/ns/test/sa/account-service";
  private static final String WRONG_PEER = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final UUID CANONICAL_TENANT_ID =
      UUID.fromString("87426bb3-a733-43f0-9c8e-2e379cbdf7ec");

  private final GameRepository repository = mock(GameRepository.class);
  private final TenantIdentityGrpcService service =
      new TenantIdentityGrpcService(repository, "test");

  @Test
  void exactAccountPeerReadsOwnerSourceAndProvenance() {
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    CANONICAL_TENANT_ID,
                    GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                    7L,
                    "legacy-game-7")));
    TestObserver observer = call("legacy-game-7", ACCOUNT_PEER);

    assertNull(observer.errorCode);
    assertTrue(observer.completed);
    assertNotNull(observer.value);
    assertEquals(CANONICAL_TENANT_ID.toString(), observer.value.getCanonicalTenantId());
    assertEquals("legacy-game-7", observer.value.getSourceLegacyGameTenantId());
    assertEquals(7L, observer.value.getSourceGameRowId());
    assertEquals("RETAINED_GAME_V30", observer.value.getProvenanceKind());
    verify(repository).findTenantIdentityByLegacyTenantId("legacy-game-7");
  }

  @Test
  void wrongOrMissingPeerNeverReadsOwnerRows() {
    assertEquals(Status.Code.PERMISSION_DENIED, status(call("legacy-game-7", WRONG_PEER)));
    assertEquals(Status.Code.PERMISSION_DENIED, status(call("legacy-game-7", null)));
    verifyNoInteractions(repository);
  }

  @Test
  void blankOrOverlongSourceKeyNeverReadsOwnerRows() {
    assertEquals(Status.Code.INVALID_ARGUMENT, status(call("", ACCOUNT_PEER)));
    assertEquals(Status.Code.INVALID_ARGUMENT, status(call("x".repeat(37), ACCOUNT_PEER)));
    verifyNoInteractions(repository);
  }

  @Test
  void unknownOrMismatchedSourceFailsClosed() {
    when(repository.findTenantIdentityByLegacyTenantId("unknown")).thenReturn(Optional.empty());
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenReturn(
            Optional.of(
                new GameTenantIdentity(
                    CANONICAL_TENANT_ID,
                    GameTenantIdentity.ProvenanceKind.RETAINED_GAME_V30,
                    7L,
                    "different-source")));

    TestObserver absent = call("unknown", ACCOUNT_PEER);
    TestObserver mismatched = call("legacy-game-7", ACCOUNT_PEER);
    assertEquals(Status.Code.NOT_FOUND, status(absent));
    assertEquals(Status.Code.FAILED_PRECONDITION, status(mismatched));
    assertNull(absent.value);
    assertNull(mismatched.value);
    assertFalse(absent.completed);
    assertFalse(mismatched.completed);
  }

  @Test
  void invalidStoredProvenanceFailsClosed() {
    when(repository.findTenantIdentityByLegacyTenantId("legacy-game-7"))
        .thenThrow(new IllegalStateException("corrupt source binding"));

    TestObserver observer = call("legacy-game-7", ACCOUNT_PEER);

    assertEquals(Status.Code.FAILED_PRECONDITION, status(observer));
    assertNull(observer.value);
  }

  private TestObserver call(String sourceKey, String peerUri) {
    TestObserver observer = new TestObserver();
    Context context = Context.current();
    if (peerUri != null) {
      context =
          context.withValue(
              GrpcPeerIdentity.CONTEXT_KEY, GrpcPeerIdentity.parseUri(peerUri).orElseThrow());
    }
    context.run(
        () ->
            service.resolveLegacyGameTenantIdentity(
                ResolveLegacyGameTenantIdentityRequest.newBuilder()
                    .setLegacyGameTenantId(sourceKey)
                    .build(),
                observer));
    return observer;
  }

  private static Status.Code status(TestObserver observer) {
    assertNotNull(observer.errorCode);
    return observer.errorCode;
  }

  private static final class TestObserver
      implements StreamObserver<ResolveLegacyGameTenantIdentityResponse> {
    private ResolveLegacyGameTenantIdentityResponse value;
    private Status.Code errorCode;
    private boolean completed;

    @Override
    public void onNext(ResolveLegacyGameTenantIdentityResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      errorCode = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
