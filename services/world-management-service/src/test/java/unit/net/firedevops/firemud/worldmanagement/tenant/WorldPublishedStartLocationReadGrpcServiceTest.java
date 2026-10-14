package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository.ConflictException;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublishedStartLocationReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublishedStartLocationRepository;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedStartLocationResponse;
import org.junit.jupiter.api.Test;

class WorldPublishedStartLocationReadGrpcServiceTest {
  @Test
  void rejectsMissingOrSubstitutedPeerBeforeDecodingOrStorageAccess() {
    var repository = mock(WorldPublishedStartLocationRepository.class);
    var service = new WorldPublishedStartLocationReadGrpcService(repository, "test");
    var malformed =
        ReadWorldPublishedStartLocationRequest.newBuilder().setSchemaVersion(-1).build();

    Collector missing = new Collector();
    service.readWorldPublishedStartLocation(malformed, missing);
    assertThat(missing.error).isEqualTo(Status.Code.PERMISSION_DENIED);

    Collector wrongService = new Collector();
    Context context =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("account-service", "test"));
    Context previous = context.attach();
    try {
      service.readWorldPublishedStartLocation(malformed, wrongService);
    } finally {
      context.detach(previous);
    }
    assertThat(wrongService.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAuthenticatedEndUserContextEvenWithCorrectWorkloadPeerBeforeDecoding() {
    var repository = mock(WorldPublishedStartLocationRepository.class);
    var service = new WorldPublishedStartLocationReadGrpcService(repository, "test");
    Collector response = new Collector();
    SessionContext.setContext("11111111-1111-4111-8111-111111111111", List.of(), Map.of());
    Context context =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("game-design-service", "test"));
    Context previous = context.attach();
    try {
      service.readWorldPublishedStartLocation(
          ReadWorldPublishedStartLocationRequest.newBuilder().setSchemaVersion(-1).build(),
          response);
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }
    assertThat(response.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void wrongNamespaceIsDeniedBeforeOwnerRead() {
    var repository = mock(WorldPublishedStartLocationRepository.class);
    var service = new WorldPublishedStartLocationReadGrpcService(repository, "test");
    Collector result = callAsGameDesign(service, request("other"));
    assertThat(result.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(repository);
  }

  @Test
  void missingSelectionAndConflictingSelectionFailClosed() {
    var repository = mock(WorldPublishedStartLocationRepository.class);
    var service = new WorldPublishedStartLocationReadGrpcService(repository, "test");
    var request = request("test");
    when(repository.readCommitted(any()))
        .thenReturn(Optional.empty())
        .thenThrow(new ConflictException("changed frozen selection"));

    Collector missing = callAsGameDesign(service, request);
    assertThat(missing.error).isEqualTo(Status.Code.NOT_FOUND);
    Collector changed = callAsGameDesign(service, request);
    assertThat(changed.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verify(repository, org.mockito.Mockito.times(2)).readCommitted(any());
  }

  private static Collector callAsGameDesign(
      WorldPublishedStartLocationReadGrpcService service,
      ReadWorldPublishedStartLocationRequest request) {
    Collector response = new Collector();
    Context context =
        Context.current()
            .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("game-design-service", "test"));
    Context previous = context.attach();
    try {
      service.readWorldPublishedStartLocation(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static ReadWorldPublishedStartLocationRequest request(String namespace) {
    return ReadWorldPublishedStartLocationRequest.newBuilder()
        .setSchemaVersion(1)
        .setTargetNamespace(namespace)
        .setCanonicalTenantId("11111111-1111-4111-8111-111111111111")
        .setCanonicalVersionId("22222222-2222-4222-8222-222222222222")
        .setIntakeRequestId("33333333-3333-4333-8333-333333333333")
        .setPublicationFence("44444444-4444-4444-8444-444444444444")
        .setPublicationRequestId("publication-request")
        .setRequestDigest("a".repeat(64))
        .setVersionStateEpoch(5L)
        .setPublishWorkflowId("publish-workflow")
        .setAppliedCommitId("55555555-5555-4555-8555-555555555555")
        .setContentDigest("b".repeat(64))
        .setDigestSchemaVersion(3)
        .build();
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static final class Collector
      implements StreamObserver<ReadWorldPublishedStartLocationResponse> {
    private ReadWorldPublishedStartLocationResponse value;
    private Status.Code error;
    private boolean completed;

    @Override
    public void onNext(ReadWorldPublishedStartLocationResponse next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(value).isNull();
      assertThat(completed).isFalse();
      error = Status.fromThrowable(failure).getCode();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
