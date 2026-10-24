package net.firedevops.firemud.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.config.CommonGrpcRequestBoundsAutoConfiguration;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterRetirementAcknowledgementRequest;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryCustomizer;
import org.springframework.boot.grpc.server.autoconfigure.GrpcServerProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.grpc.server.DefaultGrpcServerFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.GrpcServerFactory;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.DefaultGrpcServiceConfigurer;
import org.springframework.grpc.server.service.DefaultGrpcServiceDiscoverer;
import org.springframework.grpc.server.service.GrpcService;
import org.springframework.grpc.server.service.GrpcServiceConfigurer;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;

class CommonGrpcRequestBoundsAutoConfigurationTest {
  private static final MethodDescriptor<Empty, Empty> READ =
      MethodDescriptor.<Empty, Empty>newBuilder()
          .setFullMethodName("test.Discovered/Read")
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .setResponseMarshaller(ProtoUtils.marshaller(Empty.getDefaultInstance()))
          .build();
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  CommonGrpcRequestBoundsAutoConfiguration.class,
                  GrpcServerFactoryAutoConfiguration.class,
                  GrpcServerAutoConfiguration.class))
          .withPropertyValues("spring.grpc.server.port=0", "spring.grpc.server.ssl.enabled=false")
          .withUserConfiguration(DiscoveredServiceConfiguration.class);

  @Test
  void productionAutoConfigurationDiscoversRegistersAndCallsRealServiceWithOrderedInterceptors() {
    runner.run(
        context -> {
          assertThat(context)
              .hasNotFailed()
              .hasSingleBean(GrpcServerFactory.class)
              .hasSingleBean(GrpcServerLifecycle.class);
          assertThat(context.getBean(GrpcServiceDiscoverer.class))
              .isExactlyInstanceOf(DefaultGrpcServiceDiscoverer.class);
          assertThat(context.getBean(GrpcServiceConfigurer.class))
              .isExactlyInstanceOf(DefaultGrpcServiceConfigurer.class);
          assertThat(context.getBean(GrpcServerFactory.class))
              .isExactlyInstanceOf(BoundedGrpcServerFactory.class);
          var lifecycle = context.getBean(GrpcServerLifecycle.class);
          assertThat(lifecycle.getFactory())
              .isSameAs(context.getBean(BoundedGrpcServerFactory.class));
          assertThat(lifecycle.isRunning()).isTrue();
          var state = context.getBean(DiscoveryState.class);
          assertThat(state.filtered).hasValue(1);
          ManagedChannel channel =
              NettyChannelBuilder.forAddress("127.0.0.1", lifecycle.getPort())
                  .usePlaintext()
                  .build();
          try {
            assertThat(
                    ClientCalls.blockingUnaryCall(
                        channel,
                        READ,
                        CallOptions.DEFAULT
                            .withDeadlineAfter(5, TimeUnit.SECONDS)
                            .withCompression("gzip"),
                        Empty.getDefaultInstance()))
                .isEqualTo(Empty.getDefaultInstance());
            assertThat(state.events).containsExactly("first", "second", "handler");
          } finally {
            channel.shutdownNow();
            assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
          }
        });
  }

  @Test
  void realSpringBootAutoDiscoveryKeepsFrameworkSupportAndCanonicalGuardTogether()
      throws Exception {
    // Exercise classpath auto-configuration discovery rather than enumerating gRPC configurations.
    // Core runtime composition is unrelated to this focused server registration proof.
    try (var context =
        new SpringApplicationBuilder(AutoDiscoveredBootApplication.class)
            .web(WebApplicationType.NONE)
            .registerShutdownHook(false)
            .run(
                "--spring.profiles.active=test",
                "--spring.application.name=bounded-grpc-boot-test",
                "--spring.grpc.server.port=0",
                "--spring.grpc.server.ssl.enabled=false",
                "--spring.main.banner-mode=off",
                "--spring.autoconfigure.exclude=net.firedevops.firemud.common.config.CommonCoreAutoConfiguration,"
                    + "org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration")) {
      assertThat(context.getBeansOfType(GrpcServerFactory.class)).hasSize(1);
      assertThat(context.getBean(GrpcServerFactory.class))
          .isExactlyInstanceOf(BoundedGrpcServerFactory.class);
      assertThat(context.getBean(GrpcServiceDiscoverer.class))
          .isExactlyInstanceOf(DefaultGrpcServiceDiscoverer.class);
      assertThat(context.getBean(GrpcServiceConfigurer.class))
          .isExactlyInstanceOf(DefaultGrpcServiceConfigurer.class);
      var lifecycle = context.getBean(GrpcServerLifecycle.class);
      assertThat(lifecycle.isRunning()).isTrue();
      assertThat(lifecycle.getFactory()).isSameAs(context.getBean(BoundedGrpcServerFactory.class));
      ManagedChannel channel =
          NettyChannelBuilder.forAddress("127.0.0.1", lifecycle.getPort()).usePlaintext().build();
      try {
        assertThat(
                ClientCalls.blockingUnaryCall(
                    channel,
                    READ,
                    CallOptions.DEFAULT
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .withCompression("gzip"),
                    Empty.getDefaultInstance()))
            .isEqualTo(Empty.getDefaultInstance());
        assertThat(context.getBean(DiscoveryState.class).events)
            .containsExactly("first", "second", "handler");
      } finally {
        channel.shutdownNow();
        assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  @Test
  void explicitNoServerLifecycleBacksOffWithoutWeakeningFactoryDescriptorChecks() {
    runner
        .withUserConfiguration(NoGrpcServerTestConfiguration.class)
        .withBean(GrpcRequestBytePolicy.class, GrpcRequestBytePolicy::createdRosterRetirement)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(GrpcServerLifecycle.class)
                  .hasSingleBean(GrpcServerFactory.class)
                  .doesNotHaveBean("boundedGrpcServerLifecycle");
              var lifecycle = context.getBean(GrpcServerLifecycle.class);
              assertThat(lifecycle).isSameAs(context.getBean("grpcServerLifecycle"));
              verify(lifecycle, never()).start();
              var factory = context.getBean(GrpcServerFactory.class);
              assertThat(factory).isExactlyInstanceOf(BoundedGrpcServerFactory.class);
              assertThatThrownBy(factory::createServer)
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no exact registered descriptor");
            });
  }

  @Test
  void explicitNoServerFixtureAlsoBootsWhenFrameworkGrpcSupportIsExcluded() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonGrpcRequestBoundsAutoConfiguration.class))
        .withUserConfiguration(
            DiscoveredServiceConfiguration.class, NoGrpcServerTestConfiguration.class)
        .withPropertyValues("spring.grpc.server.port=0", "spring.grpc.server.ssl.enabled=false")
        .withBean(GrpcRequestBytePolicy.class, GrpcRequestBytePolicy::createdRosterRetirement)
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(GrpcServerLifecycle.class)
                  .hasSingleBean(GrpcServerFactory.class)
                  .hasSingleBean(GrpcServiceConfigurer.class)
                  .doesNotHaveBean("boundedGrpcServerLifecycle");
              var lifecycle = context.getBean(GrpcServerLifecycle.class);
              assertThat(lifecycle).isSameAs(context.getBean("grpcServerLifecycle"));
              verify(lifecycle, never()).start();
              verifyNoInteractions(context.getBean(GrpcServiceConfigurer.class));
              var factory = context.getBean(GrpcServerFactory.class);
              assertThat(factory).isExactlyInstanceOf(BoundedGrpcServerFactory.class);
              assertThatThrownBy(factory::createServer)
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("no exact registered descriptor");
            });
  }

  @Test
  void createdPolicyRequiresExplicitTransportOptInAndKeepsOrdinaryMethodsBounded() {
    var createdRunner =
        runner
            .withBean(GrpcRequestBytePolicy.class, GrpcRequestBytePolicy::createdRosterRetirement)
            .withBean(CreatedRosterService.class, CreatedRosterService::new);
    createdRunner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(
                  context.getBean(GrpcServerProperties.class).getMaxInboundMessageSize().toBytes())
              .isEqualTo(GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
          assertPurposeAndOrdinaryAdmission(
              context.getBean(GrpcServerLifecycle.class).getPort(), Status.Code.RESOURCE_EXHAUSTED);
          assertThat(context.getBean(DiscoveryState.class).events).doesNotContain("handler");
        });
    createdRunner
        .withPropertyValues(
            "spring.grpc.server.max-inbound-message-size="
                + CanonicalGameplayRosterRetirementEvidence.MAX_PLAYER_CREATED_CONFIRMATION_BYTES
                + "B")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(
                      context
                          .getBean(GrpcServerProperties.class)
                          .getMaxInboundMessageSize()
                          .toBytes())
                  .isEqualTo(
                      CanonicalGameplayRosterRetirementEvidence
                          .MAX_PLAYER_CREATED_CONFIRMATION_BYTES);
              // UNIMPLEMENTED comes from the generated base only after the actual eligible request
              // parses.
              assertPurposeAndOrdinaryAdmission(
                  context.getBean(GrpcServerLifecycle.class).getPort(), Status.Code.UNIMPLEMENTED);
              assertThat(context.getBean(DiscoveryState.class).events).doesNotContain("handler");
            });
    createdRunner
        .withPropertyValues("spring.grpc.server.max-inbound-message-size=0B")
        .run(context -> assertThat(context).hasFailed());
    createdRunner
        .withPropertyValues(
            "spring.grpc.server.max-inbound-message-size="
                + ((long)
                        CanonicalGameplayRosterRetirementEvidence
                            .MAX_PLAYER_CREATED_CONFIRMATION_BYTES
                    + 1)
                + "B")
        .run(context -> assertThat(context).hasFailed());
  }

  private static void assertPurposeAndOrdinaryAdmission(int port, Status.Code purposeStatus)
      throws Exception {
    var unknown =
        UnknownFieldSet.newBuilder()
            .addField(
                9999,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(
                        ByteString.copyFrom(new byte[GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES]))
                    .build())
            .build();
    var request =
        CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setUnknownFields(unknown)
            .build();
    assertThat(request.getSerializedSize())
        .isGreaterThan(GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
    ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    try {
      var options =
          CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS).withCompression("gzip");
      assertThatThrownBy(
              () ->
                  ClientCalls.blockingUnaryCall(
                      channel,
                      CanonicalGameplayRosterOwnerReadServiceGrpc
                          .getAcknowledgeCanonicalGameplayRosterRetirementMethod(),
                      options,
                      request))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode()).isEqualTo(purposeStatus));
      assertThatThrownBy(
              () ->
                  ClientCalls.blockingUnaryCall(
                      channel, READ, options, Empty.newBuilder().setUnknownFields(unknown).build()))
          .satisfies(
              failure ->
                  assertThat(Status.fromThrowable(failure).getCode())
                      .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
    } finally {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void alternateFactoryAndDuplicatePoliciesFailClosed() {
    runner
        .withBean("unguarded", GrpcServerFactory.class, () -> mock(GrpcServerFactory.class))
        .run(context -> assertThat(context).hasFailed());
    runner
        .withBean("policyOne", GrpcRequestBytePolicy.class, GrpcRequestBytePolicy::ordinary)
        .withBean("policyTwo", GrpcRequestBytePolicy.class, GrpcRequestBytePolicy::ordinary)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void missingExceptionsUseOrdinaryCeilingAndUnknownOrUnboundedTransportCannotWidenIt() {
    runner.run(
        context ->
            assertThat(context.getBean(GrpcRequestBytePolicy.class).transportMaximumBytes())
                .isEqualTo(GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES));
    runner
        .withPropertyValues("spring.grpc.server.max-inbound-message-size=5MB")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("spring.grpc.server.max-inbound-message-size=-1B")
        .run(context -> assertThat(context).hasFailed());
    runner
        .withPropertyValues("spring.grpc.server.max-inbound-message-size=1MB")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(DiscoveredServiceConfiguration.class)
  static class AutoDiscoveredBootApplication {}

  static class DiscoveryState {
    final List<String> events = new CopyOnWriteArrayList<>();
    final AtomicInteger filtered = new AtomicInteger();
  }

  @Configuration(proxyBeanMethods = false)
  static class DiscoveredServiceConfiguration {
    @Bean
    DiscoveryState discoveryState() {
      return new DiscoveryState();
    }

    @Bean
    DiscoveredService discoveredService(DiscoveryState state) {
      return new DiscoveredService(state);
    }

    @Bean
    GrpcServerFactoryCustomizer serviceFilter(DiscoveryState state) {
      return factory ->
          ((DefaultGrpcServerFactory<?>) factory)
              .setServiceFilter(
                  (service, ignored) -> {
                    state.filtered.incrementAndGet();
                    return true;
                  });
    }

    @Bean
    @GlobalServerInterceptor
    @Order(10)
    ServerInterceptor firstInterceptor(DiscoveryState state) {
      return interceptor(state, "first");
    }

    @Bean
    @GlobalServerInterceptor
    @Order(20)
    ServerInterceptor secondInterceptor(DiscoveryState state) {
      return interceptor(state, "second");
    }
  }

  @GrpcService
  static class CreatedRosterService
      extends CanonicalGameplayRosterOwnerReadServiceGrpc
          .CanonicalGameplayRosterOwnerReadServiceImplBase {}

  @GrpcService
  static class DiscoveredService implements BindableService {
    private final DiscoveryState state;

    DiscoveredService(DiscoveryState state) {
      this.state = state;
    }

    @Override
    public ServerServiceDefinition bindService() {
      return ServerServiceDefinition.builder("test.Discovered")
          .addMethod(
              READ,
              ServerCalls.asyncUnaryCall(
                  (request, observer) -> {
                    state.events.add("handler");
                    observer.onNext(Empty.getDefaultInstance());
                    observer.onCompleted();
                  }))
          .build();
    }
  }

  private static ServerInterceptor interceptor(DiscoveryState state, String name) {
    return new ServerInterceptor() {
      @Override
      public <Q, R> io.grpc.ServerCall.Listener<Q> interceptCall(
          io.grpc.ServerCall<Q, R> call,
          io.grpc.Metadata headers,
          io.grpc.ServerCallHandler<Q, R> next) {
        state.events.add(name);
        return next.startCall(call, headers);
      }
    };
  }
}
