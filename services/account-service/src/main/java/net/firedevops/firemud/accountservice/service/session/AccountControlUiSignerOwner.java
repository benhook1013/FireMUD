package net.firedevops.firemud.accountservice.service.session;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Objects;
import net.firedevops.firemud.accountservice.authordraft.AccountControlUiAuthority;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;

/** Unregistered genuine committed-signer owner, separate from non-authorizing readiness probes. */
public final class AccountControlUiSignerOwner {
  private final AccountJwtSignerDesiredStateRepository repository;
  private final AccountJwtSignerMaterializerTrustBinding trust;
  private final AccountJwtJwksApiBinding api;
  private final Path privateRoot;
  private final Path publicRoot;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "Injected signer repository is an internal Account owner transaction collaborator.")
  public AccountControlUiSignerOwner(
      AccountJwtSignerDesiredStateRepository repository,
      AccountJwtSignerMaterializerTrustBinding trust,
      AccountJwtJwksApiBinding api,
      Path privateRoot,
      Path publicRoot) {
    this.repository = Objects.requireNonNull(repository);
    this.trust = Objects.requireNonNull(trust);
    this.api = Objects.requireNonNull(api);
    this.privateRoot = Objects.requireNonNull(privateRoot);
    this.publicRoot = Objects.requireNonNull(publicRoot);
    if (!privateRoot.isAbsolute() || !publicRoot.isAbsolute()) {
      throw new IllegalArgumentException("Protected Account signer roots required");
    }
  }

  /** Reads existing real lifecycle receipts under their existing Account owner transaction. */
  public Capture captureCurrent() {
    return capture(null);
  }

  /** Original retained owner receipt, independently re-read rather than treated as caller proof. */
  public Capture requireOriginal(byte[] originalReceipt) {
    var observed = capture(Objects.requireNonNull(originalReceipt));
    if (!java.util.Arrays.equals(originalReceipt, observed.receipt())) {
      throw unavailable();
    }
    return observed;
  }

  private Capture capture(byte[] originalReceipt) {
    var materializer = trust.current().orElseThrow(AccountControlUiSignerOwner::unavailable);
    var publicApi = api.current();
    if (!materializer.environmentId().equals(publicApi.environmentId())
        || !materializer.clusterId().equals(publicApi.clusterId())
        || !materializer.namespace().equals(publicApi.namespace())
        || !materializer
            .expectedClusterIncarnationUid()
            .equals(publicApi.expectedClusterIncarnationUid())
        || !materializer.expectedNamespaceUid().equals(publicApi.expectedNamespaceUid())) {
      throw unavailable();
    }
    var binding =
        new AccountJwtSignerDesiredStateRepository.Binding(
            materializer.environmentId(),
            materializer.clusterId(),
            materializer.namespace(),
            CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    var fence =
        new TrustFence(
            materializer.expectedClusterIncarnationUid(),
            materializer.expectedNamespaceUid(),
            materializer.bindingDigest(),
            materializer.configRevision());
    AccountJwtSignerDesiredStateRepository.GenerationResult generation;
    AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence promotion;
    AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt privateReceipt;
    AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt publicReceipt;
    if (originalReceipt == null) {
      var evidence =
          repository
              .readCurrentCommittedSigner(
                  binding, fence, publicApi.bindingDigest(), publicApi.configRevision())
              .orElseThrow(AccountControlUiSignerOwner::unavailable);
      generation = evidence.generationResult();
      promotion = evidence.promotion();
      privateReceipt = evidence.privateReceipt();
      publicReceipt = evidence.publicReceipt();
    } else {
      var original = AccountControlUiIssuanceRepository.object(originalReceipt);
      var evidence =
          repository
              .readOriginalCommittedSigner(
                  binding,
                  fence,
                  publicApi.bindingDigest(),
                  publicApi.configRevision(),
                  java.util.UUID.fromString((String) original.get("promotionOperationId")),
                  java.util.UUID.fromString((String) original.get("generationOperationId")))
              .orElseThrow(AccountControlUiSignerOwner::unavailable);
      generation = evidence.generationResult();
      promotion = evidence.promotion();
      privateReceipt = evidence.privateReceipt();
      publicReceipt = evidence.publicReceipt();
    }
    if (!binding.equals(promotion.binding())
        || !binding.equals(generation.binding())
        || !fence.equals(promotion.trustFence())
        || !fence.equals(generation.trustFence())
        || !promotion.targetGeneration().equals(generation.targetGeneration())
        || !promotion.targetKid().equals(generation.targetKid())
        || !"RS256".equals(promotion.targetAlgorithm())
        || !"RS256".equals(generation.targetAlgorithm())
        || !promotion.targetPublicKeyFingerprint().equals(generation.publicKeyFingerprint())) {
      throw unavailable();
    }
    var expected =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            binding.environmentId(),
            binding.clusterId(),
            binding.namespace(),
            generation.operationId().toString(),
            generation.targetGeneration(),
            generation.targetKid(),
            generation.publicKeyFingerprint());
    var receipt = new LinkedHashMap<String, Object>();
    receipt.put("schema", "account-control-ui-committed-signer/v1");
    receipt.put("environmentId", binding.environmentId());
    receipt.put("clusterId", binding.clusterId());
    receipt.put("namespace", binding.namespace());
    receipt.put("clusterIncarnationUid", materializer.expectedClusterIncarnationUid());
    receipt.put("namespaceUid", materializer.expectedNamespaceUid());
    receipt.put("trustBindingDigest", materializer.bindingDigest());
    receipt.put("trustConfigRevision", materializer.configRevision());
    receipt.put("apiBindingDigest", publicApi.bindingDigest());
    receipt.put("apiConfigRevision", publicApi.configRevision());
    receipt.put("promotionOperationId", promotion.operationId().toString());
    receipt.put("promotionRequestDigest", promotion.requestDigest());
    receipt.put("generationOperationId", generation.operationId().toString());
    receipt.put("signerGeneration", generation.targetGeneration());
    receipt.put("kid", generation.targetKid());
    receipt.put("algorithm", generation.targetAlgorithm());
    receipt.put("publicKeyFingerprint", generation.publicKeyFingerprint());
    receipt.put("privateReceiptDigest", privateReceipt.receiptDigest());
    receipt.put("privateResourceVersion", privateReceipt.observedResourceVersion());
    receipt.put("publicReceiptDigest", publicReceipt.receiptDigest());
    receipt.put("publicResourceVersion", publicReceipt.observedResourceVersion());
    receipt.put("publicConfigMapUid", publicReceipt.configMapUid());
    receipt.put("publicDataDigest", publicReceipt.publicDataDigest());
    return new Capture(expected, AccountControlUiAuthority.canonical(receipt));
  }

  String sign(
      Capture original,
      AccountControlUiSigningSpec spec,
      java.util.function.BiConsumer<String, byte[]> candidateConsumer) {
    return AccountMountedJwtSignerBundle.signCommittedControlUiDigest(
        privateRoot,
        Path.of("current.key"),
        publicRoot,
        Path.of("jwks.json"),
        original.expected,
        spec,
        candidateConsumer);
  }

  public static final class Capture {
    private final AccountMountedJwtSignerBundle.ExpectedIdentity expected;
    private final byte[] receipt;

    private Capture(AccountMountedJwtSignerBundle.ExpectedIdentity expected, byte[] receipt) {
      this.expected = expected;
      this.receipt = receipt.clone();
    }

    public byte[] receipt() {
      return receipt.clone();
    }

    public String kid() {
      return expected.kid();
    }

    public String generation() {
      return expected.generation();
    }

    @Override
    public String toString() {
      return "AccountControlUiSignerOwner.Capture[redacted]";
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Existing committed control-ui signer unavailable");
  }
}
