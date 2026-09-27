package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
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
    GrpcChannelFactory channelFactory = mock(GrpcChannelFactory.class);
    when(channelFactory.buildChannel(anyString(), anyInt(), any(), anyBoolean()))
        .thenReturn(mock(ManagedChannel.class));
    TestAutomationScriptingClient client = new TestAutomationScriptingClient(channelFactory, stub);
    client.initForTest();
    return client;
  }

  private static AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub
      stubThatThrows(RuntimeException failure) {
    var stub = mock(AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub.class);
    when(stub.withDeadlineAfter(250L, TimeUnit.MILLISECONDS)).thenReturn(stub);
    when(stub.triggerScriptEvent(any())).thenThrow(failure);
    return stub;
  }

  private static final class TestAutomationScriptingClient extends AutomationScriptingClient {
    private final AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub testStub;

    private TestAutomationScriptingClient(
        GrpcChannelFactory channelFactory,
        AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub testStub) {
      super(
          new ServiceEndpointsProperties(),
          new CommonGrpcClientProperties(),
          channelFactory,
          BlockingGrpcStubCustomizer.noop());
      this.testStub = testStub;
    }

    private void initForTest() throws Exception {
      init();
    }

    @Override
    protected AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub buildStub(
        ManagedChannel channel) {
      return testStub;
    }
  }
}
