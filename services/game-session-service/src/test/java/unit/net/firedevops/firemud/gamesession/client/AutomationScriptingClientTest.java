package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import org.junit.jupiter.api.Test;

class AutomationScriptingClientTest {

  @Test
  void mapsUnavailableGrpcStatusToUnavailableResponse() throws Exception {
    var stub = stubThatThrows(new StatusRuntimeException(Status.UNAVAILABLE));
    AutomationScriptingClient client = newClient(stub);

    TriggerScriptEventResponse response =
        client.triggerScriptEvent(TriggerScriptEventRequest.getDefaultInstance());

    assertThat(response.getError().getCode()).isEqualTo("AUTOMATION_SCRIPTING_UNAVAILABLE");
    verify(stub).withDeadlineAfter(250L, TimeUnit.MILLISECONDS);
  }

  @Test
  void mapsDeadlineExceededGrpcStatusToUnavailableResponse() throws Exception {
    var stub = stubThatThrows(new StatusRuntimeException(Status.DEADLINE_EXCEEDED));
    AutomationScriptingClient client = newClient(stub);

    TriggerScriptEventResponse response =
        client.triggerScriptEvent(TriggerScriptEventRequest.getDefaultInstance());

    assertThat(response.getError().getCode()).isEqualTo("AUTOMATION_SCRIPTING_UNAVAILABLE");
  }

  @Test
  void preservesInvalidArgumentGrpcStatus() throws Exception {
    StatusRuntimeException failure =
        new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("invalid event"));
    AutomationScriptingClient client = newClient(stubThatThrows(failure));

    assertThatThrownBy(
            () -> client.triggerScriptEvent(TriggerScriptEventRequest.getDefaultInstance()))
        .isSameAs(failure);
  }

  @Test
  void preservesPermissionDeniedGrpcStatus() throws Exception {
    StatusRuntimeException failure =
        new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("not allowed"));
    AutomationScriptingClient client = newClient(stubThatThrows(failure));

    assertThatThrownBy(
            () -> client.triggerScriptEvent(TriggerScriptEventRequest.getDefaultInstance()))
        .isSameAs(failure);
  }

  @Test
  void mapsOtherRuntimeFailuresToUnavailableResponse() throws Exception {
    var stub = stubThatThrows(new IllegalStateException("stub misconfigured"));
    AutomationScriptingClient client = newClient(stub);

    TriggerScriptEventResponse response =
        client.triggerScriptEvent(TriggerScriptEventRequest.getDefaultInstance());

    assertThat(response.getError().getCode()).isEqualTo("AUTOMATION_SCRIPTING_UNAVAILABLE");
  }

  private static AutomationScriptingClient newClient(
      AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub stub) throws Exception {
    AutomationScriptingClient client =
        new AutomationScriptingClient(
            new ServiceEndpointsProperties(),
            new CommonGrpcClientProperties(),
            mock(GrpcChannelFactory.class),
            BlockingGrpcStubCustomizer.noop());
    Field field = AbstractBlockingGrpcClient.class.getDeclaredField("stub");
    field.setAccessible(true);
    field.set(client, stub);
    return client;
  }

  private static AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub
      stubThatThrows(RuntimeException failure) {
    var stub = mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    when(stub.withDeadlineAfter(250L, TimeUnit.MILLISECONDS)).thenReturn(stub);
    when(stub.triggerScriptEvent(any())).thenThrow(failure);
    return stub;
  }
}
