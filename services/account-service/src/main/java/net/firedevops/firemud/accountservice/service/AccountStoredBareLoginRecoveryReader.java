package net.firedevops.firemud.accountservice.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeIdentity;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeOperation;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginExchangeRepository;
import net.firedevops.firemud.accountservice.repository.AccountBareLoginResponseEnvelope;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeBinding;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.BareLoginRecoveryPayload;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader.HistoricalCommittedSourceEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.HistoricalGatewayConnectEvidence;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired, non-authorizing readback of one committed bare-LOGIN response frame and its original
 * committed connect source. It never releases or retains the opaque original result, compact JWT,
 * or original signed Gateway context.
 */
public final class AccountStoredBareLoginRecoveryReader {
  private final AccountBareLoginExchangeRepository exchangeRepository;
  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountEnvelopeCrypto envelopeCrypto;
  private final AccountCommittedConnectSourceReader committedSourceReader;
  private final Map<String, PublicKey> gatewayVerificationKeys;
  private final String expectedGameSessionPeerUri;

  public AccountStoredBareLoginRecoveryReader(
      AccountBareLoginExchangeRepository exchangeRepository,
      AccountJoinOperationRepository joinOperationRepository,
      AccountEnvelopeCrypto envelopeCrypto,
      AccountCommittedConnectSourceReader committedSourceReader,
      Map<String, PublicKey> gatewayVerificationKeys,
      String workloadNamespace) {
    this.exchangeRepository = Objects.requireNonNull(exchangeRepository, "exchangeRepository");
    this.joinOperationRepository =
        Objects.requireNonNull(joinOperationRepository, "joinOperationRepository");
    this.envelopeCrypto = Objects.requireNonNull(envelopeCrypto, "envelopeCrypto");
    this.committedSourceReader =
        Objects.requireNonNull(committedSourceReader, "committedSourceReader");
    this.gatewayVerificationKeys = Map.copyOf(gatewayVerificationKeys);
    if (this.gatewayVerificationKeys.isEmpty()) {
      throw new IllegalArgumentException("Gateway verification keys are required");
    }
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalArgumentException("Account workload namespace is required");
    }
    this.expectedGameSessionPeerUri =
        GrpcPeerIdentity.parseUri(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
            .filter(identity -> workloadNamespace.equals(identity.namespace()))
            .map(GrpcPeerIdentity::uri)
            .orElseThrow(
                () -> new IllegalArgumentException("Account workload namespace is invalid"));
  }

