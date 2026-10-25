package unit.net.firedevops.firemud.entitymanagement.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import net.firedevops.firemud.entitymanagement.config.PreseededActorAssignmentConfiguration;
import net.firedevops.firemud.entitymanagement.security.CanonicalPreseededActorAssignmentPeerInterceptor;
import net.firedevops.firemud.entitymanagement.service.PreseededActorAssignmentService;
import net.firedevops.firemud.entitymanagement.service.impl.CanonicalPreseededActorAssignmentGrpcService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.service.GrpcService;

class PreseededActorAssignmentIngressFactoryTest {
  @Test
  void explicitlyInvokedFactoriesCreateUnregisteredHandlerAndExactMethodPeerGuard() {
    var configuration = new PreseededActorAssignmentConfiguration();
    var assignmentService = mock(PreseededActorAssignmentService.class);

    var handler =
        configuration.canonicalPreseededActorAssignmentGrpcService("gameplay", assignmentService);
    var peerGuard = configuration.canonicalPreseededActorAssignmentPeerInterceptor("gameplay");

    assertThat(handler).isInstanceOf(CanonicalPreseededActorAssignmentGrpcService.class);
    assertThat(peerGuard).isInstanceOf(CanonicalPreseededActorAssignmentPeerInterceptor.class);
    assertThat(CanonicalPreseededActorAssignmentPeerInterceptor.METHOD_NAME)
        .isEqualTo(
            "entity_management.v1.CanonicalPreseededActorAssignmentService/AssignPreseededActor");
    assertThat(PreseededActorAssignmentConfiguration.class.isAnnotationPresent(Configuration.class))
        .isFalse();
    assertThat(handler.getClass().isAnnotationPresent(GrpcService.class)).isFalse();
    assertThat(
            java.util.Arrays.stream(
                    PreseededActorAssignmentConfiguration.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(Bean.class)))
        .isFalse();
  }
}
