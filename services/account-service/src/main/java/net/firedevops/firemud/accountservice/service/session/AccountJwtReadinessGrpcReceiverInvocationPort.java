package net.firedevops.firemud.accountservice.service.session;

import io.grpc.Attributes;
import io.grpc.ClientTransportFilter;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import net.firedevops.firemud.account.v1.AccountJwtPodReceiverIdentity;
import net.firedevops.firemud.account.v1.AccountJwtReadinessPodReceiverServiceGrpc;
import net.firedevops.firemud.account.v1.AccountPodTargetBinding;
import net.firedevops.firemud.account.v1.ReadinessProbeCoordinates;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeRequest;
import net.firedevops.firemud.account.v1.ReceiveAccountJwtReadinessProbeResponse;
import net.firedevops.firemud.accountservice.config.AccountJwtValidatorInventoryBinding;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.AuthenticatedAcceptance;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.Invocation;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.PodTarget;
import net.firedevops.firemud.accountservice.service.session.AccountJwtReadinessReceiverInvocationPort.ProbeExpectation;
import net.firedevops.firemud.accountservice.service.session.AccountMountedJwtSignerBundle.ProbeKind;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.gamesession.v1.GameSessionJwtReadinessReceiverServiceGrpc;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeRequest;
import net.firedevops.firemud.gamesession.v1.ReceiveReadinessProbeResponse;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

