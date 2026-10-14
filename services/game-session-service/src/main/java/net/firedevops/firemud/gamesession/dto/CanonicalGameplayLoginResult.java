package net.firedevops.firemud.gamesession.dto;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateRequest;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.GameplayLoginTransportClass;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Complete authenticated Account response, with process-local token material. Signature and
 * aggregate validation are non-admitting: no context CAS, ACTIVE/currentness lease, Redis writer,
 * orphan retirement or authenticated public-ingress authority is supplied by this value.
 */
public final class CanonicalGameplayLoginResult implements AutoCloseable {
  // Each projection is contained in Account's bounded 8 KiB initial owner bundle.
  private static final int MAX_CHECKPOINT_PROJECTION_BYTES = 8 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> DECISIONS =
      Set.of(
          AuthenticationErrorCodes.INVALID_CREDENTIALS,
          AuthenticationErrorCodes.ACCOUNT_LOCKED,
          AuthenticationErrorCodes.RETRY_LATER,
          "IDEMPOTENCY_CONFLICT",
          "RESPONSE_RECOVERY_EXPIRED");
  private static final ExplicitRouteProfilePolicy INITIAL_POLICY =
      new ExplicitRouteProfilePolicy(
          "game-session-canonical-credential-login",
          GameSessionAccountDelegationProfile.PROFILE,
          GameSessionAccountDelegationProfile.TYPE,
          GameSessionAccountDelegationProfile.ISSUER,
          GameSessionAccountDelegationProfile.AUDIENCE,
          GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
          Set.of("globalRoles", "scopedRoles"),
          GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
          5,
          GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
          GameSessionAccountDelegationJwtProfileValidator::validateInitialClaims);
  private final byte[] compactJwt;
  private final NonSecretEvidence evidence;
  private final Object lifecycleMutex = new Object();
  private boolean closed;

  private CanonicalGameplayLoginResult(byte[] compactJwt, NonSecretEvidence evidence) {
    this.compactJwt = compactJwt.clone();
    this.evidence = evidence;
  }

