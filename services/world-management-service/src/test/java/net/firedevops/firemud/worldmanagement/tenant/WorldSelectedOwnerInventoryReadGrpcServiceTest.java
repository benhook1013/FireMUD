package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.v1.ReadSelectedOwnerWorldInventoryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Synthetic receiver status tests; they do not establish mTLS or owner-storage proof. */
class WorldSelectedOwnerInventoryReadGrpcServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final String ACCOUNT_FAILURE_DESCRIPTION =
      "Account did not confirm the exact selected-owner World closure read";

  @AfterEach
  void clearRequestContext() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void boundsDownstreamStatusesForBothAuthenticatedOwners() {
    var statuses =
        List.of(
            Status.FAILED_PRECONDITION,
            Status.UNAUTHENTICATED,
            Status.PERMISSION_DENIED,
            Status.DEADLINE_EXCEEDED,
            Status.INTERNAL);
    for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
      var request =
          SelectedOwnerWorldInventoryReadGrpcCodec.toRequest(
              WorldSelectedOwnerInventoryTestFixtures.fixture(owner).request());
      for (Status downstreamStatus : statuses) {
        var readService = mock(WorldSelectedOwnerInventoryReadService.class);
        var trailers = new Metadata();
        trailers.put(
            Metadata.Key.of("x-secret-detail", Metadata.ASCII_STRING_MARSHALLER),
            "secret trailer value");
        var downstream =
            downstreamStatus
                .withDescription("secret downstream detail")
                .withCause(new IllegalStateException("secret downstream cause"))
                .augmentDescription(" secret suffix")
                .asRuntimeException(trailers);
        when(readService.read(any())).thenThrow(downstream);
        var receiver = new WorldSelectedOwnerInventoryReadGrpcService(NAMESPACE, readService);
        var observer = new RecordingObserver();

        withPeer(
            peer(NAMESPACE, serviceName(owner)),
            () -> receiver.readSelectedOwnerWorldInventory(request, observer));

        Status mapped = Status.fromThrowable(observer.error);
        assertThat(mapped.getCode())
            .isEqualTo(
                downstreamStatus.getCode() == Status.Code.FAILED_PRECONDITION
                    ? Status.Code.FAILED_PRECONDITION
                    : Status.Code.UNAVAILABLE);
        assertThat(mapped.getDescription()).isEqualTo(ACCOUNT_FAILURE_DESCRIPTION);
        assertThat(mapped.getCause()).isNull();
        assertThat(((StatusRuntimeException) observer.error).getTrailers()).isNull();
        assertThat(observer.error).isNotSameAs(downstream);
        assertThat(observer.response).isNull();
        assertThat(observer.completed).isFalse();
      }
    }
  }

  private static String serviceName(Owner owner) {
    return owner == Owner.ENTITY_MANAGEMENT
        ? "entity-management-service"
        : "automation-scripting-service";
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return GrpcPeerIdentity.parseUri("spiffe://firemud/ns/" + namespace + "/sa/" + service)
        .orElseThrow();
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  private static final class RecordingObserver
      implements StreamObserver<ReadSelectedOwnerWorldInventoryResponse> {
    private ReadSelectedOwnerWorldInventoryResponse response;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(ReadSelectedOwnerWorldInventoryResponse value) {
      response = value;
    }

    @Override
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification =
            "The test collector retains the original throwable for identity and status assertions.")
    public void onError(Throwable failure) {
      error = failure;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
