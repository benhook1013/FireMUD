package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.accountservice.service.exception.AuthenticationException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamesession.v1.GameSessionServiceGrpc;
import net.firedevops.firemud.gamesession.v1.GetAdmissionPointerResponse;
import net.firedevops.firemud.gamesession.v1.ListGameplayRealmsResponse;
import net.firedevops.firemud.gamesession.v1.ListGameplayWorldsResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GameSessionClientTest {

  @Test
  void listGameplayWorldsAppliesDeadlineAndMapsUnavailable() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    StatusRuntimeException cause =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("routing down"));
    when(stub.listGameplayWorlds(any())).thenThrow(cause);
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(AuthenticationException.class, client::listGameplayWorlds);

    assertUnavailable(failure, cause);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void listGameplayRealmsAppliesDeadlineAndMapsDeadlineExceeded() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    StatusRuntimeException cause =
        new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("routing timed out"));
    when(stub.listGameplayRealms(any())).thenThrow(cause);
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(AuthenticationException.class, () -> client.listGameplayRealms("demo"));

    assertUnavailable(failure, cause);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void listGameplayWorldsMapsRetryableApplicationFailuresToUnavailable(String code)
      throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.listGameplayWorlds(any()))
        .thenReturn(
            ListGameplayWorldsResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode(code).setMessage("routing failed"))
                .build());
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(AuthenticationException.class, client::listGameplayWorlds);

    assertUnavailable(failure);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @ParameterizedTest
  @ValueSource(strings = {"INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED"})
  void listGameplayRealmsMapsRetryableApplicationFailuresToUnavailable(String code)
      throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.listGameplayRealms(any()))
        .thenReturn(
            ListGameplayRealmsResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode(code).setMessage("routing failed"))
                .build());
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(AuthenticationException.class, () -> client.listGameplayRealms("demo"));

    assertUnavailable(failure);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void getAdmissionPointerAppliesDeadlineAndMapsUnavailable() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    StatusRuntimeException cause =
        new StatusRuntimeException(Status.UNAVAILABLE.withDescription("pointer authority down"));
    when(stub.getAdmissionPointer(any())).thenThrow(cause);
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(
            AuthenticationException.class, () -> client.getAdmissionPointer(7L, "demo", "live"));

    assertUnavailable(failure, cause);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void getAdmissionPointerMapsInternalApplicationFailureToUnavailable() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.getAdmissionPointer(any()))
        .thenReturn(
            GetAdmissionPointerResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("INTERNAL").setMessage("store failed"))
                .build());
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(
            AuthenticationException.class, () -> client.getAdmissionPointer(7L, "demo", "live"));

    assertThat(failure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(failure.getMessage())
        .isEqualTo("Gameplay routing authority unavailable; retry later");
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void getAdmissionPointerPreservesInvalidArgumentAsDomainFailure() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.getAdmissionPointer(any()))
        .thenReturn(
            GetAdmissionPointerResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("Unknown gameplay realm selection"))
                .build());
    GameSessionClient client = newClient(stub);

    AuthenticationException failure =
        assertThrows(
            AuthenticationException.class, () -> client.getAdmissionPointer(7L, "demo", "missing"));

    assertThat(failure.getCode()).isEqualTo("ADMISSION_POINTER_UNAVAILABLE");
    assertThat(failure.getMessage())
        .isEqualTo(
            "Selected gameplay realm is no longer admissible; rerun realm discovery before retrying gameplay entry");
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void reachableResponseErrorRemainsDomainFailure() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.listGameplayWorlds(any()))
        .thenReturn(
            ListGameplayWorldsResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage("bad"))
                .build());
    GameSessionClient client = newClient(stub);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, client::listGameplayWorlds);

    assertThat(failure).hasMessage("Gameplay world discovery failed: INVALID_ARGUMENT");
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void reachableAdmissionPointerUnavailableResponsePropagatesAsDomainFailure() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.listGameplayWorlds(any()))
        .thenReturn(
            ListGameplayWorldsResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("ADMISSION_POINTER_UNAVAILABLE")
                        .setMessage("incomplete authority"))
                .build());
    GameSessionClient client = newClient(stub);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, client::listGameplayWorlds);

    assertThat(failure)
        .hasMessage("Gameplay world discovery failed: ADMISSION_POINTER_UNAVAILABLE");
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void gameplayRealmDiscoveryPreservesNonRetryableApplicationError() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    when(stub.listGameplayRealms(any()))
        .thenReturn(
            ListGameplayRealmsResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode("INVALID_ARGUMENT").setMessage("bad"))
                .build());
    GameSessionClient client = newClient(stub);

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> client.listGameplayRealms("demo"));

    assertThat(failure).hasMessage("Gameplay realm discovery failed: INVALID_ARGUMENT");
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  @Test
  void nonRetryableTransportStatusRetainsTransportClassification() throws Exception {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub = mockStub();
    StatusRuntimeException cause =
        new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("wrong workload"));
    when(stub.listGameplayWorlds(any())).thenThrow(cause);
    GameSessionClient client = newClient(stub);

    StatusRuntimeException failure =
        assertThrows(StatusRuntimeException.class, client::listGameplayWorlds);

    assertThat(failure).isSameAs(cause);
    verify(stub).withDeadlineAfter(5L, TimeUnit.SECONDS);
  }

  private static void assertUnavailable(AuthenticationException failure, Throwable cause) {
    assertThat(failure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(failure.getMessage())
        .isEqualTo("Gameplay routing authority unavailable; retry later");
    assertThat(failure).hasCause(cause);
  }

  private static void assertUnavailable(AuthenticationException failure) {
    assertThat(failure.getCode()).isEqualTo("AUTH_UNAVAILABLE");
    assertThat(failure.getMessage())
        .isEqualTo("Gameplay routing authority unavailable; retry later");
  }

  private static GameSessionClient newClient(
      GameSessionServiceGrpc.GameSessionServiceBlockingStub stub) throws Exception {
    GameSessionClient client =
        new GameSessionClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractReloadingBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static GameSessionServiceGrpc.GameSessionServiceBlockingStub mockStub() {
    GameSessionServiceGrpc.GameSessionServiceBlockingStub stub =
        mock(GameSessionServiceGrpc.GameSessionServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    return stub;
  }
}