  /** Called only after the client has authenticated the exact Account transport peer. */
  public static CanonicalGameplayLoginResult fromAuthenticatedResponse(
      AuthenticateResponse response,
      AuthenticateRequest originalRequest,
      AccountAsymmetricJwtVerifier verifier,
      Clock clock) {
    requireOriginalRequest(originalRequest);
    Objects.requireNonNull(verifier, "Asymmetric Account verifier required");
    Objects.requireNonNull(clock, "Clock required");
    if (response == null || !response.getUnknownFields().asMap().isEmpty()) throw invalid();
    if (response.hasError()) {
      if (!response.getError().getUnknownFields().asMap().isEmpty()
          || !DECISIONS.contains(response.getError().getCode())
          || response.getError().getMessage().length() > 256
          || !response.toBuilder()
              .clearError()
              .build()
              .equals(AuthenticateResponse.getDefaultInstance())) {
        throw invalid();
      }
      throw new CredentialDecisionException(response.getError().getCode());
    }
    byte[] token = null;
    try {
      if (!originalRequest.getRequestId().equals(response.getRequestId())
          || !originalRequest
              .getSourceContext()
              .getCallerContextId()
              .equals(response.getCallerContextId())
          || !GameSessionAccountDelegationProfile.PROFILE.equals(response.getTokenProfile()))
        throw invalid();
      UUID accountId = uuid(response.getAccountId());
      UUID jti = v4(response.getTokenJti());
      String compact = response.getAuthToken();
      if (compact.isEmpty()
          || compact.length() > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
          || compact.chars().anyMatch(value -> value > 127)) throw invalid();
      token = compact.getBytes(StandardCharsets.US_ASCII);
      if (!HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(token))
          .equals(response.getTokenSha256())) throw invalid();
      var claims = verifier.verify(compact, INITIAL_POLICY).claims();
      if (!accountId.toString().equals(claims.get("accountId"))
          || !accountId.toString().equals(claims.get("sub"))
          || !jti.toString().equals(claims.get("jti"))
          || epoch(claims.get("iat")) != response.getIssuedAtEpochSecond()
          || epoch(claims.get("nbf")) != response.getNotBeforeEpochSecond()
          || epoch(claims.get("exp")) != response.getExpiresAtEpochSecond()
          || !response.getTokenGeneration().equals(claims.get("tokenGeneration"))
          || !response.getIssuanceFence().equals(claims.get("issuanceFence"))
          || response.getExpiresAtEpochSecond() <= clock.instant().getEpochSecond())
        throw invalid();
      Object tuple =
          canonicalProjection(
              response.getAuthorityTupleCanonicalJson(),
              GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES);
      if (!Arrays.equals(
          response.getAuthorityTupleCanonicalJson().toByteArray(),
          Rfc8785CanonicalJson.canonicalizeUtf8(
              JSON.writeValueAsString(claims.get("authorityTuple"))))) throw invalid();
      validateCheckpoints(response, accountId, object(tuple));
      return new CanonicalGameplayLoginResult(
          token,
          new NonSecretEvidence(
              accountId,
              v4(response.getRequestId()),
              v4(response.getCallerContextId()),
              jti,
              response.getTokenSha256(),
              response.getTokenProfile(),
              response.getIssuedAtEpochSecond(),
              response.getNotBeforeEpochSecond(),
              response.getExpiresAtEpochSecond(),
              response.getTokenGeneration(),
              response.getIssuanceFence(),
              response.getAuthorityTupleCanonicalJson(),
              response.getOutboxCheckpointsCanonicalJson(),
              response.getOutboxSourceEventEvidenceCanonicalJson()));
    } catch (AccountAsymmetricJwtVerifier.VerificationUnavailableException unavailable) {
      throw unavailable;
    } catch (Exception failure) {
      // Never retain parser messages, credentials, token bytes or potentially sensitive causes.
      throw invalid();
    } finally {
      if (token != null) Arrays.fill(token, (byte) 0);
    }
  }

  /**
   * Syntax/bounds only; trusted ingress and logical-attempt ownership remain caller obligations.
   */
  public static void requireOriginalRequest(AuthenticateRequest request) {
    try {
      if (request == null
          || !request.getUnknownFields().asMap().isEmpty()
          || !request.hasSourceContext()
          || !request.getSourceContext().getUnknownFields().asMap().isEmpty()
          || request.getSourceContext().getTransportClass()
              != GameplayLoginTransportClass.GAMEPLAY_LOGIN_TRANSPORT_CLASS_TRUSTED_TCP_PROXY)
        throw invalid();
      v4(request.getRequestId());
      v4(request.getSourceContext().getCallerContextId());
      String address = request.getSourceContext().getCanonicalClientAddress();
      if (address.isBlank()
          || address.length() > 256
          || !address.equals(address.trim())
          || address.chars().anyMatch(Character::isISOControl)
          || request.getEmail().length() > 320
          || request.getPassword().length() > 1024) throw invalid();
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  private static void validateCheckpoints(
      AuthenticateResponse response, UUID accountId, Map<?, ?> tuple) throws Exception {
    Object checkpointProjection =
        canonicalProjection(
            response.getOutboxCheckpointsCanonicalJson(), MAX_CHECKPOINT_PROJECTION_BYTES);
    Object eventProjection =
        canonicalProjection(
            response.getOutboxSourceEventEvidenceCanonicalJson(), MAX_CHECKPOINT_PROJECTION_BYTES);
    if (!(checkpointProjection instanceof List<?> checkpoints)
        || checkpoints.size() != 2
        || !(eventProjection instanceof List<?> events)
        || events.size() > 2) throw invalid();
    List<String> streams =
        List.of(
            "account:auth-authority:v1:account/" + accountId,
            "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER);
    int positive = 0;
    for (int index = 0; index < streams.size(); index++) {
      Map<?, ?> checkpoint = object(checkpoints.get(index));
      if (!checkpoint.keySet().equals(Set.of("outboxStreamKey", "outboxSequence"))
          || !streams.get(index).equals(checkpoint.get("outboxStreamKey"))) throw invalid();
      long sequence = sequence(checkpoint.get("outboxSequence"));
      if (sequence > 0) {
        if (positive >= events.size()) throw invalid();
        Map<?, ?> event = object(events.get(positive++));
        if (!event
                .keySet()
                .equals(
                    Set.of(
                        "outboxStreamKey", "outboxSequence", "sourceEventId", "sourceEventDigest"))
            || !checkpoint.get("outboxStreamKey").equals(event.get("outboxStreamKey"))
            || !checkpoint.get("outboxSequence").equals(event.get("outboxSequence"))
            || !(event.get("sourceEventId") instanceof String eventId)
            || eventId.isBlank()
            || eventId.length() > 512
            || eventId.chars().anyMatch(Character::isISOControl)
            || !(event.get("sourceEventDigest") instanceof String digest)
            || !digest.matches("sha256:[0-9a-f]{64}")) throw invalid();
      }
      if (index == 0 && tuple.containsKey("accountSecurityCutoff")) {
        Map<?, ?> cutoff = object(tuple.get("accountSecurityCutoff"));
        if (!streams.getFirst().equals(cutoff.get("outboxStreamKey"))
            || !tuple
                .get("accountAuthorityGeneration")
                .equals(cutoff.get("accountAuthorityGeneration"))
            || sequence(cutoff.get("outboxSequence")) == 0
            || sequence < sequence(cutoff.get("outboxSequence"))) throw invalid();
      }
    }
    if (positive != events.size()) throw invalid();
  }

  private static Object canonicalProjection(ByteString value, int bound) throws Exception {
    if (value.isEmpty() || value.size() > bound) throw invalid();
    byte[] bytes = value.toByteArray();
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    if (!Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(text))) throw invalid();
    return JSON.readValue(text, Object.class);
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    return map;
  }

  private static long epoch(Object value) {
    if (!(value instanceof Number number)) throw invalid();
    long result = new BigDecimal(number.toString()).longValueExact();
    if (result <= 0) throw invalid();
    return result;
  }

  private static long sequence(Object value) {
    if (!(value instanceof String text) || !text.matches("0|[1-9][0-9]{0,18}")) throw invalid();
    return Long.parseLong(text);
  }

  private static UUID uuid(String text) {
    UUID id = UUID.fromString(text);
    if (!id.toString().equals(text) || id.equals(new UUID(0, 0)) || id.variant() != 2)
      throw invalid();
    return id;
  }

  private static UUID v4(String text) {
    UUID id = uuid(text);
    if (id.version() != 4) throw invalid();
    return id;
  }

  public byte[] compactJwtBytes() {
    synchronized (lifecycleMutex) {
      if (closed) throw new IllegalStateException("Canonical LOGIN private result is closed");
      return compactJwt.clone();
    }
  }

  /** Ends this invocation's owned compact-token custody and overwrites its private byte array. */
  @Override
  public void close() {
    synchronized (lifecycleMutex) {
      if (!closed) {
        Arrays.fill(compactJwt, (byte) 0);
        closed = true;
      }
    }
  }

  public NonSecretEvidence evidence() {
    return evidence;
  }

  @Override
  public String toString() {
    return "CanonicalGameplayLoginResult[process-local,redacted,non-admitting]";
  }

  /**
   * Contains every non-secret wire field, preserving exact immutable canonical projection bytes.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The three canonical projection fields are immutable protobuf ByteString values; generated record construction and accessors expose no mutable arrays or buffers.")
  public record NonSecretEvidence(
      UUID accountId,
      UUID requestId,
      UUID callerContextId,
      UUID tokenJti,
      String tokenSha256,
      String tokenProfile,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond,
      String tokenGeneration,
      String issuanceFence,
      ByteString authorityTupleCanonicalJson,
      ByteString outboxCheckpointsCanonicalJson,
      ByteString outboxSourceEventEvidenceCanonicalJson) {
    @Override
    public String toString() {
      return "CanonicalGameplayLoginEvidence[non-admitting]";
    }
  }

  private static InvalidResultException invalid() {
    return new InvalidResultException();
  }

  public static final class InvalidResultException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    public InvalidResultException() {
      super("Canonical LOGIN request or result is invalid");
    }
  }

  public static final class CredentialDecisionException extends IllegalStateException {
    private static final long serialVersionUID = 1L;
    private final String code;

    private CredentialDecisionException(String code) {
      super("Canonical LOGIN was rejected");
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
