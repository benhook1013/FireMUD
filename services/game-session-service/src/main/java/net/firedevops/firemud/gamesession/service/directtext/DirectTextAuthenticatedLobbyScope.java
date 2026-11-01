package net.firedevops.firemud.gamesession.service.directtext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.account.v1.IssueDirectTextConnectScopeResponse;
import net.firedevops.firemud.gamesession.client.DirectTextConnectScopeTarget;
import net.firedevops.firemud.gamesession.client.DirectTextIssuedConnectScope;
import net.firedevops.firemud.gamesession.command.text.ObservedDirectTextTransport;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginContextState;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable nonsecret historical evidence. Neither construction nor decoding authenticates. */
public final class DirectTextAuthenticatedLobbyScope {
  public static final int MAX_BYTES = 128 * 1024;
  private static final String SCHEMA = "direct-text-authenticated-lobby/v1";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .build();

  private final UUID recordId;
  private final InitialGameplayLoginContextState login;
  private final ObservedDirectTextTransport transport;
  private final CanonicalPlayableTarget publicTarget;
  private final DirectTextIssuedConnectScope issued;

  public DirectTextAuthenticatedLobbyScope(
      UUID recordId,
      InitialGameplayLoginContextState login,
      ObservedDirectTextTransport transport,
      CanonicalPlayableTarget publicTarget,
      DirectTextIssuedConnectScope issued) {
    requireV4(recordId);
    this.recordId = recordId;
    this.login = Objects.requireNonNull(login);
    this.transport = Objects.requireNonNull(transport);
    this.publicTarget = Objects.requireNonNull(publicTarget);
    this.issued = Objects.requireNonNull(issued);
    if (login.state() != InitialGameplayLoginContextState.State.INSTALLED
        || !login.evidence().accountId().toString().equals(issued.accountId())
        || !targetFor(publicTarget).equals(issued.target())
        || recordId.equals(login.coordinates().requestId())
        || recordId.equals(login.coordinates().callerContextId())
        || recordId.equals(transport.proxyConnectionId())
        || recordId.equals(login.evidence().tokenJti())
        || transport.transportSessionId().length() > 32) throw invalid();
  }

  public UUID recordId() {
    return recordId;
  }

  public InitialGameplayLoginAttemptCoordinates original() {
    return login.coordinates();
  }

  public CanonicalGameplayLoginResult.NonSecretEvidence evidence() {
    return login.evidence();
  }

  public ObservedDirectTextTransport transport() {
    return transport;
  }

  public CanonicalPlayableTarget publicTarget() {
    return publicTarget;
  }

  public DirectTextIssuedConnectScope issued() {
    return issued;
  }

  public void requireCurrent(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      ObservedDirectTextTransport currentTransport,
      Clock clock) {
    if (!original().equals(original)
        || !evidence().equals(verified.evidence())
        || !transport.equals(currentTransport)
        || !issued.isLive(clock)) throw invalid();
    requireLiveResult(original, verified, clock);
  }

  /** Checks private lifecycle without retaining, returning or serializing compact bytes. */
  public static void requireLiveResult(
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult verified,
      Clock clock) {
    var evidence = Objects.requireNonNull(verified).evidence();
    long now = clock.instant().getEpochSecond();
    if (!original.requestId().equals(evidence.requestId())
        || !original.callerContextId().equals(evidence.callerContextId())
        || now < evidence.issuedAtEpochSecond()
        || now < evidence.notBeforeEpochSecond()
        || now >= evidence.expiresAtEpochSecond()) throw invalid();
    byte[] borrowed = verified.compactJwtBytes();
    Arrays.fill(borrowed, (byte) 0);
  }

  public byte[] encode() {
    try {
      var output = new BoundedOutput();
      JSON.writeValue(
          output,
          new Envelope(
              SCHEMA,
              recordId,
              new String(login.encode(), StandardCharsets.UTF_8),
              transport,
              publicTarget,
              issued.connectScopeId(),
              issued.evaluatedAt(),
              issued.connectScopeExpiresAt()));
      return output.toByteArray();
    } catch (Exception failure) {
      throw invalid();
    }
  }

  public static DirectTextAuthenticatedLobbyScope decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
    try {
      Envelope value = JSON.readValue(bytes, Envelope.class);
      if (value == null
          || !SCHEMA.equals(value.schema())
          || value.loginState() == null
          || value.loginState().length() > InitialGameplayLoginContextState.MAX_BYTES
          || value.evaluatedAt() == null
          || value.evaluatedAt().length() > 40
          || value.expiresAt() == null
          || value.expiresAt().length() > 40) throw invalid();
      var login =
          InitialGameplayLoginContextState.decode(
              value.loginState().getBytes(StandardCharsets.UTF_8));
      // Historical decoding uses the ORIGINAL evaluation instant only to reconstruct the existing
      // immutable value. It never extends expiry or establishes permission to use an expired scope.
      var issued =
          DirectTextIssuedConnectScope.fromAccountResponse(
              login.evidence().accountId().toString(),
              targetFor(value.publicTarget()),
              IssueDirectTextConnectScopeResponse.newBuilder()
                  .setConnectScopeId(value.connectScopeId())
                  .setEvaluatedAt(value.evaluatedAt())
                  .setConnectScopeExpiresAt(value.expiresAt())
                  .build(),
              Clock.fixed(Instant.parse(value.evaluatedAt()), ZoneOffset.UTC));
      var result =
          new DirectTextAuthenticatedLobbyScope(
              value.recordId(), login, value.transport(), value.publicTarget(), issued);
      if (!Arrays.equals(bytes, result.encode())) throw invalid();
      return result;
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  public static DirectTextConnectScopeTarget targetFor(CanonicalPlayableTarget target) {
    Objects.requireNonNull(target);
    return new DirectTextConnectScopeTarget(
        Long.toString(target.gameSessionTenantId()),
        target.worldSlug(),
        target.realmSlug(),
        target.realmId().toString(),
        target.playableStateNamespaceId().toString(),
        target.playableStateScope(),
        Long.toString(target.gameInstanceId()),
        target.catalogRevision(),
        target.pointerVersion());
  }

  public static void requireV4(UUID value) {
    if (value == null || value.version() != 4 || value.variant() != 2) throw invalid();
  }

  private record Envelope(
      String schema,
      UUID recordId,
      String loginState,
      ObservedDirectTextTransport transport,
      CanonicalPlayableTarget publicTarget,
      String connectScopeId,
      String evaluatedAt,
      String expiresAt) {}

  private static final class BoundedOutput extends ByteArrayOutputStream {
    @Override
    public synchronized void write(int value) {
      requireCapacity(1);
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      requireCapacity(length);
      super.write(bytes, offset, length);
    }

    private void requireCapacity(int additional) {
      if (additional > MAX_BYTES - count) throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Exact authenticated lobby evidence required");
  }

  @Override
  public String toString() {
    return "DirectTextAuthenticatedLobbyScope[nonsecret,non-admitting]";
  }
}
