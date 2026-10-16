package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.Outcome;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.WorldPublicationTerminalReadEvidence;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.worldmanagement.tenant.WorldGameDesignPublicationTerminalVerifierTest;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalReadService;
import net.firedevops.firemud.worldmanagement.tenant.WorldPublicationTerminalRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldPublicationTerminalReadServiceTest {
  @AfterEach
  void clearCallerContext() {
    SessionContext.clear();
  }

  @Test
  void rejectsMissingOrWrongAccountPeerBeforeInspectingRequestOrReadingStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalReadService(repository, "test");

    assertThatThrownBy(() -> service.read(null))
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .extracting(failure -> Status.fromThrowable(failure).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);

    var wrongPeer = peer("world-management-service", "test");
    var context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, wrongPeer);
    var previous = context.attach();
    try {
      assertThatThrownBy(() -> service.read(null))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .extracting(failure -> Status.fromThrowable(failure).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      context.detach(previous);
    }

    verifyNoInteractions(repository);
  }

  @Test
  void rejectsAuthenticatedCallerContextBeforeInspectingRequestOrReadingStorage() {
    var repository = mock(WorldPublicationTerminalRepository.class);
    var service = new WorldPublicationTerminalReadService(repository, "test");
    SessionContext.setContext("authenticated-user", java.util.List.of(), java.util.Map.of());

    var context =
        Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("account-service", "test"));
    var previous = context.attach();
    try {
      assertThatThrownBy(() -> service.read(null))
          .isInstanceOf(io.grpc.StatusRuntimeException.class)
          .extracting(failure -> Status.fromThrowable(failure).getCode())
          .isEqualTo(Status.Code.PERMISSION_DENIED);
    } finally {
      context.detach(previous);
      SessionContext.clear();
    }

    verifyNoInteractions(repository);
  }

  @Test
  void classifiesTransactionCreationAndTimeoutFailuresAsUnavailable() throws Exception {
    var operation = WorldGameDesignPublicationTerminalVerifierTest.operation();
    var terminal =
        WorldGameDesignPublicationTerminalVerifierTest.terminal(operation, Outcome.PUBLISHED);
    var request =
        WorldPublicationTerminalReadEvidence.Request.create(
            "test", operation.canonicalBytes(), terminal.canonicalBytes());

    for (RuntimeException storageFailure :
        java.util.List.of(
            new org.springframework.transaction.CannotCreateTransactionException(
                "sensitive transaction setup detail"),
            new org.springframework.transaction.TransactionTimedOutException(
                "sensitive transaction timeout detail"))) {
      var repository = mock(WorldPublicationTerminalRepository.class);
      when(repository.readCommitted(any())).thenThrow(storageFailure);
      var service = new WorldPublicationTerminalReadService(repository, "test");
      var context =
          Context.current()
              .withValue(GrpcPeerIdentity.CONTEXT_KEY, peer("account-service", "test"));
      var previous = context.attach();
      try {
        assertThatThrownBy(() -> service.read(request))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure -> {
                  var status = Status.fromThrowable(failure);
                  assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                  assertThat(status.getDescription())
                      .isEqualTo("World publication terminal owner read is unavailable")
                      .doesNotContain("sensitive");
                });
      } finally {
        context.detach(previous);
      }
    }
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }
}
