package net.firedevops.firemud.accountservice.service;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountJoinDigest;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountConnectIssuanceFenceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountConnectTokenIssuanceIdentity;
import net.firedevops.firemud.accountservice.service.AccountCommittedConnectSourceReader.OriginalSourceEvidence;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unwired, non-authorizing composition of a current gameplay-connect source and Account authority
 * readbacks. This candidate does not issue or recover credentials, establish registry or
 * postcondition state, or admit gameplay.
 */
public final class AccountBareLoginCurrentAuthorityReader {
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");

  private final AccountCommittedConnectSourceReader committedSourceReader;
  private final AccountConnectTokenAuthorityCaptureService captureService;
  private final AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer;
  private final AccountAuthorityGenerationRepository authorityGenerationRepository;

  public AccountBareLoginCurrentAuthorityReader(
      AccountCommittedConnectSourceReader committedSourceReader,
      AccountConnectTokenAuthorityCaptureService captureService,
      AccountMembershipAuthorityEventProducer membershipAuthorityEventProducer,
      AccountAuthorityGenerationRepository authorityGenerationRepository) {
    this.committedSourceReader =
        Objects.requireNonNull(committedSourceReader, "committedSourceReader");
    this.captureService = Objects.requireNonNull(captureService, "captureService");
    this.membershipAuthorityEventProducer =
        Objects.requireNonNull(
            membershipAuthorityEventProducer, "membershipAuthorityEventProducer");
    this.authorityGenerationRepository =
        Objects.requireNonNull(authorityGenerationRepository, "authorityGenerationRepository");
  }