/** Exact direct-Pod mTLS client for Account and Game Session non-authorizing probe receivers. */
public final class AccountJwtReadinessGrpcReceiverInvocationPort
    extends AccountJwtReadinessReceiverInvocationPort {
  private static final String TLS_BUNDLE_NAME = "firemud-grpc";
  private static final String GAME_SESSION_VALIDATOR = "game-session-service";
  private static final String ACCOUNT_VALIDATOR = "account-service";
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long SHUTDOWN_DEADLINE_SECONDS = 2L;
  private final SslBundles sslBundles;
  private final Clock clock;

  public AccountJwtReadinessGrpcReceiverInvocationPort(
      SslBundles sslBundles, String workloadNamespace) {
    this(sslBundles, workloadNamespace, Clock.systemUTC());
  }

  AccountJwtReadinessGrpcReceiverInvocationPort(
      SslBundles sslBundles, String workloadNamespace, Clock clock) {
    this.sslBundles = Objects.requireNonNull(sslBundles);
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Account workload namespace is required");
    }
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public void requireAvailable() {
    SslBundle bundle = configuredBundle();
    if (bundle.getManagers().getKeyManagerFactory() == null
        || bundle.getManagers().getTrustManagerFactory() == null) {
      throw new ReceiverUnavailableException();
    }
    try {
      X509TrustManager ignored = x509TrustManager(bundle.getManagers().getTrustManagerFactory());
      if (ignored.getAcceptedIssuers().length == 0) {
        throw new ReceiverUnavailableException();
      }
    } catch (RuntimeException failure) {
      throw new ReceiverUnavailableException();
    }
  }

  @Override
  public AuthenticatedAcceptance invoke(Invocation invocation) {
    Objects.requireNonNull(invocation, "readiness invocation is required");
    requireAvailable();
    if ((!GAME_SESSION_VALIDATOR.equals(invocation.validatorId())
            && !ACCOUNT_VALIDATOR.equals(invocation.validatorId()))
        || !invocation.validatorId().equals(invocation.target().validatorId())) {
      throw new ReceiverUnavailableException();
    }

    PodTarget target = invocation.target();
    target.requireRoutablePodIdentity();
    String expectedServiceUri = target.canonicalServiceUri().orElseThrow();
    if (!expectedServiceUri.equals(serviceUri(target.namespace(), target.validatorId()))) {
      throw new ReceiverUnavailableException();
    }
    URI endpoint = target.exactPodEndpoint().orElseThrow();
    InetAddress podAddress = numericPodAddress(target.podIp());
    if (!endpoint.getHost().equals(target.podIp())
        && !endpoint.getHost().equals("[" + target.podIp() + "]")) {
      throw new ReceiverUnavailableException();
    }
    if (clock.instant().getEpochSecond() > invocation.expiresAtEpochSecond()) {
      throw new ReceiverUnavailableException();
    }

    SslBundle bundle = configuredBundle();
    AccountJwtReadinessPodTrustManager peerTrust =
        new AccountJwtReadinessPodTrustManager(
            x509TrustManager(bundle.getManagers().getTrustManagerFactory()),
            expectedServiceUri,
            target.podLeafSpkiSha256().orElseThrow());
    final SslContext sslContext;
    try {
      sslContext =
          GrpcSslContexts.forClient()
              .keyManager(bundle.getManagers().getKeyManagerFactory())
              .trustManager((TrustManager) peerTrust)
              .build();
    } catch (SSLException | RuntimeException failure) {
      throw new ReceiverUnavailableException();
    }

    InetSocketAddress expectedRemoteAddress = new InetSocketAddress(podAddress, endpoint.getPort());
    RemoteAddressObservation remoteAddressObservation = new RemoteAddressObservation();
    ManagedChannel channel =
        NettyChannelBuilder.forAddress(expectedRemoteAddress)
            .proxyDetector(address -> null)
            .addTransportFilter(
                new ClientTransportFilter() {
                  @Override
                  public Attributes transportReady(Attributes attributes) {
                    remoteAddressObservation.observe(
                        attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
                    return attributes;
                  }
                })
            .sslContext(sslContext)
            .disableRetry()
            .build();
    AuthenticatedAcceptance acceptance;
    try {
      if (GAME_SESSION_VALIDATOR.equals(invocation.validatorId())) {
        ReceiveReadinessProbeResponse response =
            GameSessionJwtReadinessReceiverServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .receiveReadinessProbe(
                    ReceiveReadinessProbeRequest.newBuilder()
                        .setSchemaVersion(1)
                        .setCoordinates(coordinates(invocation))
                        .setCompactJwt(invocation.compactJwt())
                        .build());
        acceptance =
            authenticatedResponse(invocation, observation(response), peerTrust.authenticatedPeer());
      } else {
        ReceiveAccountJwtReadinessProbeResponse response =
            AccountJwtReadinessPodReceiverServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .receiveReadinessProbe(
                    ReceiveAccountJwtReadinessProbeRequest.newBuilder()
                        .setSchemaVersion(1)
                        .setCoordinates(coordinates(invocation))
                        .setCompactJwt(invocation.compactJwt())
                        .build());
        acceptance =
            authenticatedResponse(invocation, observation(response), peerTrust.authenticatedPeer());
      }
    } finally {
      channel.shutdownNow();
      try {
        if (!channel.awaitTermination(SHUTDOWN_DEADLINE_SECONDS, TimeUnit.SECONDS)) {
          throw new ReceiverUnavailableException();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new ReceiverUnavailableException();
      }
    }

    if (!remoteAddressObservation.matches(expectedRemoteAddress)) {
      throw new ReceiverUnavailableException();
    }

    return acceptance;
  }

  static final class RemoteAddressObservation {
    private SocketAddress observedAddress;
    private boolean inconsistent;

    synchronized void observe(SocketAddress address) {
      if (address == null) {
        inconsistent = true;
      } else if (observedAddress == null) {
        observedAddress = address;
      } else if (!observedAddress.equals(address)) {
        inconsistent = true;
      }
    }

    synchronized boolean matches(InetSocketAddress expected) {
      return !inconsistent
          && !expected.isUnresolved()
          && observedAddress instanceof InetSocketAddress observed
          && !observed.isUnresolved()
          && observed.equals(expected);
    }
  }

  private AuthenticatedAcceptance authenticatedResponse(
      Invocation invocation,
      ReceiverObservation response,
      AccountJwtReadinessPodTrustManager.AuthenticatedPeer peer) {
    if (response.schemaVersion() != 1
        || response.unknownFields()
        || !coordinates(invocation).equals(response.coordinates())
        || !invocation.compactTokenSha256().equals(response.compactTokenSha256())
        || !invocation.targetKid().equals(response.verifiedKeyId())
        || !response.hasReceiverIdentity()
        || !targetBinding(invocation.target()).equals(response.accountPodTarget())
        || response.targetUnknownFields()) {
      throw new ReceiverUnavailableException();
    }

    ProbeExpectation result = response.outcome();
    if (result != invocation.expectation()) {
      throw new ReceiverUnavailableException();
    }

    if (response.identityUnknownFields()
        || !invocation.validatorId().equals(response.validatorId())
        || !invocation.target().deploymentUid().equals(response.deploymentUid())
        || !invocation.target().podUid().equals(response.podUid())
        || !invocation.target().podIp().equals(response.podIp())
        || !invocation
            .target()
            .exactPodEndpoint()
            .orElseThrow()
            .toString()
            .equals(response.directPodEndpoint())
        || !peer.serviceUri().equals(response.canonicalServiceUri())
        || !invocation.target().image().equals(response.image())
        || !invocation.target().verifierConfigSha256().equals(response.verifierConfigSha256())
        || !invocation
            .target()
            .applicabilityMatrixDigest()
            .equals(response.applicabilityMatrixDigest())
        || !peer.spkiSha256().equals(response.serverLeafSpkiSha256())
        || !isCanonicalRevision(response.sourceInventoryRevision())
        || !isSha256(response.sourceInventoryDigest())
        || response.accountJwksSourceIdentity().isEmpty()
        || !sourceIdentityMatchesTarget(
            response.accountJwksSourceIdentity().orElseThrow(), invocation.target())
        || !response
            .trustBindingRevision()
            .equals(response.accountJwksSourceIdentity().orElseThrow().bindingRevision())
        || (response.publicJwksHashRequired() && !isSha256(response.publicJwksSha256()))) {
      throw new ReceiverUnavailableException();
    }

    long observedAt = response.observedAtEpochSeconds();
    if (observedAt < invocation.issuedAtEpochSecond()
        || observedAt > invocation.expiresAtEpochSecond()
        || clock.instant().getEpochSecond() > invocation.expiresAtEpochSecond()) {
      throw new ReceiverUnavailableException();
    }

    // The acceptance uses inventory values plus the actual handshake peer, never response echoes
    // for the peer SAN/SPKI. The receiver's local inventory remains receiver-owned evidence.
    return authenticatedAcceptance(
        invocation.target().podUid(),
        invocation.target().exactPodEndpoint().orElseThrow().toString(),
        invocation.target().image(),
        invocation.target().verifierConfigSha256(),
        peer.serviceUri(),
        peer.spkiSha256(),
        invocation.jti(),
        invocation.compactTokenSha256(),
        response.verifiedKeyId(),
        result,
        observedAt);
  }

  private static ReceiverObservation observation(ReceiveReadinessProbeResponse response) {
    var identity = response.getReceiverIdentity();
    return new ReceiverObservation(
        response.getSchemaVersion(),
        !response.getUnknownFields().asMap().isEmpty()
            || !response.getCoordinates().getUnknownFields().asMap().isEmpty(),
        response.getCoordinates(),
        response.getCompactTokenSha256(),
        response.getVerifiedKeyId(),
        response.hasReceiverIdentity(),
        !identity.getUnknownFields().asMap().isEmpty()
            || !identity.getAccountJwksSourceIdentity().getUnknownFields().asMap().isEmpty(),
        identity.getValidatorId(),
        identity.getDeploymentUid(),
        identity.getPodUid(),
        identity.getPodIp(),
        identity.getDirectPodEndpoint(),
        identity.getCanonicalServiceUri(),
        identity.getImage(),
        identity.getVerifierConfigSha256(),
        identity.getApplicabilityMatrixDigest(),
        identity.getSourceInventoryRevision(),
        identity.getSourceInventoryDigest(),
        identity.getServerLeafSpkiSha256(),
        accountSourceIdentity(identity.getAccountJwksSourceIdentity()),
        identity.getAccountJwksTrustBindingRevision(),
        identity.getAccountPublicJwksSha256(),
        true,
        response.hasAccountPodTarget()
            ? response.getAccountPodTarget()
            : AccountPodTargetBinding.getDefaultInstance(),
        !response.getAccountPodTarget().getUnknownFields().asMap().isEmpty(),
        outcome(response.getOutcome()),
        response.getObservedAtEpochSeconds());
  }

  private static ReceiverObservation observation(ReceiveAccountJwtReadinessProbeResponse response) {
    AccountJwtPodReceiverIdentity identity = response.getReceiverIdentity();
    return new ReceiverObservation(
        response.getSchemaVersion(),
        !response.getUnknownFields().asMap().isEmpty()
            || !response.getCoordinates().getUnknownFields().asMap().isEmpty(),
        response.getCoordinates(),
        response.getCompactTokenSha256(),
        response.getVerifiedKeyId(),
        response.hasReceiverIdentity(),
        !identity.getUnknownFields().asMap().isEmpty()
            || !identity.getAccountJwksSourceIdentity().getUnknownFields().asMap().isEmpty(),
        identity.getValidatorId(),
        identity.getDeploymentUid(),
        identity.getPodUid(),
        identity.getPodIp(),
        identity.getDirectPodEndpoint(),
        identity.getCanonicalServiceUri(),
        identity.getImage(),
        identity.getVerifierConfigSha256(),
        identity.getApplicabilityMatrixDigest(),
        identity.getSourceInventoryRevision(),
        identity.getSourceInventoryDigest(),
        identity.getServerLeafSpkiSha256(),
        accountSourceIdentity(identity.getAccountJwksSourceIdentity()),
        identity.getJwksTrustBindingRevision(),
        "",
        false,
        response.hasAccountPodTarget()
            ? response.getAccountPodTarget()
            : AccountPodTargetBinding.getDefaultInstance(),
        !response.getAccountPodTarget().getUnknownFields().asMap().isEmpty(),
        outcome(response.getOutcome()),
        response.getObservedAtEpochSeconds());
  }

  private static ProbeExpectation outcome(
      ReceiveReadinessProbeResponse.ObservationOutcome outcome) {
    return switch (outcome) {
      case VERIFIED -> ProbeExpectation.ACCEPT;
      case INAPPLICABLE_REJECT -> ProbeExpectation.INAPPLICABLE_REJECT;
      default -> throw new ReceiverUnavailableException();
    };
  }

  private static ProbeExpectation outcome(
      ReceiveAccountJwtReadinessProbeResponse.ObservationOutcome outcome) {
    return switch (outcome) {
      case VERIFIED -> ProbeExpectation.ACCEPT;
      case INAPPLICABLE_REJECT -> ProbeExpectation.INAPPLICABLE_REJECT;
      default -> throw new ReceiverUnavailableException();
    };
  }

  private SslBundle configuredBundle() {
    try {
      return Objects.requireNonNull(sslBundles.getBundle(TLS_BUNDLE_NAME));
    } catch (RuntimeException failure) {
      throw new ReceiverUnavailableException();
    }
  }

  private static X509TrustManager x509TrustManager(TrustManagerFactory factory) {
    return Arrays.stream(factory.getTrustManagers())
        .filter(X509TrustManager.class::isInstance)
        .map(X509TrustManager.class::cast)
        .findFirst()
        .orElseThrow(ReceiverUnavailableException::new);
  }

  private static ReadinessProbeCoordinates coordinates(Invocation invocation) {
    ReadinessProbeCoordinates.Builder coordinates =
        ReadinessProbeCoordinates.newBuilder()
            .setRotationOperationId(invocation.rotationOperationId().toString())
            .setOperationDigest(invocation.operationDigest())
            .setPlanDigest(invocation.planDigest())
            .setPlanVersion(invocation.planVersion())
            .setRegistryVersion(invocation.registryVersion())
            .setValidatorId(invocation.validatorId())
            .setTokenProfile(invocation.tokenProfile())
            .setAudience(invocation.audience())
            .setProbeKind(
                invocation.probeKind() == ProbeKind.CANARY
                    ? ReadinessProbeCoordinates.ProbeKind.CANARY
                    : ReadinessProbeCoordinates.ProbeKind.REPRESENTATIVE)
            .setExpectedOutcome(
                invocation.expectation() == ProbeExpectation.ACCEPT
                    ? ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
                    : ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
            .setJti(invocation.jti().toString())
            .setEntryVersion(invocation.sourceEntryVersion())
            .setTargetGeneration(invocation.targetGeneration())
            .setTargetKid(invocation.targetKid())
            .setIssuedAtEpochSeconds(invocation.issuedAtEpochSecond())
            .setExpiresAtEpochSeconds(invocation.expiresAtEpochSecond())
            .setPlanExpiresAtEpochSeconds(invocation.planExpiresAtEpochSecond());
    if (invocation.expectedActiveGeneration().isPresent()) {
      coordinates
          .setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.PRESENT)
          .setExpectedActiveGeneration(invocation.expectedActiveGeneration().orElseThrow())
          .setExpectedActiveKid(invocation.expectedActiveKid().orElseThrow());
    } else {
      coordinates.setExpectedActiveState(ReadinessProbeCoordinates.ExpectedActiveState.ABSENT);
    }
    return coordinates.build();
  }

  private static AccountPodTargetBinding targetBinding(PodTarget target) {
    return AccountPodTargetBinding.newBuilder()
        .setInventorySnapshotDigest(target.inventorySnapshotDigest())
        .setEnvironmentId(target.environmentId())
        .setClusterId(target.clusterId())
        .setClusterIncarnationUid(target.clusterIncarnationUid())
        .setNamespace(target.namespace())
        .setNamespaceUid(target.namespaceUid())
        .setApiBindingRevision(target.apiBindingRevision())
        .setApiBindingDigest(target.apiBindingDigest())
        .setInventoryBindingRevision(target.inventoryBindingRevision())
        .setInventoryBindingDigest(target.inventoryBindingDigest())
        .setValidatorId(target.validatorId())
        .setDeploymentUid(target.deploymentUid())
        .setPodUid(target.podUid())
        .setPodIp(target.podIp())
        .setImage(target.image())
        .setVerifierConfigSha256(target.verifierConfigSha256())
        .setApplicabilityMatrixDigest(target.applicabilityMatrixDigest())
        .setExpectedOutcome(
            target.expectation() == ProbeExpectation.ACCEPT
                ? ReadinessProbeCoordinates.ExpectedOutcome.ACCEPT
                : ReadinessProbeCoordinates.ExpectedOutcome.INAPPLICABLE_REJECT)
        .setDirectPodEndpoint(target.exactPodEndpoint().orElseThrow().toString())
        .setCanonicalServiceUri(target.canonicalServiceUri().orElseThrow())
        .setServerLeafSpkiSha256(target.podLeafSpkiSha256().orElseThrow())
        .build();
  }

  private static Optional<SourceIdentity> accountSourceIdentity(
      net.firedevops.firemud.account.v1.AccountSourceIdentity identity) {
    try {
      return Optional.of(
          new SourceIdentity(
              identity.getEnvironmentId(),
              identity.getClusterId(),
              identity.getClusterIncarnationUid(),
              identity.getNamespace(),
              identity.getNamespaceUid(),
              identity.getConfigMapUid(),
              identity.getBindingRevision(),
              identity.getApiServerOrigin(),
              identity.getServingCaSha256()));
    } catch (IllegalArgumentException failure) {
      return Optional.empty();
    }
  }

  static boolean sourceIdentityMatchesTarget(SourceIdentity source, PodTarget target) {
    return source.environmentId().equals(target.environmentId())
        && source.clusterId().equals(target.clusterId())
        && source.clusterIncarnationUid().equals(target.clusterIncarnationUid())
        && source.namespace().equals(target.namespace())
        && source.namespaceUid().equals(target.namespaceUid());
  }

  private static boolean isSha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static boolean isCanonicalRevision(String value) {
    return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
  }

  private static String serviceUri(String namespace, String serviceAccount) {
    return "spiffe://firemud/ns/" + namespace + "/sa/" + serviceAccount;
  }

  private static InetAddress numericPodAddress(String value) {
    try {
      if (!AccountJwtValidatorInventoryBinding.canonicalPodIp(value).equals(value)) {
        throw new ReceiverUnavailableException();
      }
      if (value.indexOf(':') >= 0) {
        InetAddress address = InetAddress.getByName(value);
        if (!(address instanceof Inet6Address)) {
          throw new ReceiverUnavailableException();
        }
        return address;
      }
      String[] octets = value.split("\\.", -1);
      byte[] address = new byte[4];
      for (int index = 0; index < address.length; index++) {
        address[index] = (byte) Integer.parseInt(octets[index]);
      }
      return InetAddress.getByAddress(address);
    } catch (UnknownHostException failure) {
      throw new ReceiverUnavailableException();
    } catch (RuntimeException failure) {
      throw new ReceiverUnavailableException();
    }
  }

  private record ReceiverObservation(
      int schemaVersion,
      boolean unknownFields,
      ReadinessProbeCoordinates coordinates,
      String compactTokenSha256,
      String verifiedKeyId,
      boolean hasReceiverIdentity,
      boolean identityUnknownFields,
      String validatorId,
      String deploymentUid,
      String podUid,
      String podIp,
      String directPodEndpoint,
      String canonicalServiceUri,
      String image,
      String verifierConfigSha256,
      String applicabilityMatrixDigest,
      String sourceInventoryRevision,
      String sourceInventoryDigest,
      String serverLeafSpkiSha256,
      Optional<SourceIdentity> accountJwksSourceIdentity,
      String trustBindingRevision,
      String publicJwksSha256,
      boolean publicJwksHashRequired,
      AccountPodTargetBinding accountPodTarget,
      boolean targetUnknownFields,
      ProbeExpectation outcome,
      long observedAtEpochSeconds) {}
}
