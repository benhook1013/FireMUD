package net.firedevops.firemud.accountservice.service.session;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountJwtPodReceiverIdentity;
import net.firedevops.firemud.account.v1.AccountSourceIdentity;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding.ProfileExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessProbeOwnerSelector.LocalIdentity;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.InventorySnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ObservationContext;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.PodObservation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtValidatorInventorySource.ValidatorObservation;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

/**
 * Derives the local Account Pod identity only from protected live inventory, the active server
 * certificate, and Account's protected JWKS source. No receiver-request identity is accepted.
 */
public final class AccountJwtReadinessPodLocalIdentityProvider {
  private static final String ACCOUNT_VALIDATOR_ID = AccountJwtReadinessProbeCrypto.VALIDATOR_ID;
  private static final String TLS_BUNDLE_NAME = "firemud-grpc";

  private final AccountJwtValidatorInventorySource inventorySource;
  private final AccountJwtJwksTrustedSource trustedJwksSource;
  private final SslBundles sslBundles;
  private final Clock clock;
  private final String localPodName;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring-managed SslBundles is retained for live server-certificate reads.")
  public AccountJwtReadinessPodLocalIdentityProvider(
      AccountJwtValidatorInventorySource inventorySource,
      AccountJwtJwksTrustedSource trustedJwksSource,
      SslBundles sslBundles,
      Clock clock,
      String localPodName) {
    this.inventorySource = Objects.requireNonNull(inventorySource);
    this.trustedJwksSource = Objects.requireNonNull(trustedJwksSource);
    this.sslBundles = Objects.requireNonNull(sslBundles);
    this.clock = Objects.requireNonNull(clock);
    this.localPodName = localPodName == null ? "" : localPodName.trim();
  }

  /** Re-reads every protected identity source and requires the actual local TLS leaf to match. */
  public LocalObservation observe(String applicabilityMatrixDigest) {
    return observe(applicabilityMatrixDigest, null);
  }

  /**
   * Re-reads protected identity using only the current Account-owner-selected operation context.
   */
  public LocalObservation observe(
      String applicabilityMatrixDigest, ObservationContext observationContext) {
    try {
      InventorySnapshot inventory =
          observationContext == null
              ? inventorySource.observe()
              : inventorySource.observe(observationContext);
      ValidatorObservation validator = exactAccountValidator(inventory);
      PodObservation pod = exactLocalPod(validator, inventory.namespace());
      SourceIdentity jwksSourceIdentity = readCurrentJwksSourceIdentity();
      requireSameAccountCluster(inventory, jwksSourceIdentity);
      String actualLeafSpki = localServerLeafSpki(sslBundles, clock, pod.receiverServiceUri());
      if (!actualLeafSpki.equals(pod.leafSpkiSha256())) {
        throw new IdentityUnavailableException();
      }

      LocalIdentity selectorIdentity =
          new LocalIdentity(
              validator.validatorId(),
              validator.deploymentUid(),
              pod.uid(),
              pod.podIp(),
              pod.endpoint().toString(),
              pod.receiverServiceUri(),
              pod.image(),
              pod.verifierConfigSha256(),
              applicabilityMatrixDigest,
              inventory.inventoryBindingRevision(),
              inventory.digest(),
              actualLeafSpki);
      AccountJwtPodReceiverIdentity wireIdentity =
          AccountJwtPodReceiverIdentity.newBuilder()
              .setValidatorId(validator.validatorId())
              .setDeploymentUid(validator.deploymentUid())
              .setPodUid(pod.uid())
              .setPodIp(pod.podIp())
              .setDirectPodEndpoint(pod.endpoint().toString())
              .setCanonicalServiceUri(pod.receiverServiceUri())
              .setImage(pod.image())
              .setVerifierConfigSha256(pod.verifierConfigSha256())
              .setApplicabilityMatrixDigest(applicabilityMatrixDigest)
              .setSourceInventoryRevision(inventory.inventoryBindingRevision())
              .setSourceInventoryDigest(inventory.digest())
              .setServerLeafSpkiSha256(actualLeafSpki)
              .setAccountJwksSourceIdentity(toProto(jwksSourceIdentity))
              .setJwksTrustBindingRevision(jwksSourceIdentity.bindingRevision())
              .build();
      return new LocalObservation(selectorIdentity, wireIdentity);
    } catch (IdentityUnavailableException failure) {
      throw failure;
    } catch (RuntimeException unavailable) {
      throw new IdentityUnavailableException();
    }
  }

  private SourceIdentity readCurrentJwksSourceIdentity() {
    PublicJwksSnapshot snapshot = trustedJwksSource.load();
    if (snapshot == null) {
      throw new IdentityUnavailableException();
    }
    byte[] bytes = snapshot.jwksBytes();
    if (bytes == null) {
      throw new IdentityUnavailableException();
    }
    try {
      if (bytes.length == 0 || bytes.length > 256 * 1024) {
        throw new IdentityUnavailableException();
      }
      return snapshot.sourceIdentity();
    } finally {
      java.util.Arrays.fill(bytes, (byte) 0);
    }
  }

