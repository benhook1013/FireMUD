package net.firedevops.firemud.common.account.sourceintake;

import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import java.util.Iterator;
import java.util.concurrent.Executor;
import net.firedevops.firemud.account.v1.AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc;

/** Method-scoped protected metadata for the unchanged selected-owner creator credential. */
public final class SelectedOwnerIntakeAuthorizationProducerCredentials {
  public static final int MAX_CREDENTIAL_CHARS = 16384;
  public static final Context.Key<Credential> CONTEXT_KEY =
      Context.key("selected-owner-intake-authorization-original-creator");
  public static final Metadata.Key<Credential> HEADER =
      Metadata.Key.of(
          "x-firemud-selected-owner-intake-creator",
          new Metadata.AsciiMarshaller<>() {
            @Override
            public String toAsciiString(Credential value) {
              return value.value();
            }

            // Credential validation is deferred until after the receiving peer is authenticated.
            @Override
            public Credential parseAsciiString(String serialized) {
              return new Credential(serialized);
            }
          });

  private SelectedOwnerIntakeAuthorizationProducerCredentials() {}

  public static final class Credential {
    private final String credential;

    private Credential(String credential) {
      this.credential = credential;
    }

    public static Credential of(String value) {
      if (!validCredential(value)) throw invalid();
      return new Credential(value);
    }

    public String value() {
      if (!validCredential(credential)) throw invalid();
      return credential;
    }

    @Override
    public String toString() {
      return "SelectedOwnerIntakeAuthorizationCredential[redacted]";
    }
  }

  /** Call only after authenticating the exact same-namespace Game Design workload. */
  public static Credential readAuthenticated(Metadata headers, String methodName) {
    if (!allowedMethod(methodName) || headers == null) throw invalid();
    Iterable<Credential> values = headers.getAll(HEADER);
    if (values == null) throw invalid();
    Iterator<Credential> iterator = values.iterator();
    if (!iterator.hasNext()) throw invalid();
    Credential credential = iterator.next();
    if (iterator.hasNext()) throw invalid();
    credential.value();
    return credential;
  }

  public static CallCredentials forAuthorize(String value) {
    Credential credential = Credential.of(value);
    return new CallCredentials() {
      @Override
      public void applyRequestMetadata(
          RequestInfo info, Executor executor, MetadataApplier applier) {
        if (info.getSecurityLevel() != SecurityLevel.PRIVACY_AND_INTEGRITY
            || info.getMethodDescriptor() == null
            || !AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
                .getAuthorizeSelectedOwnerIntakeMethod()
                .getFullMethodName()
                .equals(info.getMethodDescriptor().getFullMethodName())) {
          applier.fail(
              Status.UNAUTHENTICATED.withDescription(
                  "Protected selected-owner intake producer transport required"));
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
        return "SelectedOwnerIntakeAuthorizationCallCredentials[redacted]";
      }
    };
  }

  public static boolean allowedMethod(String methodName) {
    return AccountSelectedOwnerIntakeAuthorizationProducerServiceGrpc
        .getAuthorizeSelectedOwnerIntakeMethod()
        .getFullMethodName()
        .equals(methodName);
  }

  private static boolean validCredential(String credential) {
    return credential != null
        && !credential.isEmpty()
        && credential.length() <= MAX_CREDENTIAL_CHARS
        && credential.chars().allMatch(value -> value > 32 && value < 127);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid selected-owner intake creator metadata");
  }
}
