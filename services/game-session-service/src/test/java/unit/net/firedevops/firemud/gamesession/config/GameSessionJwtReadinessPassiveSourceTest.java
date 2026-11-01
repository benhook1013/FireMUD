package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.gamesession.service.impl.GameSessionJwtReadinessReceiverGrpcService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.grpc.server.service.GrpcService;

class GameSessionJwtReadinessPassiveSourceTest {
  @Test
  void ordinaryScanningDoesNotRegisterTheManuallyConstructibleReceiver() {
    var receiver = GameSessionJwtReadinessReceiverGrpcService.class;

    assertThat(AnnotatedElementUtils.hasAnnotation(receiver, GrpcService.class)).isFalse();
    var scanner = new ClassPathScanningCandidateComponentProvider(true);
    assertThat(scanner.findCandidateComponents(receiver.getPackageName()))
        .noneMatch(candidate -> receiver.getName().equals(candidate.getBeanClassName()));
  }
}