  /**
   * Verifies committed encrypted V37/V35 provenance for an exact bare-LOGIN exchange identity and
   * its caller-supplied immutable request digest. The stored frame is decoded only to verify its
   * result hash and original historical Gateway/source correspondence; result bytes are never
   * returned.
   *
   * <p>This proves stored framed provenance only. It does not prove prior Gateway acceptance,
   * current authority, freshness, the original result expiry or recovery horizon, registry state,
   * admission postconditions, recovery eligibility, or authorization. It does not release a
   * credential or enable the bare-LOGIN runtime path.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public HistoricalStoredBareLoginEvidence readHistorical(
      AccountBareLoginExchangeIdentity identity, byte[] requestDigest) {
    Objects.requireNonNull(identity, "identity");
    requireGameSessionPeer();
    requireOwnerTransaction();
    return readHistoricalStored(identity, requestDigest).evidence();
  }

  /**
   * Correlates exact stored original exchange/source history with a separately committed, strictly
   * current Account connect source and its independently valid current Gateway context. This is
   * only immutable source/target correspondence evidence: it does not prove current authority or
   * membershipVersion, original result expiry, registry state, admission postconditions, recovery
   * eligibility, or authorization, and releases no result, credential, JWT, or signed context.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public HistoricalStoredBareLoginCorrelationEvidence readCorrelation(
      AccountBareLoginExchangeIdentity originalIdentity,
      byte[] originalRequestDigest,
      AccountConnectTokenIssuanceIdentity freshIdentity,
      String signedFreshGatewayContext) {
    Objects.requireNonNull(originalIdentity, "originalIdentity");
    Objects.requireNonNull(freshIdentity, "freshIdentity");
    Objects.requireNonNull(signedFreshGatewayContext, "signedFreshGatewayContext");
    requireGameSessionPeer();
    requireOwnerTransaction();
    if (originalIdentity.accountId() != freshIdentity.accountId()
        || !originalIdentity.tenantId().equals(freshIdentity.tenantId())) {
      throw new IllegalArgumentException(
          "Original and fresh source identities must belong to the same Account and tenant");
    }

    // Establish the independent strict-current source first. Its Account reader checks the signed
    // Gateway deadline again after any Account-fence wait, before this component reads the opaque
    // stored original result frame.
    var fresh = committedSourceReader.read(freshIdentity, signedFreshGatewayContext);
    if (originalIdentity.sourceConnectOperationId().equals(fresh.operationId())) {
      throw new IllegalStateException(
          "Fresh connect proof must be a separate committed Account source operation");
    }
    HistoricalReadback original = readHistoricalStored(originalIdentity, originalRequestDigest);

    SelectedTargetEvidence originalTarget =
        selectedTarget(original.source().originalSourceClaims());
    SelectedTargetEvidence freshTarget = selectedTarget(fresh.originalSourceClaims());
    if (!originalTarget.equals(freshTarget)) {
      throw new IllegalStateException(
          "Fresh connect source does not select the exact original Account target");
    }

    // The original encrypted frame read can itself take time after the strict source reader's
    // fence recheck. Re-run the unchanged strict-current path immediately before returning so an
    // assertion that expires during history work cannot yield correlation evidence.
    var finalFresh = committedSourceReader.read(freshIdentity, signedFreshGatewayContext);
    requireSameCurrentReadback(fresh, finalFresh);

    return new HistoricalStoredBareLoginCorrelationEvidence(
        original.evidence(),
        connectSourceIdentity(original.source()),
        connectSourceIdentity(freshIdentity, finalFresh),
        originalTarget);
  }

  private HistoricalReadback readHistoricalStored(
      AccountBareLoginExchangeIdentity identity, byte[] requestDigest) {
    byte[] checkedRequestDigest = requireDigest(requestDigest, "request digest");
    byte[] frameBytes = null;
    byte[] originalGatewayContextBytes = null;
    byte[] originalResultBytes = null;
    byte[] originalResultHash = null;
    byte[] storedResultHash = null;
    byte[] sourceConnectTokenHash = null;
    try {
      // This is the same Account row fence used by the original-source reader. No operation,
      // envelope, key-ring or signed-context access happens before the exact caller guard above.
      joinOperationRepository.lockAccount(identity.accountId());

      AccountBareLoginExchangeOperation operation =
          exchangeRepository
              .find(identity, checkedRequestDigest)
              .orElseThrow(() -> new IllegalStateException("Bare LOGIN exchange source is absent"));
      if (operation.lifecycle() != AccountBareLoginExchangeOperation.Lifecycle.COMMITTED
          || !"SUCCESS".equals(operation.outcomeCode())
          || !operation.sourceConnectOperationId().equals(identity.sourceConnectOperationId())
          || operation.accountId() != identity.accountId()
          || !operation.tenantId().equals(identity.tenantId())
          || !operation
              .connectScopeHash()
              .equals(AccountJoinDigest.tokenHash(identity.connectScopeId()))
          || !operation.requestId().equals(identity.requestId())
          || !MessageDigest.isEqual(operation.requestDigest(), checkedRequestDigest)) {
        throw new IllegalStateException("Bare LOGIN exchange is not the exact committed success");
      }

      AccountEnvelopeBinding binding = bindingFor(operation, identity);
      AccountBareLoginResponseEnvelope responseEnvelope =
          exchangeRepository
              .readResponseEnvelope(identity, checkedRequestDigest, binding)
              .orElseThrow(
                  () -> new IllegalStateException("Committed bare LOGIN response is absent"));
      if (!operation.operationId().equals(responseEnvelope.operationId())
          || !binding.equals(responseEnvelope.binding())
          || responseEnvelope.envelope().purpose() != AccountEnvelopePurpose.BARE_LOGIN_RESPONSE) {
        throw new IllegalStateException(
            "Bare LOGIN response envelope does not match its operation");
      }

      frameBytes =
          envelopeCrypto.decrypt(
              responseEnvelope.envelope(),
              AccountEnvelopePurpose.BARE_LOGIN_RESPONSE,
              responseEnvelope.binding());
      BareLoginRecoveryPayload payload = BareLoginRecoveryPayload.decode(frameBytes);
      originalResultBytes = payload.originalResultBytes();
      originalResultHash = sha256(originalResultBytes);
      storedResultHash = operation.tokenHash();
      if (!MessageDigest.isEqual(storedResultHash, originalResultHash)) {
        throw new IllegalStateException(
            "Stored bare LOGIN result hash does not match its operation");
      }

      originalGatewayContextBytes = payload.originalGatewayContextUtf8();
      String originalGatewayContext =
          new String(originalGatewayContextBytes, StandardCharsets.UTF_8);
      HistoricalGatewayConnectEvidence gatewayEvidence =
          GatewayConnectContextCodec.verifyHistoricalEvidence(
              originalGatewayContext, gatewayVerificationKeys);
      if (!identity.connectScopeId().equals(gatewayEvidence.text("connectScopeId"))) {
        throw new IllegalArgumentException(
            "Stored original Gateway context does not match the bare LOGIN connect scope");
      }
      String sourceConnectRequestId = gatewayEvidence.text("connectRequestId");
      if (sourceConnectRequestId == null || sourceConnectRequestId.isBlank()) {
        throw new IllegalArgumentException(
            "Stored original Gateway context has no source connect request identity");
      }

      HistoricalCommittedSourceEvidence sourceEvidence =
          committedSourceReader.readHistorical(
              new AccountConnectTokenIssuanceIdentity(
                  identity.accountId(),
                  identity.tenantId(),
                  identity.connectScopeId(),
                  sourceConnectRequestId),
              originalGatewayContext);
      if (!identity.sourceConnectOperationId().equals(sourceEvidence.operationId())
          || !gatewayEvidence.kid().equals(sourceEvidence.gatewayKeyId())
          || !gatewayEvidence.claims().equals(sourceEvidence.historicalGatewayClaims())) {
        throw new IllegalStateException(
            "Stored bare LOGIN source does not match its original Gateway context");
      }
      sourceConnectTokenHash = sourceEvidence.sourceTokenHash();

      HistoricalStoredBareLoginEvidence storedEvidence =
          new HistoricalStoredBareLoginEvidence(
              operation.operationId(),
              identity.sourceConnectOperationId(),
              identity.accountId(),
              identity.tenantId(),
              operation.requestId(),
              operation.connectScopeHash(),
              operation.requestDigestVersion(),
              checkedRequestDigest,
              originalResultHash,
              sourceConnectTokenHash,
              responseEnvelope.envelope().keyId(),
              sourceEvidence.responseEnvelopeKeyId(),
              sourceEvidence.accountSourceKeyId(),
              sourceEvidence.gatewayKeyId(),
              gatewayEvidence.integer("verifiedAt"),
              gatewayEvidence.integer("expiresAt"));
      return new HistoricalReadback(storedEvidence, sourceEvidence);
    } finally {
      Arrays.fill(checkedRequestDigest, (byte) 0);
      wipe(frameBytes);
      wipe(originalGatewayContextBytes);
      wipe(originalResultBytes);
      wipe(originalResultHash);
      wipe(storedResultHash);
      wipe(sourceConnectTokenHash);
    }
  }

  private AccountEnvelopeBinding bindingFor(
      AccountBareLoginExchangeOperation operation, AccountBareLoginExchangeIdentity identity) {
    return new AccountEnvelopeBinding(
        AccountEnvelopeBinding.OperationKind.BARE_LOGIN_EXCHANGE,
        operation.operationId().toString(),
        identity.requestId(),
        Long.toString(identity.accountId()),
        identity.tenantId().toString(),
        identity.connectScopeId(),
        identity.sourceConnectOperationId().toString(),
        operation.requestDigest(),
        operation.contextEvidenceDigest(),
        operation.authorityTupleDigest(),
        operation.issuanceFenceDigest(),
        operation.postconditionDigest());
  }

  private void requireOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Stored bare LOGIN history readback requires an active owner transaction");
    }
  }

  private static ConnectSourceIdentityEvidence connectSourceIdentity(
      HistoricalCommittedSourceEvidence evidence) {
    Map<String, Object> claims = evidence.originalSourceClaims();
    Map<String, Object> gateway = evidence.historicalGatewayClaims();
    return new ConnectSourceIdentityEvidence(
        evidence.operationId(),
        requireText(claims, "requestId"),
        AccountJoinDigest.tokenHash(requireText(claims, "connectScopeId")),
        requireText(claims, "jti"),
        evidence.sourceTokenHash(),
        evidence.responseEnvelopeKeyId(),
        evidence.accountSourceKeyId(),
        evidence.gatewayKeyId(),
        requireInteger(claims, "iat"),
        requireInteger(claims, "exp"),
        requireInteger(gateway, "verifiedAt"),
        requireInteger(gateway, "expiresAt"));
  }

  private static ConnectSourceIdentityEvidence connectSourceIdentity(
      AccountConnectTokenIssuanceIdentity identity,
      AccountCommittedConnectSourceReader.OriginalSourceEvidence evidence) {
    Map<String, Object> claims = evidence.originalSourceClaims();
    Map<String, Object> gateway = evidence.gatewayContextClaims();
    return new ConnectSourceIdentityEvidence(
        evidence.operationId(),
        identity.requestId(),
        AccountJoinDigest.tokenHash(identity.connectScopeId()),
        requireText(claims, "jti"),
        evidence.sourceTokenHash(),
        evidence.responseEnvelopeKeyId(),
        null,
        evidence.gatewayKeyId(),
        requireInteger(claims, "iat"),
        requireInteger(claims, "exp"),
        requireInteger(gateway, "verifiedAt"),
        requireInteger(gateway, "expiresAt"));
  }

  private static SelectedTargetEvidence selectedTarget(Map<String, Object> claims) {
    return new SelectedTargetEvidence(
        requireText(claims, "accountId"),
        requireText(claims, "tenantId"),
        requireText(claims, "realmId"),
        requireText(claims, "worldSlug"),
        requireText(claims, "realmSlug"),
        requireText(claims, "playableStateNamespaceId"),
        requireText(claims, "playableStateScope"),
        requireText(claims, "gameInstanceId"),
        requireInteger(claims, "catalogRevision"),
        requireInteger(claims, "pointerVersion"),
        optionalText(claims, "playtestLifecycleId"),
        optionalInteger(claims, "playtestStateGeneration"));
  }

  private static void requireSameCurrentReadback(
      AccountCommittedConnectSourceReader.OriginalSourceEvidence first,
      AccountCommittedConnectSourceReader.OriginalSourceEvidence last) {
    if (!first.operationId().equals(last.operationId())
        || !first.responseEnvelopeKeyId().equals(last.responseEnvelopeKeyId())
        || !MessageDigest.isEqual(first.sourceTokenHash(), last.sourceTokenHash())
        || !first.gatewayKeyId().equals(last.gatewayKeyId())
        || !first.originalSourceClaims().equals(last.originalSourceClaims())
        || !first.gatewayContextClaims().equals(last.gatewayContextClaims())) {
      throw new IllegalStateException("Fresh Account source changed during history correlation");
    }
  }

  private static String requireText(Map<String, Object> claims, String field) {
    Object value = claims.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalStateException("Verified connect source is missing a required target fact");
    }
    return text;
  }

  private static String optionalText(Map<String, Object> claims, String field) {
    if (!claims.containsKey(field)) {
      return null;
    }
    return requireText(claims, field);
  }

  private static BigInteger requireInteger(Map<String, Object> claims, String field) {
    Object value = claims.get(field);
    if (!(value instanceof BigInteger integer)) {
      throw new IllegalStateException("Verified connect source is missing a required time fact");
    }
    return integer;
  }

  private static BigInteger optionalInteger(Map<String, Object> claims, String field) {
    if (!claims.containsKey(field)) {
      return null;
    }
    return requireInteger(claims, field);
  }

  private void requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !expectedGameSessionPeerUri.equals(peer.uri())) {
      throw new AdminAuthorizationException(
          "Stored bare LOGIN history readback requires the exact Game Session workload identity");
    }
  }

  private static byte[] requireDigest(byte[] value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.length != 32) {
      throw new IllegalArgumentException(fieldName + " must be 32 bytes");
    }
    return value.clone();
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private record HistoricalReadback(
      HistoricalStoredBareLoginEvidence evidence, HistoricalCommittedSourceEvidence source) {}

  /** Immutable provenance facts only; no original result, JWT, Gateway context, or claims. */
  public record HistoricalStoredBareLoginEvidence(
      UUID exchangeOperationId,
      UUID sourceConnectOperationId,
      long accountId,
      UUID tenantId,
      String requestId,
      String connectScopeHash,
      int requestDigestVersion,
      byte[] requestDigest,
      byte[] originalResultHash,
      byte[] sourceConnectTokenHash,
      String responseEnvelopeKeyId,
      String sourceResponseEnvelopeKeyId,
      String accountSourceKeyId,
      String gatewayKeyId,
      java.math.BigInteger signedGatewayVerifiedAt,
      java.math.BigInteger signedGatewayExpiresAt) {
    public HistoricalStoredBareLoginEvidence {
      Objects.requireNonNull(exchangeOperationId, "exchangeOperationId");
      Objects.requireNonNull(sourceConnectOperationId, "sourceConnectOperationId");
      if (accountId <= 0
          || tenantId == null
          || tenantId.equals(new UUID(0L, 0L))
          || requestDigestVersion != 1) {
        throw new IllegalArgumentException("Stored bare LOGIN provenance identity is invalid");
      }
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(connectScopeHash, "connectScopeHash");
      requestDigest = requireDigest(requestDigest, "request digest");
      originalResultHash = requireDigest(originalResultHash, "original result hash");
      sourceConnectTokenHash = requireDigest(sourceConnectTokenHash, "source connect-token hash");
      Objects.requireNonNull(responseEnvelopeKeyId, "responseEnvelopeKeyId");
      Objects.requireNonNull(sourceResponseEnvelopeKeyId, "sourceResponseEnvelopeKeyId");
      Objects.requireNonNull(accountSourceKeyId, "accountSourceKeyId");
      Objects.requireNonNull(gatewayKeyId, "gatewayKeyId");
      Objects.requireNonNull(signedGatewayVerifiedAt, "signedGatewayVerifiedAt");
      Objects.requireNonNull(signedGatewayExpiresAt, "signedGatewayExpiresAt");
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }

    @Override
    public byte[] originalResultHash() {
      return originalResultHash.clone();
    }

    @Override
    public byte[] sourceConnectTokenHash() {
      return sourceConnectTokenHash.clone();
    }

    @Override
    public String toString() {
      return "HistoricalStoredBareLoginEvidence[exchangeOperationId="
          + exchangeOperationId
          + ", sourceConnectOperationId="
          + sourceConnectOperationId
          + ", requestId=<redacted>, result=<redacted>, originalGateway=<redacted>]";
    }
  }

