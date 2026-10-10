package net.firedevops.firemud.common.gamelogic;

import io.grpc.CallCredentials;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import net.firedevops.firemud.account.v1.AccountGameLogicIntakeAuthorizationServiceGrpc;

/** Method-scoped protected metadata for the unchanged original creator credential. */
public final class AccountGameLogicIntakeAuthorizationCredentials {
  public static final int MAX_CREDENTIAL_BYTES = 16384;
  public static final Context.Key<Credential> CONTEXT_KEY =
      Context.key("account-game-logic-intake-original-creator");
  public static final Metadata.Key<Credential> HEADER =
      Metadata.Key.of(
          "firemud-game-logic-intake-creator-bin",
          new Metadata.BinaryMarshaller<>() {
            @Override
            public byte[] toBytes(Credential value) {
              return value.bytes.clone();
            }

            // Defer credential validation until the receiver has authenticated its caller.
            @Override
            public Credential parseBytes(byte[] serialized) {
              return new Credential(serialized);
            }
          });

  private AccountGameLogicIntakeAuthorizationCredentials() {}

  public static final class Credential {
    private final byte[] bytes;

    private Credential(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    public static Credential of(String value) {
      if (!validCredential(value)) throw invalid();
      return new Credential(value.getBytes(StandardCharsets.US_ASCII));
    }

    public String value() {
      if (bytes.length == 0 || bytes.length > MAX_CREDENTIAL_BYTES) throw invalid();
      for (byte value : bytes) if (value <= 32 || value >= 127) throw invalid();
      return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Override
    public String toString() {
      return "GameLogicIntakeCreatorCredential[redacted]";
    }
  }

  public static Credential readAuthenticated(Metadata headers, String methodName) {
    if (!allowedMethod(methodName)) throw invalid();
    var values = headers.getAll(HEADER);
    if (values == null) throw invalid();
    var iterator = values.iterator();
    if (!iterator.hasNext()) throw invalid();
    Credential credential = iterator.next();
    if (iterator.hasNext()) throw invalid();
    credential.value();
    return credential;
  }

  public static CallCredentials forAuthorize(String value) {
    return forMethod(
        value,
        AccountGameLogicIntakeAuthorizationServiceGrpc.getAuthorizeIntakeMethod()
            .getFullMethodName());
  }

  public static CallCredentials forRecover(String value) {
    return forMethod(
        value,
        AccountGameLogicIntakeAuthorizationServiceGrpc.getRecoverIntakeMethod()
            .getFullMethodName());
  }

  public static CallCredentials forAbort(String value) {
    return forMethod(
        value,
        AccountGameLogicIntakeAuthorizationServiceGrpc.getAbortIntakeMethod().getFullMethodName());
  }

  private static CallCredentials forMethod(String value, String allowedMethod) {
    var credential = Credential.of(value);
    return new CallCredentials() {
      @Override
      public void applyRequestMetadata(
          RequestInfo info, Executor executor, MetadataApplier applier) {
        if (info.getSecurityLevel() != SecurityLevel.PRIVACY_AND_INTEGRITY
            || !allowedMethod.equals(info.getMethodDescriptor().getFullMethodName())) {
          applier.fail(
              Status.UNAUTHENTICATED.withDescription(
                  "Protected Account intake authorization transport required"));
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
        return "GameLogicIntakeCreatorCallCredentials[redacted]";
      }
    };
  }

  public static boolean allowedMethod(String methodName) {
    return AccountGameLogicIntakeAuthorizationServiceGrpc.getAuthorizeIntakeMethod()
            .getFullMethodName()
            .equals(methodName)
        || AccountGameLogicIntakeAuthorizationServiceGrpc.getRecoverIntakeMethod()
            .getFullMethodName()
            .equals(methodName)
        || AccountGameLogicIntakeAuthorizationServiceGrpc.getAbortIntakeMethod()
            .getFullMethodName()
            .equals(methodName);
  }

  private static boolean validCredential(String credential) {
    return credential != null
        && !credential.isEmpty()
        && credential.length() <= MAX_CREDENTIAL_BYTES
        && credential.chars().allMatch(value -> value > 32 && value < 127);
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid Game Logic intake credential metadata");
  }
}
