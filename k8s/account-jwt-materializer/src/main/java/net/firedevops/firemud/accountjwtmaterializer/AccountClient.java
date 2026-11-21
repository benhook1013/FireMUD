package net.firedevops.firemud.accountjwtmaterializer;

import io.grpc.ManagedChannel;
import io.grpc.ProxyDetector;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import net.firedevops.firemud.account.v1.AccountJwtSignerMaterializationServiceGrpc;
import net.firedevops.firemud.account.v1.EnrollBootstrapRequest;
import net.firedevops.firemud.account.v1.EnrollBootstrapResponse;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentGenerationRequestResponse;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestRequest;
import net.firedevops.firemud.account.v1.GetCurrentPromotionRequestResponse;
import net.firedevops.firemud.account.v1.PromotionPublicKeyIdentity;
import net.firedevops.firemud.account.v1.RecordGenerationResultRequest;
import net.firedevops.firemud.account.v1.RecordGenerationResultResponse;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultRequest;
import net.firedevops.firemud.account.v1.RecordPrivatePromotionResultResponse;
import net.firedevops.firemud.account.v1.RecordSecretObservationRequest;

/** Pinned mTLS client for only the Account signer materialization RPCs. */
final class AccountClient implements AutoCloseable, Materializer.AccountOperations {
  private static final long RPC_DEADLINE_SECONDS = 5;
  private final ProtectedBinding.Snapshot snapshot;
  private final java.nio.file.Path bindingPath;
  private final ManagedChannel channel;

  AccountClient(ProtectedBinding.Snapshot snapshot, java.nio.file.Path bindingPath) {
    this.snapshot = snapshot;
    this.bindingPath = bindingPath;
    ManagedChannel created = null;
    try {
      snapshot.requireUnchanged(bindingPath);
      ProtectedBinding.Binding binding = snapshot.binding();
      String[] target = binding.accountTarget().split(":", -1);
      if (target.length != 2 || !target[0].equals(binding.accountServerDnsName())) {
        throw denied();
      }
      int port = Integer.parseInt(target[1]);
      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(serverTrustManager(binding))
              .keyManager(
                  binding.clientCertificatePath().toFile(), binding.clientPrivateKeyPath().toFile())
              .build();
      created =
          NettyChannelBuilder.forAddress(target[0], port)
              .overrideAuthority(binding.accountServerDnsName())
              .proxyDetector((ProxyDetector) address -> null)
              .sslContext(sslContext)
              .build();
      snapshot.requireUnchanged(bindingPath);
      this.channel = created;
    } catch (Exception ex) {
      if (created != null) {
        created.shutdownNow();
      }
      throw denied();
    }
  }

  @Override
  public EnrollBootstrapResponse enrollBootstrap() {
    return invoke(
        () ->
            stub()
                .enrollBootstrap(EnrollBootstrapRequest.newBuilder().setSchemaVersion(1).build()));
  }

  @Override
  public GetCurrentGenerationRequestResponse getCurrentGenerationRequest() {
    return invoke(
        () ->
            stub()
                .getCurrentGenerationRequest(
                    GetCurrentGenerationRequestRequest.newBuilder().setSchemaVersion(1).build()));
  }

  @Override
  public GetCurrentGenerationRequestResponse recordSecretObservation(
      String operationId,
      String operationDigest,
      String secretUid,
      String observedResourceVersion) {
    return invoke(
        () ->
            stub()
                .recordSecretObservation(
                    RecordSecretObservationRequest.newBuilder()
                        .setSchemaVersion(1)
                        .setOperationId(operationId)
                        .setOperationDigest(operationDigest)
                        .setSecretUid(secretUid)
                        .setObservedResourceVersion(observedResourceVersion)
                        .build()));
  }

  @Override
  public RecordGenerationResultResponse recordGenerationResult(
      String operationId,
      String generationRequestDigest,
      String secretUid,
      String expectedPriorResourceVersion,
      String observedResourceVersion,
      String publicJwkJson) {
    return invoke(
        () ->
            stub()
                .recordGenerationResult(
                    RecordGenerationResultRequest.newBuilder()
                        .setSchemaVersion(1)
                        .setOperationId(operationId)
                        .setGenerationRequestDigest(generationRequestDigest)
                        .setSecretUid(secretUid)
                        .setExpectedPriorResourceVersion(expectedPriorResourceVersion)
                        .setObservedResourceVersion(observedResourceVersion)
                        .setPublicJwkJson(publicJwkJson)
                        .build()));
  }

  @Override
  public GetCurrentPromotionRequestResponse getCurrentPromotionRequest() {
    return invoke(
        () ->
            stub()
                .getCurrentPromotionRequest(
                    GetCurrentPromotionRequestRequest.newBuilder().setSchemaVersion(1).build()));
  }

