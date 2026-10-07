package net.firedevops.firemud.accountservice.service.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.aopalliance.intercept.MethodInterceptor;
import org.jooq.DSLContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration-source-set-only signer harness. Cryptographic signing and verification are real;
 * protected Kubernetes/API trust and committed materializer evidence are deliberately mocked.
 */
public final class AccountGameplayDelegationCommitSignerFixture {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(AccountGameplayDelegationCommitSignerFixture.class);

  private enum ProofStage {
    BEFORE_SIGNING,
    SIGNING_PENDING_CANDIDATE,
    SIGNED_PENDING_CANDIDATE
  }

  private final AtomicReference<ProofStage> proofStage =
      new AtomicReference<>(ProofStage.BEFORE_SIGNING);
  private static final String ENVIRONMENT = "staging";
  private static final String CLUSTER = "cluster-a";
  private static final String NAMESPACE = "firemud";
  private static final String MATERIALIZER_REVISION = "materializer-r1";
  private static final String API_REVISION = "api-revision-1";
  private static final String MATERIALIZER_DIGEST = "b".repeat(64);
  private static final String API_DIGEST = "c".repeat(64);
  private static final String SERVING_CA_DIGEST = "e".repeat(64);
  private static final String CLUSTER_UID = "44444444-4444-4444-8444-444444444444";
  private static final String NAMESPACE_UID = "55555555-5555-4555-8555-555555555555";
  private static final String CONFIG_MAP_UID = "66666666-6666-4666-8666-666666666666";
  private static final String API_ORIGIN = "https://kubernetes.example.test:6443";
  private static final UUID GENERATION_OPERATION_ID =
      UUID.fromString("7af097ea-b1d1-42ea-9f24-46aeb211a779");
  private static final UUID PROMOTION_OPERATION_ID =
      UUID.fromString("8af097ea-b1d1-42ea-9f24-46aeb211a779");
  private static final UUID ROTATED_GENERATION_OPERATION_ID =
      UUID.fromString("9af097ea-b1d1-42ea-9f24-46aeb211a779");
  private static final UUID ROTATED_PROMOTION_OPERATION_ID =
      UUID.fromString("aaf097ea-b1d1-42ea-9f24-46aeb211a779");
  private static final String GENERATION = "42";
  private static final String KID = "account-key-42";
  private static final String ROTATED_GENERATION = "43";
  private static final String ROTATED_KID = "account-key-43";

  private final AccountGameplayDelegationSigner signer;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrust;
  private final AccountJwtSignerDesiredStateRepository.Binding signerBinding;
  private final TrustFence trustFence;
  private final AccountJwtJwksTrustedSource trustedJwksSource;
  private final SourceIdentity sourceIdentity;
  private final Path privateBundlePath;
  private final Path publicJwksPath;
  private final RSAPublicKey originalPublicKey;
  private final AtomicReference<KeyPair> currentKeyPair;
  private final AtomicReference<String> currentGeneration = new AtomicReference<>(GENERATION);
  private final AtomicReference<String> currentKid = new AtomicReference<>(KID);
  private final AtomicReference<CommittedSignerEvidence> currentOwnerEvidence =
      new AtomicReference<>();
  private final AtomicReference<String> trustedJwksJson = new AtomicReference<>();

  private AccountGameplayDelegationCommitSignerFixture(
      Path temporaryDirectory,
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayDelegationResponseEnvelopeService envelopeService,
      PlatformTransactionManager transactionManager,
      Clock clock)
      throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    KeyPair pair = generator.generateKeyPair();
    originalPublicKey = (RSAPublicKey) pair.getPublic();
    currentKeyPair = new AtomicReference<>(pair);
    String fingerprint = fingerprint(pair);
    sourceIdentity = sourceIdentity();
    signerBinding =
        new AccountJwtSignerDesiredStateRepository.Binding(
            ENVIRONMENT, CLUSTER, NAMESPACE, CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    trustFence =
        new TrustFence(CLUSTER_UID, NAMESPACE_UID, MATERIALIZER_DIGEST, MATERIALIZER_REVISION);
    AccountMountedJwtSignerBundle.ExpectedIdentity expectedIdentity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            ENVIRONMENT,
            CLUSTER,
            NAMESPACE,
            GENERATION_OPERATION_ID.toString(),
            GENERATION,
            KID,
            fingerprint);

