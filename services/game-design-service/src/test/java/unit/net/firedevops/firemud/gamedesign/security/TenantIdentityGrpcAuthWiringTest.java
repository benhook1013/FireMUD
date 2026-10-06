package unit.net.firedevops.firemud.gamedesign.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationRequest;
import net.firedevops.firemud.gamedesign.v1.ResolveFreshTenantCreationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class TenantIdentityGrpcAuthWiringTest {
  private static final String FRESH_CREATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation";
  private static final String OTHER_METHOD = "gamedesign.v1.TenantIdentityService/UnlistedMethod";

  @Test
  void defaultAndProductionProfilesAllowOnlyFreshReadAndRequireClientTls() throws IOException {
    for (String config : List.of("application.yml", "application-prod.yml")) {
      GrpcConfiguration properties = load(config);
      assertThat(properties.publicMethods()).containsExactly(FRESH_CREATION_METHOD);
      assertThat(properties.clientAuth()).isEqualTo("REQUIRE");
    }
  }

  @Test
  void jwtBypassAppliesOnlyToTheConfiguredFreshReadMethod() throws IOException {
    AuthTokenInterceptor interceptor =
        new AuthTokenInterceptor(
            mock(JwtUtil.class), Set.copyOf(load("application.yml").publicMethods()));

    DispatchResult fresh = dispatch(interceptor, FRESH_CREATION_METHOD);
    assertThat(fresh.dispatched()).isTrue();
    assertThat(fresh.closedStatus()).isNull();

    DispatchResult other = dispatch(interceptor, OTHER_METHOD);
    assertThat(other.dispatched()).isFalse();
    assertThat(other.closedStatus()).isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  private static GrpcConfiguration load(String file) throws IOException {
    ConfigurableEnvironment environment = new StandardEnvironment();
    List<PropertySource<?>> propertySources =
        new YamlPropertySourceLoader()
            .load(file, new FileSystemResource(Path.of("src/main/resources").resolve(file)));
    for (int index = propertySources.size() - 1; index >= 0; index--) {
      environment.getPropertySources().addFirst(propertySources.get(index));
    }
    Binder binder = Binder.get(environment);
    List<String> publicMethods =
        binder
            .bind("firemud.auth.grpc.public-methods", Bindable.listOf(String.class))
            .orElseThrow(() -> new AssertionError("Configured public methods are required"));
    String clientAuth =
        binder
            .bind("spring.grpc.server.ssl.client-auth", String.class)
            .orElseThrow(
                () -> new AssertionError("Configured TLS client authentication is required"));
    return new GrpcConfiguration(publicMethods, clientAuth);
  }

  private static DispatchResult dispatch(AuthTokenInterceptor interceptor, String fullMethodName) {
    ServerCall<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> call = mock();
    when(call.getMethodDescriptor()).thenReturn(method(fullMethodName));
    AtomicReference<Status.Code> closedStatus = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Status status = invocation.getArgument(0);
              closedStatus.set(status.getCode());
              return null;
            })
        .when(call)
        .close(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    AtomicBoolean dispatched = new AtomicBoolean();
    ServerCallHandler<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse> next =
        (serverCall, headers) -> {
          dispatched.set(true);
          return new ServerCall.Listener<>() {};
        };

    interceptor.interceptCall(call, new Metadata(), next);
    return new DispatchResult(dispatched.get(), closedStatus.get());
  }

  private static MethodDescriptor<
          ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>
      method(String fullMethodName) {
    return MethodDescriptor
        .<ResolveFreshTenantCreationRequest, ResolveFreshTenantCreationResponse>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY)
        .setFullMethodName(fullMethodName)
        .setRequestMarshaller(
            ProtoUtils.marshaller(ResolveFreshTenantCreationRequest.getDefaultInstance()))
        .setResponseMarshaller(
            ProtoUtils.marshaller(ResolveFreshTenantCreationResponse.getDefaultInstance()))
        .build();
  }

  private record DispatchResult(boolean dispatched, Status.Code closedStatus) {}

  private record GrpcConfiguration(List<String> publicMethods, String clientAuth) {}
}
