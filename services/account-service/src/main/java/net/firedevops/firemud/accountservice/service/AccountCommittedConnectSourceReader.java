package net.firedevops.firemud.accountservice.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.repository.AccountCommittedConnectSource;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJoinOperationRepository;
import net.firedevops.firemud.accountservice.repository.AccountRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantIdentityResolver;
import net.firedevops.firemud.accountservice.repository.ApprovedLegacyTenantAssociationRepository.ApprovedAssociation;
import net.firedevops.firemud.accountservice.repository.FreshTenantIdentityAssociationRepository;
import net.firedevops.firemud.accountservice.security.AccountEnvelopeCrypto;
import net.firedevops.firemud.accountservice.security.AccountEnvelopePurpose;
import net.firedevops.firemud.accountservice.security.AccountGameplayConnectSourceVerifier;
import net.firedevops.firemud.accountservice.security.HistoricalAccountGameplayConnectEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AdminAuthorizationException;
import net.firedevops.firemud.common.security.GatewayConnectContext;
import net.firedevops.firemud.common.security.GatewayConnectContextCodec;
import net.firedevops.firemud.common.security.HistoricalGatewayConnectEvidence;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unwired, non-authorizing readback of the original committed Account gameplay-connect source. This
 * component verifies provenance and source/context correspondence only; it does not establish
 * current gameplay authority, registry readiness, or admission.
 */
public final class AccountCommittedConnectSourceReader {
  private final AccountConnectTokenIssuanceRepository issuanceRepository;
  private final AccountRepository accountRepository;
  private final AccountTenantIdentityResolver tenantIdentityResolver;
  private final FreshTenantIdentityAssociationRepository freshTenantIdentityRepository;
  private final AccountJoinOperationRepository joinOperationRepository;
  private final AccountEnvelopeCrypto envelopeCrypto;
  private final AccountGameplayConnectSourceVerifier sourceVerifier;
  private final Map<String, PublicKey> gatewayVerificationKeys;
  private final Clock clock;
  private final String workloadNamespace;
  private final String expectedGameSessionPeerUri;

