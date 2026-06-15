package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.stub.AbstractStub;
import java.time.Instant;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.gamesession.config.InternalGrpcClientAuthConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InternalGrpcClientAuthConfigTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  CommonSecurityAutoConfiguration.class,
                  InternalGrpcClientAuthConfig.class))
          .withBean(
              RuntimeIdentity.class,
              () ->
                  new RuntimeIdentity(
                      "game-session-service",
                      "gs-1",
                      "localhost",
                      Instant.now(),
                      "1.0.0",
                      "abc123",
                      "local"))
          .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234");

  @Test
  void serviceOverridesSharedStubCustomizerWithInternalOnlyBehavior() {
    contextRunner.run(
        ctx -> {
          BlockingGrpcStubCustomizer customizer = ctx.getBean(BlockingGrpcStubCustomizer.class);
          CapturingStub stub = new CapturingStub();

          assertThat(customizer.customize(stub)).isNotSameAs(stub);
        });
  }

  private static final class CapturingStub extends AbstractStub<CapturingStub> {
    private CapturingStub() {
      super(Mockito.mock(io.grpc.Channel.class), io.grpc.CallOptions.DEFAULT);
    }

    private CapturingStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected CapturingStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CapturingStub(channel, callOptions);
    }
  }
}
