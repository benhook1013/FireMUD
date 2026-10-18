package unit.net.firedevops.firemud.worldmanagement.tenant;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionActivationProtectionAcquisitionInput;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.world.GrpcWorldOriginalStartSessionProtectedActivationClient;
import net.firedevops.firemud.common.world.WorldOriginalStartSessionActivationTerminalEvidence;

/**
 * Controlled canonical carriers shared by these activation socket tests; no owner/SQL authority.
 */
final class WorldOriginalStartSessionActivationTransportTestSupport {
  static final String NAMESPACE = "test";
  private static final java.util.concurrent.atomic.AtomicLong CERTIFICATE_SERIAL =
      new java.util.concurrent.atomic.AtomicLong(1L);
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private WorldOriginalStartSessionActivationTransportTestSupport() {}

  /**
   * Canonical synthetic wire fixture only; it establishes no real Account authority or owner state.
   */
  static Fixture fixture() throws Exception {
    var active =
        net.firedevops.firemud.common.testing.AuthoringFixtures.lifecycleEvidence("ACTIVE", 3L);
    var lifecycleRequest = active.request();
    var binding = active.launchBinding();
    var descriptor = binding.descriptor();
    var release = binding.releaseAttestation();
    var preparing =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
            lifecycleRequest,
            binding,
            active.startLocation(),
            active.runtimeRoomInstanceId(),
            "PREPARING",
            active.lifecycleEpoch(),
            active.rowVersion(),
            active.captureId(),
            active.graphSha256(),
            active.preparationInputDigest(),
            active.operationalRegionAssignments());
    var activation =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Request(
            java.util.UUID.fromString("d0d0d0d0-d0d0-40d0-80d0-d0d0d0d0d0d0"), preparing);
    var tuple = originalTuple(lifecycleRequest, descriptor.gameTemplateId());
    var launchRequest =
        new net.firedevops.firemud.common.gamesession
            .CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            java.util.UUID.fromString("e0e0e0e0-e0e0-40e0-80e0-e0e0e0e0e0e0"),
            lifecycleRequest.targetNamespace(),
            lifecycleRequest.canonicalTenantId(),
            lifecycleRequest.worldSlug(),
            lifecycleRequest.canonicalGameInstanceId(),
            lifecycleRequest.controlPlaneRequestId(),
            descriptor.launchDescriptorId(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            release.evidenceDigest(),
            new net.firedevops.firemud.common.gamesession
                .CanonicalGameInstanceLaunchAssociationReadEvidence.OriginalOperation(
                tuple.canonicalBytes(),
                java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"),
                java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"),
                21L));
    var launchResult =
        new net.firedevops.firemud.common.gamesession
            .CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            launchRequest,
            lifecycleRequest.playableStateNamespaceId(),
            net.firedevops.firemud.common.publication.RealmEntryPolicy.StateScope.SHARED,
            true,
            net.firedevops.firemud.common.gamesession
                .CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus
                .STARTING,
            7L,
            java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"),
            21L,
            java.util.UUID.fromString("f0f0f0f0-f0f0-40f0-80f0-f0f0f0f0f0f0"),
            17L,
            binding);
    var input =
        new AccountStartSessionActivationProtectionAcquisitionInput(
            tuple.canonicalBytes(),
            java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"),
            java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"),
            21L,
            activation);
    var attemptRequest =
        new net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence
            .Request(
            java.util.UUID.fromString("b0b0b0b0-b0b0-40b0-80b0-b0b0b0b0b0b0"),
            NAMESPACE,
            tuple.canonicalBytes(),
            input.gameSessionOwnerAttemptId(),
            input.gameSessionOwnerMutationId(),
            input.gameSessionOwnerFence());
    var originalAttempt =
        new net.firedevops.firemud.common.gamesession.OriginalStartSessionCurrentAttemptEvidence
            .Result(
            attemptRequest,
            java.time.Instant.parse("2099-01-01T00:05:00Z"),
            net.firedevops.firemud.common.account.startsession
                .StartSessionAccountRedemptionProjection.fromOriginalTuple(tuple));
    var request =
        net.firedevops.firemud.common.account.startsession
            .AccountStartSessionActivationProtectionRequest.create(
            java.util.UUID.fromString("33333333-3333-4333-8333-333333333333"),
            22L,
            launchResult,
            originalAttempt,
            activation);
    byte[] capture = captureReference(tuple);
    var protection =
        net.firedevops.firemud.common.account.startsession
            .AccountStartSessionActivationProtectionEvidence.create(
            request,
            java.util.UUID.fromString("44444444-4444-4444-8444-444444444444"),
            23L,
            java.time.Instant.parse("2099-01-01T00:01:12.123000Z"),
            capture,
            sha256(capture),
            java.util.List.of(
                new net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                    .SourceEvidence(
                    net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding
                        .SourceKind.ACCOUNT,
                    lifecycleRequest.canonicalTenantId().toString(),
                    "2",
                    "17",
                    null,
                    null,
                    "synthetic transport source snapshot"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    var activeResult =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
            lifecycleRequest,
            binding,
            active.startLocation(),
            active.runtimeRoomInstanceId(),
            "ACTIVE",
            preparing.lifecycleEpoch() + 1L,
            preparing.rowVersion() + 1L,
            preparing.captureId(),
            preparing.graphSha256(),
            preparing.preparationInputDigest(),
            preparing.operationalRegionAssignments());
    var activationResult =
        new net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Result(
            activation,
            net.firedevops.firemud.common.world.WorldCanonicalInstanceActivation.Outcome.COMMITTED,
            null,
            activeResult);
    var terminal =
        WorldOriginalStartSessionActivationTerminalEvidence.create(
            protection, activationResult, 29L);
    return new Fixture(input, protection, terminal);
  }

  private static net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
      originalTuple(
          net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request world,
          long gameTemplateId)
          throws Exception {
    var action =
        new net.firedevops.firemud.common.operator.StartSessionOperatorAction(
            net.firedevops.firemud.common.operator.StartSessionOperatorAction
                .ACTION_FAMILY_SCHEMA_ID,
            net.firedevops.firemud.common.operator.StartSessionOperatorAction
                .ACTION_FAMILY_SCHEMA_VERSION,
            new net.firedevops.firemud.common.operator.StartSessionOperatorAction.Scope(
                world.canonicalTenantId(), world.targetNamespace()),
            new net.firedevops.firemud.common.operator.StartSessionOperatorAction.Target(
                gameTemplateId, java.util.UUID.fromString("55555555-5555-4555-8555-555555555555")),
            net.firedevops.firemud.common.operator.StartSessionOperatorAction.ExpectedVersion
                .ABSENT,
            new net.firedevops.firemud.common.operator.StartSessionOperatorAction.Mutation(
                net.firedevops.firemud.common.operator.StartSessionOperatorAction.ClientIp
                    .absent()),
            "World activation transport fixture with mocked owners");
    var preTuple =
        net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple
            .createHuman(
                world.controlPlaneRequestId(),
                java.util.UUID.fromString("66666666-6666-4666-8666-666666666666"),
                action);
    return net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple
        .createHuman(
            preTuple,
            "spiffe://firemud/ns/" + world.targetNamespace() + "/sa/logging-admin-service",
            "arfp/v1/test-key/" + "5".repeat(64),
            java.util.UUID.fromString("77777777-7777-4777-8777-777777777777"),
            19L,
            authorityBundle(preTuple),
            new net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle
                .BundleReference(
                net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle
                    .BUNDLE_VERSION,
                "17",
                "23",
                "18446744073709551615"));
  }

  private static byte[] authorityBundle(
      net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple tuple)
      throws Exception {
    String tenant = tuple.action().scope().tenantId().toString();
    var projection =
        java.util.Map.of(
            "sourceType", "ACCOUNT",
            "sourceEvidenceId", "sha256:" + "a".repeat(64),
            "sourceEvidenceVersion", "17",
            "projectionStatus", "CURRENT",
            "evaluatedAt", "2099-01-01T00:00:00Z",
            "expiresAt", "2099-01-01T00:05:00Z");
    var operation =
        java.util.Map.of(
            "issuanceOperationId", "88888888-8888-4888-8888-888888888888",
            "controlPlaneRequestId", tuple.controlPlaneRequestId(),
            "actionFamilyRequestIdentity",
                java.util.Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    tuple.controlPlaneRequestId()),
            "mutationDigest", tuple.mutationDigest());
    var authority =
        java.util.Map.of(
            "issuerAuthGeneration",
            1L,
            "accountAuthorityGeneration",
            2L,
            "tenantAuthorityGeneration",
            java.util.Map.of(tenant, 3L),
            "membershipAuthorityGeneration",
            java.util.Map.of(tenant, 4L),
            "privateRealmGrantVersions",
            java.util.List.of());
    var issuance =
        java.util.Map.of(
            "evidenceType",
            net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle
                .HUMAN_EVIDENCE_TYPE,
            "actorAccountId",
            "66666666-6666-4666-8666-666666666666",
            "controlUiTokenJti",
            "99999999-9999-4999-8999-999999999999",
            "role",
            "tenantAdmin",
            "accountGeneration",
            "2",
            "tenantGeneration",
            "3");
    var scope =
        java.util.Map.of(
            "scope",
            java.util.Map.of(
                "tenantId", tenant, "targetNamespace", tuple.action().scope().targetNamespace()),
            "actionFamily",
            tuple.actionFamily(),
            "applicableAccountId",
            "66666666-6666-4666-8666-666666666666",
            "applicableTenantId",
            tenant);
    return canonical(
        java.util.Map.of(
            "bundleVersion",
            net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle
                .BUNDLE_VERSION,
            "authorityScope",
            scope,
            "accountProjectionEvidence",
            projection,
            "issuanceOperationIdentity",
            operation,
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            authority,
            "membershipVersion",
            java.util.Map.of(tenant, 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            issuance));
  }

  private static byte[] captureReference(
      net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple tuple)
      throws Exception {
    return canonical(
        java.util.Map.of(
            "schema",
            "account-start-session-authority-capture/v1",
            "controlPlaneRequestId",
            tuple.controlPlaneRequestId(),
            "capturedAt",
            "2099-01-01T00:01:12.123Z",
            "bundleReference",
            java.util.Map.of(
                "bundleVersion",
                tuple.bundleReference().bundleVersion(),
                "sourceVersion",
                tuple.bundleReference().sourceVersion(),
                "sourceFence",
                tuple.bundleReference().sourceFence(),
                "linearization",
                tuple.bundleReference().linearization()),
            "snapshotSha256",
            "b".repeat(64)));
  }

  private static byte[] canonical(Object value) throws Exception {
    return net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
        JSON.writeValueAsString(value));
  }

  private static String sha256(byte[] value) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
  }

  static TransportServer startServer(
      TestPki pki,
      io.grpc.BindableService service,
      AtomicInteger handlerCalls,
      boolean injectEndUserContext,
      java.util.function.Consumer<Object> responseCapture)
      throws Exception {
    return startServer(
        pki, pki.worldServer(), service, handlerCalls, injectEndUserContext, responseCapture);
  }

  static TransportServer startServer(
      TestPki pki,
      TestIdentity serverIdentity,
      io.grpc.BindableService service,
      AtomicInteger handlerCalls,
      boolean injectEndUserContext,
      java.util.function.Consumer<Object> responseCapture)
      throws Exception {
    java.util.List<io.grpc.ServerInterceptor> interceptors = new java.util.ArrayList<>();
    interceptors.add(
        new io.grpc.ServerInterceptor() {
          @Override
          public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
              io.grpc.ServerCall<ReqT, RespT> call,
              io.grpc.Metadata headers,
              io.grpc.ServerCallHandler<ReqT, RespT> next) {
            handlerCalls.incrementAndGet();
            return next.startCall(call, headers);
          }
        });
    interceptors.add(new GrpcPeerIdentityInterceptor());
    if (responseCapture != null) {
      interceptors.add(
          new io.grpc.ServerInterceptor() {
            @Override
            public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                io.grpc.ServerCall<ReqT, RespT> call,
                io.grpc.Metadata headers,
                io.grpc.ServerCallHandler<ReqT, RespT> next) {
              return next.startCall(
                  new io.grpc.ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    @Override
                    public void sendMessage(RespT message) {
                      responseCapture.accept(message);
                      super.sendMessage(message);
                    }
                  },
                  headers);
            }
          });
    }
    if (injectEndUserContext) {
      interceptors.add(
          new io.grpc.ServerInterceptor() {
            @Override
            public <ReqT, RespT> io.grpc.ServerCall.Listener<ReqT> interceptCall(
                io.grpc.ServerCall<ReqT, RespT> call,
                io.grpc.Metadata headers,
                io.grpc.ServerCallHandler<ReqT, RespT> next) {
              var delegate = next.startCall(call, headers);
              return new io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                  delegate) {
                @Override
                public void onHalfClose() {
                  net.firedevops.firemud.common.security.SessionContext.setContext(
                      "authenticated-user", java.util.List.of(), java.util.Map.of());
                  try {
                    super.onHalfClose();
                  } finally {
                    net.firedevops.firemud.common.security.SessionContext.clear();
                  }
                }
              };
            }
          });
    }
    var server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(
                GrpcSslContexts.configure(
                        SslContextBuilder.forServer(
                            serverIdentity.privateKey(), serverIdentity.certificate()))
                    .trustManager(pki.caCertificate())
                    .clientAuth(ClientAuth.REQUIRE)
                    .build())
            .addService(
                io.grpc.ServerInterceptors.intercept(
                    service, interceptors.toArray(io.grpc.ServerInterceptor[]::new)))
            .build()
            .start();
    return new TransportServer(server, handlerCalls);
  }

  static GrpcWorldOriginalStartSessionProtectedActivationClient protectedClient(
      TestPki pki, Path directory, int port, TestIdentity identity) throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + port);
    return new GrpcWorldOriginalStartSessionProtectedActivationClient(
        endpoints, pki.clientProperties(directory, identity), new GrpcChannelFactory(), NAMESPACE);
  }

  static net.firedevops.firemud.common.world
          .GrpcWorldOriginalStartSessionActivationTerminalReadClient
      terminalReadClient(TestPki pki, Path directory, int port, TestIdentity identity)
          throws Exception {
    var endpoints = new ServiceEndpointsProperties();
    endpoints.setWorldManagementService("127.0.0.1:" + port);
    return new net.firedevops.firemud.common.world
        .GrpcWorldOriginalStartSessionActivationTerminalReadClient(
        endpoints, pki.clientProperties(directory, identity), new GrpcChannelFactory(), NAMESPACE);
  }

  static UnauthenticatedClient clientWithoutIdentity(TestPki pki, int port) throws Exception {
    return new UnauthenticatedClient(
        NettyChannelBuilder.forAddress(new InetSocketAddress("127.0.0.1", port))
            .sslContext(GrpcSslContexts.forClient().trustManager(pki.caCertificate()).build())
            .build());
  }

  record Fixture(
      AccountStartSessionActivationProtectionAcquisitionInput input,
      net.firedevops.firemud.common.account.startsession
              .AccountStartSessionActivationProtectionEvidence
          protection,
      WorldOriginalStartSessionActivationTerminalEvidence terminal) {}

  record TransportServer(io.grpc.Server server, AtomicInteger handlerCalls)
      implements AutoCloseable {
    int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      if (!server.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Loopback World gRPC server did not terminate");
      }
    }
  }

  record UnauthenticatedClient(ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow();
      if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Unauthenticated loopback gRPC client did not terminate");
      }
    }
  }

  record TestIdentity(
      String alias,
      java.security.PrivateKey privateKey,
      java.security.cert.X509Certificate certificate) {}

  record TestPki(
      java.security.cert.X509Certificate caCertificate,
      Path caCertificatePem,
      TestIdentity worldServer,
      TestIdentity otherNamespaceWorldServer,
      TestIdentity gameSessionClient,
      TestIdentity accountClient,
      TestIdentity gameDesignClient,
      TestIdentity otherNamespaceGameSessionClient,
      TestIdentity otherNamespaceAccountClient,
      TestIdentity otherNamespaceGameDesignClient) {
    static TestPki create(Path directory) throws Exception {
      if (java.security.Security.getProvider(
              org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
          == null) {
        java.security.Security.addProvider(
            new org.bouncycastle.jce.provider.BouncyCastleProvider());
      }
      var caKeys = keyPair();
      var caName =
          new org.bouncycastle.asn1.x500.X500Name("CN=World Activation Test CA, O=FireMUD Test");
      var caCertificate =
          issueCertificate(
              caName, caKeys.getPublic(), caName, caKeys.getPrivate(), true, null, false);
      Path caPem =
          writePem(
              directory.resolve("world-activation-test-ca.crt"),
              "CERTIFICATE",
              caCertificate.getEncoded());
      return new TestPki(
          caCertificate,
          caPem,
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "world-server",
              "spiffe://firemud/ns/test/sa/world-management-service",
              true),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "other-world-server",
              "spiffe://firemud/ns/other/sa/world-management-service",
              true),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "game-session",
              "spiffe://firemud/ns/test/sa/game-session-service",
              false),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "account",
              "spiffe://firemud/ns/test/sa/account-service",
              false),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "game-design",
              "spiffe://firemud/ns/test/sa/game-design-service",
              false),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "other-game-session",
              "spiffe://firemud/ns/other/sa/game-session-service",
              false),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "other-account",
              "spiffe://firemud/ns/other/sa/account-service",
              false),
          issueIdentity(
              caName,
              caKeys.getPrivate(),
              "other-game-design",
              "spiffe://firemud/ns/other/sa/game-design-service",
              false));
    }

    CommonGrpcClientProperties clientProperties(Path directory, TestIdentity identity)
        throws Exception {
      var tls = new CommonGrpcClientProperties();
      tls.setCertChain(
          writePem(
                  directory.resolve(identity.alias() + ".crt"),
                  "CERTIFICATE",
                  identity.certificate().getEncoded())
              .toString());
      tls.setPrivateKey(
          writePem(
                  directory.resolve(identity.alias() + ".key"),
                  "PRIVATE KEY",
                  identity.privateKey().getEncoded())
              .toString());
      tls.setCaCert(caCertificatePem.toString());
      tls.setPlaintext(false);
      return tls;
    }

    private static TestIdentity issueIdentity(
        org.bouncycastle.asn1.x500.X500Name caName,
        java.security.PrivateKey caKey,
        String alias,
        String uri,
        boolean server)
        throws Exception {
      var keys = keyPair();
      return new TestIdentity(
          alias,
          keys.getPrivate(),
          issueCertificate(
              new org.bouncycastle.asn1.x500.X500Name("CN=" + alias),
              keys.getPublic(),
              caName,
              caKey,
              false,
              uri,
              server));
    }

    private static java.security.KeyPair keyPair() throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    }

    private static java.security.cert.X509Certificate issueCertificate(
        org.bouncycastle.asn1.x500.X500Name subject,
        java.security.PublicKey publicKey,
        org.bouncycastle.asn1.x500.X500Name issuer,
        java.security.PrivateKey issuerKey,
        boolean ca,
        String workloadUri,
        boolean server)
        throws Exception {
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              issuer,
              java.math.BigInteger.valueOf(CERTIFICATE_SERIAL.getAndIncrement()),
              java.util.Date.from(java.time.Instant.now().minusSeconds(60)),
              java.util.Date.from(java.time.Instant.now().plusSeconds(86_400)),
              subject,
              publicKey);
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(ca));
      if (ca) {
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage,
            true,
            new org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.keyCertSign
                    | org.bouncycastle.asn1.x509.KeyUsage.cRLSign));
      } else {
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage,
            true,
            new org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                    | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment));
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
            false,
            new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
                server
                    ? org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
                    : org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));
        if (workloadUri != null) {
          builder.addExtension(
              org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
              false,
              new org.bouncycastle.asn1.x509.GeneralNames(
                  new org.bouncycastle.asn1.x509.GeneralName[] {
                    new org.bouncycastle.asn1.x509.GeneralName(
                        org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                        workloadUri),
                    new org.bouncycastle.asn1.x509.GeneralName(
                        org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")
                  }));
        }
      }
      var signer =
          new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
              .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
              .build(issuerKey);
      return new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
          .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
          .getCertificate(builder.build(signer));
    }

    private static Path writePem(Path path, String label, byte[] bytes) throws Exception {
      String body = java.util.Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
      return java.nio.file.Files.writeString(
          path,
          "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n",
          java.nio.charset.StandardCharsets.US_ASCII);
    }
  }
}
