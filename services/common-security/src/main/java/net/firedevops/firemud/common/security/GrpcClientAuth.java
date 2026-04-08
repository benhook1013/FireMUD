package net.firedevops.firemud.common.security;

import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Helper for attaching bearer auth metadata to internal gRPC clients. */
public final class GrpcClientAuth {
  private static final Metadata.Key<String> AUTH_HEADER =
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final String INTERNAL_SUBJECT = "internal-service";

  private GrpcClientAuth() {}

  public static <T extends AbstractStub<T>> T attach(T stub, JwtUtil jwtUtil) {
    Metadata metadata = new Metadata();
    metadata.put(AUTH_HEADER, "Bearer " + createBearerToken(jwtUtil));
    return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
  }

  private static String createBearerToken(JwtUtil jwtUtil) {
    String accountId = SessionContext.getAccountId();
    List<String> globalRoles = SessionContext.getGlobalRoles();
    Map<String, List<String>> scopedRoles = SessionContext.getScopedRolesMap();

    if (accountId == null || accountId.isBlank()) {
      accountId = INTERNAL_SUBJECT;
    }
    if (globalRoles == null || globalRoles.isEmpty()) {
      globalRoles = List.of("platformAdmin");
    }
    if (scopedRoles == null) {
      scopedRoles = Map.of();
    }

    Map<String, Object> claims = new HashMap<>();
    claims.put("accountId", accountId);
    claims.put("globalRoles", globalRoles);
    claims.put("scopedRoles", scopedRoles);
    return jwtUtil.generateToken(accountId, claims);
  }
}
