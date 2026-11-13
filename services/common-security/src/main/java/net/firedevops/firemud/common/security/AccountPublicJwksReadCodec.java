package net.firedevops.firemud.common.security;

import com.google.protobuf.ByteString;
import net.firedevops.firemud.account.v1.AccountPublicJwksSourceIdentity;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksRequest;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse;
import net.firedevops.firemud.account.v1.GetCurrentAccountPublicJwksResponse.Disposition;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;

/** Closed bounded wire frame for the private current Account public-JWKS read. */
public final class AccountPublicJwksReadCodec {
  public static final int MAX_REQUEST_BYTES = 32;
  public static final int MAX_RESPONSE_BYTES = AccountPublicJwksCache.MAX_JWKS_BYTES + 2048;
  private static final int SCHEMA_VERSION = 2;

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

  /** Ordinary snapshots do not assert current committed promotion evidence. */
  public static GetCurrentAccountPublicJwksResponse response(PublicJwksSnapshot snapshot) {
    if (snapshot == null) throw new InvalidFrameException();
    return response(new ReadResult(snapshot.sourceIdentity(), snapshot.jwksBytes(), false, false));
  }

  /** Producer classification comes only from Account's verified current owner evidence. */
  public static GetCurrentAccountPublicJwksResponse response(ReadResult result) {
    if (result == null) throw new InvalidFrameException();
    SourceIdentity source = result.sourceIdentity();
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
            .setDisposition(
                result.preparedDeny() ? Disposition.PREPARED_DENY : Disposition.SNAPSHOT);
    if (!result.preparedDeny()) {
      response.setPublicJwks(ByteString.copyFrom(result.jwksBytes()));
      response.setCommittedRecoveryEligible(result.committedRecoveryEligible());
    }
    var built = response.build();
    result(built);
    return built;
  }

  /** A deny is never a snapshot and cannot be mistaken for ordinary source unavailability. */
  public static PublicJwksSnapshot snapshot(GetCurrentAccountPublicJwksResponse response) {
    ReadResult result = result(response);
    if (result.preparedDeny()) throw new InvalidFrameException();
    return new PublicJwksSnapshot(
        result.sourceIdentity(), result.jwksBytes(), result.committedRecoveryEligible());
  }

  public static ReadResult result(GetCurrentAccountPublicJwksResponse response) {
    if (response == null
        || response.getSchemaVersion() != SCHEMA_VERSION
        || !response.hasSourceIdentity()
        || !response.getUnknownFields().asMap().isEmpty()
        || !response.getSourceIdentity().getUnknownFields().asMap().isEmpty()
        || response.getSerializedSize() > MAX_RESPONSE_BYTES) {
      throw new InvalidFrameException();
    }
    boolean deny;
    switch (response.getDisposition()) {
      case SNAPSHOT -> {
        deny = false;
        if (!response.hasCommittedRecoveryEligible()) throw new InvalidFrameException();
      }
      case PREPARED_DENY -> {
        deny = true;
        if (response.hasCommittedRecoveryEligible()) throw new InvalidFrameException();
      }
      default -> throw new InvalidFrameException();
    }
    var source = response.getSourceIdentity();
    try {
      return new ReadResult(
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
          response.getPublicJwks().toByteArray(),
          deny,
          response.getCommittedRecoveryEligible());
    } catch (IllegalArgumentException invalid) {
      throw new InvalidFrameException();
    }
  }

  /** Public-only owner result. Wire presence and closed-disposition checks remain in the codec. */
  public record ReadResult(
      SourceIdentity sourceIdentity,
      byte[] jwksBytes,
      boolean preparedDeny,
      boolean committedRecoveryEligible) {
    public ReadResult {
      if (sourceIdentity == null
          || jwksBytes == null
          || jwksBytes.length > AccountPublicJwksCache.MAX_JWKS_BYTES
          || (preparedDeny
              ? jwksBytes.length != 0 || committedRecoveryEligible
              : jwksBytes.length == 0)) {
        throw new InvalidFrameException();
      }
      jwksBytes = jwksBytes.clone();
    }

    @Override
    public byte[] jwksBytes() {
      return jwksBytes.clone();
    }
  }

  public static final class InvalidFrameException extends IllegalArgumentException {
    public InvalidFrameException() {
      super("Current Account public JWKS frame is invalid");
    }
  }
}