  /**
   * Composes current-only Account evidence under the caller's existing writable Account
   * transaction. The strict source read runs first so peer, signature, context, and deadline
   * failures precede retained-capture and current-membership reads.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CurrentAuthorityReadback read(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] exactOriginalRequestDigest,
      String signedCurrentGatewayContext) {
    Objects.requireNonNull(identity, "identity");

    // This reader checks the exact peer before parsing the signed context or touching repositories.
    OriginalSourceEvidence original =
        committedSourceReader.read(identity, signedCurrentGatewayContext);
    byte[] requestDigest = requireDigest(exactOriginalRequestDigest);
    UUID accountUuid = requireSourceBinding(identity, original);

    AccountConnectIssuanceFenceEvidence capture =
        captureService
            .read(identity, requestDigest)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Exact Account issuance-fence capture is not proved"));
    requireCaptureBinding(identity, requestDigest, accountUuid, original, capture);

    RuntimeMembershipSnapshotDto membershipSnapshot =
        membershipAuthorityEventProducer.readExistingRuntimeMembershipSnapshot(
            accountUuid, identity.tenantId());
    requireExactActiveMembership(membershipSnapshot, accountUuid, identity.tenantId());

    ScopeState accountAuthority =
        authorityGenerationRepository.read(AuthorityScope.account(accountUuid));
    IssuanceFence currentFence = requireCurrentAccountState(accountAuthority, accountUuid);
    requireCurrentSnapshotMatchesSource(
        original.originalSourceClaims(), membershipSnapshot, accountAuthority, currentFence);
    requireCaptureMatchesCurrentFence(capture, currentFence, membershipSnapshot.issuanceFence());

    // Authority reads may wait on Account's owner fence. Re-run the strict current source read so
    // neither the original JWT nor Gateway context can expire while those reads are in progress.
    OriginalSourceEvidence finalSource =
        committedSourceReader.read(identity, signedCurrentGatewayContext);
    requireUnchangedSource(original, finalSource);

    return CurrentAuthorityReadback.from(
        identity, original, capture, membershipSnapshot, currentFence);
  }

  private static UUID requireSourceBinding(
      AccountConnectTokenIssuanceIdentity identity, OriginalSourceEvidence source) {
    Objects.requireNonNull(source, "verified original source evidence is required");
    if (source.operationId() == null || source.operationId().equals(new UUID(0L, 0L))) {
      throw invalidEvidence();
    }
    Map<String, Object> claims = source.originalSourceClaims();
    String accountText = requireClaimText(claims, "accountId");
    UUID accountUuid = parseCanonicalUuid(accountText);
    if (!identity.tenantId().toString().equals(requireClaimText(claims, "tenantId"))
        || !identity.requestId().equals(requireClaimText(claims, "requestId"))
        || !identity.connectScopeId().equals(requireClaimText(claims, "connectScopeId"))) {
      throw invalidEvidence();
    }
    Map<String, Object> context = source.gatewayContextClaims();
    if (!identity.tenantId().toString().equals(requireClaimText(context, "tenantId"))
        || !accountUuid.toString().equals(requireClaimText(context, "accountId"))
        || !identity.requestId().equals(requireClaimText(context, "connectRequestId"))
        || !identity.connectScopeId().equals(requireClaimText(context, "connectScopeId"))) {
      throw invalidEvidence();
    }
    requireSha256(source.sourceTokenHash());
    requireNonBlank(source.responseEnvelopeKeyId());
    requireNonBlank(source.gatewayKeyId());
    return accountUuid;
  }

  private static void requireCaptureBinding(
      AccountConnectTokenIssuanceIdentity identity,
      byte[] requestDigest,
      UUID accountUuid,
      OriginalSourceEvidence source,
      AccountConnectIssuanceFenceEvidence capture) {
    if (!AccountConnectIssuanceFenceEvidence.SCHEMA_NAME.equals(capture.schemaName())
        || !source.operationId().equals(capture.operationId())
        || !accountUuid.equals(capture.accountUuid())
        || !identity.tenantId().equals(capture.tenantUuid())
        || !AccountJoinDigest.tokenHash(identity.connectScopeId())
            .equals(capture.connectScopeHash())
        || !identity.requestId().equals(capture.requestId())
        || !MessageDigest.isEqual(requestDigest, capture.requestDigest())
        || capture.issuanceFence() <= 0L
        || capture.fenceSourceVersion() <= 0L
        || capture.digest().length != 32) {
      throw invalidEvidence();
    }
  }

  private static void requireExactActiveMembership(
      RuntimeMembershipSnapshotDto snapshot, UUID accountUuid, UUID tenantUuid) {
    if (snapshot == null
        || !accountUuid.toString().equals(snapshot.requestAccountUuid())
        || !accountUuid.toString().equals(snapshot.accountUuid())
        || !tenantUuid.toString().equals(snapshot.requestTenantUuid())
        || !tenantUuid.toString().equals(snapshot.tenantUuid())
        || !snapshot.membershipExists()
        || !snapshot.gameplayAdmissionAllowed()
        || snapshot.membershipBaseline() == null
        || !"ACTIVE".equals(snapshot.membershipBaseline().membershipLifecycleState())
        || snapshot.roles() == null
        || !snapshot.roles().contains("player")
        || snapshot.authorityTuple() == null
        || snapshot.outboxCheckpoints() == null
        || snapshot.outboxSourceEvidence() == null
        || snapshot.evaluatedAt() == null) {
      throw new IllegalStateException(
          "Current selected-target Account membership is not exact and ACTIVE");
    }
  }

  private static IssuanceFence requireCurrentAccountState(ScopeState state, UUID accountUuid) {
    if (state == null
        || !AuthorityScope.account(accountUuid).equals(state.scope())
        || state.generation() <= 0L
        || state.sourceVersion() <= 0L) {
      throw invalidEvidence();
    }
    IssuanceFence fence = state.issuanceFence();
    if (fence == null
        || !accountUuid.equals(fence.accountId())
        || fence.value() <= 0L
        || fence.sourceVersion() <= 0L) {
      throw invalidEvidence();
    }
    return fence;
  }

  private static void requireCurrentSnapshotMatchesSource(
      Map<String, Object> sourceClaims,
      RuntimeMembershipSnapshotDto snapshot,
      ScopeState accountAuthority,
      IssuanceFence currentFence) {
    UUID accountUuid = parseCanonicalUuid(snapshot.accountUuid());
    UUID tenantUuid = parseCanonicalUuid(snapshot.tenantUuid());
    Map<String, Object> sourceTuple = requireObject(sourceClaims.get("authorityTuple"));
    Map<String, Object> projectedCurrentTuple =
        projectCurrentTuple(snapshot.authorityTuple(), tenantUuid);
    if (!projectedCurrentTuple.equals(sourceTuple)
        || !BigInteger.valueOf(accountAuthority.generation())
            .equals(positiveInteger(sourceTuple.get("accountAuthorityGeneration")))
        || !Map.of(
                tenantUuid.toString(),
                decimalInteger(
                    snapshot.membershipBaseline().membershipVersion().get(tenantUuid.toString())))
            .equals(projectMembershipVersion(sourceClaims.get("membershipVersion"), tenantUuid))
        || !BigInteger.valueOf(currentFence.value())
            .equals(decimalInteger(snapshot.issuanceFence()))) {
      throw new IllegalStateException(
          "Committed gameplay-connect source differs from current Account authority");
    }

    Map<String, String> membershipVersions = snapshot.membershipBaseline().membershipVersion();
    Map<String, String> membershipGenerations =
        snapshot.authorityTuple().membershipAuthorityGeneration();
    if (!membershipVersions.keySet().equals(Set.of(tenantUuid.toString()))
        || !membershipGenerations.keySet().equals(Set.of(tenantUuid.toString()))
        || !decimalInteger(snapshot.membershipBaseline().membershipAuthorityGeneration())
            .equals(decimalInteger(membershipGenerations.get(tenantUuid.toString())))
        || !accountUuid.equals(currentFence.accountId())) {
      throw invalidEvidence();
    }
  }

  private static Map<String, Object> projectCurrentTuple(AuthorityTuple tuple, UUID tenantUuid) {
    Objects.requireNonNull(tuple, "current authority tuple is required");
    requireExactTenantKeys(
        tuple.tenantAuthorityGeneration(), tenantUuid, "tenant authority generation");
    requireExactTenantKeys(
        tuple.membershipAuthorityGeneration(), tenantUuid, "membership authority generation");

    Map<String, Object> projected = new LinkedHashMap<>();
    projected.put("issuerAuthGeneration", decimalInteger(tuple.issuerAuthGeneration()));
    projected.put("accountAuthorityGeneration", decimalInteger(tuple.accountAuthorityGeneration()));
    projected.put(
        "tenantAuthorityGeneration",
        projectDecimalMap(tuple.tenantAuthorityGeneration(), "tenant authority generation"));
    projected.put(
        "membershipAuthorityGeneration",
        projectDecimalMap(
            tuple.membershipAuthorityGeneration(), "membership authority generation"));

    List<Map<String, Object>> grants = new ArrayList<>();
    tuple
        .privateRealmGrantVersions()
        .forEach(
            grant -> {
              if (grant == null || !tenantUuid.toString().equals(grant.tenantId())) {
                throw invalidEvidence();
              }
              Map<String, Object> projectedGrant = new LinkedHashMap<>();
              projectedGrant.put("tenantId", grant.tenantId());
              projectedGrant.put("worldSlug", requireNonBlank(grant.worldSlug()));
              projectedGrant.put("realmSlug", requireNonBlank(grant.realmSlug()));
              projectedGrant.put(
                  "playtestLifecycleId", requireNonBlank(grant.playtestLifecycleId()));
              projectedGrant.put("grantVersion", decimalInteger(grant.grantVersion()));
              grants.add(Map.copyOf(projectedGrant));
            });
    projected.put("privateRealmGrantVersions", List.copyOf(grants));

    tuple
        .accountSecurityCutoff()
        .ifPresent(cutoff -> projected.put("accountSecurityCutoff", projectAccountCutoff(cutoff)));
    tuple
        .tenantBillingCutoff()
        .ifPresent(
            cutoffs -> {
              requireExactTenantKeys(cutoffs, tenantUuid, "tenant billing cutoff");
              Map<String, Object> projectedCutoffs = new LinkedHashMap<>();
              cutoffs.forEach(
                  (tenantId, cutoff) ->
                      projectedCutoffs.put(tenantId, projectTenantCutoff(cutoff)));
              projected.put("tenantBillingCutoff", Map.copyOf(projectedCutoffs));
            });
    return Map.copyOf(projected);
  }

  private static Map<String, Object> projectAccountCutoff(AccountSecurityCutoff cutoff) {
    Objects.requireNonNull(cutoff, "current Account security cutoff is required");
    return Map.of(
        "accountAuthorityGeneration", decimalInteger(cutoff.accountAuthorityGeneration()),
        "outboxStreamKey", requireNonBlank(cutoff.outboxStreamKey()),
        "outboxSequence", decimalInteger(cutoff.outboxSequence()));
  }

  private static Map<String, Object> projectTenantCutoff(TenantBillingCutoff cutoff) {
    Objects.requireNonNull(cutoff, "current tenant billing cutoff is required");
    return Map.of(
        "tenantAuthorityGeneration", decimalInteger(cutoff.tenantAuthorityGeneration()),
        "tenantBillingSequence", decimalInteger(cutoff.tenantBillingSequence()),
        "outboxStreamKey", requireNonBlank(cutoff.outboxStreamKey()),
        "outboxSequence", decimalInteger(cutoff.outboxSequence()));
  }

  private static Map<String, Object> projectDecimalMap(Map<String, String> values, String field) {
    Map<String, Object> projected = new LinkedHashMap<>();
    values.forEach((key, value) -> projected.put(key, decimalInteger(value)));
    if (projected.isEmpty()) {
      throw new IllegalStateException("Current " + field + " is incomplete");
    }
    return Map.copyOf(projected);
  }

  private static Map<String, BigInteger> projectMembershipVersion(
      Object supplied, UUID tenantUuid) {
    Map<String, Object> versionMap = requireObject(supplied);
    if (!versionMap.keySet().equals(Set.of(tenantUuid.toString()))) {
      throw invalidEvidence();
    }
    return Map.of(tenantUuid.toString(), positiveInteger(versionMap.get(tenantUuid.toString())));
  }

  private static void requireCaptureMatchesCurrentFence(
      AccountConnectIssuanceFenceEvidence capture,
      IssuanceFence currentFence,
      String membershipSnapshotFence) {
    if (capture.issuanceFence() != currentFence.value()
        || capture.fenceSourceVersion() != currentFence.sourceVersion()
        || !Long.toString(currentFence.value()).equals(membershipSnapshotFence)) {
      throw new IllegalStateException(
          "Captured Account issuance fence differs from current fenced authority");
    }
  }

  private static void requireUnchangedSource(
      OriginalSourceEvidence original, OriginalSourceEvidence current) {
    if (current == null
        || !original.operationId().equals(current.operationId())
        || !original.responseEnvelopeKeyId().equals(current.responseEnvelopeKeyId())
        || !original.gatewayKeyId().equals(current.gatewayKeyId())
        || !MessageDigest.isEqual(original.sourceTokenHash(), current.sourceTokenHash())
        || !original.originalSourceClaims().equals(current.originalSourceClaims())
        || !original.gatewayContextClaims().equals(current.gatewayContextClaims())) {
      throw new IllegalStateException(
          "Committed gameplay-connect source changed during current authority readback");
    }
  }

  private static Map<String, Object> requireObject(Object value) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw invalidEvidence();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw invalidEvidence();
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static String requireClaimText(Map<String, Object> claims, String name) {
    Object value = claims.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw invalidEvidence();
    }
    return text;
  }

  private static BigInteger positiveInteger(Object value) {
    if (!(value instanceof BigInteger integer) || integer.signum() <= 0) {
      throw invalidEvidence();
    }
    return integer;
  }

  private static BigInteger decimalInteger(String value) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw invalidEvidence();
    }
    return new BigInteger(value);
  }

  private static void requireExactTenantKeys(
      Map<String, ?> values, UUID tenantUuid, String description) {
    if (values == null || !values.keySet().equals(Set.of(tenantUuid.toString()))) {
      throw new IllegalStateException("Current " + description + " does not match selected tenant");
    }
  }

  private static byte[] requireDigest(byte[] supplied) {
    if (supplied == null || supplied.length != 32) {
      throw new IllegalArgumentException("Exact original request digest must be 32 bytes");
    }
    return supplied.clone();
  }

  private static byte[] requireSha256(byte[] value) {
    if (value == null || value.length != 32) {
      throw invalidEvidence();
    }
    return value;
  }

  private static UUID parseCanonicalUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (parsed.equals(new UUID(0L, 0L)) || !parsed.toString().equals(value)) {
        throw invalidEvidence();
      }
      return parsed;
    } catch (IllegalArgumentException exception) {
      throw invalidEvidence();
    }
  }

  private static String requireNonBlank(String value) {
    if (value == null || value.isBlank()) {
      throw invalidEvidence();
    }
    return value;
  }

  private static IllegalStateException invalidEvidence() {
    return new IllegalStateException(
        "Current Account authority evidence is malformed or mismatched");
  }

  /** Redacted, immutable values sufficient to inspect this source/current-authority comparison. */
  public record CurrentAuthorityReadback(
      UUID operationId,
      UUID accountUuid,
      UUID tenantUuid,
      String requestId,
      String connectScopeHash,
      String responseEnvelopeKeyId,
      String gatewayKeyId,
      byte[] sourceTokenHash,
      AuthorityTuple authorityTuple,
      Map<String, String> membershipVersion,
      String membershipLifecycleState,
      List<String> roles,
      Instant evaluatedAt,
      long issuanceFence,
      long fenceSourceVersion,
      byte[] requestDigest,
      byte[] captureDigest,
      List<CheckpointReadback> checkpoints,
      List<SourceEventReadback> sourceEvents) {
    public CurrentAuthorityReadback {
      Objects.requireNonNull(operationId, "operationId");
      Objects.requireNonNull(accountUuid, "accountUuid");
      Objects.requireNonNull(tenantUuid, "tenantUuid");
      Objects.requireNonNull(requestId, "requestId");
      Objects.requireNonNull(connectScopeHash, "connectScopeHash");
      Objects.requireNonNull(responseEnvelopeKeyId, "responseEnvelopeKeyId");
      Objects.requireNonNull(gatewayKeyId, "gatewayKeyId");
      sourceTokenHash = requireReadbackDigest(sourceTokenHash, "source token hash");
      Objects.requireNonNull(authorityTuple, "authorityTuple");
      membershipVersion = Map.copyOf(membershipVersion);
      Objects.requireNonNull(membershipLifecycleState, "membershipLifecycleState");
      roles = List.copyOf(roles);
      Objects.requireNonNull(evaluatedAt, "evaluatedAt");
      if (issuanceFence <= 0L || fenceSourceVersion <= 0L) {
        throw new IllegalArgumentException("Account issuance fence must be positive");
      }
      requestDigest = requireReadbackDigest(requestDigest, "request digest");
      captureDigest = requireReadbackDigest(captureDigest, "capture digest");
      checkpoints = List.copyOf(checkpoints);
      sourceEvents = List.copyOf(sourceEvents);
    }

    @Override
    public byte[] sourceTokenHash() {
      return sourceTokenHash.clone();
    }

    @Override
    public byte[] requestDigest() {
      return requestDigest.clone();
    }

    @Override
    public byte[] captureDigest() {
      return captureDigest.clone();
    }

    @Override
    public String toString() {
      return "CurrentAuthorityReadback[operationId="
          + operationId
          + ", accountUuid="
          + accountUuid
          + ", tenantUuid="
          + tenantUuid
          + ", authorityTuple="
          + authorityTuple
          + ", membershipVersion="
          + membershipVersion
          + ", issuanceFence="
          + issuanceFence
          + ", fenceSourceVersion="
          + fenceSourceVersion
          + ", checkpointCount="
          + checkpoints.size()
          + ", sourceEvents="
          + sourceEvents.size()
          + ", source=<redacted>]";
    }

    private static CurrentAuthorityReadback from(
        AccountConnectTokenIssuanceIdentity identity,
        OriginalSourceEvidence source,
        AccountConnectIssuanceFenceEvidence capture,
        RuntimeMembershipSnapshotDto snapshot,
        IssuanceFence fence) {
      List<CheckpointReadback> checkpoints =
          snapshot.outboxCheckpoints().stream()
              .map(
                  checkpoint ->
                      new CheckpointReadback(
                          checkpoint.outboxStreamKey(), checkpoint.outboxSequence()))
              .toList();
      List<SourceEventReadback> sourceEvents =
          snapshot.outboxSourceEvidence().stream()
              .map(
                  evidence ->
                      new SourceEventReadback(
                          evidence.outboxStreamKey(),
                          evidence.outboxSequence(),
                          evidence.eventId(),
                          evidence.eventDigest()))
              .toList();
      return new CurrentAuthorityReadback(
          source.operationId(),
          parseCanonicalUuid(source.originalSourceClaims().get("accountId").toString()),
          identity.tenantId(),
          identity.requestId(),
          AccountJoinDigest.tokenHash(identity.connectScopeId()),
          source.responseEnvelopeKeyId(),
          source.gatewayKeyId(),
          source.sourceTokenHash(),
          snapshot.authorityTuple(),
          snapshot.membershipBaseline().membershipVersion(),
          snapshot.membershipBaseline().membershipLifecycleState(),
          snapshot.roles(),
          snapshot.evaluatedAt(),
          fence.value(),
          fence.sourceVersion(),
          capture.requestDigest(),
          capture.digest(),
          checkpoints,
          sourceEvents);
    }

    private static byte[] requireReadbackDigest(byte[] value, String field) {
      if (value == null || value.length != 32) {
        throw new IllegalArgumentException(field + " must be 32 bytes");
      }
      return value.clone();
    }
  }

  /** Checkpoint identity only; canonical source-event payloads are deliberately omitted. */
  public record CheckpointReadback(String outboxStreamKey, String outboxSequence) {
    public CheckpointReadback {
      Objects.requireNonNull(outboxStreamKey, "outboxStreamKey");
      Objects.requireNonNull(outboxSequence, "outboxSequence");
    }
  }

  /** Event identity and digest only; the source event's canonical JSON is deliberately omitted. */
  public record SourceEventReadback(
      String outboxStreamKey, String outboxSequence, String eventId, String eventDigest) {
    public SourceEventReadback {
      Objects.requireNonNull(outboxStreamKey, "outboxStreamKey");
      Objects.requireNonNull(outboxSequence, "outboxSequence");
      Objects.requireNonNull(eventId, "eventId");
      Objects.requireNonNull(eventDigest, "eventDigest");
    }
  }
}
