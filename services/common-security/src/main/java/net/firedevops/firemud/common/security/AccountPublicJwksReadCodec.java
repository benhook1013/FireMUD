package net.firedevops.firemud.common.security;

import com.google.protobuf.ByteString;
import net.firedevops.firemud.account.v1.AccountPublicJwksSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;

/** Closed bounded wire frame for the private current Account public-JWKS read. */
public final class AccountPublicJwksReadCodec {
  public static final int MAX_REQUEST_BYTES = 32;
  public static final int MAX_RESPONSE_BYTES = AccountPublicJwksCache.MAX_JWKS_BYTES + 2048;
  private static final int SCHEMA_VERSION = 1;

  private AccountPublicJwksReadCodec() {}

  public static GetCurrentAccountPublicJwksRequest request() {
    return GetCurrentAccountPublicJwksRequest.newBuilder().setSchemaVersion(SCHEMA_VERSION).build();
  }

  public static void requireRequest(GetCurrentAccountPublicJwksRequest request) {
    if (request == null
        || request.getSchemaVersion() != SCHEMA_VERSION
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSerializedSize() > MAX_REQUEST_BYTES) {
      throw new InvalidFrameException();
    }
  }

  /** Producer accepts only the snapshot already checked by Account's real trusted source. */
  public static GetCurrentAccountPublicJwksResponse response(PublicJwksSnapshot snapshot) {
    if (snapshot == null) throw new InvalidFrameException();
    SourceIdentity source = snapshot.sourceIdentity();
    byte[] bytes = snapshot.jwksBytes();
    if (bytes == null
        || bytes.length == 0
        || bytes.length > AccountPublicJwksCache.MAX_JWKS_BYTES) {
      throw new InvalidFrameException();
    }
    var identity =
        AccountPublicJwksSourceIdentity.newBuilder()
            .setEnvironmentId(source.environmentId())
            .setClusterId(source.clusterId())
            .setClusterIncarnationUid(source.clusterIncarnationUid())
            .setNamespace(source.namespace())
            .setNamespaceUid(source.namespaceUid())
            .setConfigMapUid(source.configMapUid())
            .setBindingRevision(source.bindingRevision())
            .setApiServerOrigin(source.apiServerOrigin())
            .setServingCaSha256(source.servingCaSha256())
            .build();
    var response =
        GetCurrentAccountPublicJwksResponse.newBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setSourceIdentity(identity)
            .setPublicJwks(ByteString.copyFrom(bytes))
            .build();
    snapshot(response);
    return response;
  }

  public static PublicJwksSnapshot snapshot(GetCurrentAccountPublicJwksResponse response) {
    if (response == null
        || response.getSchemaVersion() != SCHEMA_VERSION
        || !response.hasSourceIdentity()
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.getSourceIdentity().getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES
        || response.getPublicJwks().isEmpty()
        || response.getPublicJwks().size() > AccountPublicJwksCache.MAX_JWKS_BYTES) {
      throw new InvalidFrameException();
    }
    var source = response.getSourceIdentity();
    try {
      return new PublicJwksSnapshot(
          new SourceIdentity(
              source.getEnvironmentId(),
              source.getClusterId(),
              source.getClusterIncarnationUid(),
              source.getNamespace(),
              source.getNamespaceUid(),
              source.getConfigMapUid(),
              source.getBindingRevision(),
              source.getApiServerOrigin(),
              source.getServingCaSha256()),
          response.getPublicJwks().toByteArray());
    } catch (IllegalArgumentException invalid) {
      throw new InvalidFrameException();
    }
  }

  public static final class InvalidFrameException extends IllegalArgumentException {
    public InvalidFrameException() {
      super("Current Account public JWKS frame is invalid");
    }
  }
}
