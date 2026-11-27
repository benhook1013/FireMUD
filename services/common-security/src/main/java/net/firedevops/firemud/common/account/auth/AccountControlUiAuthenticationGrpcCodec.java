package net.firedevops.firemud.common.account.auth;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.account.v1.AccountControlUiAuthLoginStatus;
import net.firedevops.firemud.account.v1.AccountControlUiCredentialTransportClass;
import net.firedevops.firemud.account.v1.AuthLoginRequest;
import net.firedevops.firemud.account.v1.AuthLoginResponse;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed bounded transport mapping for the Gateway-to-Account Control-UI login handoff. */
public final class AccountControlUiAuthenticationGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 8 * 1024;
  public static final int MAX_RESPONSE_WIRE_BYTES = 20 * 1024;
  public static final int MAX_EMAIL_UTF8_BYTES = 320;
  public static final int MAX_SECRET_UTF8_BYTES = 4 * 1024;
  public static final int MAX_EMAIL_CHARS = 254;
  public static final int MAX_SECRET_CHARS = 1024;
  public static final int MAX_COMPACT_AUTH_TOKEN_BYTES = 16 * 1024;

  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern COMPACT_JWT =
      Pattern.compile("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

  private AccountControlUiAuthenticationGrpcCodec() {}

  /**
   * The secret exists only in this transient request and is deliberately redacted in diagnostics.
   */
  public record Request(
      UUID requestId,
      UUID callerContextId,
      String callerNamespace,
      String email,
      String secret,
      SourceContext sourceContext) {
    public Request {
      requireUuid(requestId);
      requireUuid(callerContextId);
      requireNamespace(callerNamespace);
      requireEmail(email);
      requireSecret(secret);
      if (sourceContext == null) throw invalid();
    }

    @Override
    public String toString() {
      return "AccountControlUiLoginRequest[redacted]";
    }
  }

  /** Transient Gateway-attested HTTP source. Construction validates shape, not provenance. */
  public record SourceContext(String canonicalClientAddress) {
    public SourceContext {
      if (canonicalClientAddress == null
          || canonicalClientAddress.length() > 64
          || !canonicalClientAddress.matches("[0-9a-f:.]+")) throw invalid();
      try {
        // Literal-only parsing never performs DNS. Match the Gateway's normalized JDK spelling.
        if (!InetAddress.ofLiteral(canonicalClientAddress)
            .getHostAddress()
            .equals(canonicalClientAddress)) throw invalid();
      } catch (IllegalArgumentException malformed) {
        throw invalid();
      }
    }

    @Override
    public String toString() {
      return "AccountControlUiCredentialSource[redacted]";
    }
  }

  /** Successful Account-authenticated result. The token is never included in diagnostics. */
  public record Authenticated(UUID accountId, String authToken, long expiresAtEpochSeconds) {
    public Authenticated {
      requireUuid(accountId);
      requireToken(authToken);
      requireExpiry(expiresAtEpochSeconds);
    }

    @Override
    public String toString() {
      return "AccountControlUiLoginResult[redacted]";
    }
  }

  public enum Outcome {
    SUCCESS,
    DENIED,
    RETRY_LATER,
    ABUSE_UNAVAILABLE
  }

  /** A denial intentionally carries no account identity or token. */
  public record Result(Outcome outcome, Authenticated authenticated, Long retryAfterMillis) {
    public Result(Outcome outcome, Authenticated authenticated) {
      this(outcome, authenticated, null);
    }

    public Result {
      Objects.requireNonNull(outcome);
      if ((outcome == Outcome.SUCCESS) != (authenticated != null)) throw invalid();
      if (outcome == Outcome.RETRY_LATER && retryAfterMillis == null) throw invalid();
      if (retryAfterMillis != null) {
        if ((outcome != Outcome.RETRY_LATER && outcome != Outcome.ABUSE_UNAVAILABLE)
            || retryAfterMillis < 1
            || retryAfterMillis > 9_007_199_254_740_991L) throw invalid();
      }
    }

    @Override
    public String toString() {
      return "AccountControlUiLoginResult[" + outcome + "]";
    }
  }

  public static AuthLoginRequest toRequest(Request request) {
    Objects.requireNonNull(request);
    AuthLoginRequest wire =
        AuthLoginRequest.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(request.callerNamespace())
            .addRequestId(request.requestId().toString())
            .addCallerContextId(request.callerContextId().toString())
            .addEmail(request.email())
            .addSecret(request.secret())
            .addCanonicalClientAddress(request.sourceContext().canonicalClientAddress())
            .addTransportClass(
                AccountControlUiCredentialTransportClass
                    .ACCOUNT_CONTROL_UI_CREDENTIAL_TRANSPORT_CLASS_HTTP)
            .build();
    parseRequest(wire);
    return wire;
  }

  public static Request parseRequest(AuthLoginRequest wire) {
    if (wire == null
        || wire.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()) throw invalid();
    if (oneInt(wire.getSchemaVersionList()) != SCHEMA_VERSION) throw invalid();
    if (oneInt(wire.getTransportClassValueList())
        != AccountControlUiCredentialTransportClass
            .ACCOUNT_CONTROL_UI_CREDENTIAL_TRANSPORT_CLASS_HTTP_VALUE) throw invalid();
    return new Request(
        uuid(one(wire.getRequestIdList())),
        uuid(one(wire.getCallerContextIdList())),
        one(wire.getTargetNamespaceList()),
        one(wire.getEmailList()),
        one(wire.getSecretList()),
        new SourceContext(one(wire.getCanonicalClientAddressList())));
  }

  /** Maps only stable request context into the response; the primary secret is never echoed. */
  public static AuthLoginResponse toResponse(Request request, Authenticated authenticated) {
    Objects.requireNonNull(request);
    AuthLoginResponse.Builder builder =
        AuthLoginResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(request.callerNamespace())
            .addRequestId(request.requestId().toString())
            .addCallerContextId(request.callerContextId().toString())
            .addStatus(
                authenticated == null
                    ? AccountControlUiAuthLoginStatus.ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_DENIED
                    : AccountControlUiAuthLoginStatus.ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_SUCCESS);
    if (authenticated != null) {
      builder
          .addAccountId(authenticated.accountId().toString())
          .addAuthToken(authenticated.authToken())
          .addExpiresAtEpochSeconds(authenticated.expiresAtEpochSeconds());
    }
    AuthLoginResponse wire = builder.build();
    requireResponse(request, wire);
    return wire;
  }

  /** Closed policy refusal; never contains account fields or echoes credential data. */
  public static AuthLoginResponse toRefusalResponse(
      Request request, Outcome outcome, Long retryAfterMillis) {
    new Result(outcome, null, retryAfterMillis);
    if (outcome != Outcome.RETRY_LATER && outcome != Outcome.ABUSE_UNAVAILABLE) throw invalid();
    var builder =
        AuthLoginResponse.newBuilder()
            .addSchemaVersion(SCHEMA_VERSION)
            .addTargetNamespace(request.callerNamespace())
            .addRequestId(request.requestId().toString())
            .addCallerContextId(request.callerContextId().toString())
            .addStatus(
                outcome == Outcome.RETRY_LATER
                    ? AccountControlUiAuthLoginStatus
                        .ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_RETRY_LATER
                    : AccountControlUiAuthLoginStatus
                        .ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_ABUSE_UNAVAILABLE);
    if (retryAfterMillis != null) builder.addRetryAfterMillis(retryAfterMillis);
    var wire = builder.build();
    requireResponse(request, wire);
    return wire;
  }

  /** Validates the complete response against the exact request before exposing a token. */
  public static Result requireResponse(Request request, AuthLoginResponse wire) {
    Objects.requireNonNull(request);
    if (wire == null
        || wire.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || !wire.getUnknownFields().asMap().isEmpty()
        || oneInt(wire.getSchemaVersionList()) != SCHEMA_VERSION
        || !request.callerNamespace().equals(one(wire.getTargetNamespaceList()))
        || !request.requestId().equals(uuid(one(wire.getRequestIdList())))
        || !request.callerContextId().equals(uuid(one(wire.getCallerContextIdList())))
        || wire.getStatusValueList().size() != 1) throw invalid();

    int status = wire.getStatusValueList().getFirst();
    if (status
            == AccountControlUiAuthLoginStatus
                .ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_RETRY_LATER_VALUE
        || status
            == AccountControlUiAuthLoginStatus
                .ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_ABUSE_UNAVAILABLE_VALUE) {
      if (!wire.getAccountIdList().isEmpty()
          || !wire.getAuthTokenList().isEmpty()
          || !wire.getExpiresAtEpochSecondsList().isEmpty()) throw invalid();
      Long retry =
          wire.getRetryAfterMillisList().isEmpty() ? null : oneLong(wire.getRetryAfterMillisList());
      return new Result(
          status
                  == AccountControlUiAuthLoginStatus
                      .ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_RETRY_LATER_VALUE
              ? Outcome.RETRY_LATER
              : Outcome.ABUSE_UNAVAILABLE,
          null,
          retry);
    }
    if (!wire.getRetryAfterMillisList().isEmpty()) throw invalid();
    if (status
        == AccountControlUiAuthLoginStatus.ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_DENIED_VALUE) {
      if (!wire.getAccountIdList().isEmpty()
          || !wire.getAuthTokenList().isEmpty()
          || !wire.getExpiresAtEpochSecondsList().isEmpty()) throw invalid();
      return new Result(Outcome.DENIED, null);
    }
    if (status
        != AccountControlUiAuthLoginStatus.ACCOUNT_CONTROL_UI_AUTH_LOGIN_STATUS_SUCCESS_VALUE)
      throw invalid();
    Authenticated authenticated =
        new Authenticated(
            uuid(one(wire.getAccountIdList())),
            one(wire.getAuthTokenList()),
            oneLong(wire.getExpiresAtEpochSecondsList()));
    return new Result(Outcome.SUCCESS, authenticated);
  }

  private static String one(List<String> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static int oneInt(List<Integer> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static long oneLong(List<Long> values) {
    if (values.size() != 1) throw invalid();
    return values.getFirst();
  }

  private static UUID uuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) throw invalid();
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static void requireUuid(UUID value) {
    if (value == null || value.equals(NIL_UUID)) throw invalid();
  }

  private static void requireNamespace(String value) {
    if (!GrpcPeerIdentity.isValidNamespace(value)) throw invalid();
  }

  private static void requireEmail(String value) {
    int bytes = utf8Length(value);
    if (bytes < 3
        || bytes > MAX_EMAIL_UTF8_BYTES
        || value.length() > MAX_EMAIL_CHARS
        || value.indexOf('@') <= 0) throw invalid();
    int at = value.indexOf('@');
    if (at != value.lastIndexOf('@')
        || at == value.length() - 1
        || value.chars().anyMatch(Character::isWhitespace)
        || value.chars().anyMatch(Character::isISOControl)) throw invalid();
  }

  private static void requireSecret(String value) {
    int bytes = utf8Length(value);
    if (bytes < 1
        || bytes > MAX_SECRET_UTF8_BYTES
        || value.length() > MAX_SECRET_CHARS
        || value.indexOf('\0') >= 0) throw invalid();
  }

  private static void requireToken(String value) {
    int length = utf8Length(value);
    if (length < 1
        || length > MAX_COMPACT_AUTH_TOKEN_BYTES
        || !COMPACT_JWT.matcher(value).matches()) throw invalid();
  }

  private static void requireExpiry(long epochSeconds) {
    if (epochSeconds <= 0L) throw invalid();
    try {
      Instant.ofEpochSecond(epochSeconds);
    } catch (RuntimeException malformed) {
      throw invalid();
    }
  }

  private static int utf8Length(String value) {
    if (value == null) throw invalid();
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      return encoded.remaining();
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exact bounded Control-UI authentication message required");
  }
}
