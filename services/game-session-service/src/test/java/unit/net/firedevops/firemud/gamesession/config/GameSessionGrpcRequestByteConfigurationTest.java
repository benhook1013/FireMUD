package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Empty;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.TlsServerCredentials;
import io.grpc.protobuf.ProtoUtils;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.config.CommonGrpcRequestBoundsAutoConfiguration;
import net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory;
import net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.gamesession.config.GameSessionGrpcRequestByteConfiguration;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.ShadedNettyGrpcServerFactory;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcService;

class GameSessionGrpcRequestByteConfigurationTest {
  @Test
  void onlyGeneratedAcknowledgementAndConfirmationDescriptorsReceiveLargeAdmission() {
    var policy = new GameSessionGrpcRequestByteConfiguration().gameSessionGrpcRequestBytePolicy();
    assertThat(policy.transportMaximumBytes())
        .isEqualTo(CanonicalGameplayRosterRetirementEvidence.MAX_PLAYER_CREATED_CONFIRMATION_BYTES);
    var factory =
        new BoundedGrpcServerFactory(
            "127.0.0.1:0",
            List.of(),
            null,
            null,
            TlsServerCredentials.ClientAuth.NONE,
            policy,
            policy.transportMaximumBytes());
    Map<String, Integer> parsedMessages = new HashMap<>();
    factory.addService(
        recordOriginalMessages(
            new CanonicalGameplayRosterOwnerReadServiceGrpc
                .CanonicalGameplayRosterOwnerReadServiceImplBase() {}.bindService(),
            parsedMessages));
    var ordinary =
        MethodDescriptor.<Empty, Empty>newBuilder()
            .setFullMethodName("test.Unrelated/Read")
            .setType(MethodDescriptor.MethodType.UNARY)
            .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
            .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
            .build();
    factory.addService(
        recordOriginalMessages(
            ServerServiceDefinition.builder("test.Unrelated")
                .addMethod(
                    ordinary,
                    io.grpc.stub.ServerCalls.asyncUnaryCall(
                        (request, response) -> {
                          response.onNext(Empty.getDefaultInstance());
                          response.onCompleted();
                        }))
                .build(),
            parsedMessages));
    Server server = factory.createServer();
    try {
      // Well-formed protobuf unknown fields exercise parsing without domain authorization.
      byte[] exact = rawRequest(GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
      byte[] oversized = rawRequest(GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES + 1);
      for (var service : server.getServices()) {
        for (var method : service.getMethods()) {
          String name = method.getMethodDescriptor().getFullMethodName();
          boolean retirementException =
              name.equals(
                      CanonicalGameplayRosterOwnerReadServiceGrpc
                          .getAcknowledgeCanonicalGameplayRosterRetirementMethod()
                          .getFullMethodName())
                  || name.equals(
                      CanonicalGameplayRosterOwnerReadServiceGrpc
                          .getConfirmCanonicalGameplayRosterRetirementAcknowledgementMethod()
                          .getFullMethodName());
          // The generated base reports UNIMPLEMENTED only after parsing and unary dispatch.
          Status.Code admittedOutcome =
              name.equals(ordinary.getFullMethodName())
                  ? Status.Code.OK
                  : Status.Code.UNIMPLEMENTED;
          assertThat(dispatch(method, exact).getCode()).isEqualTo(admittedOutcome);
          assertThat(parsedMessages.get(name)).isEqualTo(1);
          assertThat(dispatch(method, oversized).getCode())
              .isEqualTo(retirementException ? admittedOutcome : Status.Code.RESOURCE_EXHAUSTED);
          assertThat(parsedMessages.get(name)).isEqualTo(retirementException ? 2 : 1);
          if (retirementException) {
            int maximum =
                name.equals(
                        CanonicalGameplayRosterOwnerReadServiceGrpc
                            .getAcknowledgeCanonicalGameplayRosterRetirementMethod()
                            .getFullMethodName())
                    ? CanonicalGameplayRosterRetirementEvidence.MAX_PLAYER_CREATED_REQUEST_BYTES
                    : CanonicalGameplayRosterRetirementEvidence
                        .MAX_PLAYER_CREATED_CONFIRMATION_BYTES;
            var abovePurposeMaximum = new OversizedRawStream(maximum + 1);
            assertThat(dispatch(method, abovePurposeMaximum).getCode())
                .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
            assertThat(abovePurposeMaximum.consumed).isEqualTo(maximum + 1);
            assertThat(parsedMessages.get(name)).isEqualTo(2);
          }
        }
      }
    } finally {
      server.shutdownNow();
    }
  }

  @Test
  void activeGameSessionPolicyFailsStartupWithoutSharedGuardOrWithPlainFactory() {
    var runner = productionRunner(false);
    runner.run(
        context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(
                  org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        });
    runner
        .withBean(
            GrpcServerFactory.class,
            () ->
                new ShadedNettyGrpcServerFactory(
                    "127.0.0.1:0", List.of(), null, null, TlsServerCredentials.ClientAuth.NONE))
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasRootCauseInstanceOf(
                      org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
            });
  }

