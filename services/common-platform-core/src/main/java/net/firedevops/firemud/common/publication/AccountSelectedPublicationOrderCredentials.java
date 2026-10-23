package net.firedevops.firemud.common.publication;

import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import net.firedevops.firemud.account.v1.AccountSelectedPublicationOrderServiceGrpc;

/**
 * Purpose-specific protected metadata for the original creator credential. Never log metadata:
 * metadata's wire serialization necessarily contains the secret, unlike this redacted wrapper. The
 * receiver must authenticate its workload peer before reading or decoding this header.
 */
public final class AccountSelectedPublicationOrderCredentials {
  public static final Context.Key<Credential> CONTEXT_KEY =
      Context.key("account-selected-publication-original-creator");
  public static final Metadata.Key<Credential> HEADER =
      Metadata.Key.of(
          "firemud-selected-publication-creator-bin",
          new Metadata.BinaryMarshaller<>() {
            @Override
            public byte[] toBytes(Credential value) {
              return value.bytes.clone();
            }

            // Parsing deliberately defers all credential validation until after workload
            // authentication.
            @Override
            public Credential parseBytes(byte[] serialized) {
              return new Credential(serialized);
            }
          });

  private AccountSelectedPublicationOrderCredentials() {}

  public static final class Credential {
    private final byte[] bytes;

    private Credential(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    public static Credential of(String value) {
      if (value == null
          || value.isEmpty()
          || value.length() > AccountSelectedPublicationOrderGrpcCodec.MAX_CREDENTIAL_BYTES
          || value.chars().anyMatch(c -> c <= 32 || c >= 127)) throw invalid();
      return new Credential(value.getBytes(StandardCharsets.US_ASCII));
    }

    public String value() {
      if (bytes.length == 0
          || bytes.length > AccountSelectedPublicationOrderGrpcCodec.MAX_CREDENTIAL_BYTES)
        throw invalid();
      for (byte value : bytes) if (value <= 32 || value >= 127) throw invalid();
      return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public String toString() {
      return "SelectedPublicationCredential[redacted]";
    }
  }

  /** Only after authenticating the exact same-namespace Game Design peer. */
  public static Credential readAuthenticated(Metadata headers) {
    var values = headers.getAll(HEADER);
    if (values == null) throw invalid();
    var iterator = values.iterator();
    if (!iterator.hasNext()) throw invalid();
    Credential credential = iterator.next();
    if (iterator.hasNext()) throw invalid();
    credential.value();
    return credential;
  }

  public static CallCredentials forCall(String value) {
    var credential = Credential.of(value);
    return new CallCredentials() {
      @Override
      public void applyRequestMetadata(
          RequestInfo info, Executor executor, MetadataApplier applier) {
        if (info.getSecurityLevel() != SecurityLevel.PRIVACY_AND_INTEGRITY
            || !AccountSelectedPublicationOrderServiceGrpc.getAuthorizeSelectedPublicationMethod()
                .getFullMethodName()
                .equals(info.getMethodDescriptor().getFullMethodName())) {
          applier.fail(
              Status.UNAUTHENTICATED.withDescription(
                  "Protected selected-publication transport required"));
          return;
        }
        var headers = new Metadata();
        headers.put(HEADER, credential);
        applier.apply(headers);
      }

      @Override
      public void thisUsesUnstableApi() {}

      @Override
      public String toString() {
        return "SelectedPublicationCallCredentials[redacted]";
      }
    };
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid selected-publication credential metadata");
  }
}
