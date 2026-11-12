package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec.Selector;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

/** Controlled signer/JWKS inputs; this fixture does not supply Account issuance authority. */
final class CanonicalGameplayOriginalNewFinalAdmissionTestFixture implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
  private final CanonicalGameplayOriginalNewCandidate candidate;
  private final AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential credential;
  private final CanonicalGameplayOriginalNewBoundCredentialVerifier verifier;
  private final String jwt;
  private final byte[] handle = new byte[32];

  CanonicalGameplayOriginalNewFinalAdmissionTestFixture(
      CanonicalGameplayOriginalNewCandidate candidate, Clock clock) throws Exception {
    this.candidate = candidate;
    RANDOM.nextBytes(handle);
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    KeyPair keys = generator.generateKeyPair();
    UUID boundJti = UUID.randomUUID();
    var lease = candidate.leaseEvidence().carrier();
    long now = clock.instant().getEpochSecond();
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", GameSessionAccountDelegationProfile.ISSUER);
    claims.put("sub", candidate.bindingIdentity().accountId().toString());
    claims.put("accountId", candidate.bindingIdentity().accountId().toString());
    claims.put("tenantId", candidate.bindingIdentity().tenantId().toString());
    claims.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
    claims.put("jti", boundJti.toString());
    claims.put("iat", now);
    claims.put("nbf", now);
    claims.put("exp", now + 240L);
    claims.put("tokenGeneration", "1");
    claims.put("issuanceFence", lease.get("issuanceFence"));
    claims.put("authorityTuple", lease.get("authorityTuple"));
    claims.put(
        "membershipVersion",
        ((Map<?, ?>) lease.get("membershipBaseline")).get("membershipVersion"));
    String input =
        base64(
                "{\"alg\":\"RS256\",\"kid\":\"controlled-final-proof\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8))
            + "."
            + base64(JSON.writeValueAsBytes(claims));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(input.getBytes(StandardCharsets.US_ASCII));
    jwt = input + "." + base64(signature.sign());
    credential =
        AccountGameplayAdmissionLeaseGrpcCodec.originalNewCredential(
            jwt,
            handle,
            UUID.randomUUID(),
            boundJti,
            hash(jwt.getBytes(StandardCharsets.US_ASCII)),
            Long.parseLong((String) lease.get("issuanceFence")),
            UUID.fromString((String) lease.get("leaseId")),
            candidate.leaseEvidence().leaseFence().longValueExact(),
            "42",
            canonicalHash(lease.get("outboxCheckpoints")));

    var pin =
        new AccountPublicJwksCache.SourceIdentity(
            "test",
            "controlled-cluster",
            UUID.randomUUID().toString(),
            "test",
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "controlled-binding",
            "https://kubernetes.example:6443",
            "a".repeat(64));
    RSAPublicKey publicKey = (RSAPublicKey) keys.getPublic();
    byte[] jwks =
        JSON.writeValueAsBytes(
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
                        "controlled-final-proof",
                        "n",
                        base64(unsigned(publicKey.getModulus().toByteArray())),
                        "e",
                        base64(unsigned(publicKey.getPublicExponent().toByteArray()))))));
    var cache =
        new AccountPublicJwksCache(
            () -> new AccountPublicJwksCache.PublicJwksSnapshot(pin, jwks),
            pin,
            clock,
            Duration.ofSeconds(30));
    verifier = new CanonicalGameplayOriginalNewBoundCredentialVerifier(cache, clock);
  }

  CanonicalGameplayOriginalNewAuthenticatedSession.Template template() {
    return CanonicalGameplayOriginalNewAuthenticatedSession.template(
        candidate, credential, verifier, 120_000L);
  }

  static net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution
      controlledCapacity(
          CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
          Clock clock) {
    var candidate = source.candidate();
    var b = candidate.bindingIdentity();
    var t = candidate.targetEvidence();
    UUID requestId = UUID.fromString((String) candidate.leaseEvidence().carrier().get("requestId"));
    var player =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.PlayerContext(
            b.accountId(),
            b.tenantId(),
            b.playableStateNamespaceId(),
            b.gameInstanceId(),
            b.characterId(),
            UUID.fromString(b.sessionId()),
            t.realmId(),
            requestId,
            "SHARED");
    var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetClass", "PUBLIC_PRODUCTION");
    target.put("characterCreationPolicy", "PRESEEDED_ONLY");
    target.put("targetNamespace", t.targetNamespace());
    target.put("tenantId", b.tenantId().toString());
    target.put("tenantSlug", t.tenantSlug());
    target.put("worldSlug", t.worldSlug());
    target.put("realmId", t.realmId().toString());
    target.put("realmSlug", t.realmSlug());
    target.put("playableStateNamespaceId", b.playableStateNamespaceId().toString());
    target.put("playableStateScope", "SHARED");
    target.put("gameInstanceId", b.gameInstanceId().toString());
    target.put("canonicalVersionId", t.canonicalVersionId().toString());
    target.put("catalogRevision", Long.toString(t.catalogRevision()));
    target.put("pointerVersion", Long.toString(t.pointerVersion()));
    target.put("activeWorldEpoch", Long.toString(t.activeWorldEpoch()));
    target.put("evaluatedAt", now.minusSeconds(1).toString());
    target.put("connectScopeExpiresAt", now.plusSeconds(120).toString());
    var request =
        net.firedevops.firemud.common.account.capacity.CapacityAdmissionTestFixture
            .playerRequestFor(
                player,
                candidate.bindingDecisionId(),
                UUID.randomUUID(),
                t.targetNamespace(),
                target,
                now);
    var reservation =
        new net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            1,
            request.mutationDigest(),
            UUID.randomUUID(),
            1,
            request.evidence().expiresAt(),
            net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    return new net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution(
        request,
        reservation,
        candidate.bindingDecisionId(),
        source.candidateDigest(),
        "controlled-owner",
        source.regionAcknowledgement().executorFence().longValueExact());
  }

  Selector selector() throws Exception {
    var lease = candidate.leaseEvidence().carrier();
    return new Selector(
        UUID.fromString((String) lease.get("requestId")),
        candidate.bindingIdentity().accountId(),
        candidate.bindingIdentity().tenantId(),
        UUID.fromString(candidate.bindingIdentity().sessionId()),
        candidate.bindingDecisionId(),
        UUID.fromString((String) lease.get("leaseId")),
        candidate.leaseEvidence().leaseFence().longValueExact(),
        hash(candidate.leaseEvidence().canonicalJson().getBytes(StandardCharsets.UTF_8)),
        credential.tokenSha256(),
        1L,
        credential.issuanceFence(),
        canonicalHash(lease.get("bindingScope")),
        canonicalHash(lease.get("membershipBaseline")),
        canonicalHash(lease.get("outboxCheckpoints")),
        hash(handle));
  }

  Selector wrongTokenSelector() throws Exception {
    var original = selector();
    String wrongHash =
        original.tokenSha256().equals("f".repeat(64)) ? "e".repeat(64) : "f".repeat(64);
    return new Selector(
        original.requestId(),
        original.accountId(),
        original.tenantId(),
        original.sessionId(),
        original.bindingDecisionId(),
        original.originalLeaseId(),
        original.originalLeaseFence(),
        original.originalLeaseEvidenceSha256(),
        wrongHash,
        original.tokenGeneration(),
        original.issuanceFence(),
        original.bindingScopeSha256(),
        original.membershipBaselineSha256(),
        original.checkpointsSha256(),
        original.rebindHandleSha256());
  }

  String jwt() {
    return jwt;
  }

  String initialLoginJti() {
    return (String)
        ((Map<?, ?>) candidate.leaseEvidence().carrier().get("tokenIdentityEvidence"))
            .get("tokenJti");
  }

  UUID boundJti() {
    return credential.tokenJti();
  }

  private static String canonicalHash(Object value) throws Exception {
    return hash(Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value)));
  }

  private static String hash(byte[] value) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }

  private static String base64(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] unsigned(byte[] value) {
    return value.length > 1 && value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length) : value;
  }

  /** Real authenticated client decoding over controlled World/Entity response DTOs and test PKI. */
  static final class ControlledRegionSources implements AutoCloseable {
    final net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request;
    final net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver
        resolver;
    final net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient
        entity;
    private final net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient
        placement;
    private final net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient
        lifecycle;
    private final io.grpc.Server worldServer;
    private final io.grpc.Server entityServer;
    private final Map<
            UUID, net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest>
        requestsByTransition;

    ControlledRegionSources(
        org.jooq.DSLContext dsl,
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision decision,
        java.nio.file.Path directory)
        throws Exception {
      this(dsl, List.of(decision), directory);
    }

    ControlledRegionSources(
        org.jooq.DSLContext dsl,
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision first,
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision second,
        java.nio.file.Path directory)
        throws Exception {
      this(dsl, List.of(first, second), directory);
    }

    private ControlledRegionSources(
        org.jooq.DSLContext dsl,
        List<net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision>
            decisions,
        java.nio.file.Path directory)
        throws Exception {
      if (decisions.isEmpty() || decisions.size() > 2) {
        throw new IllegalArgumentException(
            "Controlled region sources support one or two candidates");
      }
      var decision = decisions.getFirst();
      var target = decision.targetEvidence();
      var identity = decision.candidate();
      String namespace = target.targetNamespace();
      for (var candidateDecision : decisions) {
        var candidate = candidateDecision.candidate();
        var candidateTarget = candidateDecision.targetEvidence();
        if (!candidateDecision.targetEvidence().targetNamespace().equals(namespace)
            || !candidate.tenantId().equals(identity.tenantId())
            || !candidate.gameInstanceId().equals(identity.gameInstanceId())
            || !candidate.regionId().equals(identity.regionId())
            || !candidate.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
            || !candidateTarget.realmId().equals(target.realmId())
            || !candidateTarget.canonicalVersionId().equals(target.canonicalVersionId())) {
          throw new IllegalArgumentException(
              "Controlled region candidates must share one exact tenant/game/region target");
        }
      }
      if (decisions.size() == 2
          && (decisions
                  .get(0)
                  .candidate()
                  .characterId()
                  .equals(decisions.get(1).candidate().characterId())
              || decisions
                  .get(0)
                  .candidate()
                  .sessionId()
                  .equals(decisions.get(1).candidate().sessionId())
              || decisions
                  .get(0)
                  .bindingDecisionId()
                  .equals(decisions.get(1).bindingDecisionId()))) {
        throw new IllegalArgumentException("Controlled region candidates must be independent");
      }
      String caller = "spiffe://firemud/ns/" + namespace + "/sa/game-session-service";
      var pki =
          net.firedevops.firemud.test.LoopbackMtlsTestPki.create(
              directory,
              "Controlled final component source CA",
              new net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec(
                  "client", caller, false),
              new net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec(
                  "world",
                  "spiffe://firemud/ns/" + namespace + "/sa/world-management-service",
                  true),
              new net.firedevops.firemud.test.LoopbackMtlsTestPki.IdentitySpec(
                  "entity",
                  "spiffe://firemud/ns/" + namespace + "/sa/entity-management-service",
                  true));
      String bindingJson =
          dsl.fetchSingle(
                  "SELECT complete_launch_binding_evidence::text FROM game_session_canonical_instance_launch"
                      + " WHERE game_instance_uuid = ?",
                  identity.gameInstanceId())
              .get(0, String.class);
      var seededBinding =
          tools.jackson.databind.json.JsonMapper.builder()
              .build()
              .readValue(
                  bindingJson,
                  net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence.class);
      var descriptor = seededBinding.descriptor();
      // The shared GS runtime deliberately retains a minimal schema-1 launch release. It has no
      // World selector. Decode the separately retained complete publication fixture instead and
      // construct a distinct controlled World lifecycle binding through the canonical APIs. No
      // seeded launch bytes or receipt are rewritten or claimed as genuine World publication.
      byte[] policyBytes =
          java.util.Objects.requireNonNull(
              dsl.fetchSingle(
                      "SELECT complete_policy_set_evidence FROM game_session_canonical_realm_catalog"
                          + " WHERE target_namespace = ? AND canonical_tenant_id = ? AND realm_id = ?",
                      namespace,
                      identity.tenantId(),
                      target.realmId())
                  .get(0, byte[].class),
              "Controlled complete publication fixture is required");
      var publishedSet =
          net.firedevops.firemud.common.gamedesign.RealmPolicyPublishedEvidenceCodec.decode(
              policyBytes);
      var publishedRelease =
          net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence.fromStored(
                  publishedSet.terminalEvidenceBytes())
              .releaseContent();
      var worldSelector = publishedRelease.worldStartLocationEvidence();
      var participants =
          publishedRelease.participantDigests().stream()
              .map(
                  participant ->
                      new net.firedevops.firemud.common.gamedesign
                          .AuthoredWorldReleaseAttestationEvidence.Participant(
                          participant.participantKey(),
                          participant.scopeValue(),
                          false,
                          null,
                          participant.appliedCommitId(),
                          participant.contentDigest(),
                          participant.digestSchemaVersion(),
                          participant.abilitySchemaDigest() != null,
                          participant.abilitySchemaDigest()))
              .toList();
      var release =
          net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence.create(
              descriptor.targetNamespace(),
              descriptor.resultDigest(),
              descriptor.canonicalTenantId(),
              publishedRelease.canonicalVersionId(),
              descriptor.worldSlug(),
              descriptor.authoredWorldSourceOperationId(),
              descriptor.authoredWorldSourceEvidenceDigest(),
              descriptor.launchDescriptorId(),
              descriptor.publishedReleaseBundleRef(),
              descriptor.versionStateEpoch(),
              publishedRelease.publishWorkflowId(),
              worldSelector.request().appliedCommitId(),
              participants,
              publishedRelease.manifestHash(),
              publishedRelease.manifestSchemaVersion(),
              publishedRelease.requiredManifestAssetKeys(),
              publishedRelease.artifactDigests(),
              publishedRelease.commandDefinitions(),
              descriptor.generationConfigRevision(),
              worldSelector);
      var selector =
          net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence.fromStored(
              worldSelector.selectorReceiptBytes());
      var wireTarget =
          net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
              .newBuilder()
              .setCanonicalTenantUuid(identity.tenantId().toString())
              .setRealmUuid(target.realmId().toString())
              .setWorldSlug(target.worldSlug())
              .setRealmSlug(target.realmSlug())
              .setGameInstanceId(identity.gameInstanceId().toString())
              .setCatalogRevision(target.catalogRevision())
              .setCanonicalVersionUuid(target.canonicalVersionId().toString())
              .setFrozenPolicyDigest("a".repeat(64))
              .setPublishedReleaseBundleRef(descriptor.publishedReleaseBundleRef())
              .setPlayableStateNamespaceUuid(identity.playableStateNamespaceId().toString())
              .setPlayableStateScope(
                  net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                      .PLAYABLE_STATE_SCOPE_SHARED)
              .build();
      UUID canonicalRegion = UUID.randomUUID();
      var lifecycleRequestTemplate =
          new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request(
              1,
              UUID.randomUUID(),
              namespace,
              identity.tenantId(),
              target.worldSlug(),
              identity.gameInstanceId(),
              identity.playableStateNamespaceId(),
              "SHARED",
              true,
              descriptor.controlPlaneRequestId(),
              target.canonicalVersionId(),
              descriptor.requestDigest(),
              descriptor.resultDigest(),
              release.evidenceDigest());
      var binding =
          new net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence(
              descriptor, release);
      var lifecycleEvidenceTemplate =
          new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
              lifecycleRequestTemplate,
              binding,
              selector.startLocation(),
              1042L,
              "ACTIVE",
              target.activeWorldEpoch(),
              0L,
              UUID.randomUUID(),
              selector.graphDigest().substring(7),
              "sha256:" + "e".repeat(64),
              Map.of(canonicalRegion, identity.regionId()));
      var bindings =
          java.util.stream.IntStream.range(0, decisions.size())
              .mapToObj(
                  index ->
                      controlledBinding(
                          decisions.get(index),
                          selector,
                          wireTarget,
                          canonicalRegion,
                          lifecycleRequestTemplate,
                          lifecycleEvidenceTemplate,
                          7301L + index))
              .toList();
      var bindingByRequest =
          new java.util.HashMap<
              net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest,
              ControlledBinding>();
      for (var controlledBinding : bindings) {
        bindingByRequest.put(controlledBinding.request(), controlledBinding);
      }
      var bindingByLifecycleRequest =
          new java.util.HashMap<
              net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request,
              ControlledBinding>();
      for (var controlledBinding : bindings) {
        bindingByLifecycleRequest.put(controlledBinding.lifecycleRequest(), controlledBinding);
      }
      request = bindings.getFirst().request();
      requestsByTransition =
          bindings.stream()
              .collect(
                  java.util.stream.Collectors.toUnmodifiableMap(
                      item -> item.decision().bindingDecisionId(), ControlledBinding::request));
      var worldPlacementService =
          new net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationServiceGrpc
              .CanonicalInitialPlayerLocationServiceImplBase() {
            @Override
            public void readInitialPlayerLocationRegion(
                net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest
                    incoming,
                io.grpc.stub.StreamObserver<
                        net.firedevops.firemud.worldmanagement.v1
                            .CanonicalInitialPlayerLocationRegionResponse>
                    response) {
              var controlled = bindingByRequest.get(incoming);
              if (controlled == null || !isPeer(caller)) {
                response.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
                return;
              }
              response.onNext(controlled.observation());
              response.onCompleted();
            }
          };
      var worldLifecycleService =
          new net.firedevops.firemud.worldmanagement.v1
              .WorldCanonicalInstanceLifecycleReadServiceGrpc
              .WorldCanonicalInstanceLifecycleReadServiceImplBase() {
            @Override
            public void readWorldCanonicalInstanceLifecycle(
                net.firedevops.firemud.worldmanagement.v1.ReadWorldCanonicalInstanceLifecycleRequest
                    incoming,
                io.grpc.stub.StreamObserver<
                        net.firedevops.firemud.worldmanagement.v1
                            .ReadWorldCanonicalInstanceLifecycleResponse>
                    response) {
              var incomingRequest =
                  net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec
                      .fromRequest(incoming);
              var controlled = bindingByLifecycleRequest.get(incomingRequest);
              if (!isPeer(caller) || controlled == null) {
                response.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
                return;
              }
              response.onNext(
                  net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleGrpcCodec
                      .toResponse(incomingRequest, controlled.lifecycleEvidence()));
              response.onCompleted();
            }
          };
      var entityService =
          new net.firedevops.firemud.entitymanagement.v1
              .CanonicalPreseededActorAssignmentServiceGrpc
              .CanonicalPreseededActorAssignmentServiceImplBase() {
            @Override
            public void readPreseededActorRuntimeReference(
                net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest
                    incoming,
                io.grpc.stub.StreamObserver<
                        net.firedevops.firemud.entitymanagement.v1
                            .ReadPreseededActorRuntimeReferenceResponse>
                    response) {
              var controlled =
                  bindings.stream()
                      .filter(item -> item.matchesEntityRequest(incoming))
                      .findFirst()
                      .orElse(null);
              if (!isPeer(caller) || controlled == null) {
                response.onError(io.grpc.Status.PERMISSION_DENIED.asRuntimeException());
                return;
              }
              response.onNext(
                  net.firedevops.firemud.entitymanagement.v1
                      .ReadPreseededActorRuntimeReferenceResponse.newBuilder()
                      .setSchemaVersion(1)
                      .setExpectedTarget(controlled.wireTarget())
                      .setAssignmentUuid(incoming.getAssignmentUuid())
                      .setCanonicalAccountUuid(incoming.getCanonicalAccountUuid())
                      .setCharacterUuid(incoming.getCharacterUuid())
                      .setIntentDigest(incoming.getIntentDigest())
                      .setPublishedOwnerProofDigest(
                          controlled.decision().targetEvidence().ownerProofDigest().substring(7))
                      .setRuntimeEntityId(controlled.runtimeEntityId())
                      .setProjectionGeneration(1L)
                      .setProjectionFence(1L)
                      .setCorePayload(
                          net.firedevops.firemud.entitymanagement.v1
                              .PreseededActorAssignmentCorePayload.newBuilder()
                              .setActorKind(
                                  net.firedevops.firemud.entitymanagement.v1
                                      .CanonicalGameplayRosterActorKind
                                      .CANONICAL_ROSTER_ACTOR_KIND_PLAYER)
                              .setDisplayName("Controlled Final Component Player"))
                      .build());
              response.onCompleted();
            }
          };
      worldServer = server(pki, "world", worldPlacementService, worldLifecycleService);
      entityServer = server(pki, "entity", entityService);
      var endpoints = new net.firedevops.firemud.common.config.ServiceEndpointsProperties();
      endpoints.setWorldManagementService("localhost:" + worldServer.getPort());
      endpoints.setEntityManagementService("localhost:" + entityServer.getPort());
      var tls = pki.clientProperties(directory, pki.identity("client"));
      var channels = new net.firedevops.firemud.common.grpc.GrpcChannelFactory();
      placement =
          new net.firedevops.firemud.gamesession.client.CanonicalInitialPlayerLocationClient(
              endpoints, tls, channels, namespace);
      lifecycle =
          new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleClient(
              endpoints, tls, channels, namespace);
      entity =
          new net.firedevops.firemud.gamesession.client
              .CanonicalPreseededActorRuntimeReferenceClient(endpoints, tls, channels, namespace);
      placement.init();
      lifecycle.init();
      entity.init();
      resolver =
          new net.firedevops.firemud.gamesession.service
              .CanonicalInitialPlayerLocationRegionResolver(
              placement,
              new net.firedevops.firemud.gamesession.service
                  .CanonicalRegionBootstrapAssignmentResolver(lifecycle));
    }

    net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request(
        UUID transitionId) {
      var result = requestsByTransition.get(transitionId);
      if (result == null) throw new IllegalArgumentException("Unknown controlled transition");
      return result;
    }

    private static ControlledBinding controlledBinding(
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision decision,
        net.firedevops.firemud.common.world.WorldDraftStartLocationEvidence selector,
        net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
            wireTarget,
        UUID canonicalRegion,
        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request
            lifecycleRequestTemplate,
        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
            lifecycleEvidenceTemplate,
        long runtimeEntityId) {
      var target = decision.targetEvidence();
      var identity = decision.candidate();
      UUID placementId = UUID.randomUUID();
      UUID assignment = UUID.randomUUID();
      var request =
          net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest
              .newBuilder()
              .setSchemaVersion(1)
              .setPlacementOperationUuid(placementId.toString())
              .setExpectedTarget(wireTarget)
              .setCanonicalAccountUuid(identity.accountId().toString())
              .setCharacterUuid(identity.characterId().toString())
              .setAssignmentUuid(assignment.toString())
              .setAssignmentIntentDigest("b".repeat(64))
              .setInitialAdmissionHoldUuid(target.holdId().toString())
              .setInitialAdmissionHoldFence(target.holdFence().toString())
              .setInitialAdmissionRequestId(target.initialAdmissionRequestId())
              .setInitialAdmissionRequestDigest(target.initialAdmissionRequestDigest())
              .setActiveLifecycleEpoch(target.activeWorldEpoch())
              .setTerminalProofDigest(target.ownerProofDigest())
              .setCommittedPointerVersion(target.pointerVersion())
              .setAuditEventId(target.auditEventId())
              .build();
      var lifecycleRequest =
          new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request(
              lifecycleRequestTemplate.schemaVersion(),
              net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
                  .initialLocationReadRequestId(placementId),
              lifecycleRequestTemplate.targetNamespace(),
              lifecycleRequestTemplate.canonicalTenantId(),
              lifecycleRequestTemplate.worldSlug(),
              lifecycleRequestTemplate.canonicalGameInstanceId(),
              lifecycleRequestTemplate.playableStateNamespaceId(),
              lifecycleRequestTemplate.playableStateScope(),
              lifecycleRequestTemplate.publicProduction(),
              lifecycleRequestTemplate.controlPlaneRequestId(),
              lifecycleRequestTemplate.canonicalVersionId(),
              lifecycleRequestTemplate.expectedDescriptorRequestDigest(),
              lifecycleRequestTemplate.expectedDescriptorResultDigest(),
              lifecycleRequestTemplate.expectedReleaseAttestationDigest());
      var lifecycleEvidence =
          new net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence(
              lifecycleRequest,
              lifecycleEvidenceTemplate.launchBinding(),
              lifecycleEvidenceTemplate.startLocation(),
              lifecycleEvidenceTemplate.runtimeRoomInstanceId(),
              lifecycleEvidenceTemplate.lifecycleStatus(),
              lifecycleEvidenceTemplate.lifecycleEpoch(),
              lifecycleEvidenceTemplate.rowVersion(),
              lifecycleEvidenceTemplate.captureId(),
              lifecycleEvidenceTemplate.graphSha256(),
              lifecycleEvidenceTemplate.preparationInputDigest(),
              lifecycleEvidenceTemplate.operationalRegionAssignments());
      var room = selector.startLocation();
      var placementResult =
          net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationResponse
              .newBuilder()
              .setSchemaVersion(1)
              .setPlacementOperationUuid(placementId.toString())
              .setRequestDigest("sha256:" + "d".repeat(64))
              .setCanonicalAccountUuid(request.getCanonicalAccountUuid())
              .setCharacterUuid(request.getCharacterUuid())
              .setAssignmentUuid(request.getAssignmentUuid())
              .setAssignmentIntentDigest(request.getAssignmentIntentDigest())
              .setResultDigest("sha256:" + "f".repeat(64))
              .setOutcome(
                  net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationOutcome
                      .CANONICAL_INITIAL_PLAYER_LOCATION_OUTCOME_APPLIED)
              .setStartLocation(
                  net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRoom
                      .newBuilder()
                      .setCanonicalTenantUuid(room.tenantId().toString())
                      .setCanonicalVersionUuid(room.versionId().toString())
                      .setRoomTemplateUuid(room.roomTemplateId().toString()))
              .setPrivateRuntimeRoomKey("1042")
              .build();
      var observation =
          net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse
              .newBuilder()
              .setSchemaVersion(1)
              .setPlacementResult(placementResult)
              .setExpectedTarget(wireTarget)
              .setCanonicalRegionInstanceUuid(canonicalRegion.toString())
              .setOperationalRegionUuid(identity.regionId().toString())
              .setPrivateWorldInstanceKey(7L)
              .setObservedRuntimeRoomKey(1042L)
              .setLifecycle(
                  net.firedevops.firemud.worldmanagement.v1
                      .CanonicalInitialPlayerLocationLifecycleConsequence.newBuilder()
                      .setCanonicalRequestBytes(
                          com.google.protobuf.ByteString.copyFrom(
                              lifecycleRequest.canonicalBytes()))
                      .setLifecycleStatus("ACTIVE")
                      .setLifecycleEpoch(lifecycleEvidence.lifecycleEpoch())
                      .setRowVersion(lifecycleEvidence.rowVersion())
                      .setCaptureUuid(lifecycleEvidence.captureId().toString())
                      .setGraphSha256(lifecycleEvidence.graphSha256())
                      .setPreparationInputDigest(lifecycleEvidence.preparationInputDigest()))
              .build();
      return new ControlledBinding(
          decision,
          request,
          lifecycleRequest,
          lifecycleEvidence,
          observation,
          wireTarget,
          assignment,
          runtimeEntityId);
    }

    private record ControlledBinding(
        net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision decision,
        net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest request,
        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request
            lifecycleRequest,
        net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence
            lifecycleEvidence,
        net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRegionResponse
            observation,
        net.firedevops.firemud.entitymanagement.v1.PreseededActorAssignmentExpectedTarget
            wireTarget,
        UUID assignment,
        long runtimeEntityId) {
      boolean matchesEntityRequest(
          net.firedevops.firemud.entitymanagement.v1.ReadPreseededActorRuntimeReferenceRequest
              incoming) {
        var identity = decision.candidate();
        return incoming.getExpectedTarget().equals(wireTarget)
            && incoming.getAssignmentUuid().equals(assignment.toString())
            && incoming.getCanonicalAccountUuid().equals(identity.accountId().toString())
            && incoming.getCharacterUuid().equals(identity.characterId().toString())
            && incoming.getIntentDigest().equals(request.getAssignmentIntentDigest());
      }
    }

    private static boolean isPeer(String expected) {
      var peer = net.firedevops.firemud.common.grpc.GrpcPeerIdentity.current();
      return peer != null && expected.equals(peer.uri());
    }

    private static io.grpc.Server server(
        net.firedevops.firemud.test.LoopbackMtlsTestPki.Pki pki,
        String identity,
        io.grpc.BindableService... services)
        throws Exception {
      var factory =
          new net.firedevops.firemud.common.grpc.BoundedGrpcServerFactory(
              "127.0.0.1:0",
              List.of(),
              pki.keyManagerFactory(pki.identity(identity)),
              pki.trustManagerFactory(),
              io.grpc.TlsServerCredentials.ClientAuth.REQUIRE,
              net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy.ordinary(),
              net.firedevops.firemud.common.grpc.GrpcRequestBytePolicy.ORDINARY_MAXIMUM_BYTES);
      for (var service : services)
        factory.addService(
            io.grpc.ServerInterceptors.intercept(
                service, new net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor()));
      return factory.createServer().start();
    }

    @Override
    public void close() throws Exception {
      try {
        entity.close();
      } finally {
        try {
          placement.close();
        } finally {
          try {
            lifecycle.close();
          } finally {
            worldServer.shutdownNow();
            entityServer.shutdownNow();
            if (!worldServer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
                || !entityServer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
              throw new IllegalStateException("Controlled source servers did not terminate");
            }
          }
        }
      }
    }
  }

  @Override
  public void close() {
    credential.close();
    Arrays.fill(handle, (byte) 0);
  }
}
