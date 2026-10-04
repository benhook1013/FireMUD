package unit.net.firedevops.firemud.gamedesign.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.Empty;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import net.firedevops.firemud.common.security.AuthTokenInterceptor;
import net.firedevops.firemud.common.security.GrpcAuthProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

class TenantIdentityAuthConfigurationTest {
  private static final String ACCOUNT_ASSOCIATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveLegacyAccountTenantAssociation";
  private static final String FRESH_CREATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveFreshTenantCreation";
  private static final String RUNTIME_TENANT_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveRuntimeTenantIdentity";
  private static final String AUTHORED_WORLD_SOURCE_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveAuthoredWorldSource";
  private static final String GAME_SESSION_ASSOCIATION_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveLegacyGameSessionTenantAssociation";
  private static final String ACCOUNT_LEGACY_IDENTITY_METHOD =
      "gamedesign.v1.TenantIdentityService/ResolveLegacyGameTenantIdentity";
  private static final String RESOLVE_LAUNCH_DESCRIPTOR_METHOD =
      "gamedesign.v1.GameDesignService/ResolveLaunchDescriptor";
  private static final String GET_LAUNCH_DESCRIPTOR_METHOD =
      "gamedesign.v1.GameDesignService/GetLaunchDescriptor";
  private static final String GET_COMPLETE_LAUNCH_BINDING_METHOD =
      "gamedesign.v1.GameDesignService/GetCompleteLaunchBinding";

  @Test
  void baseConfigurationExemptsOnlyTheIntendedTenantIdentityMethods() throws IOException {
    assertConfiguration(loadProperties(false));
  }

  @Test
  void productionConfigurationRetainsTheIntendedTenantIdentityMethods() throws IOException {
    assertConfiguration(loadProperties(true));
  }

  private GrpcAuthProperties loadProperties(boolean production) throws IOException {
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    StandardEnvironment environment = new StandardEnvironment();
    ClassPathResource productionResource = new ClassPathResource("application-prod.yml");
    FileSystemResource baseResource =
        new FileSystemResource(
            productionResource.getFile().toPath().resolveSibling("application.yml"));
    List<PropertySource<?>> baseSources = loader.load("application", baseResource);
    baseSources.forEach(environment.getPropertySources()::addFirst);
    if (production) {
      List<PropertySource<?>> productionSources =
          loader.load("application-prod", productionResource);
      productionSources.forEach(environment.getPropertySources()::addFirst);
    }
    return Binder.get(environment)
        .bind("firemud.auth.grpc", Bindable.of(GrpcAuthProperties.class))
        .orElseThrow(IllegalStateException::new);
  }

  private void assertConfiguration(GrpcAuthProperties properties) {
    assertThat(properties.getPublicMethods())
        .containsExactlyInAnyOrder(
            ACCOUNT_ASSOCIATION_METHOD,
            FRESH_CREATION_METHOD,
            RUNTIME_TENANT_METHOD,
            AUTHORED_WORLD_SOURCE_METHOD,
            GAME_SESSION_ASSOCIATION_METHOD,
            RESOLVE_LAUNCH_DESCRIPTOR_METHOD,
            GET_LAUNCH_DESCRIPTOR_METHOD,
            GET_COMPLETE_LAUNCH_BINDING_METHOD);

    AuthTokenInterceptor interceptor =
        new AuthTokenInterceptor(null, Set.copyOf(properties.getPublicMethods()));
    for (String method : properties.getPublicMethods()) {
      assertMethodBypassesBearer(interceptor, method);
    }
    assertMethodRequiresBearer(interceptor, ACCOUNT_LEGACY_IDENTITY_METHOD);
  }

  private void assertMethodBypassesBearer(AuthTokenInterceptor interceptor, String method) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = mock(ServerCall.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCallHandler<Empty, Empty> next = mock(ServerCallHandler.class);
    when(call.getMethodDescriptor()).thenReturn(unaryMethod(method));
    when(next.startCall(eq(call), any(Metadata.class))).thenReturn(new ServerCall.Listener<>() {});

    interceptor.interceptCall(call, new Metadata(), next);

    verify(next).startCall(eq(call), any(Metadata.class));
    verify(call, never()).close(any(Status.class), any(Metadata.class));
  }

  private void assertMethodRequiresBearer(AuthTokenInterceptor interceptor, String method) {
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCall<Empty, Empty> call = mock(ServerCall.class);
    @SuppressWarnings({"rawtypes", "unchecked"})
    ServerCallHandler<Empty, Empty> next = mock(ServerCallHandler.class);
    when(call.getMethodDescriptor()).thenReturn(unaryMethod(method));

    interceptor.interceptCall(call, new Metadata(), next);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(call).close(statusCaptor.capture(), any(Metadata.class));
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
    verify(next, never()).startCall(any(), any(Metadata.class));
  }

  private MethodDescriptor<Empty, Empty> unaryMethod(String fullMethodName) {
    return MethodDescriptor.<Empty, Empty>newBuilder()
        .setFullMethodName(fullMethodName)
        .setType(MethodDescriptor.MethodType.UNARY)
        .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
        .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(Empty.getDefaultInstance()))
        .build();
  }
}