  @Override
  public RecordPrivatePromotionResultResponse recordPrivatePromotionResult(
      GetCurrentPromotionRequestResponse request,
      String secretUid,
      String expectedPriorResourceVersion,
      String observedResourceVersion,
      SignerKeyBundle.PublicIdentity current,
      SignerKeyBundle.PublicIdentity previous,
      List<String> resultingSlots) {
    RecordPrivatePromotionResultRequest.Builder builder =
        RecordPrivatePromotionResultRequest.newBuilder()
            .setSchemaVersion(1)
            .setPromotionOperationId(request.getPromotionOperationId())
            .setPromotionRequestDigest(request.getPromotionRequestDigest())
            .setGenerationOperationId(request.getGenerationOperationId())
            .setGenerationOperationDigest(request.getGenerationOperationDigest())
            .setSecretUid(secretUid)
            .setExpectedPriorResourceVersion(expectedPriorResourceVersion)
            .setObservedResourceVersion(observedResourceVersion)
            .setCurrent(toWire(current));
    if (previous != null) {
      builder.setPrevious(toWire(previous));
    }
    builder.addAllResultingSlots(resultingSlots);
    return invoke(() -> stub().recordPrivatePromotionResult(builder.build()));
  }

  private static PromotionPublicKeyIdentity toWire(SignerKeyBundle.PublicIdentity identity) {
    return PromotionPublicKeyIdentity.newBuilder()
        .setGeneration(identity.generation())
        .setKid(identity.kid())
        .setPublicKeyFingerprint(identity.fingerprint())
        .build();
  }

  private AccountJwtSignerMaterializationServiceGrpc
          .AccountJwtSignerMaterializationServiceBlockingStub
      stub() {
    return AccountJwtSignerMaterializationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private <T> T invoke(RpcCall<T> call) {
    requireSnapshot();
    T result;
    try {
      result = call.run();
    } catch (RuntimeException ex) {
      requireSnapshot();
      throw ex;
    }
    requireSnapshot();
    return result;
  }

  private void requireSnapshot() {
    try {
      snapshot.requireUnchanged(bindingPath);
    } catch (Exception ex) {
      throw denied();
    }
  }

  private static X509TrustManager serverTrustManager(ProtectedBinding.Binding binding)
      throws Exception {
    byte[] caBytes = ProtectedBinding.readCredential(binding.accountCaPath());
    try {
      var certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(caBytes));
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null);
      int index = 0;
      for (var certificate : certificates) {
        trustStore.setCertificateEntry("account-ca-" + index++, certificate);
      }
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(trustStore);
      X509TrustManager delegate =
          java.util.Arrays.stream(factory.getTrustManagers())
              .filter(X509TrustManager.class::isInstance)
              .map(X509TrustManager.class::cast)
              .findFirst()
              .orElseThrow(AccountClient::denied);
      return new X509TrustManager() {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
          return delegate.getAcceptedIssuers();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws java.security.cert.CertificateException {
          delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws java.security.cert.CertificateException {
          delegate.checkServerTrusted(chain, authType);
          verifyServerIdentity(chain, binding);
        }
      };
    } finally {
      java.util.Arrays.fill(caBytes, (byte) 0);
    }
  }

  private static void verifyServerIdentity(
      X509Certificate[] chain, ProtectedBinding.Binding binding)
      throws java.security.cert.CertificateException {
    try {
      if (chain == null || chain.length == 0) {
        throw denied();
      }
      X509Certificate leaf = chain[0];
      String spki =
          java.util.HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(leaf.getPublicKey().getEncoded()));
      List<String> uris = new java.util.ArrayList<>();
      var names = leaf.getSubjectAlternativeNames();
      if (names != null) {
        for (List<?> name : names) {
          if (name.size() >= 2
              && Integer.valueOf(6).equals(name.get(0))
              && name.get(1) instanceof String uri) {
            uris.add(uri);
          }
        }
      }
      if (!binding.accountServerSpkiSha256Pins().contains(spki)
          || !uris.equals(List.of(binding.expectedAccountSpiffeUri()))) {
        throw denied();
      }
    } catch (java.security.GeneralSecurityException | RuntimeException ex) {
      throw new java.security.cert.CertificateException("Account peer identity is not pinned");
    }
  }

  @Override
  public void close() {
    channel.shutdownNow();
    try {
      channel.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  private static IllegalStateException denied() {
    return new IllegalStateException("Account materialization peer is unavailable or untrusted");
  }

  @FunctionalInterface
  private interface RpcCall<T> {
    T run();
  }
}