    Path privateRoot =
        Files.createDirectory(temporaryDirectory.resolve("private-" + UUID.randomUUID()));
    Path publicRoot =
        Files.createDirectory(temporaryDirectory.resolve("public-" + UUID.randomUUID()));
    privateBundlePath = privateRoot.resolve("current.key");
    publicJwksPath = publicRoot.resolve("jwks.json");
    Files.writeString(privateBundlePath, privateBundleJson(expectedIdentity, pair));
    String originalJwks = jwksJson(Map.of(KID, originalPublicKey));
    Files.writeString(publicJwksPath, originalJwks);
    trustedJwksJson.set(originalJwks);

    materializerTrust = mock(AccountJwtSignerMaterializerTrustBinding.class);
    AccountJwtSignerMaterializerTrustBinding.Binding materializer =
        mock(AccountJwtSignerMaterializerTrustBinding.Binding.class);
    when(materializer.environmentId()).thenReturn(ENVIRONMENT);
    when(materializer.clusterId()).thenReturn(CLUSTER);
    when(materializer.namespace()).thenReturn(NAMESPACE);
    when(materializer.expectedClusterIncarnationUid()).thenReturn(CLUSTER_UID);
    when(materializer.expectedNamespaceUid()).thenReturn(NAMESPACE_UID);
    when(materializer.bindingDigest()).thenReturn(MATERIALIZER_DIGEST);
    when(materializer.configRevision()).thenReturn(MATERIALIZER_REVISION);
    when(materializerTrust.current()).thenReturn(Optional.of(materializer));

    AccountJwtJwksApiBinding apiBinding = mock(AccountJwtJwksApiBinding.class);
    AccountJwtJwksApiBinding.ParsedBinding api = mock(AccountJwtJwksApiBinding.ParsedBinding.class);
    when(api.environmentId()).thenReturn(ENVIRONMENT);
    when(api.clusterId()).thenReturn(CLUSTER);
    when(api.namespace()).thenReturn(NAMESPACE);
    when(api.expectedClusterIncarnationUid()).thenReturn(CLUSTER_UID);
    when(api.expectedNamespaceUid()).thenReturn(NAMESPACE_UID);
    when(api.bindingDigest()).thenReturn(API_DIGEST);
    when(api.configRevision()).thenReturn(API_REVISION);
    when(api.apiServer()).thenReturn(java.net.URI.create(API_ORIGIN));
    when(api.servingCaSha256()).thenReturn(SERVING_CA_DIGEST);
    when(apiBinding.current()).thenReturn(api);

    trustedJwksSource = mock(AccountJwtJwksTrustedSource.class);
    when(trustedJwksSource.sourceIdentity()).thenReturn(sourceIdentity);
    when(trustedJwksSource.load())
        .thenAnswer(
            invocation ->
                new PublicJwksSnapshot(
                    sourceIdentity, trustedJwksJson.get().getBytes(StandardCharsets.UTF_8)));

    AccountJwtSignerDesiredStateRepository desiredState =
        mock(AccountJwtSignerDesiredStateRepository.class);
    currentOwnerEvidence.set(
        ownerEvidence(
            signerBinding,
            trustFence,
            fingerprint,
            GENERATION,
            KID,
            GENERATION_OPERATION_ID,
            PROMOTION_OPERATION_ID,
            Optional.empty(),
            Optional.empty(),
            6L,
            "12",
            "13",
            "20",
            "21"));
    when(desiredState.readCurrentCommittedSigner(
            any(AccountJwtSignerDesiredStateRepository.Binding.class),
            any(TrustFence.class),
            eq(API_DIGEST),
            eq(API_REVISION)))
        .thenAnswer(invocation -> Optional.of(currentOwnerEvidence.get()));