  private ValidatorObservation exactAccountValidator(InventorySnapshot inventory) {
    List<ValidatorObservation> matches =
        inventory.validators().stream()
            .filter(value -> ACCOUNT_VALIDATOR_ID.equals(value.validatorId()))
            .toList();
    if (matches.size() != 1) {
      throw new IdentityUnavailableException();
    }
    ValidatorObservation validator = matches.getFirst();
    Set<ProfileExpectation> expectedProfiles =
        AccountJwtReadinessProbeCrypto.representativeProfiles().values().stream()
            .map(value -> new ProfileExpectation(value.profile(), value.audience()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (!Set.copyOf(validator.profiles()).equals(expectedProfiles)) {
      throw new IdentityUnavailableException();
    }
    return validator;
  }

  private PodObservation exactLocalPod(ValidatorObservation validator, String namespace) {
    if (localPodName.isBlank()) {
      throw new IdentityUnavailableException();
    }
    List<PodObservation> matches =
        validator.pods().stream().filter(value -> localPodName.equals(value.name())).toList();
    if (matches.size() != 1) {
      throw new IdentityUnavailableException();
    }
    PodObservation pod = matches.getFirst();
    if (!validator.deploymentUid().equals(pod.ownerUid())
        || !validator.deploymentName().equals(pod.ownerName())
        || !validator.image().equals(pod.image())
        || !validator.verifierConfigSha256().equals(pod.verifierConfigSha256())) {
      throw new IdentityUnavailableException();
    }
    validateServiceIdentity(pod.receiverServiceUri(), namespace, validator);
    return pod;
  }

  private static void validateServiceIdentity(
      String uri, String namespace, ValidatorObservation validator) {
    if (uri == null
        || !uri.equals("spiffe://firemud/ns/" + namespace + "/sa/" + ACCOUNT_VALIDATOR_ID)
        || !uri.matches(
            "spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/account-service")
        || !validator.validatorId().equals(ACCOUNT_VALIDATOR_ID)) {
      throw new IdentityUnavailableException();
    }
  }

  private static void requireSameAccountCluster(
      InventorySnapshot inventory, SourceIdentity source) {
    if (!inventory.environmentId().equals(source.environmentId())
        || !inventory.clusterId().equals(source.clusterId())
        || !inventory.clusterIncarnationUid().equals(source.clusterIncarnationUid())
        || !inventory.namespace().equals(source.namespace())
        || !inventory.namespaceUid().equals(source.namespaceUid())) {
      throw new IdentityUnavailableException();
    }
  }

  private static String localServerLeafSpki(
      SslBundles sslBundles, Clock clock, String expectedServiceUri) {
    try {
      SslBundle bundle = Objects.requireNonNull(sslBundles.getBundle(TLS_BUNDLE_NAME));
      KeyStore keyStore = Objects.requireNonNull(bundle.getStores().getKeyStore());
      List<X509Certificate> leaves = new ArrayList<>();
      var aliases = keyStore.aliases();
      while (aliases.hasMoreElements()) {
        String alias = aliases.nextElement();
        if (!keyStore.isKeyEntry(alias)) {
          continue;
        }
        Certificate certificate = keyStore.getCertificate(alias);
        if (!(certificate instanceof X509Certificate x509)) {
          throw new IdentityUnavailableException();
        }
        leaves.add(x509);
      }
      if (leaves.size() != 1) {
        throw new IdentityUnavailableException();
      }
      X509Certificate leaf = leaves.getFirst();
      leaf.checkValidity(java.util.Date.from(clock.instant()));
      GrpcPeerIdentity peer =
          GrpcPeerIdentity.fromCertificate(leaf).orElseThrow(IdentityUnavailableException::new);
      if (!expectedServiceUri.equals(peer.uri())) {
        throw new IdentityUnavailableException();
      }
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getPublicKey().getEncoded()));
    } catch (IdentityUnavailableException failure) {
      throw failure;
    } catch (Exception unavailable) {
      throw new IdentityUnavailableException();
    }
  }

  private static AccountSourceIdentity toProto(SourceIdentity source) {
    return AccountSourceIdentity.newBuilder()
        .setEnvironmentId(source.environmentId())
        .setClusterId(source.clusterId())
        .setClusterIncarnationUid(source.clusterIncarnationUid())
        .setNamespace(source.namespace())
        .setNamespaceUid(source.namespaceUid())
        .setConfigMapUid(source.configMapUid())
        .setBindingRevision(source.bindingRevision())
        .setApiServerOrigin(source.apiServerOrigin())
        .setServingCaSha256(source.servingCaSha256())
        .build();
  }

  public record LocalObservation(
      LocalIdentity selectorIdentity, AccountJwtPodReceiverIdentity wireIdentity) {
    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "AccountJwtPodReceiverIdentity is an immutable generated protobuf value.")
    public LocalObservation {
      Objects.requireNonNull(selectorIdentity);
      Objects.requireNonNull(wireIdentity);
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "AccountJwtPodReceiverIdentity is an immutable generated protobuf value.")
    public AccountJwtPodReceiverIdentity wireIdentity() {
      return wireIdentity;
    }
  }

  public static final class IdentityUnavailableException extends RuntimeException {
    public IdentityUnavailableException() {
      super("Protected Account Pod receiver identity is unavailable");
    }
  }
}
