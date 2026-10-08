package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsRequest;
import net.firedevops.firemud.worldmanagement.v1.ReadWorldPublishedSpawnRequirementsResponse;
import org.junit.jupiter.api.Test;

class WorldPublishedSpawnRequirementsReadGrpcServiceTest {
  @Test
  void mapsOwnerFailureToBoundedStatusWithoutReturningInternalDetails() {
    var owner = mock(WorldPublishedSpawnRequirementsReadOwner.class);
    when(owner.read(any()))
        .thenThrow(
            new WorldPublishedSpawnRequirementsReadOwner.ReadRejectedException(
                Status.Code.FAILED_PRECONDITION, "secret database row detail"));
    var service = new WorldPublishedSpawnRequirementsReadGrpcService(owner);
    var observer = new Collector();

    service.readWorldPublishedSpawnRequirements(
        ReadWorldPublishedSpawnRequirementsRequest.newBuilder().build(), observer);

    assertThat(observer.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(observer.description)
        .isEqualTo("Published World source evidence is incomplete or inconsistent");
    assertThat(observer.description).doesNotContain("secret database row detail");
    assertThat(observer.value).isNull();
    assertThat(observer.completed).isFalse();
  }

  @Test
  void unexpectedOwnerFailureReturnsRedactedInternalAndNeverMasksObserverDelivery() {
    var owner = mock(WorldPublishedSpawnRequirementsReadOwner.class);
    when(owner.read(any())).thenThrow(new IllegalStateException("private exception detail"));
    var service = new WorldPublishedSpawnRequirementsReadGrpcService(owner);
    var observer = new Collector();

    service.readWorldPublishedSpawnRequirements(
        ReadWorldPublishedSpawnRequirementsRequest.newBuilder().build(), observer);

    assertThat(observer.error).isEqualTo(Status.Code.INTERNAL);
    assertThat(observer.description).isEqualTo("Published World source read failed");
    assertThat(observer.description).doesNotContain("private exception detail");
  }

  private static final class Collector
      implements StreamObserver<ReadWorldPublishedSpawnRequirementsResponse> {
    private ReadWorldPublishedSpawnRequirementsResponse value;
    private Status.Code error;
    private String description;
    private boolean completed;

    @Override
    public void onNext(ReadWorldPublishedSpawnRequirementsResponse next) {
      value = next;
    }

    @Override
    public void onError(Throwable failure) {
      error = Status.fromThrowable(failure).getCode();
      description = Status.fromThrowable(failure).getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
