package net.firedevops.firemud.common.security;

import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;

/** Helper for attaching bearer auth metadata to internal gRPC clients. */
public final class GrpcClientAuth {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final String INTERNAL_SUBJECT_PREFIX = "service:";

  private GrpcClientAuth() {}

  public static <T extends AbstractStub<T>> T attach(
      T stub, JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    Metadata metadata = new Metadata();
    metadata.put(AUTH_HEADER, "Bearer " + createBearerToken(jwtUtil, runtimeIdentity));
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
  }

  public static <T extends AbstractStub<T>> T attachInternal(
      T stub, JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    Metadata metadata = new Metadata();
    metadata.put(AUTH_HEADER, "Bearer " + createInternalBearerToken(jwtUtil, runtimeIdentity));
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
  }

  static String createBearerToken(JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    String accountId = SessionContext.getAccountId();
    List<String> globalRoles = SessionContext.getGlobalRoles();
    Map<String, List<String>> scopedRoles = SessionContext.getScopedRolesMap();
    if (accountId == null || accountId.isBlank()) {
      return createInternalBearerToken(jwtUtil, runtimeIdentity);
    }

    Map<String, Object> claims = new HashMap<>();
    claims.put("accountId", accountId);
    claims.put("globalRoles", globalRoles == null ? List.of() : globalRoles);
    claims.put("scopedRoles", scopedRoles == null ? Map.of() : scopedRoles);
    return jwtUtil.generateToken(accountId, claims);
  }

  static String createInternalBearerToken(JwtUtil jwtUtil, RuntimeIdentity runtimeIdentity) {
    String serviceName =
        runtimeIdentity != null && runtimeIdentity.service() != null
            ? runtimeIdentity.service()
            : "unknown-service";
    String serviceInstanceId = runtimeIdentity != null ? runtimeIdentity.serviceInstanceId() : null;

    Map<String, Object> claims = new HashMap<>();
    claims.put("globalRoles", List.of());
    claims.put("scopedRoles", Map.of());
    claims.put("internalService", true);
    claims.put("serviceName", serviceName);
    if (serviceInstanceId != null && !serviceInstanceId.isBlank()) {
      claims.put("serviceInstanceId", serviceInstanceId);
    }
    return jwtUtil.generateToken(INTERNAL_SUBJECT_PREFIX + serviceName, claims);
  }
}
