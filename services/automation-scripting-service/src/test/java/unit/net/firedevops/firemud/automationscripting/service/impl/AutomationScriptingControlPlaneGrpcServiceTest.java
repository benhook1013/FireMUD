package net.firedevops.firemud.automationscripting.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionRequest;
import net.firedevops.firemud.automationscripting.v1.GetScriptEventDefinitionResponse;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsRequest;
import net.firedevops.firemud.automationscripting.v1.ListScriptEventDefinitionsResponse;
import net.firedevops.firemud.common.security.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AutomationScriptingControlPlaneGrpcServiceTest {
  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void getsBuiltInEventDefinition() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(new BuiltInScriptEventRegistryService());
    AtomicReference<GetScriptEventDefinitionResponse> ref = new AtomicReference<>();

    service.getScriptEventDefinition(
        GetScriptEventDefinitionRequest.newBuilder().setEventType("onCommand").build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDefinition().getEventType()).isEqualTo("onCommand");
    assertThat(ref.get().getDefinition().getSnapshotAuthority())
        .isEqualTo("PRODUCER_SUPPLIED_TOKEN");
  }

  @Test
  void listsBuiltInEventDefinitionsByOwner() {
    SessionContext.setContext("1", List.of("platformAdmin"), Map.of());
    AutomationScriptingControlPlaneGrpcService service =
        new AutomationScriptingControlPlaneGrpcService(new BuiltInScriptEventRegistryService());
    AtomicReference<ListScriptEventDefinitionsResponse> ref = new AtomicReference<>();

    service.listScriptEventDefinitions(
        ListScriptEventDefinitionsRequest.newBuilder()
            .setOwnerService("automation-scripting-service")
            .build(),
        observer(ref));

    assertThat(ref.get().hasError()).isFalse();
    assertThat(ref.get().getDefinitionsList())
        .extracting(definition -> definition.getEventType())
        .contains("onLoad", "onInterval", "onTimerExpire");
  }

  private static <T> StreamObserver<T> observer(AtomicReference<T> ref) {
    return new StreamObserver<>() {
      @Override
      public void onNext(T value) {
        ref.set(value);
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {}
    };
  }
}
