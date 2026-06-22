package unit.net.firedevops.firemud.tcpproxy.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.AbstractStub;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.tcpproxy.config.InternalGrpcClientAuthConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InternalGrpcClientAuthConfigTest {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final MethodDescriptor<Empty, Empty> METHOD =
      MethodDescriptor.<Empty, Empty>newBuilder()
          .setFullMethodName("demo.Service/Ping")
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .build();

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
                      "tcp-proxy-service",
                      "tp-1",
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
          CapturingChannel channel = new CapturingChannel();
          CapturingStub stub = new CapturingStub(channel, CallOptions.DEFAULT);

          CapturingStub customized = customizer.customize(stub);

          assertThat(customized).isNotSameAs(stub);
          customized.invoke();
          String authorization = channel.lastAuthorization();
          assertThat(authorization).startsWith("Bearer ");
          String token = authorization.substring(7);
          String payloadJson = jwtPayload(token);
          assertThat(payloadJson).contains("\"sub\":\"service:tcp-proxy-service\"");
          assertThat(payloadJson).contains("\"internalService\":true");
        });
  }

  private static String jwtPayload(String token) {
    String[] parts = token.split("\\.");
    return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
  }

  private static final class CapturingChannel extends Channel {
    private String lastAuthorization;

    @Override
    public String authority() {
      return "test-authority";
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      return new ClientCall<>() {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          lastAuthorization = headers.get(AUTH_HEADER);
        }

        @Override
        public void request(int numMessages) {}

        @Override
        public void cancel(String message, Throwable cause) {}

        @Override
        public void halfClose() {}

        @Override
        public void sendMessage(ReqT message) {}
      };
    }

    private String lastAuthorization() {
      return lastAuthorization;
    }
  }

  private static final class CapturingStub extends AbstractStub<CapturingStub> {
    private CapturingStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected CapturingStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new CapturingStub(channel, callOptions);
    }

    private void invoke() {
      ClientCall<Empty, Empty> call = getChannel().newCall(METHOD, getCallOptions());
      call.start(new ClientCall.Listener<>() {}, new Metadata());
      call.sendMessage(Empty.getDefaultInstance());
      call.halfClose();
      call.request(1);
    }
  }
}
