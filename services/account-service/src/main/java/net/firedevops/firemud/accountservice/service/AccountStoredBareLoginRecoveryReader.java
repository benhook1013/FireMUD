package net.firedevops.firemud.accountservice.service;

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
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Stored bare LOGIN history readback requires an active owner transaction");
    }
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
          || operation.tenantId() != identity.tenantId()
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

      return new HistoricalStoredBareLoginEvidence(
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
          gatewayEvidence.integer("exp"));
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
        Long.toString(identity.tenantId()),
        identity.connectScopeId(),
        identity.sourceConnectOperationId().toString(),
        operation.requestDigest(),
        operation.contextEvidenceDigest(),
        operation.authorityTupleDigest(),
        operation.issuanceFenceDigest(),
        operation.postconditionDigest());
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

  /** Immutable provenance facts only; no original result, JWT, Gateway context, or claims. */
  public record HistoricalStoredBareLoginEvidence(
      UUID exchangeOperationId,
      UUID sourceConnectOperationId,
      long accountId,
      long tenantId,
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
      if (accountId <= 0 || tenantId <= 0 || requestDigestVersion != 1) {
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
}