  public AccountCommittedConnectSourceReader(
      AccountConnectTokenIssuanceRepository issuanceRepository,
      AccountRepository accountRepository,
      AccountTenantIdentityResolver tenantIdentityResolver,
      FreshTenantIdentityAssociationRepository freshTenantIdentityRepository,
      AccountJoinOperationRepository joinOperationRepository,
      AccountEnvelopeCrypto envelopeCrypto,
      AccountGameplayConnectSourceVerifier sourceVerifier,
      Map<String, PublicKey> gatewayVerificationKeys,
      Clock clock,
      String workloadNamespace) {
    this.issuanceRepository = Objects.requireNonNull(issuanceRepository, "issuanceRepository");
    this.accountRepository = Objects.requireNonNull(accountRepository, "accountRepository");
    this.tenantIdentityResolver =
        Objects.requireNonNull(tenantIdentityResolver, "tenantIdentityResolver");
    this.freshTenantIdentityRepository =
        Objects.requireNonNull(freshTenantIdentityRepository, "freshTenantIdentityRepository");
    this.joinOperationRepository =
        Objects.requireNonNull(joinOperationRepository, "joinOperationRepository");
    this.envelopeCrypto = Objects.requireNonNull(envelopeCrypto, "envelopeCrypto");
    this.sourceVerifier = Objects.requireNonNull(sourceVerifier, "sourceVerifier");
    this.gatewayVerificationKeys = Map.copyOf(gatewayVerificationKeys);
    if (this.gatewayVerificationKeys.isEmpty()) {
      throw new IllegalArgumentException("Gateway verification keys are required");
    }
    this.clock = Objects.requireNonNull(clock, "clock");
    if (workloadNamespace == null || workloadNamespace.isBlank()) {
      throw new IllegalArgumentException("Account workload namespace is required");
    }
    this.workloadNamespace = workloadNamespace;
    this.expectedGameSessionPeerUri =
        GrpcPeerIdentity.parseUri(
                "spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
            .filter(identity -> workloadNamespace.equals(identity.namespace()))
            .map(GrpcPeerIdentity::uri)
            .orElseThrow(
                () -> new IllegalArgumentException("Account workload namespace is invalid"));
  }

  /**
   * Reads and verifies one original committed source in the caller's owner transaction. The exact
   * Game Session peer, Gateway assertion, account fence, canonical tenant association, source
   * envelope, Account JWT signature/profile, and complete source/context correspondence are all
   * checked before returning immutable evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public OriginalSourceEvidence read(
      AccountConnectTokenIssuanceIdentity identity, String signedGatewayContext) {
    Objects.requireNonNull(identity, "identity");
    requireGameSessionPeer();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Committed Account source readback requires an active owner transaction");
    }
    GatewayConnectContext initialContext = verifyGatewayContext(signedGatewayContext);
    requireContextRequestIdentity(identity, initialContext);

    // The same Account row fence serializes this identity/provenance read with JOIN and other
    // fenced Account work. The retained tenant association resolver separately verifies its
    // exact fresh source claim or approved retained row and current source-evidence digest in this
    // owner transaction.
    joinOperationRepository.lockAccount(identity.accountId());
    Account account =
        accountRepository
            .findById(identity.accountId())
            .orElseThrow(() -> new IllegalStateException("Account source identity is absent"));
    UUID accountUuid = requireAccountIdentity(account, identity.accountId());
    requireContextValue(initialContext, "accountId", accountUuid.toString());

    UUID canonicalTenantId = requireTenantIdentity(identity.tenantId());
    requireContextValue(initialContext, "tenantId", canonicalTenantId.toString());

    AccountCommittedConnectSource committedSource =
        issuanceRepository
            .readCommittedResponseEnvelope(identity)
            .orElseThrow(() -> new IllegalStateException("Committed Account source is absent"));
    byte[] compactJwtBytes =
        envelopeCrypto.decrypt(
            committedSource.responseEnvelope().envelope(),
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            committedSource.responseEnvelope().binding());
    try {
      String compactJwt = decodeStrictUtf8(compactJwtBytes);
      byte[] actualTokenHash = sha256(compactJwtBytes);
      if (!MessageDigest.isEqual(committedSource.operation().tokenHash(), actualTokenHash)) {
        throw new IllegalStateException("Committed Account source token hash does not match");
      }

      Map<String, Object> sourceClaims = sourceVerifier.verify(compactJwt);
      if (!Objects.equals(sourceClaims.get("jti"), committedSource.operation().tokenIdentity())) {
        throw new IllegalStateException("Committed Account source token identity does not match");
      }
      GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesGatewayContext(
          sourceClaims, initialContext);

      // A transaction may wait on the Account fence after the Gateway originally verified its
      // short-lived context. Recheck the unchanged signed assertion at the current clock without
      // restamping or extending any deadline before returning the evidence.
      GatewayConnectContext currentContext = verifyGatewayContext(signedGatewayContext);
      if (!initialContext.kid().equals(currentContext.kid())
          || !initialContext.claims().equals(currentContext.claims())) {
        throw new IllegalStateException("Gateway source context changed during Account readback");
      }
      Map<String, Object> currentSourceClaims = sourceVerifier.verify(compactJwt);
      if (!sourceClaims.equals(currentSourceClaims)) {
        throw new IllegalStateException("Account source claims changed during source readback");
      }
      GatewayConnectContextCodec.requireVerifiedAccountSourceMatchesGatewayContext(
          currentSourceClaims, currentContext);

      return new OriginalSourceEvidence(
          committedSource.operation().operationId(),
          committedSource.responseEnvelope().envelope().keyId(),
          actualTokenHash,
          currentContext.kid(),
          currentSourceClaims,
          currentContext.claims());
    } finally {
      java.util.Arrays.fill(compactJwtBytes, (byte) 0);
    }
  }

  /**
   * Reads one exact, already-committed V35 Account source as historical provenance, using the
   * caller-supplied signed original Gateway assertion to anchor source-time verification. This
   * proves the committed source and its correspondence to that supplied assertion only. It does not
   * prove that a V37 frame retained the original Gateway context, that Gateway accepted it, fresh
   * authorization, current authority, registry readiness, result recovery eligibility, or
   * admission. It cannot be used in place of {@link #read} or a current Gateway context.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public HistoricalCommittedSourceEvidence readHistorical(
      AccountConnectTokenIssuanceIdentity identity, String signedOriginalGatewayAssertion) {
    Objects.requireNonNull(identity, "identity");
    requireGameSessionPeer();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Committed Account source readback requires an active owner transaction");
    }
    HistoricalGatewayConnectEvidence historicalGatewayEvidence =
        GatewayConnectContextCodec.verifyHistoricalEvidence(
            signedOriginalGatewayAssertion, gatewayVerificationKeys);
    requireContextRequestIdentity(identity, historicalGatewayEvidence);

    // Keep the same Account row fence, canonical UUID provenance, and fresh-or-retained tenant
    // resolver as strict-current source readback. The historical assertion changes neither the
    // source identity nor the transaction/data-access boundary.
    joinOperationRepository.lockAccount(identity.accountId());
    Account account =
        accountRepository
            .findById(identity.accountId())
            .orElseThrow(() -> new IllegalStateException("Account source identity is absent"));
    UUID accountUuid = requireAccountIdentity(account, identity.accountId());
    requireContextValue(historicalGatewayEvidence, "accountId", accountUuid.toString());

    UUID canonicalTenantId = requireTenantIdentity(identity.tenantId());
    requireContextValue(historicalGatewayEvidence, "tenantId", canonicalTenantId.toString());

    AccountCommittedConnectSource committedSource =
        issuanceRepository
            .readCommittedResponseEnvelope(identity)
            .orElseThrow(() -> new IllegalStateException("Committed Account source is absent"));
    byte[] compactJwtBytes =
        envelopeCrypto.decrypt(
            committedSource.responseEnvelope().envelope(),
            AccountEnvelopePurpose.CONNECT_TOKEN_RESPONSE,
            committedSource.responseEnvelope().binding());
    try {
      String compactJwt = decodeStrictUtf8(compactJwtBytes);
      byte[] actualTokenHash = sha256(compactJwtBytes);
      if (!MessageDigest.isEqual(committedSource.operation().tokenHash(), actualTokenHash)) {
        throw new IllegalStateException("Committed Account source token hash does not match");
      }

      HistoricalAccountGameplayConnectEvidence historicalSourceEvidence =
          sourceVerifier.verifyHistorical(compactJwt, historicalGatewayEvidence);
      Map<String, Object> sourceClaims = historicalSourceEvidence.sourceClaims();
      if (!Objects.equals(sourceClaims.get("jti"), committedSource.operation().tokenIdentity())) {
        throw new IllegalStateException("Committed Account source token identity does not match");
      }

      return new HistoricalCommittedSourceEvidence(
          committedSource.operation().operationId(),
          committedSource.responseEnvelope().envelope().keyId(),
          actualTokenHash,
          historicalSourceEvidence.accountKeyId(),
          historicalGatewayEvidence.kid(),
          sourceClaims,
          historicalGatewayEvidence.claims());
    } finally {
      java.util.Arrays.fill(compactJwtBytes, (byte) 0);
    }
  }

  private GatewayConnectContext verifyGatewayContext(String signedGatewayContext) {
    return GatewayConnectContextCodec.verifyAndDecode(
        signedGatewayContext, gatewayVerificationKeys, clock);
  }

  private void requireGameSessionPeer() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null || !expectedGameSessionPeerUri.equals(peer.uri())) {
      throw new AdminAuthorizationException(
          "Committed Account source readback requires the exact Game Session workload identity");
    }
  }

  private UUID requireTenantIdentity(UUID canonicalTenantId) {
    Optional<FreshTenantCreationEvidence> fresh =
        freshTenantIdentityRepository.read(canonicalTenantId);
    if (fresh.isPresent()) {
      FreshTenantCreationEvidence evidence = fresh.orElseThrow();
      if (!canonicalTenantId.equals(evidence.canonicalTenantId())
          || !workloadNamespace.equals(evidence.targetNamespace())) {
        throw new IllegalStateException("Fresh Account tenant identity evidence is mismatched");
      }
      return evidence.canonicalTenantId();
    }

    ApprovedAssociation retained = tenantIdentityResolver.resolve(canonicalTenantId);
    if (!canonicalTenantId.equals(retained.canonicalTenantId())
        || !workloadNamespace.equals(retained.targetNamespace())) {
      throw new IllegalStateException("Retained Account tenant identity evidence is mismatched");
    }
    return retained.canonicalTenantId();
  }

  private static void requireContextRequestIdentity(
      AccountConnectTokenIssuanceIdentity identity, GatewayConnectContext context) {
    if (!identity.connectScopeId().equals(context.text("connectScopeId"))
        || !identity.requestId().equals(context.text("connectRequestId"))) {
      throw new IllegalArgumentException(
          "Gateway context does not match the exact Account source request identity");
    }
  }

  private static void requireContextRequestIdentity(
      AccountConnectTokenIssuanceIdentity identity,
      HistoricalGatewayConnectEvidence historicalContext) {
    if (!identity.connectScopeId().equals(historicalContext.text("connectScopeId"))
        || !identity.requestId().equals(historicalContext.text("connectRequestId"))) {
      throw new IllegalArgumentException(
          "Gateway context does not match the exact Account source request identity");
    }
  }

  private static UUID requireAccountIdentity(Account account, long numericAccountId) {
    if (account.getId() == null
        || account.getId() != numericAccountId
        || account.getAccountUuid() == null
        || account.getAccountUuidProvenance() == null
        || account.getAccountUuidSourceNumericId() == null
        || account.getAccountUuidSourceNumericId() != numericAccountId) {
      throw new IllegalStateException("Account canonical identity provenance is mismatched");
    }
    return account.getAccountUuid();
  }

  private static void requireContextValue(
      GatewayConnectContext context, String field, String expectedValue) {
    if (!expectedValue.equals(context.text(field))) {
      throw new IllegalArgumentException(
          "Gateway context does not match the Account " + field + " identity");
    }
  }

  private static void requireContextValue(
      HistoricalGatewayConnectEvidence historicalContext, String field, String expectedValue) {
    if (!expectedValue.equals(historicalContext.text(field))) {
      throw new IllegalArgumentException(
          "Gateway context does not match the Account " + field + " identity");
    }
  }

  private static String decodeStrictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalStateException("Committed Account source envelope is not strict UTF-8");
    }
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /**
   * Immutable, non-authorizing exact original-source readback. It never exposes the compact JWT.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Verified claim maps and nested JSON values are deeply copied into immutable containers "
              + "by the canonical constructor before record accessors expose them")
  public record OriginalSourceEvidence(
      UUID operationId,
      String responseEnvelopeKeyId,
      byte[] sourceTokenHash,
      String gatewayKeyId,
      Map<String, Object> originalSourceClaims,
      Map<String, Object> gatewayContextClaims) {
    public OriginalSourceEvidence {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(responseEnvelopeKeyId, "responseEnvelopeKeyId");
      Objects.requireNonNull(gatewayKeyId, "gatewayKeyId");
      if (sourceTokenHash == null || sourceTokenHash.length != 32) {
        throw new IllegalArgumentException("source token hash must be 32 bytes");
      }
      sourceTokenHash = sourceTokenHash.clone();
      originalSourceClaims = immutableObject(originalSourceClaims);
      gatewayContextClaims = immutableObject(gatewayContextClaims);
    }

    @Override
    public byte[] sourceTokenHash() {
      return sourceTokenHash.clone();
    }

    @Override
    public String toString() {
      return "OriginalSourceEvidence[operationId="
          + operationId
          + ", source=<redacted>, gatewayContext=<redacted>]";
    }

    private static Map<String, Object> immutableObject(Map<String, ?> source) {
      Objects.requireNonNull(source, "claims");
      Map<String, Object> copy = new LinkedHashMap<>();
      source.forEach((key, value) -> copy.put(key, immutableValue(value)));
      return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
      if (value == null
          || value instanceof String
          || value instanceof Boolean
          || value instanceof BigInteger
          || value instanceof java.math.BigDecimal) {
        return value;
      }
      if (value instanceof Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach(
            (key, nested) -> {
              if (!(key instanceof String text)) {
                throw new IllegalArgumentException("claim object keys must be strings");
              }
              copy.put(text, immutableValue(nested));
            });
        return Collections.unmodifiableMap(copy);
      }
      if (value instanceof List<?> source) {
        List<Object> copy = new ArrayList<>(source.size());
        source.forEach(nested -> copy.add(immutableValue(nested)));
        return Collections.unmodifiableList(copy);
      }
      throw new IllegalArgumentException("unsupported verified claim value");
    }
  }

  /**
   * Immutable readback of a committed V35 source matched to a caller-supplied historical Gateway
   * assertion. This is source/correspondence provenance only; it does not establish retained V37
   * context, past Gateway acceptance, fresh or current authorization, registry state, recovery
   * eligibility, or admission. It retains neither compact Account JWT nor signed Gateway envelope.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "Verified claim maps and nested JSON values are deeply copied into immutable containers "
              + "by the canonical constructor before record accessors expose them")
  public record HistoricalCommittedSourceEvidence(
      UUID operationId,
      String responseEnvelopeKeyId,
      byte[] sourceTokenHash,
      String accountSourceKeyId,
      String gatewayKeyId,
      Map<String, Object> originalSourceClaims,
      Map<String, Object> historicalGatewayClaims) {
    public HistoricalCommittedSourceEvidence {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(responseEnvelopeKeyId, "responseEnvelopeKeyId");
      Objects.requireNonNull(accountSourceKeyId, "accountSourceKeyId");
      Objects.requireNonNull(gatewayKeyId, "gatewayKeyId");
      if (sourceTokenHash == null || sourceTokenHash.length != 32) {
        throw new IllegalArgumentException("source token hash must be 32 bytes");
      }
      sourceTokenHash = sourceTokenHash.clone();
      originalSourceClaims = OriginalSourceEvidence.immutableObject(originalSourceClaims);
      historicalGatewayClaims = OriginalSourceEvidence.immutableObject(historicalGatewayClaims);
    }

    @Override
    public byte[] sourceTokenHash() {
      return sourceTokenHash.clone();
    }

    @Override
    public String toString() {
      return "HistoricalCommittedSourceEvidence[operationId="
          + operationId
          + ", committedSource=<redacted>, historicalGateway=<redacted>]";
    }
  }
}
