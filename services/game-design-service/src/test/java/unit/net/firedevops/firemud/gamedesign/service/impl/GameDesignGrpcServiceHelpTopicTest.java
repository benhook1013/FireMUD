package net.firedevops.firemud.gamedesign.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.gamedesign.dto.HelpTopicDto;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.v1.HelpTopic;
import net.firedevops.firemud.gamedesign.v1.HelpTopicScope;
import net.firedevops.firemud.gamedesign.v1.PutHelpTopicRequest;
import net.firedevops.firemud.gamedesign.v1.PutHelpTopicResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveHelpTopicRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveHelpTopicResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameDesignGrpcServiceHelpTopicTest {
  private final GameAuthoredHelpTopicService helpTopicService =
      Mockito.mock(GameAuthoredHelpTopicService.class);
  private final GameDesignGrpcService grpcService =
      new GameDesignGrpcService(
          Mockito.mock(PingService.class),
          Mockito.mock(RevisionService.class),
          Mockito.mock(VersionService.class),
          Mockito.mock(LaunchDescriptorService.class),
          Mockito.mock(TemplateRemapSetService.class),
          Mockito.mock(VersionAssetArtifactService.class),
          Mockito.mock(SettingsAuthorityService.class),
          helpTopicService,
          new TemporalVersionPublishWorkflowMetadataResolver(Optional.empty(), Optional.empty()),
          new SimpleMeterRegistry());

  @AfterEach
  void tearDown() {
    SessionContext.clear();
  }

  @Test
  void resolveAllowsInternalServiceAndReturnsPublishedTopic() {
    SessionContext.setContext(null, List.of(), Map.of(), true, "game-session-service", "gs-1");
    when(helpTopicService.resolvePublishedTopic("42", 7L, "move"))
        .thenReturn(
            Optional.of(
                new HelpTopicDto("movement", "Movement", "Walk north.", List.of("move"), true)));
    CapturingObserver<ResolveHelpTopicResponse> observer = new CapturingObserver<>();

    grpcService.resolveHelpTopic(
        ResolveHelpTopicRequest.newBuilder()
            .setScope(HelpTopicScope.newBuilder().setTenantId("42").setGameTemplateId(7L))
            .setTopic("move")
            .build(),
        observer);

    assertThat(observer.value.hasError()).isFalse();
    assertThat(observer.value.getHelpTopic().getCanonicalTopicId()).isEqualTo("movement");
    verify(helpTopicService).resolvePublishedTopic("42", 7L, "move");
  }

  @Test
  void putRequiresAdminAuthority() {
    SessionContext.setContext("1", List.of("player"), Map.of());
    CapturingObserver<PutHelpTopicResponse> observer = new CapturingObserver<>();

    grpcService.putHelpTopic(
        PutHelpTopicRequest.newBuilder()
            .setScope(HelpTopicScope.newBuilder().setTenantId("42").setGameTemplateId(7L))
            .setHelpTopic(
                HelpTopic.newBuilder()
                    .setCanonicalTopicId("movement")
                    .setTitle("Movement")
                    .setBody("Walk north.")
                    .build())
            .build(),
        observer);

    assertThat(observer.value.getError().getCode()).isEqualTo("PERMISSION_DENIED");
  }

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private T value;

    @Override
    public void onNext(T value) {
      this.value = value;
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Unexpected gRPC error", throwable);
    }

    @Override
    public void onCompleted() {}
  }
}
