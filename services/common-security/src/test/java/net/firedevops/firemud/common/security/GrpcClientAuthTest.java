package net.firedevops.firemud.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Empty;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.AbstractStub;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcClientAuthTest {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final JwtUtil jwtUtil = new JwtUtil("testsecretkeytestsecretkeytest1234", 60_000L);
  private final RuntimeIdentity runtimeIdentity =
      new RuntimeIdentity(
          "game-logic-service", "gl-1", "localhost", Instant.now(), "1.0.0", "abc123", "local");

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  @Test
  void attachUsesCurrentSessionContextPerCall() {
    CapturingChannel channel = new CapturingChannel();
    TestStub stub = new TestStub(channel, CallOptions.DEFAULT);
    TestStub customized = GrpcClientAuth.attach(stub, jwtUtil, runtimeIdentity);

    SessionContext.setContext("1", List.of("player"), Map.of());
    customized.invoke();
    String firstToken = bearerToken(channel.lastAuthorization());
    assertThat(jwtUtil.parseToken(firstToken).getPayload().getSubject()).isEqualTo("1");

    SessionContext.setContext("2", List.of("player"), Map.of());
    customized.invoke();
    String secondToken = bearerToken(channel.lastAuthorization());
    assertThat(jwtUtil.parseToken(secondToken).getPayload().getSubject()).isEqualTo("2");
    assertThat(secondToken).isNotEqualTo(firstToken);
  }

  @Test
  void attachInternalUsesFreshInternalTokenPerCall() {
    CapturingChannel channel = new CapturingChannel();
    TestStub stub = new TestStub(channel, CallOptions.DEFAULT);
    TestStub customized = GrpcClientAuth.attachInternal(stub, jwtUtil, runtimeIdentity);

    customized.invoke();
    String firstToken = bearerToken(channel.lastAuthorization());
    assertThat(jwtUtil.parseToken(firstToken).getPayload().getSubject())
        .isEqualTo("service:game-logic-service");
    assertThat(jwtUtil.parseToken(firstToken).getPayload().get("internalService", Boolean.class))
        .isTrue();

    customized.invoke();
    String secondToken = bearerToken(channel.lastAuthorization());
    assertThat(jwtUtil.parseToken(secondToken).getPayload().getSubject())
        .isEqualTo("service:game-logic-service");
    assertThat(jwtUtil.parseToken(secondToken).getPayload().get("internalService", Boolean.class))
        .isTrue();
  }

  @Test
  void attachRejectsMalformedCurrentAccountClaim() {
    CapturingChannel channel = new CapturingChannel();
    TestStub stub = new TestStub(channel, CallOptions.DEFAULT);
    TestStub customized = GrpcClientAuth.attach(stub, jwtUtil, runtimeIdentity);

    SessionContext.setContext("not-a-long", List.of("player"), Map.of());

    assertThrows(IllegalArgumentException.class, customized::invoke);
    assertThat(channel.lastAuthorization()).isNull();
  }

  private static String bearerToken(String header) {
    assertThat(header).startsWith("Bearer ");
    return header.substring(7);
  }

  private static final MethodDescriptor<Empty, Empty> METHOD =
      MethodDescriptor.<Empty, Empty>newBuilder()
          .setFullMethodName("demo.Service/Ping")
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .build();

  private static final class TestStub extends AbstractStub<TestStub> {
    private TestStub(Channel channel, CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected TestStub build(Channel channel, CallOptions callOptions) {
      return new TestStub(channel, callOptions);
    }

    private void invoke() {
      ClientCall<Empty, Empty> call = getChannel().newCall(METHOD, getCallOptions());
      call.start(new ClientCall.Listener<>() {}, new Metadata());
      call.sendMessage(Empty.getDefaultInstance());
      call.halfClose();
      call.request(1);
    }
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
}
