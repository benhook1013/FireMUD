package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec.Event;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository.CommittedIssuance;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.Binding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only Account verification of a signed control-ui actor against committed issuance,
 * Coordination, protected signer/JWKS state, and current Account tenant membership sources.
 *
 * <p>The returned object is authenticated-current actor evidence only. This class does not issue or
 * redeem operator references, select a privileged route, or turn a creator grant into owner
 * authority. It deliberately denies global-role claims; callers must perform the approved route
 * predicate separately against this current tenant-scoped evidence.
 */
public final class AccountControlUiCurrentActorVerifier {
  private static final String ISSUER = "firemud-account-service";
  private static final int MAX_BUNDLE_BYTES = 131072;
  private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Set<String> SIGNER_RECEIPT_FIELDS =
      Set.of(
          "schema",
          "environmentId",
          "clusterId",
          "namespace",
          "clusterIncarnationUid",
          "namespaceUid",
          "trustBindingDigest",
          "trustConfigRevision",
          "apiBindingDigest",
          "apiConfigRevision",
          "promotionOperationId",
          "promotionRequestDigest",
          "generationOperationId",
          "signerGeneration",
          "kid",
          "algorithm",
          "publicKeyFingerprint",
          "privateReceiptDigest",
          "privateResourceVersion",
          "publicReceiptDigest",
          "publicResourceVersion",
          "publicConfigMapUid",
          "publicDataDigest");
  private static final Set<String> PENDING_RECEIPT_FIELDS =
      Set.of(
          "schema",
          "tokenHash",
          "recordDigest",
          "expiryMillis",
          "aclIdentity",
          "localAofCount",
          "replicaAofCount");
  private static final Set<String> BUNDLE_FIELDS =
      Set.of(
          "schema",
          "bundleRef",
          "snapshotIdentity",
          "evaluationIdentity",
          "issuer",
          "profile",
          "audience",
          "scope",
          "operation",
          "tokenIdentity",
          "authorityTuple",
          "membershipVersion",
          "issuanceFence",
          "authoritySourceVersions",
          "accountIdentitySource",
          "outboxCheckpoints",
          "roleEvidence");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};
  private static final ExplicitRouteProfilePolicy POLICY =
      new ExplicitRouteProfilePolicy(
          "account-control-ui-current-actor",
          ControlUiJwtProfileValidator.PROFILE,
          ControlUiJwtProfileValidator.TOKEN_TYPE,
          ISSUER,
          ControlUiJwtProfileValidator.AUDIENCE,
          ControlUiJwtProfileValidator.REQUIRED_CLAIMS,
          Set.of(),
          300,
          0,
          16384,
          claims -> ControlUiJwtProfileValidator.validateClaims(claims, 1));

  private final AccountJwtJwksTrustedSource publicJwks;
  private final AccountJwtSignerDesiredStateRepository signerStates;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrust;
  private final AccountJwtJwksApiBinding jwksApiBinding;
  private final AccountControlUiRegistryReader registry;
  private final AccountControlUiCommittedIssuanceRepository issuances;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountTenantAuthorityEventRepository tenantEvents;
  private final AccountTenantMembershipRepository memberships;
  private final AccountMembershipPairAuthorityRepository membershipPairs;
  private final AccountTenantMembershipRoleSnapshotRepository roleSnapshots;
  private final AccountAuthorityOutboxRepository outbox;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected Account repositories are shared owner services; they must retain their transaction and current-state identity rather than be copied.")
  public AccountControlUiCurrentActorVerifier(
      AccountJwtJwksTrustedSource publicJwks,
      AccountJwtSignerDesiredStateRepository signerStates,
      AccountJwtSignerMaterializerTrustBinding materializerTrust,
      AccountJwtJwksApiBinding jwksApiBinding,
      AccountControlUiRegistryReader registry,
      AccountControlUiCommittedIssuanceRepository issuances,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthorityGenerationRepository generations,
      AccountTenantAuthorityEventRepository tenantEvents,
      AccountTenantMembershipRepository memberships,
      AccountMembershipPairAuthorityRepository membershipPairs,
      AccountTenantMembershipRoleSnapshotRepository roleSnapshots,
      AccountAuthorityOutboxRepository outbox,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.publicJwks = Objects.requireNonNull(publicJwks);
    this.signerStates = Objects.requireNonNull(signerStates);
    this.materializerTrust = Objects.requireNonNull(materializerTrust);
    this.jwksApiBinding = Objects.requireNonNull(jwksApiBinding);
    this.registry = Objects.requireNonNull(registry);
    this.issuances = Objects.requireNonNull(issuances);
    this.sourceEvidence = Objects.requireNonNull(sourceEvidence);
    this.generations = Objects.requireNonNull(generations);
    this.tenantEvents = Objects.requireNonNull(tenantEvents);
    this.memberships = Objects.requireNonNull(memberships);
    this.membershipPairs = Objects.requireNonNull(membershipPairs);
    this.roleSnapshots = Objects.requireNonNull(roleSnapshots);
    this.outbox = Objects.requireNonNull(outbox);
    this.clock = Objects.requireNonNull(clock);
    accountTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    accountTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    accountTransaction.setReadOnly(false);
  }

  /** Verifies one signed actor for its selected canonical tenant; no authorization is returned. */
  public CurrentActor verify(String compactJwt, UUID selectedTenant) {
    outsideSql();
    requireTenant(selectedTenant);
    byte[] compactBytes = compactBytes(compactJwt);
    SourceIdentity pin = publicJwks.sourceIdentity();
    AccountPublicJwksCache cache =
        new AccountPublicJwksCache(publicJwks, pin, clock, Duration.ofSeconds(30));
    VerifiedClaims signed =
        new AccountAsymmetricJwtVerifier(cache, clock).verify(compactJwt, POLICY);
    PublicJwksSnapshot currentJwks = publicJwks.load();
    String tokenHash = sha256(compactBytes);
    byte[] activeRegistry = registry.readActive(tokenHash);

    CurrentActor result =
        accountTransaction.execute(
            ignored ->
                verifyOwnerState(
                    tokenHash, selectedTenant, signed, pin, currentJwks, activeRegistry));
    if (result == null) {
      throw denied();
    }
    return result;
  }

  private CurrentActor verifyOwnerState(
      String tokenHash,
      UUID selectedTenant,
      VerifiedClaims signed,
      SourceIdentity pin,
      PublicJwksSnapshot currentJwks,
      byte[] activeRegistry) {
    Map<String, Object> claims = signed.claims();
    UUID accountId = uuid(claims.get("accountId"));
    UUID tokenTenant = selectedScopedTenant(claims, selectedTenant);
    if (!selectedTenant.equals(tokenTenant) || claims.containsKey("globalRoles")) {
      throw denied();
    }
    CommittedIssuance issuance =
        issuances
            .readCommitted(tokenHash)
            .orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    Map<String, Object> registryRecord =
        AccountControlUiRegistryReader.parseActiveRecord(activeRegistry, tokenHash);
    if (!ISSUER.equals(claims.get("iss"))
        || !claims.get("sub").equals(accountId.toString())
        || !issuance.tokenHash().equals(tokenHash)
        || !issuance.accountId().equals(accountId)
        || !issuance.tenantId().equals(selectedTenant)
        || !issuance.tokenJti().equals(uuid(claims.get("jti")))
        || !issuance.requestId().equals(uuid(registryRecord.get("requestId")))
        || !issuance.operationId().equals(uuid(registryRecord.get("operationId")))
        || !Arrays.equals(activeRegistry, issuance.activeRegistry())
        || !Arrays.equals(canonicalBytes(claims), issuance.claims())
        || !signed.keyId().equals(registryRecord.get("kid"))
        || !accountId.toString().equals(registryRecord.get("accountId"))
        || !claims.get("jti").equals(registryRecord.get("jti"))
        || !canonicalMapEquals(registryRecord.get("authorityTuple"), claims.get("authorityTuple"))
        || !canonicalMapEquals(
            registryRecord.get("membershipVersion"), claims.get("membershipVersion"))
        || !numericEquals(
            registryRecord.get("tokenGeneration"), positiveLong(claims.get("tokenGeneration")))
        || !numericEquals(
            registryRecord.get("issuanceFence"), positiveLong(claims.get("issuanceFence")))
        || !numericEquals(claims.get("iat"), issuance.issuedAtEpochSecond())
        || !numericEquals(claims.get("nbf"), issuance.issuedAtEpochSecond())
        || !numericEquals(claims.get("exp"), issuance.expiresAtEpochSecond())
        || !numericEquals(registryRecord.get("iat"), issuance.issuedAtEpochSecond())
        || !numericEquals(registryRecord.get("nbf"), issuance.issuedAtEpochSecond())
        || !numericEquals(registryRecord.get("exp"), issuance.expiresAtEpochSecond())
        || !Arrays.equals(issuance.pendingRegistry(), derivePendingRegistry(activeRegistry))) {
      throw denied();
    }
    requireBundle(issuance, claims, registryRecord);
    requirePendingReceipt(issuance);
    requireSignerReceipt(issuance, signed.keyId(), pin, currentJwks);
    return requireCurrentAccountAuthority(issuance, claims, selectedTenant, tokenHash);
  }

  private void requireBundle(
      CommittedIssuance issuance, Map<String, Object> claims, Map<String, Object> registryRecord) {
    Map<String, Object> storedClaims = parseCanonicalObject(issuance.claims(), 16384);
    Map<String, Object> bundle = parseCanonicalObject(issuance.bundle(), MAX_BUNDLE_BYTES);
    Map<String, Object> bundleRef = object(bundle.get("bundleRef"));
    Map<String, Object> scope = object(bundle.get("scope"));
    Map<String, Object> operation = object(bundle.get("operation"));
    Map<String, Object> tokenIdentity = object(bundle.get("tokenIdentity"));
    Map<String, Object> roleEvidence = object(bundle.get("roleEvidence"));
    if (!bundle.keySet().equals(BUNDLE_FIELDS)
        || !bundleRef
            .keySet()
            .equals(Set.of("bundleVersion", "sourceVersion", "sourceFence", "linearization"))
        || !scope.keySet().equals(Set.of("kind", "accountId", "tenantIds"))
        || !operation
            .keySet()
            .equals(
                Set.of(
                    "operationId",
                    "requestId",
                    "requestDigest",
                    "callerWorkload",
                    "callerContextId",
                    "accountId"))
        || !tokenIdentity.keySet().equals(Set.of("jti", "tokenGeneration", "iat", "nbf", "exp"))
        || !roleEvidence.keySet().equals(Set.of("scopedRoles", "freshnessSource"))
        || !Arrays.equals(canonicalBytes(claims), canonicalBytes(storedClaims))
        || !"account-auth-evidence-bundle/v1".equals(bundle.get("schema"))
        || !ISSUER.equals(bundle.get("issuer"))
        || !"control-ui".equals(bundle.get("profile"))
        || !"control-ui".equals(bundle.get("audience"))
        || !issuance.operationId().toString().equals(operation.get("operationId"))
        || !issuance.requestId().toString().equals(operation.get("requestId"))
        || !issuance.requestDigest().equals(operation.get("requestDigest"))
        || !issuance.accountId().toString().equals(operation.get("accountId"))
        || !issuance.callerWorkload().equals(operation.get("callerWorkload"))
        || !issuance.callerContextId().toString().equals(operation.get("callerContextId"))
        || !issuance.accountId().toString().equals(scope.get("accountId"))
        || !List.of(issuance.tenantId().toString()).equals(scope.get("tenantIds"))
        || !"TENANT".equals(scope.get("kind"))
        || !canonicalMapEquals(bundle.get("authorityTuple"), claims.get("authorityTuple"))
        || !canonicalMapEquals(bundle.get("membershipVersion"), claims.get("membershipVersion"))
        || !numericEquals(bundle.get("issuanceFence"), positiveLong(claims.get("issuanceFence")))
        || !canonicalMapEquals(
            bundle.get("authoritySourceVersions"), registryRecord.get("authoritySourceVersions"))
        || !canonicalMapEquals(tokenIdentity, tokenIdentity(claims))
        || !canonicalMapEquals(roleEvidence.get("scopedRoles"), claims.get("scopedRoles"))) {
      throw denied();
    }
    parseCanonicalObject(issuance.source(), 131072);
    String sourceIdentity = sha256(issuance.source());
    if (!sourceIdentity.equals(bundle.get("snapshotIdentity"))
        || !sourceIdentity.equals(roleEvidence.get("freshnessSource"))) {
      throw denied();
    }
    Map<String, Object> expectedReference = new LinkedHashMap<>(bundleRef);
    expectedReference.put("canonicalSha256", sha256(issuance.bundle()));
    if (!canonicalMapEquals(expectedReference, registryRecord.get("authEvidenceBundle"))) {
      throw denied();
    }
    Map<String, Object> evaluation =
        Map.of(
            "operation", operation,
            "claims", claims,
            "bundleRef", bundleRef,
            "snapshotIdentity", sourceIdentity);
    if (!sha256(canonicalBytes(evaluation)).equals(bundle.get("evaluationIdentity"))) {
      throw denied();
    }
  }

  private void requirePendingReceipt(CommittedIssuance issuance) {
    Map<String, Object> receipt = parseCanonicalObject(issuance.pendingReceipt(), 8192);
    if (!receipt.keySet().equals(PENDING_RECEIPT_FIELDS)
        || !"account-control-ui-pending-readback/v1".equals(receipt.get("schema"))
        || !issuance.tokenHash().equals(receipt.get("tokenHash"))
        || !sha256(issuance.pendingRegistry()).equals(receipt.get("recordDigest"))
        || !"account_coord_app".equals(receipt.get("aclIdentity"))
        || !numericEquals(receipt.get("expiryMillis"), issuance.expiryMillis())
        || !atLeastOne(receipt.get("localAofCount"))
        || !atLeastOne(receipt.get("replicaAofCount"))) {
      throw denied();
    }
  }

  private void requireSignerReceipt(
      CommittedIssuance issuance,
      String verifiedKid,
      SourceIdentity pin,
      PublicJwksSnapshot currentJwks) {
    Map<String, Object> receipt = parseCanonicalObject(issuance.signerReceipt(), 65536);
    Optional<String> fingerprint =
        AccountJwtJwksTrustedSource.publicKeyFingerprint(currentJwks, pin, verifiedKid);
    Map<String, Object> registryRecord =
        AccountControlUiRegistryReader.parseActiveRecord(
            issuance.activeRegistry(), issuance.tokenHash());
    if (!receipt.keySet().equals(SIGNER_RECEIPT_FIELDS)
        || !"account-control-ui-committed-signer/v1".equals(receipt.get("schema"))
        || !pin.environmentId().equals(receipt.get("environmentId"))
        || !pin.clusterId().equals(receipt.get("clusterId"))
        || !pin.clusterIncarnationUid().equals(receipt.get("clusterIncarnationUid"))
        || !pin.namespace().equals(receipt.get("namespace"))
        || !pin.namespaceUid().equals(receipt.get("namespaceUid"))
        || !pin.configMapUid().equals(receipt.get("publicConfigMapUid"))
        || !pin.bindingRevision().equals(receipt.get("apiConfigRevision"))
        || !verifiedKid.equals(receipt.get("kid"))
        || !verifiedKid.equals(registryRecord.get("kid"))
        || !"RS256".equals(receipt.get("algorithm"))
        || !fingerprint.orElse("").equals(receipt.get("publicKeyFingerprint"))
        || !registryRecord.get("signerGeneration").equals(receipt.get("signerGeneration"))
        || !issuance.operationId().toString().equals(registryRecord.get("operationId"))
        || !issuance.requestId().toString().equals(registryRecord.get("requestId"))
        || !issuance.requestDigest().equals(registryRecord.get("requestDigest"))
        || !sha256(issuance.signerReceipt())
            .equals(registryRecord.get("originalSignerReceiptDigest"))
        || !DIGEST.matcher(text(receipt.get("trustBindingDigest"))).matches()
        || !DIGEST.matcher(text(receipt.get("apiBindingDigest"))).matches()
        || !DIGEST.matcher(text(receipt.get("promotionRequestDigest"))).matches()
        || !DIGEST.matcher(text(receipt.get("privateReceiptDigest"))).matches()
        || !DIGEST.matcher(text(receipt.get("publicReceiptDigest"))).matches()
        || !DIGEST.matcher(text(receipt.get("publicDataDigest"))).matches()
        || !DIGEST.matcher(text(receipt.get("publicKeyFingerprint"))).matches()) {
      throw denied();
    }
    requireOriginalCommittedSigner(receipt, verifiedKid);
  }

  private void requireOriginalCommittedSigner(Map<String, Object> receipt, String verifiedKid) {
    var materializer =
        materializerTrust.current().orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    var api = jwksApiBinding.current();
    if (!materializer.environmentId().equals(api.environmentId())
        || !materializer.clusterId().equals(api.clusterId())
        || !materializer.namespace().equals(api.namespace())
        || !materializer.expectedClusterIncarnationUid().equals(api.expectedClusterIncarnationUid())
        || !materializer.expectedNamespaceUid().equals(api.expectedNamespaceUid())
        || !text(receipt.get("apiBindingDigest")).equals(api.bindingDigest())
        || !text(receipt.get("apiConfigRevision")).equals(api.configRevision())
        || !text(receipt.get("trustBindingDigest")).equals(materializer.bindingDigest())
        || !text(receipt.get("trustConfigRevision")).equals(materializer.configRevision())) {
      throw denied();
    }
    Binding binding =
        new Binding(
            materializer.environmentId(),
            materializer.clusterId(),
            materializer.namespace(),
            CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    TrustFence trust =
        new TrustFence(
            materializer.expectedClusterIncarnationUid(),
            materializer.expectedNamespaceUid(),
            materializer.bindingDigest(),
            materializer.configRevision());
    var original =
        signerStates
            .readOriginalCommittedSigner(
                binding,
                trust,
                api.bindingDigest(),
                api.configRevision(),
                uuid(receipt.get("promotionOperationId")),
                uuid(receipt.get("generationOperationId")))
            .orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    var promotion = original.promotion();
    var generation = original.generationResult();
    if (!"COMMITTED".equals(promotion.status())
        || !promotion.operationId().toString().equals(receipt.get("promotionOperationId"))
        || !promotion.requestDigest().equals(receipt.get("promotionRequestDigest"))
        || !generation.operationId().toString().equals(receipt.get("generationOperationId"))
        || !verifiedKid.equals(generation.targetKid())
        || !generation.targetGeneration().equals(receipt.get("signerGeneration"))
        || !"RS256".equals(generation.targetAlgorithm())
        || !generation.publicKeyFingerprint().equals(receipt.get("publicKeyFingerprint"))
        || !original.privateReceipt().receiptDigest().equals(receipt.get("privateReceiptDigest"))
        || !original
            .privateReceipt()
            .observedResourceVersion()
            .equals(receipt.get("privateResourceVersion"))
        || !original.publicReceipt().receiptDigest().equals(receipt.get("publicReceiptDigest"))
        || !original
            .publicReceipt()
            .observedResourceVersion()
            .equals(receipt.get("publicResourceVersion"))
        || !original.publicReceipt().configMapUid().equals(receipt.get("publicConfigMapUid"))
        || !original.publicReceipt().publicDataDigest().equals(receipt.get("publicDataDigest"))) {
      throw denied();
    }
  }

  private CurrentActor requireCurrentAccountAuthority(
      CommittedIssuance issuance, Map<String, Object> claims, UUID tenantId, String tokenHash) {
    UUID accountId = issuance.accountId();
    IssuerAccountSourceSnapshot source =
        sourceEvidence.readCurrentIssuerAccountSources(ISSUER, accountId);
    CompositeSnapshot snapshot =
        generations.readCompositeSnapshot(ISSUER, accountId, List.of(tenantId), List.of(tenantId));
    if (source.issuer().generation() != snapshot.issuer().generation()
        || source.issuer().sourceVersion() != snapshot.issuer().sourceVersion()
        || source.account().generation() != snapshot.account().generation()
        || source.account().sourceVersion() != snapshot.account().sourceVersion()
        || !source.issuanceFence().equals(snapshot.issuanceFence())) {
      throw denied();
    }
    var tenantState = only(snapshot.tenants(), "tenant authority");
    var membershipState = only(snapshot.memberships(), "membership authority");
    Event currentTenant = tenantEvents.readCurrentByTenant(tenantId);
    if (!tenantId.equals(currentTenant.tenantId())
        || currentTenant.tenantAuthorityGeneration() != tenantState.generation()
        || currentTenant.tenantAuthoritySourceVersion() != tenantState.sourceVersion()) {
      throw denied();
    }

    PairAuthority pair =
        membershipPairs
            .readPositive(accountId, tenantId)
            .orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    AccountTenantMembership membership =
        memberships
            .findFreshMembership(accountId, tenantId)
            .orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    if (!pair.membershipExists()
        || pair.eventSequence() <= 0L
        || pair.lastTransitionInvalidated()
        || membership.getId() == null
        || membership.getAccount() == null
        || membership.getAccount().getId() == null
        || !accountId.equals(membership.getAccount().getAccountUuid())
        || !tenantId.equals(membership.getTenantUuid())
        || !"ACTIVE".equals(membership.getLifecycleState())
        || membership.getMembershipVersion() != pair.membershipVersion()
        || membership.getMembershipAuthorityGeneration() != pair.membershipAuthorityGeneration()
        || pair.membershipAuthorityGeneration() != membershipState.generation()) {
      throw denied();
    }
    MembershipEvent currentMembershipEvent =
        requireCurrentMembershipEvent(accountId, tenantId, pair, membershipState.generation());
    RoleSnapshot roles =
        roleSnapshots
            .findForCanonicalUpdate(
                accountId,
                tenantId,
                pair.provenance(),
                membership.getId(),
                membership.getMembershipVersion())
            .orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    if (!roles.roles().equals(currentMembershipEvent.roles())) {
      throw denied();
    }
    requireClaimsCurrent(
        claims, accountId, tenantId, source, snapshot, currentTenant, pair, membership, roles);
    return new CurrentActor(
        accountId,
        tenantId,
        issuance.operationId(),
        tokenHash,
        roles.roles(),
        snapshot.issuer().generation(),
        snapshot.account().generation(),
        tenantState.generation(),
        membershipState.generation(),
        membership.getMembershipVersion(),
        snapshot.issuanceFence().value());
  }

  private MembershipEvent requireCurrentMembershipEvent(
      UUID accountId, UUID tenantId, PairAuthority pair, long membershipGeneration) {
    String streamKey =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + accountId
            + "/"
            + tenantId;
    var stored =
        outbox
            .findEvent(streamKey, pair.eventSequence())
            .orElseThrow(AccountControlUiCurrentActorVerifier::denied);
    MembershipEvent event =
        MembershipAuthorityEventV1Codec.verify(
            new String(stored.payload(), StandardCharsets.UTF_8));
    if (!stored.outboxStreamKey().equals(streamKey)
        || !stored.eventId().equals(event.eventId())
        || !stored.eventDigest().equals(event.eventDigest())
        || !Long.toString(stored.outboxSequence()).equals(event.outboxSequence())
        || !pair.eventId().equals(event.eventId())
        || !pair.eventDigest().equals(event.eventDigest())
        || !accountId.toString().equals(event.accountId())
        || !tenantId.toString().equals(event.tenantId())
        || !Long.toString(pair.membershipVersion())
            .equals(event.membershipVersion().get(tenantId.toString()))
        || !Long.toString(membershipGeneration).equals(event.membershipAuthorityGeneration())
        || !"ACTIVE".equals(event.membershipLifecycleState())
        || event.callerBoundAuthorityInvalidated() != pair.lastTransitionInvalidated()) {
      throw denied();
    }
    return event;
  }

  private void requireClaimsCurrent(
      Map<String, Object> claims,
      UUID accountId,
      UUID tenantId,
      IssuerAccountSourceSnapshot source,
      CompositeSnapshot snapshot,
      Event tenantEvent,
      PairAuthority pair,
      AccountTenantMembership membership,
      RoleSnapshot roles) {
    Map<String, Object> tuple = object(claims.get("authorityTuple"));
    Map<String, Long> tenantGenerations =
        Map.of(tenantId.toString(), only(snapshot.tenants(), "tenant").generation());
    Map<String, Long> membershipGenerations =
        Map.of(tenantId.toString(), pair.membershipAuthorityGeneration());
    Map<String, Long> membershipVersions = Map.of(tenantId.toString(), pair.membershipVersion());
    Map<String, Object> expectedTuple = new LinkedHashMap<>();
    expectedTuple.put("issuerAuthGeneration", snapshot.issuer().generation());
    expectedTuple.put("accountAuthorityGeneration", snapshot.account().generation());
    expectedTuple.put("tenantAuthorityGeneration", tenantGenerations);
    expectedTuple.put("membershipAuthorityGeneration", membershipGenerations);
    expectedTuple.put("privateRealmGrantVersions", List.of());
    source
        .account()
        .accountSecurityCutoff()
        .ifPresent(
            cutoff ->
                expectedTuple.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration",
                        Long.parseLong(cutoff.accountAuthorityGeneration()),
                        "outboxStreamKey",
                        cutoff.outboxStreamKey(),
                        "outboxSequence",
                        Long.parseLong(cutoff.outboxSequence()))));
    if (tuple.containsKey("tenantBillingCutoff")) {
      expectedTuple.put(
          "tenantBillingCutoff",
          Map.of(
              tenantId.toString(),
              Map.of(
                  "tenantAuthorityGeneration",
                  tenantEvent.tenantAuthorityGeneration(),
                  "tenantBillingSequence",
                  tenantEvent.tenantBillingSequence(),
                  "outboxStreamKey",
                  tenantEvent.tenantBillingStreamKey(),
                  "outboxSequence",
                  tenantEvent.outboxSequence())));
    }
    Map<String, Object> scoped = object(claims.get("scopedRoles"));
    if (!numericEquals(tuple.get("issuerAuthGeneration"), snapshot.issuer().generation())
        || !numericEquals(tuple.get("accountAuthorityGeneration"), snapshot.account().generation())
        || !canonicalMapEquals(tuple.get("tenantAuthorityGeneration"), tenantGenerations)
        || !canonicalMapEquals(tuple.get("membershipAuthorityGeneration"), membershipGenerations)
        || !canonicalMapEquals(tuple, expectedTuple)
        || !canonicalMapEquals(claims.get("membershipVersion"), membershipVersions)
        || !numericEquals(claims.get("issuanceFence"), snapshot.issuanceFence().value())
        || !canonicalMapEquals(scoped, Map.of(tenantId.toString(), roles.roles()))
        || membership.getMembershipVersion() != pair.membershipVersion()
        || !accountId.equals(membership.getAccount().getAccountUuid())) {
      throw denied();
    }
  }

  private static byte[] derivePendingRegistry(byte[] activeBytes) {
    Map<String, Object> active =
        parseCanonicalObject(activeBytes, AccountControlUiRegistryReader.MAX_RECORD_BYTES);
    if (!"active".equals(active.get("state"))
        || !Integer.valueOf(2).equals(number(active.get("registryVersion")))) {
      throw denied();
    }
    Map<String, Object> pending = new LinkedHashMap<>(active);
    pending.put("state", "pending");
    pending.put("registryVersion", 1);
    return canonicalBytes(pending);
  }

  private static Map<String, Object> tokenIdentity(Map<String, Object> claims) {
    Map<String, Object> identity = new LinkedHashMap<>();
    identity.put("jti", claims.get("jti"));
    identity.put("tokenGeneration", claims.get("tokenGeneration"));
    identity.put("iat", claims.get("iat"));
    identity.put("nbf", claims.get("nbf"));
    identity.put("exp", claims.get("exp"));
    return identity;
  }

  private static UUID selectedScopedTenant(Map<String, Object> claims, UUID selectedTenant) {
    Map<String, Object> roles = object(claims.get("scopedRoles"));
    if (!roles.keySet().equals(Set.of(selectedTenant.toString()))) {
      throw denied();
    }
    return selectedTenant;
  }

  private static AccountAuthorityGenerationRepository.ScopeState only(
      List<AccountAuthorityGenerationRepository.ScopeState> states, String label) {
    if (states == null || states.size() != 1) {
      throw denied();
    }
    return states.getFirst();
  }

  private static Map<String, Object> parseCanonicalObject(byte[] bytes, int maximum) {
    if (bytes == null || bytes.length == 0 || bytes.length > maximum) {
      throw denied();
    }
    try {
      String json =
          StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      if (!Arrays.equals(bytes, Rfc8785CanonicalJson.canonicalizeUtf8(json))) {
        throw denied();
      }
      Map<String, Object> fields = JSON.readValue(bytes, OBJECT);
      if (fields == null) {
        throw denied();
      }
      return Map.copyOf(fields);
    } catch (IOException | RuntimeException malformed) {
      throw denied();
    }
  }

  private static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw denied();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    map.forEach(
        (key, item) -> {
          if (!(key instanceof String text)) {
            throw denied();
          }
          result.put(text, item);
        });
    return result;
  }

  private static boolean canonicalMapEquals(Object first, Object second) {
    return Arrays.equals(canonicalBytes(first), canonicalBytes(second));
  }

  private static byte[] canonicalBytes(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException failure) {
      throw denied();
    }
  }

  private static boolean numericEquals(Object actual, long expected) {
    return actual instanceof Number number && number.toString().equals(Long.toString(expected));
  }

  private static Number number(Object value) {
    return value instanceof Number number ? number : null;
  }

  private static long positiveLong(Object value) {
    if (!(value instanceof Number number)) {
      throw denied();
    }
    try {
      long parsed = Long.parseLong(number.toString());
      if (parsed <= 0L) {
        throw denied();
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw denied();
    }
  }

  private static boolean atLeastOne(Object value) {
    return value instanceof Number number
        && number.toString().matches("[1-9][0-9]{0,2}")
        && number.longValue() >= 1L;
  }

  private static UUID uuid(Object value) {
    if (!(value instanceof String text)) {
      throw denied();
    }
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)
          || parsed.getMostSignificantBits() == 0L && parsed.getLeastSignificantBits() == 0L) {
        throw denied();
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw denied();
    }
  }

  private static String text(Object value) {
    return value instanceof String text ? text : "";
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw denied();
    }
  }

  private static byte[] compactBytes(String compactJwt) {
    if (compactJwt == null
        || compactJwt.isBlank()
        || compactJwt.length() > 16384
        || compactJwt.codePoints().anyMatch(codePoint -> codePoint > 0x7f)) {
      throw denied();
    }
    return compactJwt.getBytes(StandardCharsets.US_ASCII);
  }

  private static void requireTenant(UUID tenant) {
    if (tenant == null
        || tenant.getMostSignificantBits() == 0L && tenant.getLeastSignificantBits() == 0L) {
      throw denied();
    }
  }

  private static void outsideSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account JWT and Coordination reads must precede SQL ownership");
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Current Account control-ui actor evidence unavailable");
  }

  /**
   * Identity and current source tuple only. It is not a route authorization or a reference grant.
   */
  public record CurrentActor(
      UUID accountId,
      UUID tenantId,
      UUID issuanceOperationId,
      String tokenHash,
      List<String> currentTenantRoles,
      long issuerGeneration,
      long accountGeneration,
      long tenantGeneration,
      long membershipGeneration,
      long membershipVersion,
      long issuanceFence) {
    public CurrentActor {
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(tenantId);
      Objects.requireNonNull(issuanceOperationId);
      if (tokenHash == null || !DIGEST.matcher(tokenHash).matches()) {
        throw new IllegalArgumentException("Control-ui actor token identity is invalid");
      }
      currentTenantRoles = List.copyOf(currentTenantRoles);
      if (issuerGeneration <= 0L
          || accountGeneration <= 0L
          || tenantGeneration <= 0L
          || membershipGeneration <= 0L
          || membershipVersion <= 0L
          || issuanceFence <= 0L) {
        throw new IllegalArgumentException("Control-ui current authority values must be positive");
      }
    }
  }
}
