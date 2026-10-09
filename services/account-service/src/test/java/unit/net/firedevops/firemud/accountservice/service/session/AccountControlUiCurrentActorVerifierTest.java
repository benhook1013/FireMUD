package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding.ParsedBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding.Binding;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.accountservice.entity.Account;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.entity.AccountTenantMembership;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountControlUiCommittedIssuanceRepository.CommittedIssuance;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.OriginalCommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiCurrentActorVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountControlUiRegistryReader;
import net.firedevops.firemud.accountservice.service.session.AccountJwtJwksTrustedSource;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * Full verifier unit proof. Owner repositories and protected signer/JWKS bindings are mocked at
 * their public seams; this does not claim physical PostgreSQL, Redis, or Kubernetes integration
 * authority.
 */
class AccountControlUiCurrentActorVerifierTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String ISSUER = "firemud-account-service";
  private static final String KID = "control-ui-key";
  private static final String ENVIRONMENT = "test";
  private static final String CLUSTER = "cluster-test";
  private static final String NAMESPACE = "firemud-test";
  private static final String CLUSTER_UID = "11111111-1111-4111-8111-111111111111";
  private static final String NAMESPACE_UID = "22222222-2222-4222-8222-222222222222";
  private static final String CONFIG_MAP_UID = "33333333-3333-4333-8333-333333333333";
  private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
  private static final UUID OTHER_ACCOUNT_ID =
      UUID.fromString("10000000-0000-4000-8000-000000000009");
  private static final UUID TENANT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");
  private static final UUID OTHER_TENANT_ID =
      UUID.fromString("20000000-0000-4000-8000-000000000009");
  private static final UUID TOKEN_ID = UUID.fromString("30000000-0000-4000-8000-000000000003");
  private static final UUID OPERATION_ID = UUID.fromString("40000000-0000-4000-8000-000000000004");
  private static final UUID REQUEST_ID = UUID.fromString("50000000-0000-4000-8000-000000000005");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("60000000-0000-4000-8000-000000000006");
  private static final UUID PROMOTION_OPERATION_ID =
      UUID.fromString("70000000-0000-4000-8000-000000000007");
  private static final UUID GENERATION_OPERATION_ID =
      UUID.fromString("80000000-0000-4000-8000-000000000008");
  private static final UUID MEMBERSHIP_EVENT_ID =
      UUID.fromString("90000000-0000-4000-8000-000000000009");
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String PROMOTION_REQUEST_DIGEST = "b".repeat(64);
  private static final String PRIVATE_RECEIPT_DIGEST = "c".repeat(64);
  private static final String PUBLIC_RECEIPT_DIGEST = "d".repeat(64);
  private static final String API_BINDING_DIGEST = "e".repeat(64);
  private static final String API_CA_DIGEST = "f".repeat(64);
  private static final Instant NOW = Instant.parse("2035-04-05T06:07:08Z");
  private static final long NOW_SECONDS = NOW.getEpochSecond();
  private static final long EXPIRY_SECONDS = NOW_SECONDS + 300L;
  private static KeyPair signingKey;

  @BeforeAll
  static void generateSigningKey() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    signingKey = generator.generateKeyPair();
  }

  @Test
  void verifiesARealSignedTokenAgainstExactCommittedRegistrySignerAndCurrentTenantEvidence()
      throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 1, List.of("tenantAdmin"), 1, false);

    AccountControlUiCurrentActorVerifier.CurrentActor actor =
        fixture.verifier.verify(fixture.compactJwt, TENANT_ID);

    assertThat(actor.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(actor.tenantId()).isEqualTo(TENANT_ID);
    assertThat(actor.issuanceOperationId()).isEqualTo(OPERATION_ID);
    assertThat(actor.tokenHash()).isEqualTo(fixture.tokenHash);
    assertThat(actor.currentTenantRoles()).containsExactly("tenantAdmin");
    assertThat(actor.issuerGeneration()).isEqualTo(1L);
    assertThat(actor.accountGeneration()).isEqualTo(1L);
    assertThat(actor.tenantGeneration()).isEqualTo(1L);
    assertThat(actor.membershipGeneration()).isEqualTo(1L);
    assertThat(actor.membershipVersion()).isEqualTo(1L);
    assertThat(actor.issuanceFence()).isEqualTo(1L);
    verify(fixture.signerStates)
        .readOriginalCommittedSigner(
            eq(fixture.signerBinding),
            eq(fixture.trustFence),
            eq(API_BINDING_DIGEST),
            eq("api-r1"),
            eq(PROMOTION_OPERATION_ID),
            eq(GENERATION_OPERATION_ID));
  }

  @Test
  void rejectsAChangedActorEvenWhenThePresentedCompactTokenIsValidlySigned() throws Exception {
    Fixture fixture = new Fixture(OTHER_ACCOUNT_ID, null, 1, 1, List.of("tenantAdmin"), 1, false);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));

    verify(fixture.issuances).readCommitted(fixture.tokenHash);
    verify(fixture.membershipPairs, never()).readPositive(OTHER_ACCOUNT_ID, TENANT_ID);
  }

  @Test
  void rejectsASelectedTenantOutsideTheExactSignedTenantScope() throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 1, List.of("tenantAdmin"), 1, false);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, OTHER_TENANT_ID));

    verify(fixture.issuances, never()).readCommitted(any());
  }

  @Test
  void rejectsStaleTenantGenerationEvenWhenTheCurrentTenantEventAgreesWithTheOwnerSnapshot()
      throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 2, 1, List.of("tenantAdmin"), 1, false);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));
  }

  @Test
  void rejectsStaleMembershipGenerationAgainstTheCurrentPositivePairAndEvent() throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 2, List.of("tenantAdmin"), 1, false);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));
  }

  @Test
  void rejectsRoleSnapshotThatDiffersFromTheCurrentMembershipEvent() throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 1, List.of("moderator"), 1, false);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));
  }

  @Test
  void rejectsARevokedIssuanceWhenNoCommittedIssuanceCanBeRead() throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 1, List.of("tenantAdmin"), 1, false);
    when(fixture.issuances.readCommitted(fixture.tokenHash)).thenReturn(Optional.empty());

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));

    verify(fixture.sourceEvidence, never()).readCurrentIssuerAccountSources(ISSUER, ACCOUNT_ID);
  }

  @Test
  void rejectsACommitRecordWithAnInvalidCoordinationReadbackReceipt() throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 1, List.of("tenantAdmin"), 0, false);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));
  }

  @Test
  void rejectsACommittedSignerReceiptThatDoesNotMatchProtectedOriginalReadback() throws Exception {
    Fixture fixture = new Fixture(ACCOUNT_ID, null, 1, 1, List.of("tenantAdmin"), 1, true);

    assertDenied(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID));
  }

  @Test
  void rejectsGlobalRoleClaimsInsteadOfTreatingThemAsTenantOperatorAuthority() throws Exception {
    Fixture fixture =
        new Fixture(ACCOUNT_ID, List.of("platformAdmin"), 1, 1, List.of("tenantAdmin"), 1, false);

    assertThatThrownBy(() -> fixture.verifier.verify(fixture.compactJwt, TENANT_ID))
        .isInstanceOf(AccountAsymmetricJwtVerifier.VerificationException.class);

    verify(fixture.issuances, never()).readCommitted(any());
  }

  private static void assertDenied(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class);
  }

  private static final class Fixture {
    private final UUID issuanceAccountId = ACCOUNT_ID;
    private final String compactJwt;
    private final String tokenHash;
    private final AccountJwtSignerDesiredStateRepository.Binding signerBinding;
    private final TrustFence trustFence;
    private final AccountControlUiCurrentActorVerifier verifier;
    private final AccountControlUiCommittedIssuanceRepository issuances =
        mock(AccountControlUiCommittedIssuanceRepository.class);
    private final AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    private final AccountAuthorityGenerationRepository generations =
        mock(AccountAuthorityGenerationRepository.class);
    private final AccountTenantAuthorityEventRepository tenantEvents =
        mock(AccountTenantAuthorityEventRepository.class);
    private final AccountTenantMembershipRepository memberships =
        mock(AccountTenantMembershipRepository.class);
    private final AccountMembershipPairAuthorityRepository membershipPairs =
        mock(AccountMembershipPairAuthorityRepository.class);
    private final AccountTenantMembershipRoleSnapshotRepository roleSnapshots =
        mock(AccountTenantMembershipRoleSnapshotRepository.class);
    private final AccountAuthorityOutboxRepository outbox =
        mock(AccountAuthorityOutboxRepository.class);
    private final AccountJwtSignerDesiredStateRepository signerStates =
        mock(AccountJwtSignerDesiredStateRepository.class);

    private Fixture(
        UUID tokenAccountId,
        List<String> globalRoles,
        long currentTenantGeneration,
        long currentMembershipGeneration,
        List<String> roleSnapshot,
        int localAofCount,
        boolean mismatchPublicReceipt)
        throws Exception {
      Map<String, Object> claims = claims(tokenAccountId, globalRoles);
      compactJwt = sign(claims, signingKey);
      tokenHash = sha256(compactJwt.getBytes(StandardCharsets.US_ASCII));

      String jwksJson = jwks((RSAPublicKey) signingKey.getPublic());
      byte[] jwksBytes = jwksJson.getBytes(StandardCharsets.UTF_8);
      SourceIdentity sourceIdentity = sourceIdentity();
      PublicJwksSnapshot jwksSnapshot = new PublicJwksSnapshot(sourceIdentity, jwksBytes);
      AccountJwtJwksTrustedSource publicJwks = mock(AccountJwtJwksTrustedSource.class);
      when(publicJwks.sourceIdentity()).thenReturn(sourceIdentity);
      when(publicJwks.load()).thenReturn(jwksSnapshot);

      String trustBindingDigest =
          AccountJwtSignerMaterializerTrustBinding.computeBindingDigest(
              "trust-r1",
              ENVIRONMENT,
              CLUSTER,
              NAMESPACE,
              CLUSTER_UID,
              NAMESPACE_UID,
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-jwt-materializer",
              List.of("1".repeat(64)));
      Binding materializerBinding =
          new Binding(
              ENVIRONMENT,
              CLUSTER,
              NAMESPACE,
              CLUSTER_UID,
              NAMESPACE_UID,
              "spiffe://firemud/ns/" + NAMESPACE + "/sa/account-jwt-materializer",
              List.of("1".repeat(64)),
              "trust-r1",
              trustBindingDigest);
      when(materializerTrust.current()).thenReturn(Optional.of(materializerBinding));

      ParsedBinding apiParsedBinding =
          new ParsedBinding(
              "api-r1",
              ENVIRONMENT,
              CLUSTER,
              URI.create("https://kubernetes.test:6443"),
              "kubernetes.test",
              Path.of("/test/serving-ca.pem"),
              API_CA_DIGEST,
              Path.of("/test/token"),
              NAMESPACE,
              CLUSTER_UID,
              NAMESPACE_UID,
              "system:serviceaccount:" + NAMESPACE + ":account-service",
              API_BINDING_DIGEST);
      when(apiBinding.current()).thenReturn(apiParsedBinding);

      AccountJwtSignerDesiredStateRepository.CustodyMode custodyMode =
          CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK;
      signerBinding =
          new AccountJwtSignerDesiredStateRepository.Binding(
              ENVIRONMENT, CLUSTER, NAMESPACE, custodyMode);
      trustFence = new TrustFence(CLUSTER_UID, NAMESPACE_UID, trustBindingDigest, "trust-r1");

      byte[] sourceBytes =
          canonicalBytes(
              Map.of(
                  "schemaVersion", 1,
                  "issuer", ISSUER,
                  "accountId", issuanceAccountId.toString(),
                  "tenantId", TENANT_ID.toString(),
                  "sources", List.of()));
      String sourceHash = sha256(sourceBytes);
      Map<String, Object> bundleRef =
          Map.of(
              "bundleVersion", "1",
              "sourceVersion", "1",
              "sourceFence", "1",
              "linearization", "1");
      Map<String, Object> operation =
          Map.of(
              "operationId", OPERATION_ID.toString(),
              "requestId", REQUEST_ID.toString(),
              "requestDigest", REQUEST_DIGEST,
              "callerWorkload", "logging-admin-service",
              "callerContextId", CALLER_CONTEXT_ID.toString(),
              "accountId", issuanceAccountId.toString());
      Map<String, Object> sourceVersions = sourceVersions();
      Map<String, Object> roleEvidence =
          Map.of("scopedRoles", claims.get("scopedRoles"), "freshnessSource", sourceHash);
      Map<String, Object> bundle =
          new LinkedHashMap<>(
              Map.ofEntries(
                  Map.entry("schema", "account-auth-evidence-bundle/v1"),
                  Map.entry("bundleRef", bundleRef),
                  Map.entry("snapshotIdentity", sourceHash),
                  Map.entry("issuer", ISSUER),
                  Map.entry("profile", "control-ui"),
                  Map.entry("audience", "control-ui"),
                  Map.entry(
                      "scope",
                      Map.of(
                          "kind", "TENANT",
                          "accountId", issuanceAccountId.toString(),
                          "tenantIds", List.of(TENANT_ID.toString()))),
                  Map.entry("operation", operation),
                  Map.entry("tokenIdentity", tokenIdentity(claims)),
                  Map.entry("authorityTuple", claims.get("authorityTuple")),
                  Map.entry("membershipVersion", claims.get("membershipVersion")),
                  Map.entry("issuanceFence", claims.get("issuanceFence")),
                  Map.entry("authoritySourceVersions", sourceVersions),
                  Map.entry(
                      "accountIdentitySource",
                      Map.of(
                          "accountId",
                          issuanceAccountId.toString(),
                          "source",
                          "account_repository_insert",
                          "sourceVersion",
                          1)),
                  Map.entry(
                      "outboxCheckpoints",
                      Map.of(
                          "issuer", 0,
                          "account", 0,
                          "tenant", 0,
                          "membership", 1)),
                  Map.entry("roleEvidence", roleEvidence)));
      Map<String, Object> evaluation =
          Map.of(
              "operation", operation,
              "claims", claims,
              "bundleRef", bundleRef,
              "snapshotIdentity", sourceHash);
      bundle.put("evaluationIdentity", sha256(canonicalBytes(evaluation)));
      byte[] bundleBytes = canonicalBytes(bundle);

      String fingerprint = publicKeyFingerprint((RSAPublicKey) signingKey.getPublic());
      String publicDataDigest =
          AccountJwtJwksPublicationRepository.publicDataDigest(
              jwksJson, "{\"generation\":\"1\",\"kid\":\"" + KID + "\"}");
      byte[] signerReceipt =
          canonicalBytes(
              Map.ofEntries(
                  Map.entry("schema", "account-control-ui-committed-signer/v1"),
                  Map.entry("environmentId", ENVIRONMENT),
                  Map.entry("clusterId", CLUSTER),
                  Map.entry("namespace", NAMESPACE),
                  Map.entry("clusterIncarnationUid", CLUSTER_UID),
                  Map.entry("namespaceUid", NAMESPACE_UID),
                  Map.entry("trustBindingDigest", trustBindingDigest),
                  Map.entry("trustConfigRevision", "trust-r1"),
                  Map.entry("apiBindingDigest", API_BINDING_DIGEST),
                  Map.entry("apiConfigRevision", "api-r1"),
                  Map.entry("promotionOperationId", PROMOTION_OPERATION_ID.toString()),
                  Map.entry("promotionRequestDigest", PROMOTION_REQUEST_DIGEST),
                  Map.entry("generationOperationId", GENERATION_OPERATION_ID.toString()),
                  Map.entry("signerGeneration", "1"),
                  Map.entry("kid", KID),
                  Map.entry("algorithm", "RS256"),
                  Map.entry("publicKeyFingerprint", fingerprint),
                  Map.entry("privateReceiptDigest", PRIVATE_RECEIPT_DIGEST),
                  Map.entry("privateResourceVersion", "22"),
                  Map.entry("publicReceiptDigest", PUBLIC_RECEIPT_DIGEST),
                  Map.entry("publicResourceVersion", "23"),
                  Map.entry("publicConfigMapUid", CONFIG_MAP_UID),
                  Map.entry("publicDataDigest", publicDataDigest)));

      Map<String, Object> authBundleReference = new LinkedHashMap<>(bundleRef);
      authBundleReference.put("canonicalSha256", sha256(bundleBytes));
      Map<String, Object> activeRegistryRecord =
          new LinkedHashMap<>(
              Map.ofEntries(
                  Map.entry("schemaVersion", 1),
                  Map.entry("registryVersion", 2),
                  Map.entry("tokenHash", tokenHash),
                  Map.entry("kid", KID),
                  Map.entry("signerGeneration", "1"),
                  Map.entry("issuer", ISSUER),
                  Map.entry("profile", "control-ui"),
                  Map.entry("type", "control-ui"),
                  Map.entry("audience", "control-ui"),
                  Map.entry("accountId", issuanceAccountId.toString()),
                  Map.entry("jti", TOKEN_ID.toString()),
                  Map.entry("iat", NOW_SECONDS),
                  Map.entry("nbf", NOW_SECONDS),
                  Map.entry("exp", EXPIRY_SECONDS),
                  Map.entry("tokenGeneration", 1),
                  Map.entry("authorityTuple", claims.get("authorityTuple")),
                  Map.entry("membershipVersion", claims.get("membershipVersion")),
                  Map.entry("issuanceFence", 1),
                  Map.entry("operationId", OPERATION_ID.toString()),
                  Map.entry("requestId", REQUEST_ID.toString()),
                  Map.entry("requestDigest", REQUEST_DIGEST),
                  Map.entry("state", "active"),
                  Map.entry("authoritySourceVersions", sourceVersions),
                  Map.entry("authEvidenceBundle", authBundleReference),
                  Map.entry("originalSignerReceiptDigest", sha256(signerReceipt))));
      byte[] activeRegistry = canonicalBytes(activeRegistryRecord);
      Map<String, Object> pendingRegistryRecord = new LinkedHashMap<>(activeRegistryRecord);
      pendingRegistryRecord.put("state", "pending");
      pendingRegistryRecord.put("registryVersion", 1);
      byte[] pendingRegistry = canonicalBytes(pendingRegistryRecord);
      byte[] pendingReceipt =
          canonicalBytes(
              Map.of(
                  "schema",
                  "account-control-ui-pending-readback/v1",
                  "tokenHash",
                  tokenHash,
                  "recordDigest",
                  sha256(pendingRegistry),
                  "expiryMillis",
                  Math.multiplyExact(EXPIRY_SECONDS, 1000L),
                  "aclIdentity",
                  "account_coord_app",
                  "localAofCount",
                  localAofCount,
                  "replicaAofCount",
                  1));

      var committedIssuance = mock(CommittedIssuance.class);
      when(committedIssuance.requestId()).thenReturn(REQUEST_ID);
      when(committedIssuance.operationId()).thenReturn(OPERATION_ID);
      when(committedIssuance.tokenJti()).thenReturn(TOKEN_ID);
      when(committedIssuance.accountId()).thenReturn(issuanceAccountId);
      when(committedIssuance.tenantId()).thenReturn(TENANT_ID);
      when(committedIssuance.callerWorkload()).thenReturn("logging-admin-service");
      when(committedIssuance.callerContextId()).thenReturn(CALLER_CONTEXT_ID);
      when(committedIssuance.requestDigest()).thenReturn(REQUEST_DIGEST);
      when(committedIssuance.claims()).thenReturn(canonicalBytes(claims));
      when(committedIssuance.source()).thenReturn(sourceBytes);
      when(committedIssuance.bundle()).thenReturn(bundleBytes);
      when(committedIssuance.signerReceipt()).thenReturn(signerReceipt);
      when(committedIssuance.issuedAtEpochSecond()).thenReturn(NOW_SECONDS);
      when(committedIssuance.expiresAtEpochSecond()).thenReturn(EXPIRY_SECONDS);
      when(committedIssuance.expiryMillis()).thenReturn(Math.multiplyExact(EXPIRY_SECONDS, 1000L));
      when(committedIssuance.status()).thenReturn("COMMITTED");
      when(committedIssuance.tokenHash()).thenReturn(tokenHash);
      when(committedIssuance.pendingRegistry()).thenReturn(pendingRegistry);
      when(committedIssuance.activeRegistry()).thenReturn(activeRegistry);
      when(committedIssuance.pendingReceipt()).thenReturn(pendingReceipt);
      when(issuances.readCommitted(tokenHash)).thenReturn(Optional.of(committedIssuance));

      configureSignerReadback(mismatchPublicReceipt ? "0".repeat(64) : publicDataDigest);
      configureRegistry(activeRegistry);
      configureCurrentSources(currentTenantGeneration, currentMembershipGeneration, roleSnapshot);

      PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
      when(transactionManager.getTransaction(any(TransactionDefinition.class)))
          .thenReturn(new SimpleTransactionStatus());
      verifier =
          new AccountControlUiCurrentActorVerifier(
              publicJwks,
              signerStates,
              materializerTrust,
              apiBinding,
              registry,
              issuances,
              sourceEvidence,
              generations,
              tenantEvents,
              memberships,
              membershipPairs,
              roleSnapshots,
              outbox,
              transactionManager,
              Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private final AccountJwtSignerMaterializerTrustBinding materializerTrust =
        mock(AccountJwtSignerMaterializerTrustBinding.class);
    private final AccountJwtJwksApiBinding apiBinding = mock(AccountJwtJwksApiBinding.class);
    private AccountControlUiRegistryReader registry;

    private void configureSignerReadback(String originalPublicDataDigest) throws Exception {
      var original = mock(OriginalCommittedSignerEvidence.class);
      var promotion = mock(PromotionOperationEvidence.class);
      var generation = mock(GenerationResult.class);
      var privateReceipt = mock(PrivatePromotionReceipt.class);
      var publicReceipt = mock(ActiveJwksPromotionReceipt.class);
      when(original.promotion()).thenReturn(promotion);
      when(original.generationResult()).thenReturn(generation);
      when(original.privateReceipt()).thenReturn(privateReceipt);
      when(original.publicReceipt()).thenReturn(publicReceipt);
      when(promotion.status()).thenReturn("COMMITTED");
      when(promotion.operationId()).thenReturn(PROMOTION_OPERATION_ID);
      when(promotion.requestDigest()).thenReturn(PROMOTION_REQUEST_DIGEST);
      when(promotion.generationOperationId()).thenReturn(GENERATION_OPERATION_ID);
      when(generation.operationId()).thenReturn(GENERATION_OPERATION_ID);
      when(generation.targetKid()).thenReturn(KID);
      when(generation.targetGeneration()).thenReturn("1");
      when(generation.targetAlgorithm()).thenReturn("RS256");
      when(generation.publicKeyFingerprint())
          .thenReturn(publicKeyFingerprint((RSAPublicKey) signingKey.getPublic()));
      when(privateReceipt.receiptDigest()).thenReturn(PRIVATE_RECEIPT_DIGEST);
      when(privateReceipt.observedResourceVersion()).thenReturn("22");
      when(publicReceipt.receiptDigest()).thenReturn(PUBLIC_RECEIPT_DIGEST);
      when(publicReceipt.observedResourceVersion()).thenReturn("23");
      when(publicReceipt.configMapUid()).thenReturn(CONFIG_MAP_UID);
      when(publicReceipt.publicDataDigest()).thenReturn(originalPublicDataDigest);
      when(signerStates.readOriginalCommittedSigner(
              any(),
              any(),
              eq(API_BINDING_DIGEST),
              eq("api-r1"),
              eq(PROMOTION_OPERATION_ID),
              eq(GENERATION_OPERATION_ID)))
          .thenReturn(Optional.of(original));
    }

    private void configureRegistry(byte[] activeRegistry) {
      StatefulRedisConnection<byte[], byte[]> connection = mock(StatefulRedisConnection.class);
      ClientOptions options = mock(ClientOptions.class);
      @SuppressWarnings("unchecked")
      RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);
      when(connection.isOpen()).thenReturn(true);
      when(connection.getOptions()).thenReturn(options);
      when(options.isAutoReconnect()).thenReturn(false);
      when(connection.sync()).thenReturn(commands);
      when(commands.aclWhoami()).thenReturn(AccountControlUiRegistryReader.REQUIRED_ACL_IDENTITY);
      when(commands.get(any(byte[].class))).thenReturn(activeRegistry);
      when(commands.pexpiretime(any(byte[].class)))
          .thenReturn(Math.multiplyExact(EXPIRY_SECONDS, 1000L));
      registry = new AccountControlUiRegistryReader(() -> connection);
    }

    private void configureCurrentSources(
        long currentTenantGeneration,
        long currentMembershipGeneration,
        List<String> currentRoleSnapshot) {
      IssuanceFence fence = new IssuanceFence(ACCOUNT_ID, 1L, 1L);
      String issuerStream = "account:auth-authority:v1:issuer/" + ISSUER;
      String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
      CurrentSourceEvidence issuerSource =
          new CurrentSourceEvidence(
              AuthorityScope.issuer(ISSUER),
              1L,
              1L,
              null,
              new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                  issuerStream, 0L, Optional.empty(), Optional.empty()),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              1L,
              null);
      CurrentSourceEvidence accountSource =
          new CurrentSourceEvidence(
              AuthorityScope.account(ACCOUNT_ID),
              1L,
              1L,
              fence,
              new AccountAuthoritySourceEvidenceRepository.SourceCheckpoint(
                  accountStream, 0L, Optional.empty(), Optional.empty()),
              Optional.empty(),
              "ACCOUNT_REPOSITORY_INSERT",
              77L,
              AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name(),
              77L,
              77L);
      IssuerAccountSourceSnapshot sourceSnapshot =
          new IssuerAccountSourceSnapshot(issuerSource, accountSource, fence);
      when(sourceEvidence.readCurrentIssuerAccountSources(ISSUER, ACCOUNT_ID))
          .thenReturn(sourceSnapshot);

      ScopeState issuerState = new ScopeState(AuthorityScope.issuer(ISSUER), 1L, 1L, null);
      ScopeState accountState = new ScopeState(AuthorityScope.account(ACCOUNT_ID), 1L, 1L, fence);
      ScopeState tenantState =
          new ScopeState(
              AuthorityScope.tenant(TENANT_ID),
              currentTenantGeneration,
              currentTenantGeneration,
              null);
      ScopeState membershipState =
          new ScopeState(
              AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
              currentMembershipGeneration,
              currentMembershipGeneration,
              fence);
      CompositeSnapshot composite =
          new CompositeSnapshot(
              issuerState, accountState, List.of(tenantState), List.of(membershipState), fence);
      when(generations.readCompositeSnapshot(
              eq(ISSUER), eq(ACCOUNT_ID), eq(List.of(TENANT_ID)), eq(List.of(TENANT_ID))))
          .thenReturn(composite);

      TenantAuthorityEventV1Codec.Event tenantEvent = mock(TenantAuthorityEventV1Codec.Event.class);
      when(tenantEvent.tenantId()).thenReturn(TENANT_ID);
      when(tenantEvent.tenantAuthorityGeneration()).thenReturn(currentTenantGeneration);
      when(tenantEvent.tenantAuthoritySourceVersion()).thenReturn(currentTenantGeneration);
      when(tenantEvents.readCurrentByTenant(TENANT_ID)).thenReturn(tenantEvent);

      VerifiedTenantProvenance provenance =
          new VerifiedTenantProvenance(
              null,
              TenantProvenanceKind.FRESH_GAME_DESIGN,
              UUID.fromString("a0000000-0000-4000-8000-00000000000a"),
              "sha256:" + "9".repeat(64));
      MembershipEvent membershipEvent =
          MembershipAuthorityEventV1Codec.seal(
              membershipEventPreimage(
                  currentTenantGeneration, currentMembershipGeneration, List.of("tenantAdmin")));
      PairAuthority pair =
          new PairAuthority(
              ACCOUNT_ID,
              TENANT_ID,
              provenance,
              true,
              1L,
              currentMembershipGeneration,
              1L,
              membershipEvent.eventId(),
              membershipEvent.eventDigest(),
              false);
      when(membershipPairs.readPositive(ACCOUNT_ID, TENANT_ID)).thenReturn(Optional.of(pair));

      Account account = new Account();
      account.setId(101L);
      account.setAccountUuid(ACCOUNT_ID);
      AccountTenantMembership membership = new AccountTenantMembership();
      membership.setId(202L);
      membership.setAccount(account);
      membership.setTenantUuid(TENANT_ID);
      membership.setLifecycleState("ACTIVE");
      membership.setMembershipVersion(1L);
      membership.setMembershipAuthorityGeneration(currentMembershipGeneration);
      when(memberships.findFreshMembership(ACCOUNT_ID, TENANT_ID))
          .thenReturn(Optional.of(membership));
      RoleSnapshot roles =
          new RoleSnapshot(
              101L, null, 202L, 1L, currentRoleSnapshot, ACCOUNT_ID, TENANT_ID, provenance);
      when(roleSnapshots.findForCanonicalUpdate(ACCOUNT_ID, TENANT_ID, provenance, 202L, 1L))
          .thenReturn(Optional.of(roles));

      String streamKey =
          MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
              + "membership/"
              + ACCOUNT_ID
              + "/"
              + TENANT_ID;
      AccountAuthorityOutboxRepository.Event membershipOutboxEvent =
          new AccountAuthorityOutboxRepository.Event(
              streamKey,
              membershipEvent.requestId(),
              1L,
              membershipEvent.eventId(),
              membershipEvent.eventDigest(),
              membershipEvent.canonicalJson().getBytes(StandardCharsets.UTF_8));
      when(outbox.findEvent(streamKey, 1L)).thenReturn(Optional.of(membershipOutboxEvent));
    }
  }

  private static Map<String, Object> claims(UUID accountId, List<String> globalRoles) {
    Map<String, Object> authorityTuple =
        new LinkedHashMap<>(
            Map.of(
                "issuerAuthGeneration", 1,
                "accountAuthorityGeneration", 1,
                "tenantAuthorityGeneration", Map.of(TENANT_ID.toString(), 1),
                "membershipAuthorityGeneration", Map.of(TENANT_ID.toString(), 1),
                "privateRealmGrantVersions", List.of()));
    Map<String, Object> claims =
        new LinkedHashMap<>(
            Map.ofEntries(
                Map.entry("iss", ISSUER),
                Map.entry("sub", accountId.toString()),
                Map.entry("jti", TOKEN_ID.toString()),
                Map.entry("accountId", accountId.toString()),
                Map.entry("aud", "control-ui"),
                Map.entry("iat", NOW_SECONDS),
                Map.entry("nbf", NOW_SECONDS),
                Map.entry("exp", EXPIRY_SECONDS),
                Map.entry("tokenGeneration", 1),
                Map.entry("authorityTuple", authorityTuple),
                Map.entry("membershipVersion", Map.of(TENANT_ID.toString(), 1)),
                Map.entry("issuanceFence", 1),
                Map.entry("scopedRoles", Map.of(TENANT_ID.toString(), List.of("tenantAdmin")))));
    if (globalRoles != null) {
      claims.put("globalRoles", globalRoles);
    }
    return claims;
  }

  private static Map<String, Object> membershipEventPreimage(
      long tenantGeneration, long membershipGeneration, List<String> roles) {
    String sourceScope = "membership/" + ACCOUNT_ID + "/" + TENANT_ID;
    String streamKey = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + sourceScope;
    Map<String, Object> tuple =
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration",
                Map.of(TENANT_ID.toString(), Long.toString(tenantGeneration)),
            "membershipAuthorityGeneration",
                Map.of(TENANT_ID.toString(), Long.toString(membershipGeneration)),
            "privateRealmGrantVersions", List.of());
    return Map.ofEntries(
        Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
        Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
        Map.entry("eventId", MEMBERSHIP_EVENT_ID.toString()),
        Map.entry("requestId", MEMBERSHIP_EVENT_ID.toString()),
        Map.entry("outboxStreamKey", streamKey),
        Map.entry("outboxSequence", "1"),
        Map.entry("sourceScope", sourceScope),
        Map.entry("accountId", ACCOUNT_ID.toString()),
        Map.entry("tenantId", TENANT_ID.toString()),
        Map.entry("membershipExists", true),
        Map.entry("membershipLifecycleState", "ACTIVE"),
        Map.entry("membershipVersion", Map.of(TENANT_ID.toString(), "1")),
        Map.entry("membershipAuthorityGeneration", Long.toString(membershipGeneration)),
        Map.entry("authorityTuple", tuple),
        Map.entry("issuanceFence", "1"),
        Map.entry("roles", roles),
        Map.entry("gameplayAdmissionAllowed", true),
        Map.entry("callerBoundAuthorityInvalidated", false));
  }

  private static Map<String, Object> tokenIdentity(Map<String, Object> claims) {
    return Map.of(
        "jti", claims.get("jti"),
        "tokenGeneration", claims.get("tokenGeneration"),
        "iat", claims.get("iat"),
        "nbf", claims.get("nbf"),
        "exp", claims.get("exp"));
  }

  private static Map<String, Object> sourceVersions() {
    return Map.of(
        "ISSUER:" + ISSUER, 1,
        "ACCOUNT:" + ACCOUNT_ID, 1,
        "TENANT:" + TENANT_ID, 1,
        "MEMBERSHIP:" + ACCOUNT_ID + ":" + TENANT_ID, 1);
  }

  private static SourceIdentity sourceIdentity() {
    return new SourceIdentity(
        ENVIRONMENT,
        CLUSTER,
        CLUSTER_UID,
        NAMESPACE,
        NAMESPACE_UID,
        CONFIG_MAP_UID,
        "api-r1",
        "https://kubernetes.test:6443",
        API_CA_DIGEST);
  }

  private static String jwks(RSAPublicKey key) throws Exception {
    String modulus =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(unsigned(key.getModulus().toByteArray()));
    String exponent =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(unsigned(key.getPublicExponent().toByteArray()));
    return JSON.writeValueAsString(
        Map.of(
            "keys",
            List.of(
                Map.of(
                    "kty",
                    "RSA",
                    "use",
                    "sig",
                    "alg",
                    "RS256",
                    "kid",
                    KID,
                    "key_ops",
                    List.of("verify"),
                    "n",
                    modulus,
                    "e",
                    exponent))));
  }

  private static String publicKeyFingerprint(RSAPublicKey key) throws Exception {
    String modulus =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(unsigned(key.getModulus().toByteArray()));
    String exponent =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(unsigned(key.getPublicExponent().toByteArray()));
    byte[] canonical = canonicalBytes(Map.of("e", exponent, "kty", "RSA", "n", modulus));
    return sha256(canonical);
  }

  private static byte[] unsigned(byte[] value) {
    return value.length > 1 && value[0] == 0
        ? java.util.Arrays.copyOfRange(value, 1, value.length)
        : value;
  }

  private static String sign(Map<String, Object> claims, KeyPair keyPair) throws Exception {
    byte[] header = canonicalBytes(Map.of("alg", "RS256", "kid", KID, "typ", "JWT"));
    byte[] payload = canonicalBytes(claims);
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String signingInput = encoder.encodeToString(header) + "." + encoder.encodeToString(payload);
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keyPair.getPrivate());
    signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + encoder.encodeToString(signature.sign());
  }

  private static byte[] canonicalBytes(Object value) throws Exception {
    return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