  /** Separate original/fresh V35 identities and selected target; not an authorization result. */
  public record HistoricalStoredBareLoginCorrelationEvidence(
      HistoricalStoredBareLoginEvidence originalExchange,
      ConnectSourceIdentityEvidence originalConnectSource,
      ConnectSourceIdentityEvidence freshConnectSource,
      SelectedTargetEvidence selectedTarget) {
    public HistoricalStoredBareLoginCorrelationEvidence {
      Objects.requireNonNull(originalExchange, "originalExchange");
      Objects.requireNonNull(originalConnectSource, "originalConnectSource");
      Objects.requireNonNull(freshConnectSource, "freshConnectSource");
      Objects.requireNonNull(selectedTarget, "selectedTarget");
      if (!originalExchange.sourceConnectOperationId().equals(originalConnectSource.operationId())
          || originalConnectSource.operationId().equals(freshConnectSource.operationId())) {
        throw new IllegalArgumentException("Historical/fresh source identities are not distinct");
      }
    }

    @Override
    public String toString() {
      return "HistoricalStoredBareLoginCorrelationEvidence[originalExchange=<redacted>, "
          + "originalConnectSource=<redacted>, freshConnectSource=<redacted>, "
          + "selectedTarget=<redacted>]";
    }
  }

  /** Immutable source identity facts; connect scope is represented only by its digest. */
  public record ConnectSourceIdentityEvidence(
      UUID operationId,
      String requestId,
      String connectScopeHash,
      String tokenIdentity,
      byte[] sourceTokenHash,
      String responseEnvelopeKeyId,
      String accountSourceKeyId,
      String gatewayKeyId,
      BigInteger sourceIssuedAt,
      BigInteger sourceExpiresAt,
      BigInteger gatewayVerifiedAt,
      BigInteger gatewayExpiresAt) {
    public ConnectSourceIdentityEvidence {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(connectScopeHash, "connectScopeHash");
      Objects.requireNonNull(tokenIdentity, "tokenIdentity");
      sourceTokenHash = requireDigest(sourceTokenHash, "source token hash");
      Objects.requireNonNull(responseEnvelopeKeyId, "responseEnvelopeKeyId");
      Objects.requireNonNull(gatewayKeyId, "gatewayKeyId");
      Objects.requireNonNull(sourceIssuedAt, "sourceIssuedAt");
      Objects.requireNonNull(sourceExpiresAt, "sourceExpiresAt");
      Objects.requireNonNull(gatewayVerifiedAt, "gatewayVerifiedAt");
      Objects.requireNonNull(gatewayExpiresAt, "gatewayExpiresAt");
    }

    @Override
    public byte[] sourceTokenHash() {
      return sourceTokenHash.clone();
    }

    @Override
    public String toString() {
      return "ConnectSourceIdentityEvidence[operationId="
          + operationId
          + ", request=<redacted>, scope=<redacted>, tokenIdentity=<redacted>, "
          + "sourceToken=<redacted>, gatewayContext=<redacted>]";
    }
  }

