package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Server;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorldPublishedAdmissionSourceCleanupTest {
  @Test
  void successfulBodyStopsServerAndChecksLatePeerDiagnostic() throws Exception {
    var server = stoppedServer();
    var peer = new AtomicReference<String>();
    Throwable result =
        catchThrowable(
            () -> {
              try (var peerCheck = WorldPublishedAdmissionSourceComposition.checkPeerOnClose(peer);
                  var lifetime = WorldPublishedAdmissionSourceComposition.stopOnClose(server)) {
                peer.set("wrong authenticated peer");
              }
            });

    assertThat(result)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("wrong authenticated peer");
    verify(server).shutdownNow();
    verify(server).awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void primaryFailureRetainsPeerDiagnostic() throws Exception {
    var server = stoppedServer();
    var primary = new AssertionError("admission failed");
    Throwable result = run(server, "wrong authenticated peer", primary);

    assertThat(result).isSameAs(primary);
    assertThat(result.getSuppressed()).hasSize(1);
    assertThat(result.getSuppressed()[0])
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("published policy source peer")
        .hasMessageContaining("wrong authenticated peer");
  }

  @Test
  void primaryFailureRetainsCleanupFailureAndPeerDiagnostic() throws Exception {
    var server = stoppedServer();
    var cleanup = new InterruptedException("cleanup interrupted");
    when(server.awaitTermination(5, TimeUnit.SECONDS)).thenThrow(cleanup);
    var primary = new AssertionError("admission failed");
    Throwable result = run(server, "wrong authenticated peer", primary);

    assertThat(result).isSameAs(primary);
    assertThat(result.getSuppressed()).hasSize(2);
    assertThat(result.getSuppressed()[0]).isSameAs(cleanup);
    assertThat(result.getSuppressed()[1]).hasMessageContaining("wrong authenticated peer");
  }

  @Test
  void cleanupFailureRemainsPrimaryWhenBodySucceeds() throws Exception {
    var server = stoppedServer();
    var cleanup = new IllegalStateException("shutdown failed");
    when(server.shutdownNow()).thenThrow(cleanup);
    Throwable result = run(server, "wrong authenticated peer", null);

    assertThat(result).isSameAs(cleanup);
    assertThat(result.getSuppressed()).hasSize(1);
    assertThat(result.getSuppressed()[0]).hasMessageContaining("wrong authenticated peer");
  }

  @Test
  void unsuccessfulTerminationCannotReplacePrimaryFailure() throws Exception {
    var server = stoppedServer();
    when(server.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(false);
    var primary = new AssertionError("admission failed");
    Throwable result = run(server, null, primary);

    assertThat(result).isSameAs(primary);
    assertThat(result.getSuppressed()).singleElement().isInstanceOf(AssertionError.class);
  }

  @Test
  void successfulBodyAndCleanupDoNotFail() throws Exception {
    var server = stoppedServer();
    assertThat(run(server, null, null)).isNull();
    verify(server).shutdownNow();
    verify(server).awaitTermination(5, TimeUnit.SECONDS);
  }

  private static Server stoppedServer() throws InterruptedException {
    var server = mock(Server.class);
    when(server.awaitTermination(5, TimeUnit.SECONDS)).thenReturn(true);
    return server;
  }

  private static Throwable run(Server server, String peerFailure, AssertionError primary) {
    var peer = new AtomicReference<>(peerFailure);
    return catchThrowable(
        () -> {
          try (var peerCheck = WorldPublishedAdmissionSourceComposition.checkPeerOnClose(peer);
              var lifetime = WorldPublishedAdmissionSourceComposition.stopOnClose(server)) {
            if (primary != null) {
              throw primary;
            }
          }
        });
  }
}