  @Test
  void realProductionDiscoverySatisfiesRequiredGameSessionGuardAndClosedPolicy() {
    productionRunner(true)
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(GrpcServerFactory.class);
              assertThat(context.getBean(GrpcServerFactory.class))
                  .isExactlyInstanceOf(BoundedGrpcServerFactory.class);
              assertThat(context.getBean(GrpcServerLifecycle.class).isRunning()).isTrue();
              assertThat(context.getBean(GrpcRequestBytePolicy.class).transportMaximumBytes())
                  .isEqualTo(
                      CanonicalGameplayRosterRetirementEvidence
                          .MAX_PLAYER_CREATED_CONFIRMATION_BYTES);
            });
  }

  private static byte[] rawRequest(int size) {
    int payloadSize =
        size
            - CodedOutputStream.computeTagSize(9999)
            - CodedOutputStream.computeUInt32SizeNoTag(size);
    byte[] raw =
        Empty.newBuilder()
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        9999,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFrom(new byte[payloadSize]))
                            .build())
                    .build())
            .build()
            .toByteArray();
    assertThat(raw).hasSize(size);
    return raw;
  }

  private static ServerServiceDefinition recordOriginalMessages(
      ServerServiceDefinition service, Map<String, Integer> messages) {
    return ServerInterceptors.intercept(
        service,
        new ServerInterceptor() {
          @Override
          public <Q, R> ServerCall.Listener<Q> interceptCall(
              ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                next.startCall(call, headers)) {
              @Override
              public void onMessage(Q message) {
                messages.merge(call.getMethodDescriptor().getFullMethodName(), 1, Integer::sum);
                super.onMessage(message);
              }
            };
          }
        });
  }

  private static Status dispatch(ServerMethodDefinition<?, ?> definition, byte[] raw) {
    return dispatch(definition, new ByteArrayInputStream(raw));
  }

  @SuppressWarnings("unchecked")
  private static Status dispatch(ServerMethodDefinition<?, ?> definition, InputStream raw) {
    // The bounded production factory's public adapter uses InputStream in both directions.
    var method = (ServerMethodDefinition<InputStream, InputStream>) definition;
    var call = new RecordingCall(method.getMethodDescriptor());
    var listener = method.getServerCallHandler().startCall(call, new Metadata());
    listener.onMessage(method.getMethodDescriptor().parseRequest(raw));
    listener.onHalfClose();
    listener.onComplete();
    assertThat(call.closed).hasSize(1);
    return call.closed.getFirst();
  }

  /**
   * Finite unknown-length source; the guard's real readNBytes still allocates its admission buffer.
   */
  private static final class OversizedRawStream extends InputStream {
    private int remaining;
    private int consumed;

    private OversizedRawStream(int size) {
      remaining = size;
    }

    @Override
    public int read() {
      if (remaining == 0) {
        return -1;
      }
      remaining--;
      consumed++;
      return 255;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (length == 0) {
        return 0;
      }
      if (remaining == 0) {
        return -1;
      }
      int count = Math.min(length, remaining);
      Arrays.fill(buffer, offset, offset + count, (byte) 255);
      remaining -= count;
      consumed += count;
      return count;
    }

    @Override
    public int available() {
      return 0;
    }
  }

  private static final class RecordingCall extends ServerCall<InputStream, InputStream> {
    private final MethodDescriptor<InputStream, InputStream> method;
    private final List<Status> closed = new ArrayList<>();

    private RecordingCall(MethodDescriptor<InputStream, InputStream> method) {
      this.method = method;
    }

    @Override
    public void request(int count) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(InputStream response) {}

    @Override
    public void close(Status status, Metadata trailers) {
      closed.add(status);
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<InputStream, InputStream> getMethodDescriptor() {
      return method;
    }
  }

  private static ApplicationContextRunner productionRunner(boolean includeGuard) {
    var runner =
        new ApplicationContextRunner()
            .withUserConfiguration(GameSessionGrpcRequestByteConfiguration.class)
            .withBean(TestRosterService.class, TestRosterService::new)
            .withPropertyValues(
                "spring.grpc.server.port=0", "spring.grpc.server.ssl.enabled=false");
    return includeGuard
        ? runner.withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonGrpcRequestBoundsAutoConfiguration.class,
                GrpcServerFactoryAutoConfiguration.class,
                GrpcServerAutoConfiguration.class))
        : runner.withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                GrpcServerFactoryAutoConfiguration.class,
                GrpcServerAutoConfiguration.class));
  }

  @GrpcService
  static class TestRosterService
      extends CanonicalGameplayRosterOwnerReadServiceGrpc
          .CanonicalGameplayRosterOwnerReadServiceImplBase {}
}
