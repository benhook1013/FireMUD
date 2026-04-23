package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.automationscripting.v1.AutomationScriptingServiceGrpc;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressRequest;
import net.firedevops.firemud.automationscripting.v1.ObserveRuntimeTickProgressResponse;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventRequest;
import net.firedevops.firemud.automationscripting.v1.TriggerScriptEventResponse;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AutomationScriptingClient
    extends AbstractBlockingGrpcClient<
        AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub> {
  private static final Logger LOG = LoggerFactory.getLogger(AutomationScriptingClient.class);
  private static final long CALL_DEADLINE_SECONDS = 3L;
  private static final long TICK_PROGRESS_DEADLINE_MILLIS = 250L;

  public AutomationScriptingClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, grpcClientProperties, channelFactory, stubCustomizer);
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getAutomationScriptingService();
  }

  @Override
  protected String defaultTarget() {
    return "automation-scripting-service:6565";
  }

  @Override
  protected AutomationScriptingServiceGrpc.AutomationScriptingServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        AutomationScriptingServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public TriggerScriptEventResponse triggerScriptEvent(TriggerScriptEventRequest request) {
    if (stub() == null) {
      return unavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
          .triggerScriptEvent(request);
    } catch (RuntimeException ex) {
      LOG.warn("Automation & Scripting triggerScriptEvent failed", ex);
      return unavailable();
    }
  }

  public ObserveRuntimeTickProgressResponse observeRuntimeTickProgress(
      ObserveRuntimeTickProgressRequest request) {
    if (stub() == null) {
      return tickProgressUnavailable();
    }
    try {
      return stub()
          .withDeadlineAfter(TICK_PROGRESS_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
          .observeRuntimeTickProgress(request);
    } catch (RuntimeException ex) {
      LOG.warn("Automation & Scripting observeRuntimeTickProgress failed", ex);
      return tickProgressUnavailable();
    }
  }

  private static TriggerScriptEventResponse unavailable() {
    return TriggerScriptEventResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("AUTOMATION_SCRIPTING_UNAVAILABLE")
                .setMessage("Automation & Scripting service unavailable"))
        .build();
  }

  private static ObserveRuntimeTickProgressResponse tickProgressUnavailable() {
    return ObserveRuntimeTickProgressResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("AUTOMATION_SCRIPTING_UNAVAILABLE")
                .setMessage("Automation & Scripting service unavailable"))
        .build();
  }
}