  /** The complete canonical selected-target projection, excluding per-issuance identity/time. */
  public record SelectedTargetEvidence(
      String accountId,
      String tenantId,
      String realmId,
      String worldSlug,
      String realmSlug,
      String playableStateNamespaceId,
      String playableStateScope,
      String gameInstanceId,
      BigInteger catalogRevision,
      BigInteger pointerVersion,
      String playtestLifecycleId,
      BigInteger playtestStateGeneration) {
    public SelectedTargetEvidence {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(realmId, "realmId");
      Objects.requireNonNull(worldSlug, "worldSlug");
      Objects.requireNonNull(realmSlug, "realmSlug");
      Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      Objects.requireNonNull(playableStateScope, "playableStateScope");
      Objects.requireNonNull(gameInstanceId, "gameInstanceId");
      Objects.requireNonNull(catalogRevision, "catalogRevision");
      Objects.requireNonNull(pointerVersion, "pointerVersion");
      if ((playtestLifecycleId == null) != (playtestStateGeneration == null)) {
        throw new IllegalArgumentException("Playtest target lifecycle and generation differ");
      }
    }

    @Override
    public String toString() {
      return "SelectedTargetEvidence[account=<redacted>, tenant=<redacted>, realm=<redacted>, "
          + "world=<redacted>, runtime=<redacted>, revision=<redacted>]";
    }
  }
}
