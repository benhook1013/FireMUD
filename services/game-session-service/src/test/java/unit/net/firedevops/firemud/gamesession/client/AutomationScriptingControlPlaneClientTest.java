package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingControlPlaneServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.GetPluginStatusResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;

class AutomationScriptingControlPlaneClientTest {

  @Test
  void mapsTransportUnavailableToRetryableUnavailableResponse() throws Exception {
    AutomationScriptingControlPlaneServiceGrpc.AutomationScriptingControlPlaneServiceBlockingStub
        stub = stubThatThrows(Status.UNAVAILABLE.withDescription("automation down"));
    AutomationScriptingControlPlaneClient client = newClient(stub);

    GetPluginStatusResponse response = client.getPluginStatus(1L, 2L, "plugin-1");

    assertThat(response.getError().getCode()).isEqualTo("AUTOMATION_SCRIPTING_UNAVAILABLE");
  }

  @Test
  void propagatesNonRetryableGrpcStatus() throws Exception {
    StatusRuntimeException failure =
        new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("not allowed"));
    AutomationScriptingControlPlaneServiceGrpc.AutomationScriptingControlPlaneServiceBlockingStub
        stub = mockStub();
    when(stub.getPluginStatus(any())).thenThrow(failure);
    AutomationScriptingControlPlaneClient client = newClient(stub);

    assertThatThrownBy(() -> client.getPluginStatus(1L, 2L, "plugin-1")).isSameAs(failure);
  }

  @Test
  void propagatesProgrammingRuntimeExceptions() throws Exception {
    IllegalStateException failure = new IllegalStateException("stub misconfigured");
    AutomationScriptingControlPlaneServiceGrpc.AutomationScriptingControlPlaneServiceBlockingStub
        stub = mockStub();
    when(stub.getPluginStatus(any())).thenThrow(failure);
    AutomationScriptingControlPlaneClient client = newClient(stub);

    assertThatThrownBy(() -> client.getPluginStatus(1L, 2L, "plugin-1")).isSameAs(failure);
  }

  private static AutomationScriptingControlPlaneClient newClient(
      AutomationScriptingControlPlaneServiceGrpc.AutomationScriptingControlPlaneServiceBlockingStub
          stub)
      throws Exception {
    AutomationScriptingControlPlaneClient client =
        new AutomationScriptingControlPlaneClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static AutomationScriptingControlPlaneServiceGrpc
          .AutomationScriptingControlPlaneServiceBlockingStub
      stubThatThrows(Status status) {
    AutomationScriptingControlPlaneServiceGrpc.AutomationScriptingControlPlaneServiceBlockingStub
        stub = mockStub();
    when(stub.getPluginStatus(any())).thenThrow(new StatusRuntimeException(status));
    return stub;
  }

  private static AutomationScriptingControlPlaneServiceGrpc
          .AutomationScriptingControlPlaneServiceBlockingStub
      mockStub() {
    AutomationScriptingControlPlaneServiceGrpc.AutomationScriptingControlPlaneServiceBlockingStub
        stub =
            mock(
                AutomationScriptingControlPlaneServiceGrpc
                    .AutomationScriptingControlPlaneServiceBlockingStub.class);
    when(stub.withDeadlineAfter(250L, TimeUnit.MILLISECONDS)).thenReturn(stub);
    return stub;
  }
}