    signer =
        spy(
            new AccountGameplayDelegationSigner(
                issuance,
                desiredState,
                materializerTrust,
                apiBinding,
                trustedJwksSource,
                envelopeService,
                transactionManager,
                clock,
                privateBundlePath.getParent(),
                privateBundlePath.getFileName(),
                publicJwksPath.getParent(),
                publicJwksPath.getFileName()));
    doAnswer(
            invocation -> {
              proofStage.set(ProofStage.SIGNING_PENDING_CANDIDATE);
              try {
                Object result = invocation.callRealMethod();
                proofStage.set(ProofStage.SIGNED_PENDING_CANDIDATE);
                return result;
              } catch (RuntimeException failure) {
                reportSafeFailure(failure);
                throw failure;
              }
            })
        .when(signer)
        .signPendingCandidate(any(UUID.class));
  }

  /** Observes failures only; does not retain SQL, exception text, or throwable objects. */
  public void observeDatabaseFailures(DSLContext dsl) {
    dsl.configuration()
        .setAppending(
            new DefaultExecuteListenerProvider(
                ExecuteListener.onException(context -> reportSafeFailure(context.sqlException()))));
  }

  /** Applies the pending-read transaction boundary to manually assembled repository fixtures. */
  public static AccountGameplayDelegationIssuanceRepository transactionalPendingRegistryReads(
      AccountGameplayDelegationIssuanceRepository issuance,
      PlatformTransactionManager transactionManager) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    ProxyFactory proxy = new ProxyFactory(issuance);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        (MethodInterceptor)
            invocation -> {
              if (!invocation.getMethod().getName().equals("readPendingRegistryCandidate")) {
                return invocation.proceed();
              }
              UUID requestId = (UUID) invocation.getArguments()[0];
              return transaction.execute(
                  status -> issuance.readPendingRegistryCandidate(requestId));
            });
    return (AccountGameplayDelegationIssuanceRepository) proxy.getProxy();
  }

  private void reportSafeFailure(Throwable failure) {
    String sqlState = "UNKNOWN";
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof SQLException sqlFailure) {
        String candidate = sqlFailure.getSQLState();
        if (candidate != null && candidate.matches("[0-9A-Z]{5}")) {
          sqlState = candidate;
          break;
        }
      }
      current = current.getCause();
    }
    LOGGER.warn(
        "Account integration proof failure: stage={};sqlstate={}",
        proofStage.get().name(),
        sqlState);
  }

  public static AccountGameplayDelegationCommitSignerFixture create(
      Path temporaryDirectory,
      AccountGameplayDelegationIssuanceRepository issuance,
      AccountGameplayDelegationResponseEnvelopeService envelopeService,
      PlatformTransactionManager transactionManager,
      Clock clock)
      throws Exception {
    return new AccountGameplayDelegationCommitSignerFixture(
        temporaryDirectory, issuance, envelopeService, transactionManager, clock);
  }

  public AccountGameplayDelegationSigner signer() {
    return signer;
  }

  /** Prepares the real signer-produced pending candidate without exposing its compact JWT. */
  public void preparePendingCandidate(UUID requestId) {
    signer.signPendingCandidate(requestId);
  }

  /** Advances mocked protected signer evidence while retaining the original public key in JWKS. */
  public void rotateToNextGenerationRetainingOriginalKey() throws Exception {
    if (!GENERATION.equals(currentGeneration.get())) {
      throw new IllegalStateException("The integration signer fixture has already rotated");
    }
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    KeyPair rotatedPair = generator.generateKeyPair();
    String rotatedFingerprint = fingerprint(rotatedPair);
    var expectedIdentity =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            ENVIRONMENT,
            CLUSTER,
            NAMESPACE,
            ROTATED_GENERATION_OPERATION_ID.toString(),
            ROTATED_GENERATION,
            ROTATED_KID,
            rotatedFingerprint);
    LinkedHashMap<String, RSAPublicKey> retainedKeys = new LinkedHashMap<>();
    retainedKeys.put(KID, originalPublicKey);
    retainedKeys.put(ROTATED_KID, (RSAPublicKey) rotatedPair.getPublic());
    String retainedJwks = jwksJson(retainedKeys);

    Files.writeString(privateBundlePath, privateBundleJson(expectedIdentity, rotatedPair));
    Files.writeString(publicJwksPath, retainedJwks);
    trustedJwksJson.set(retainedJwks);
    currentOwnerEvidence.set(
        ownerEvidence(
            signerBinding,
            trustFence,
            rotatedFingerprint,
            ROTATED_GENERATION,
            ROTATED_KID,
            ROTATED_GENERATION_OPERATION_ID,
            ROTATED_PROMOTION_OPERATION_ID,
            Optional.of(new ActiveSigner(GENERATION, KID)),
            Optional.of(fingerprint(originalPublicKey)),
            7L,
            "13",
            "14",
            "21",
            "22"));
    currentKeyPair.set(rotatedPair);
    currentKid.set(ROTATED_KID);
    currentGeneration.set(ROTATED_GENERATION);
  }

  /**
   * Removes only the original key from the mocked current JWKS to exercise fail-closed recovery.
   */
  public void withdrawOriginalKeyFromCurrentJwks() {
    if (!ROTATED_GENERATION.equals(currentGeneration.get())) {
      throw new IllegalStateException("Historical-key withdrawal requires the rotated fixture");
    }
    trustedJwksJson.set(
        jwksJson(Map.of(currentKid.get(), (RSAPublicKey) currentKeyPair.get().getPublic())));
  }

  /** Restores the exact original public key in the mocked current JWKS after a negative case. */
  public void restoreOriginalKeyInCurrentJwks() {
    if (!ROTATED_GENERATION.equals(currentGeneration.get())) {
      throw new IllegalStateException("Historical-key restoration requires the rotated fixture");
    }
    LinkedHashMap<String, RSAPublicKey> retainedKeys = new LinkedHashMap<>();
    retainedKeys.put(KID, originalPublicKey);
    retainedKeys.put(currentKid.get(), (RSAPublicKey) currentKeyPair.get().getPublic());
    trustedJwksJson.set(jwksJson(retainedKeys));
  }

  public String currentGeneration() {
    return currentGeneration.get();
  }

  public String currentKid() {
    return currentKid.get();
  }

  public void withdrawTrust() {
    when(materializerTrust.current()).thenReturn(Optional.empty());
  }

  private static CommittedSignerEvidence ownerEvidence(
      AccountJwtSignerDesiredStateRepository.Binding binding,
      TrustFence trust,
      String fingerprint,
      String generationValue,
      String kid,
      UUID generationOperationId,
      UUID promotionOperationId,
      Optional<ActiveSigner> previousActive,
      Optional<String> previousFingerprint,
      long prePromotionStateVersion,
      String expectedSecretResourceVersion,
      String observedSecretResourceVersion,
      String expectedPublicResourceVersion,
      String observedPublicResourceVersion) {
    ActiveSigner active = new ActiveSigner(generationValue, kid);
    boolean initialGeneration = GENERATION.equals(generationValue);
    String operationDigest = initialGeneration ? "2".repeat(64) : "3".repeat(64);
    String generationRequestDigest = initialGeneration ? "3".repeat(64) : "4".repeat(64);
    String promotionRequestDigest = initialGeneration ? "5".repeat(64) : "6".repeat(64);
    String generationReceiptDigest = initialGeneration ? "4".repeat(64) : "5".repeat(64);
    String prepublicationIntentDigest = initialGeneration ? "6".repeat(64) : "7".repeat(64);
    String prepublicationReceiptDigest = initialGeneration ? "7".repeat(64) : "8".repeat(64);
    String mountedObservationDigest = initialGeneration ? "8".repeat(64) : "9".repeat(64);
    String readinessPlanDigest = initialGeneration ? "9".repeat(64) : "a".repeat(64);
    String readinessEvidenceDigest = initialGeneration ? "a".repeat(64) : "b".repeat(64);
    String privateReceiptDigest = initialGeneration ? "b".repeat(64) : "c".repeat(64);
    String publicDataDigest = initialGeneration ? "c".repeat(64) : "d".repeat(64);
    String publicReceiptDigest = initialGeneration ? "d".repeat(64) : "e".repeat(64);
    DesiredState desired =
        new DesiredState(
            binding,
            Math.addExact(prePromotionStateVersion, 1L),
            Optional.of(active),
            Optional.of(active),
            Optional.of(generationOperationId),
            Optional.empty(),
            Optional.empty());

    GenerationResult generation = mock(GenerationResult.class);
    when(generation.operationId()).thenReturn(generationOperationId);
    when(generation.binding()).thenReturn(binding);
    when(generation.trustFence()).thenReturn(trust);
    when(generation.operationDigest()).thenReturn(operationDigest);
    when(generation.generationRequestDigest()).thenReturn(generationRequestDigest);
    when(generation.desiredStateVersion()).thenReturn(prePromotionStateVersion);
    when(generation.privateSecretName())
        .thenReturn(AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME);
    when(generation.secretUid()).thenReturn(CONFIG_MAP_UID);
    when(generation.expectedPriorResourceVersion()).thenReturn(expectedSecretResourceVersion);
    when(generation.observedResourceVersion()).thenReturn(observedSecretResourceVersion);
    when(generation.targetGeneration()).thenReturn(generationValue);
    when(generation.targetKid()).thenReturn(kid);
    when(generation.targetAlgorithm()).thenReturn("RS256");
    when(generation.publicKeyFingerprint()).thenReturn(fingerprint);
    when(generation.receiptDigest()).thenReturn(generationReceiptDigest);

    PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
    when(promotion.operationId()).thenReturn(promotionOperationId);
    when(promotion.generationOperationId()).thenReturn(generationOperationId);
    when(promotion.binding()).thenReturn(binding);
    when(promotion.trustFence()).thenReturn(trust);
    when(promotion.targetGeneration()).thenReturn(generationValue);
    when(promotion.targetKid()).thenReturn(kid);
    when(promotion.targetAlgorithm()).thenReturn("RS256");
    when(promotion.targetPublicKeyFingerprint()).thenReturn(fingerprint);
    when(promotion.requestDigest()).thenReturn(promotionRequestDigest);
    when(promotion.expectedRecordVersion()).thenReturn(prePromotionStateVersion);
    when(promotion.expectedPreviousActive()).thenReturn(previousActive);
    when(promotion.expectedPreviousPublicKeyFingerprint()).thenReturn(previousFingerprint);
    when(promotion.expectedPublishedActive()).thenReturn(Optional.of(active));
    when(promotion.generationOperationDigest()).thenReturn(operationDigest);
    when(promotion.generationReceiptDigest()).thenReturn(generationReceiptDigest);
    when(promotion.secretUid()).thenReturn(CONFIG_MAP_UID);
    when(promotion.expectedPrivateResourceVersion()).thenReturn(expectedSecretResourceVersion);
    when(promotion.apiBindingDigest()).thenReturn(API_DIGEST);
    when(promotion.apiConfigRevision()).thenReturn(API_REVISION);
    when(promotion.publicConfigMapUid()).thenReturn(CONFIG_MAP_UID);
    when(promotion.expectedPublicResourceVersion()).thenReturn(expectedPublicResourceVersion);
    when(promotion.prepublicationIntentDigest()).thenReturn(prepublicationIntentDigest);
    when(promotion.prepublicationReceiptDigest()).thenReturn(prepublicationReceiptDigest);
    when(promotion.mountedObservationDigest()).thenReturn(mountedObservationDigest);
    when(promotion.readinessPlanDigest()).thenReturn(readinessPlanDigest);
    when(promotion.readinessEvidenceDigest()).thenReturn(readinessEvidenceDigest);
    when(promotion.status()).thenReturn("COMMITTED");
    when(promotion.privatePromotionDispatched()).thenReturn(true);
    when(promotion.privatePromotionObservedResourceVersion())
        .thenReturn(Optional.of(observedSecretResourceVersion));
    when(promotion.privatePromotionReceiptDigest()).thenReturn(Optional.of(privateReceiptDigest));
    when(promotion.activeJwksObservedResourceVersion())
        .thenReturn(Optional.of(observedPublicResourceVersion));
    when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.of(publicDataDigest));
    when(promotion.activeJwksReceiptDigest()).thenReturn(Optional.of(publicReceiptDigest));

    return new CommittedSignerEvidence(
        promotion,
        generation,
        desired,
        new PrivatePromotionReceipt(
            promotionOperationId,
            generationOperationId,
            observedSecretResourceVersion,
            privateReceiptDigest),
        new ActiveJwksPromotionReceipt(
            promotionOperationId,
            CONFIG_MAP_UID,
            expectedPublicResourceVersion,
            observedPublicResourceVersion,
            publicDataDigest,
            publicReceiptDigest));
  }

  private static SourceIdentity sourceIdentity() {
    return new SourceIdentity(
        ENVIRONMENT,
        CLUSTER,
        CLUSTER_UID,
        NAMESPACE,
        NAMESPACE_UID,
        CONFIG_MAP_UID,
        API_REVISION,
        API_ORIGIN,
        SERVING_CA_DIGEST);
  }

  private static String privateBundleJson(
      AccountMountedJwtSignerBundle.ExpectedIdentity identity, KeyPair pair) {
    return "{"
        + "\"version\":1,"
        + "\"environmentId\":\""
        + identity.environmentId()
        + "\","
        + "\"clusterId\":\""
        + identity.clusterId()
        + "\","
        + "\"namespace\":\""
        + identity.namespace()
        + "\","
        + "\"operationId\":\""
        + identity.operationId()
        + "\","
        + "\"generation\":\""
        + identity.generation()
        + "\","
        + "\"kid\":\""
        + identity.kid()
        + "\","
        + "\"algorithm\":\"RS256\","
        + "\"privateKeyPkcs8\":\""
        + base64Url(((RSAPrivateCrtKey) pair.getPrivate()).getEncoded())
        + "\",\"publicKeyFingerprint\":\""
        + identity.publicKeyFingerprint()
        + "\"}";
  }

  private static String jwksJson(Map<String, RSAPublicKey> keys) {
    StringBuilder json = new StringBuilder("{\"keys\":[");
    boolean first = true;
    for (Map.Entry<String, RSAPublicKey> entry : keys.entrySet()) {
      if (!first) json.append(',');
      first = false;
      RSAPublicKey key = entry.getValue();
      json.append("{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"")
          .append(entry.getKey())
          .append("\",\"key_ops\":[\"verify\"],\"n\":\"")
          .append(base64Url(unsigned(key.getModulus())))
          .append("\",\"e\":\"")
          .append(base64Url(unsigned(key.getPublicExponent())))
          .append("\"}");
    }
    return json.append("]}").toString();
  }

  private static String fingerprint(KeyPair pair) {
    return fingerprint((RSAPublicKey) pair.getPublic());
  }

  private static String fingerprint(RSAPublicKey key) {
    String material =
        "{\"e\":\""
            + base64Url(unsigned(key.getPublicExponent()))
            + "\",\"kty\":\"RSA\",\"n\":\""
            + base64Url(unsigned(key.getModulus()))
            + "\"}";
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(material.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException("Test signer fingerprint is unavailable");
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] encoded = value.toByteArray();
    return encoded.length > 1 && encoded[0] == 0
        ? Arrays.copyOfRange(encoded, 1, encoded.length)
        : encoded;
  }

  private static String base64Url(byte[] value) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    } finally {
      Arrays.fill(value, (byte) 0);
    }
  }
}
