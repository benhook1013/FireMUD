package unit.net.firedevops.firemud.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Empty;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.stub.AbstractStub;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.firedevops.firemud.common.config.CommonSecurityAutoConfiguration;
import net.firedevops.firemud.common.config.CommonSecurityServletAutoConfiguration;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import net.firedevops.firemud.common.security.HttpAuthProperties;
import net.firedevops.firemud.common.security.HttpJwtAuthInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CommonSecurityAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ConfigurationPropertiesAutoConfiguration.class,
                  CommonSecurityAutoConfiguration.class,
                  CommonSecurityServletAutoConfiguration.class));

  @Test
  void registersSharedGrpcAuthInterceptorWhenEnabled() {
    contextRunner
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.grpc.public-methods[0]=firemud.gateway.v1.GatewayManagementService/Ping")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(AuthTokenInterceptor.class);
              GrpcAuthProperties props = ctx.getBean(GrpcAuthProperties.class);
              assertThat(props.getPublicMethods())
                  .containsExactly("firemud.gateway.v1.GatewayManagementService/Ping");
            });
  }

  @Test
  void registersSharedHttpAuthWhenEnabled() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class,
                CommonSecurityServletAutoConfiguration.class))
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.http.enabled=true",
            "firemud.auth.http.public-routes[0].method=GET",
            "firemud.auth.http.public-routes[0].path-pattern=/ping")
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(HttpJwtAuthInterceptor.class);
              HttpAuthProperties props = ctx.getBean(HttpAuthProperties.class);
              assertThat(props.getPublicRoutes()).hasSize(1);
              assertThat(props.getPublicRoutes().get(0).getMethod()).isEqualTo("GET");
              assertThat(props.getPublicRoutes().get(0).getPathPattern()).isEqualTo("/ping");
            });
  }

  @Test
  void reactiveAppsDoNotLoadServletHttpAuthAutoConfiguration() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class,
                CommonSecurityAutoConfiguration.class,
                CommonSecurityServletAutoConfiguration.class))
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.http.enabled=true")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(HttpJwtAuthInterceptor.class);
              assertThat(ctx).hasSingleBean(JwtUtil.class);
            });
  }

  @Test
  void sharedGrpcAuthInterceptorCanBeDisabledForCustomServerPolicy() {
    contextRunner
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.grpc.interceptor-enabled=false")
        .run(
            ctx -> {
              assertThat(ctx).doesNotHaveBean(AuthTokenInterceptor.class);
              assertThat(ctx).hasSingleBean(JwtUtil.class);
            });
  }

  @Test
  void configuredPublicMethodsBypassBearerRequirement() {
    contextRunner
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.grpc.public-methods[0]=demo.Service/Ping")
        .run(
            ctx -> {
              AuthTokenInterceptor interceptor = ctx.getBean(AuthTokenInterceptor.class);
              @SuppressWarnings({"rawtypes", "unchecked"})
              ServerCall call = Mockito.mock(ServerCall.class);
              @SuppressWarnings({"rawtypes", "unchecked"})
              ServerCallHandler next = Mockito.mock(ServerCallHandler.class);
              MethodDescriptor<Empty, Empty> methodDescriptor = unaryMethod("demo.Service/Ping");
              Mockito.when(call.getMethodDescriptor()).thenReturn(methodDescriptor);
              Mockito.when(next.startCall(Mockito.eq(call), Mockito.any(Metadata.class)))
                  .thenReturn(new ServerCall.Listener<>() {});

              interceptor.interceptCall(call, new Metadata(), next);

              Mockito.verify(next).startCall(Mockito.eq(call), Mockito.any(Metadata.class));
            });
  }

  @Test
  void jwtUtilStillCarriesRoleClaims() {
    contextRunner
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            ctx -> {
              JwtUtil jwtUtil = ctx.getBean(JwtUtil.class);
              String token =
                  jwtUtil.generateToken(
                      "user", java.util.Map.of("globalRoles", List.of("platformAdmin")));
              Jws<Claims> claims = jwtUtil.parseToken(token);
              assertThat(claims.getPayload().getSubject()).isEqualTo("user");
              assertThat(claims.getPayload().get("globalRoles", List.class))
                  .containsExactly("platformAdmin");
            });
  }

  @Test
  void jwtUtilCanInitializeFromSecretPathWithoutInlineSecret() throws Exception {
    Path secretFile = Files.createTempFile("firemud-jwt-secret", ".txt");
    Files.writeString(secretFile, "testsecretkeytestsecretkeytest1234");

    contextRunner
        .withPropertyValues("firemud.auth.jwt-secret-path=" + secretFile)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(JwtUtil.class);
              JwtUtil jwtUtil = ctx.getBean(JwtUtil.class);
              String token = jwtUtil.generateToken("user", java.util.Map.of());
              Jws<Claims> claims = jwtUtil.parseToken(token);
              assertThat(claims.getPayload().getSubject()).isEqualTo("user");
            });
  }

  @Test
  void protectedMethodsStillRequireBearerToken() {
    contextRunner
        .withPropertyValues("firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234")
        .run(
            ctx -> {
              AuthTokenInterceptor interceptor = ctx.getBean(AuthTokenInterceptor.class);
              @SuppressWarnings({"rawtypes", "unchecked"})
              ServerCall call = Mockito.mock(ServerCall.class);
              @SuppressWarnings({"rawtypes", "unchecked"})
              ServerCallHandler next = Mockito.mock(ServerCallHandler.class);
              MethodDescriptor<Empty, Empty> methodDescriptor =
                  unaryMethod("demo.Service/Protected");
              Mockito.when(call.getMethodDescriptor()).thenReturn(methodDescriptor);

              interceptor.interceptCall(call, new Metadata(), next);

              ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
              Mockito.verify(call).close(statusCaptor.capture(), Mockito.any(Metadata.class));
              assertThat(statusCaptor.getValue().getCode())
                  .isEqualTo(Status.UNAUTHENTICATED.getCode());
            });
  }

  @Test
  void outboundStubCustomizerCanForceInternalServiceIdentity() {
    contextRunner
        .withBean(
            RuntimeIdentity.class,
            () ->
                new RuntimeIdentity(
                    "game-logic-service",
                    "gl-1",
                    "localhost",
                    java.time.Instant.now(),
                    "1.0.0",
                    "abc123",
                    "local"))
        .withPropertyValues(
            "firemud.auth.jwt-secret=testsecretkeytestsecretkeytest1234",
            "firemud.auth.grpc.force-internal-service-outbound=true")
        .run(
            ctx -> {
              BlockingGrpcStubCustomizer customizer = ctx.getBean(BlockingGrpcStubCustomizer.class);
              CapturingStub stub = new CapturingStub();

              assertThat(customizer.customize(stub)).isNotSameAs(stub);
            });
  }

  private MethodDescriptor<Empty, Empty> unaryMethod(String fullMethodName) {
    return MethodDescriptor.<Empty, Empty>newBuilder()
        .setFullMethodName(fullMethodName)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
        .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
        .build();
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
