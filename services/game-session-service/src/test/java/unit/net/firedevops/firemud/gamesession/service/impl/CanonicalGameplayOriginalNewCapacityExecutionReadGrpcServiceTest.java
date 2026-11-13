package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecutionReadGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewCapacityExecutionOwner;
import net.firedevops.firemud.gamesession.security.CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewCapacityExecutionResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Controlled receiver boundary; actual source/transport authority is tested in composition. */
class CanonicalGameplayOriginalNewCapacityExecutionReadGrpcServiceTest {
  @Test
  void missingPeerRefusesBeforeNullPayloadParsingOrAnyOwnerRead() {
    CanonicalGameplayOriginalNewCapacityExecutionOwner owner = mock();
    StreamObserver<ReadOriginalNewCapacityExecutionResponse> observer = mock();
    service(owner).readOriginalNewCapacityExecution(null, observer);
    assertStatus(observer, Status.Code.UNAUTHENTICATED);
    verifyNoInteractions(owner);
  }

  @Test
  void authenticatedMalformedSelectorRefusesBeforeAnyOwnerRead() {
    CanonicalGameplayOriginalNewCapacityExecutionOwner owner = mock();
    StreamObserver<ReadOriginalNewCapacityExecutionResponse> observer = mock();
    account()
        .run(
            () ->
                service(owner)
                    .readOriginalNewCapacityExecution(
                        ReadOriginalNewCapacityExecutionRequest.getDefaultInstance(), observer));
    assertStatus(observer, Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(owner);
  }

  @Test
  void unavailableExactOwnerEvidenceIsNeverEncodedAsNoCommit() {
    var selector = selector();
    CanonicalGameplayOriginalNewCapacityExecutionOwner owner = mock();
    when(owner.readExact(selector.request(), selector.reservation()))
        .thenThrow(new IllegalStateException("Controlled missing exact source"));
    StreamObserver<ReadOriginalNewCapacityExecutionResponse> observer = mock();
    account()
        .run(
            () ->
                service(owner)
                    .readOriginalNewCapacityExecution(
                        OriginalNewCapacityExecutionReadGrpcCodec.toRequest(selector), observer));
    assertStatus(observer, Status.Code.UNAVAILABLE);
    verify(owner).readExact(selector.request(), selector.reservation());
  }

  private static OriginalNewCapacityExecutionReadGrpcCodec.Selector selector() {
    var request = CapacityAdmissionTestFixture.playerRequest();
    var reservation =
        new TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.randomUUID(),
            1,
            request.evidence().expiresAt(),
            TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    return new OriginalNewCapacityExecutionReadGrpcCodec.Selector(request, reservation);
  }

  private static Context account() {
    return Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            GrpcPeerIdentity.parseUri("spiffe://firemud/ns/prod/sa/account-service").orElseThrow());
  }

  private static CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService service(
      CanonicalGameplayOriginalNewCapacityExecutionOwner owner) {
    return new CanonicalGameplayOriginalNewCapacityExecutionReadGrpcService(
        owner, new CanonicalGameplayOriginalNewCapacityExecutionReadWorkloadGuard("prod"), "prod");
  }

  private static void assertStatus(StreamObserver<?> observer, Status.Code code) {
    ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
    verify(observer).onError(error.capture());
    verifyNoMoreInteractions(observer);
    assertThat(Status.fromThrowable(error.getValue()).getCode()).isEqualTo(code);
  }
}
