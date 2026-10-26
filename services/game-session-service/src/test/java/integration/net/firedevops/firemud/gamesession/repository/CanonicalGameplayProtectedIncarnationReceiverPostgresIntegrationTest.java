package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.test.TestContainerImages;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uses actual SQL intent/target reads with a controlled local signer.
 *
 * <p>The controlled key/channel proves only the verifier's local current-observation algorithm. It
 * is not operational attestor custody or evidence that a real deployment has this incarnation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayProtectedIncarnationReceiverPostgresIntegrationTest {
  private static final String MIGRATION_LOCATION =
      "filesystem:" + Path.of("src/main/resources/db/migration").toAbsolutePath().normalize();
  private static final String KEY_ID = "controlled-attestor-1";
  private static final String ATTESTOR_BINDING = "controlled-local-attestor-binding";
  private static final String ACCEPTED_ATTESTATION_DIGEST = "sha256:" + "a".repeat(64);
  private static final String ACL_DIGEST = "sha256:" + "b".repeat(64);
  private static final String CONFIG_DIGEST = "sha256:" + "c".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Test
  void verifiesOnlySignedExactCurrentTargetAndRejectsReplayAndMismatches() throws Exception {
    try (Fixture fixture = Fixture.open()) {
      ControlledSigner channel = new ControlledSigner(fixture.keyPair());
      var receiver = fixture.receiver(channel);

      var verified = receiver.observeForFenceTarget(fixture.intent().operationId(), 0L);

      assertThat(verified.operationId()).isEqualTo(fixture.intent().operationId());
      assertThat(verified.accountId()).isEqualTo(fixture.intent().accountId());
      assertThat(verified.tenantId()).isEqualTo(fixture.targetTenantId());
      assertThat(verified.playableStateNamespaceId()).isEqualTo(fixture.targetNamespaceId());
      assertThat(verified.plannedAccountAdmissionFence())
          .isEqualTo(fixture.intent().plannedAccountAdmissionFence());
      assertThat(verified.operationFence()).isEqualTo(fixture.intent().operationFence().toString());
      assertThat(verified.cohortDigest()).isEqualTo(fixture.intent().cohortDigest());
      assertThat(verified.barrierTargetDigest()).isEqualTo(fixture.intent().barrierTargetDigest());
      assertThat(verified.acceptedAttestationDigest()).isEqualTo(ACCEPTED_ATTESTATION_DIGEST);
      assertThat(verified.evidence()).isEqualTo(fixture.expectedEvidence());
      assertThat(verified.expiresAtEpochMillis()).isEqualTo(NOW.toEpochMilli() + 30_000L);
      assertThat(verified.payloadDigest()).startsWith("sha256:");
      assertThat(verified.incarnationDigest()).startsWith("sha256:");

      var repeated = receiver.observeForFenceTarget(fixture.intent().operationId(), 0L);
      assertThat(repeated.observationNonce()).isNotEqualTo(verified.observationNonce());
      assertThat(repeated.payloadDigest()).isNotEqualTo(verified.payloadDigest());
      assertThat(repeated.incarnationDigest()).isEqualTo(verified.incarnationDigest());

      assertThat(
              java.lang.reflect.Modifier.isPrivate(
                  CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation.class
                      .getDeclaredConstructors()[0].getModifiers()))
          .isTrue();

      var restartedReceiver = fixture.receiver(request -> channel.lastSigned());
      assertThatThrownBy(
              () -> restartedReceiver.observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);

      for (Mutation mutation :
          List.of(
              Mutation.WRONG_NONCE,
              Mutation.WRONG_OPERATION_ID,
              Mutation.WRONG_OPERATION_FENCE,
              Mutation.WRONG_STORAGE_GENERATION,
              Mutation.FOREIGN_ACCOUNT,
              Mutation.FOREIGN_TENANT,
              Mutation.FOREIGN_NAMESPACE,
              Mutation.EXPIRED_REQUEST_WINDOW,
              Mutation.FUTURE_OBSERVATION_TIME,
              Mutation.UNKNOWN_FIELD,
              Mutation.TOO_MANY_NODES,
              Mutation.TRAILING_TOKEN,
              Mutation.NON_CANONICAL_BYTES,
              Mutation.OVERSIZED,
              Mutation.WRONG_KEY_ID,
              Mutation.BAD_SIGNATURE)) {
        ControlledSigner invalid = new ControlledSigner(fixture.keyPair(), mutation, null);
        assertThatThrownBy(
                () ->
                    fixture
                        .receiver(invalid)
                        .observeForFenceTarget(fixture.intent().operationId(), 0L))
            .isInstanceOf(
                CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
      }

      assertThatThrownBy(
              () ->
                  fixture.receiver(null).observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);

      var missingPinnedKeys = fixture.configuration(Map.of(), fixture.expectedEvidence());
      assertThatThrownBy(
              () ->
                  fixture
                      .receiver(channel, missingPinnedKeys)
                      .observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);

      assertThatThrownBy(
              () ->
                  fixture.configuration(
                      Map.of(
                          KEY_ID,
                          (java.security.interfaces.RSAPublicKey) fixture.keyPair().getPublic()),
                      null))
          .isInstanceOf(
              CanonicalGameplayProtectedIncarnationReceiver.ObservationUnavailableException.class);
    }
  }

  @Test
  void changedTrustedStorageGenerationChangesIncarnationDigestInControlledFixture()
      throws Exception {
    try (Fixture fixture = Fixture.open()) {
      // Both signed observations use this same SQL fixture. The local controlled signer does not
      // establish custody of the actual target storage generation.
      var trustedKeys =
          Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) fixture.keyPair().getPublic());
      var original = fixture.expectedEvidence();
      var changedEvidence =
          new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
              original.deploymentIdentity(),
              original.deploymentGeneration(),
              original.canonicalNodeSet(),
              original.redisBuildIdentity(),
              original.luaScriptRegistryVersion(),
              original.luaScriptRegistryDigest(),
              original.startupState(),
              original.bootGeneration(),
              original.startupGeneration(),
              "storage-controlled-fixture-8",
              original.aofGeneration());

      var originalVerified =
          fixture
              .receiver(new ControlledSigner(fixture.keyPair()))
              .observeForFenceTarget(fixture.intent().operationId(), 0L);
      var changedVerified =
          fixture
              .receiver(
                  new ControlledSigner(fixture.keyPair()),
                  fixture.configuration(trustedKeys, changedEvidence))
              .observeForFenceTarget(fixture.intent().operationId(), 0L);

      assertThat(changedVerified.evidence()).isEqualTo(changedEvidence);
      assertThat(changedVerified.operationFence()).isEqualTo(originalVerified.operationFence());
      assertThat(changedVerified.barrierTargetDigest())
          .isEqualTo(originalVerified.barrierTargetDigest());
      assertThat(changedVerified.incarnationDigest())
          .isNotEqualTo(originalVerified.incarnationDigest());
    }
  }

  @Test
  void changedCurrentSqlContextAfterSourceCallCannotProduceVerifiedEvidence() throws Exception {
    try (Fixture fixture = Fixture.open()) {
      ControlledSigner channel =
          new ControlledSigner(fixture.keyPair(), Mutation.NONE, fixture.dsl());
      assertThatThrownBy(
              () ->
                  fixture
                      .receiver(channel)
                      .observeForFenceTarget(fixture.intent().operationId(), 0L))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(channel.lastSigned()).isNotNull();
    }
  }

  @Test
  void initialArmVerifiesExactOriginalAndNonzeroNoActiveEpochWithSameTrustedVerifier()
      throws Exception {
    try (InitialFixture fixture = InitialFixture.open()) {
      ControlledSigner channel = new ControlledSigner(fixture.keyPair());
      var receiver = fixture.receiver(channel);

      var verified = receiver.observeForInitialAccountBarrier(fixture.original().requestId());

      assertThat(verified.ownerKind())
          .isEqualTo(
              CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER);
      assertThat(verified.requestId()).isEqualTo(fixture.original().requestId());
      assertThat(verified.originalCallerContextId()).isEqualTo(fixture.original().callerContextId());
      assertThat(verified.originalAttemptFence()).isEqualTo(17L);
      assertThat(verified.accountId()).isEqualTo(fixture.accountId());
      assertThat(verified.sourceControlState()).isEqualTo("NO_ACTIVE_ACCOUNT_WIDE_FLOW");
      assertThat(verified.sourceControlEpoch()).isEqualTo("8");
      assertThat(verified.acceptedAttestationDigest()).isEqualTo(ACCEPTED_ATTESTATION_DIGEST);
      assertThat(verified.evidence()).isEqualTo(fixture.expectedEvidence());

      var repeated = receiver.observeForInitialAccountBarrier(fixture.original().requestId());
      assertThat(repeated.observationNonce()).isNotEqualTo(verified.observationNonce());
      assertThat(repeated.incarnationDigest()).isEqualTo(verified.incarnationDigest());

      for (Mutation mutation : List.of(Mutation.WRONG_INITIAL_SOURCE_EPOCH, Mutation.WRONG_INITIAL_ATTEMPT)) {
        assertThatThrownBy(
                () ->
                    fixture
                        .receiver(new ControlledSigner(fixture.keyPair(), mutation, null))
                        .observeForInitialAccountBarrier(fixture.original().requestId()))
            .isInstanceOf(
                CanonicalGameplayProtectedIncarnationReceiver.ObservationRejectedException.class);
      }
    }
  }

  @Test
  void changedNoActiveEpochAfterInitialObservationCannotProduceVerifiedEvidence() throws Exception {
    try (InitialFixture fixture = InitialFixture.open()) {
      ControlledSigner channel =
          new ControlledSigner(fixture.keyPair(), Mutation.NONE, fixture.dsl());

      assertThatThrownBy(
              () ->
                  fixture
                      .receiver(channel)
                      .observeForInitialAccountBarrier(fixture.original().requestId()))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(channel.lastSigned()).isNotNull();
    }
  }

  private enum Mutation {
    NONE,
    WRONG_NONCE,
    WRONG_OPERATION_ID,
    WRONG_OPERATION_FENCE,
    WRONG_INITIAL_SOURCE_EPOCH,
    WRONG_INITIAL_ATTEMPT,
    WRONG_STORAGE_GENERATION,
    FOREIGN_ACCOUNT,
    FOREIGN_TENANT,
    FOREIGN_NAMESPACE,
    EXPIRED_REQUEST_WINDOW,
    FUTURE_OBSERVATION_TIME,
    UNKNOWN_FIELD,
    TOO_MANY_NODES,
    TRAILING_TOKEN,
    NON_CANONICAL_BYTES,
    OVERSIZED,
    WRONG_KEY_ID,
    BAD_SIGNATURE
  }

  private static final class ControlledSigner
      implements CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel {
    private final KeyPair keyPair;
    private final Mutation mutation;
    private final DSLContext contextMutationDsl;
    private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation lastSigned;

    private ControlledSigner(KeyPair keyPair) {
      this(keyPair, Mutation.NONE, null);
    }

    private ControlledSigner(KeyPair keyPair, Mutation mutation, DSLContext contextMutationDsl) {
      this.keyPair = keyPair;
      this.mutation = mutation;
      this.contextMutationDsl = contextMutationDsl;
    }

    @Override
    public CanonicalGameplayProtectedIncarnationReceiver.SignedObservation observe(
        CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
      if (contextMutationDsl != null) {
        if (request.context().ownerKind()
            == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER) {
          contextMutationDsl.execute(
              "UPDATE game_session_canonical_account_coverage_control"
                  + " SET last_operation_fence = 9 WHERE account_id = ?",
              request.context().accountId());
        } else {
          contextMutationDsl.execute(
              "UPDATE game_session_canonical_account_admission_fence_intent"
                  + " SET cohort_digest = ? WHERE operation_id = ?",
              "sha256:" + "f".repeat(64),
              request.context().operationId());
        }
      }
      Map<String, Object> root = payload(request);
      switch (mutation) {
        case WRONG_NONCE -> root.put("observationNonce", "A".repeat(43));
        case WRONG_OPERATION_ID -> {
          String field =
              request.context().ownerKind()
                      == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER
                  ? "initializerId"
                  : "operationId";
          asMap(root.get("owner")).put(field, UUID.randomUUID().toString());
        }
        case WRONG_OPERATION_FENCE -> {
          String field =
              request.context().ownerKind()
                      == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER
                  ? "sourceControlEpoch"
                  : "operationFence";
          asMap(root.get("owner")).put(field, "999999");
        }
        case WRONG_INITIAL_SOURCE_EPOCH ->
            asMap(root.get("owner")).put("sourceControlEpoch", "9");
        case WRONG_INITIAL_ATTEMPT ->
            asMap(root.get("owner")).put("originalAttemptFence", 18L);
        case WRONG_STORAGE_GENERATION ->
            asMap(root.get("targetDeploymentEvidence"))
                .put("storageGeneration", "storage-untrusted-response");
        case FOREIGN_ACCOUNT ->
            asMap(root.get("owner")).put("accountId", UUID.randomUUID().toString());
        case FOREIGN_TENANT ->
            asMap(root.get("target")).put("tenantId", UUID.randomUUID().toString());
        case FOREIGN_NAMESPACE ->
            asMap(root.get("target")).put("playableStateNamespaceId", UUID.randomUUID().toString());
        case EXPIRED_REQUEST_WINDOW ->
            root.put("observationExpiresAtEpochMillis", request.issuedAtEpochMillis());
        case FUTURE_OBSERVATION_TIME ->
            root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis() + 1_000L);
        case UNKNOWN_FIELD -> root.put("unexpected", "field");
        case TOO_MANY_NODES ->
            asMap(root.get("targetDeploymentEvidence"))
                .put("canonicalNodeSet", List.of("node-a", "node-b", "node-c"));
        default -> {
          // The other mutations are applied to the bytes or detached signature below.
        }
      }
      try {
        byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
        byte[] payload;
        if (mutation == Mutation.NON_CANONICAL_BYTES) {
          payload =
              ByteBuffer.allocate(canonical.length + 1).put(canonical).put((byte) ' ').array();
        } else if (mutation == Mutation.TRAILING_TOKEN) {
          byte[] suffix = " {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          payload =
              ByteBuffer.allocate(canonical.length + suffix.length)
                  .put(canonical)
                  .put(suffix)
                  .array();
        } else if (mutation == Mutation.OVERSIZED) {
          asMap(root.get("owner")).put("padding", "x".repeat(17_000));
          byte[] oversized = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
          payload = oversized;
        } else {
          payload = canonical;
        }
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(canonical);
        byte[] signature = signer.sign();
        if (mutation == Mutation.BAD_SIGNATURE) {
          signature[signature.length - 1] ^= 1;
        }
        String responseKeyId =
            mutation == Mutation.WRONG_KEY_ID ? "unconfigured-attestor-key" : KEY_ID;
        lastSigned =
            new CanonicalGameplayProtectedIncarnationReceiver.SignedObservation(
                responseKeyId, payload, signature);
        return lastSigned;
      } catch (Exception failure) {
        throw new IllegalStateException("Controlled test signer failed", failure);
      }
    }

    private CanonicalGameplayProtectedIncarnationReceiver.SignedObservation lastSigned() {
      return lastSigned;
    }
  }

  private static Map<String, Object> payload(
      CanonicalGameplayProtectedIncarnationReceiver.ObservationRequest request) {
    var context = request.context();
    var evidence = request.expectedEvidence();
    Map<String, Object> owner = new LinkedHashMap<>();
    boolean initial =
        context.ownerKind()
            == CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.INITIAL_ACCOUNT_BARRIER;
    if (initial) {
      owner.put("ownerKind", context.ownerKind().name());
      owner.put("initializerId", context.operationId().toString());
      owner.put("originalRequestId", context.requestId().toString());
      owner.put("originalCallerContextId", context.originalCallerContextId().toString());
      owner.put("originalAttemptFence", context.originalAttemptFence());
      owner.put("accountId", context.accountId().toString());
      owner.put("sourceControlState", context.sourceControlState());
      owner.put("sourceControlEpoch", context.sourceControlEpoch());
      owner.put("inventorySnapshotRevision", context.inventorySnapshotRevision());
      owner.put("accountLoginEvidenceDigest", context.accountLoginEvidenceDigest());
      owner.put("sourceControlDigest", context.sourceControlDigest());
      owner.put("targetDigest", context.barrierTargetDigest());
    } else {
      owner.put("operationId", context.operationId().toString());
      owner.put("requestId", context.requestId().toString());
      owner.put("accountId", context.accountId().toString());
      owner.put("operationFence", context.operationFence());
      owner.put("plannedAccountAdmissionFence", context.plannedAccountAdmissionFence().toString());
      owner.put("inventorySnapshotRevision", context.inventorySnapshotRevision());
      owner.put("cohortDigest", context.cohortDigest());
      owner.put("barrierTargetDigest", context.barrierTargetDigest());
    }

    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetOrdinal", context.targetOrdinal());
    target.put("tenantId", context.tenantId().toString());
    target.put("playableStateNamespaceId", context.playableStateNamespaceId().toString());
    target.put("tenantGameplayTag", context.tenantGameplayTag());
    target.put("workloadNamespace", context.workloadNamespace());
    target.put("allocationVersion", context.allocationVersion());

    Map<String, Object> targetEvidence = new LinkedHashMap<>();
    targetEvidence.put("deploymentIdentity", evidence.deploymentIdentity());
    targetEvidence.put("deploymentGeneration", evidence.deploymentGeneration());
    targetEvidence.put("canonicalNodeSet", evidence.canonicalNodeSet());
    targetEvidence.put("redisBuildIdentity", evidence.redisBuildIdentity());
    targetEvidence.put("luaScriptRegistryVersion", evidence.luaScriptRegistryVersion());
    targetEvidence.put("luaScriptRegistryDigest", evidence.luaScriptRegistryDigest());
    targetEvidence.put("startupState", evidence.startupState());
    targetEvidence.put("bootGeneration", evidence.bootGeneration());
    targetEvidence.put("startupGeneration", evidence.startupGeneration());
    targetEvidence.put("storageGeneration", evidence.storageGeneration());
    targetEvidence.put("aofGeneration", evidence.aofGeneration());

    Map<String, Object> root = new LinkedHashMap<>();
    root.put(
        "schema",
        initial
            ? "firemud.gs.protected-initial-account-barrier-read/v1"
            : "firemud.gs.protected-incarnation-read/v1");
    root.put("attestorBinding", request.attestorBinding());
    root.put("attestorKeyId", KEY_ID);
    root.put("acceptedAttestationDigest", request.acceptedAttestationDigest());
    root.put("observationNonce", request.nonce());
    root.put("observationIssuedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationObservedAtEpochMillis", request.issuedAtEpochMillis());
    root.put("observationExpiresAtEpochMillis", request.expiresAtEpochMillis());
    root.put("owner", owner);
    root.put("target", target);
    root.put("targetDeploymentEvidence", targetEvidence);
    root.put("coordOpsRoAclDigest", request.coordOpsRoAclDigest());
    root.put("coordRedisConfigObservedDigest", request.coordRedisConfigObservedDigest());
    return root;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }

  private static final class Fixture implements AutoCloseable {
    private final String schema;
    private final DSLContext dsl;
    private final CanonicalGameplayAccountAdmissionFenceIntent intent;
    private final UUID targetTenantId;
    private final UUID targetNamespaceId;
    private final String targetWorkloadNamespace;
    private final KeyPair keyPair;
    private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence;

    private Fixture(
        String schema,
        DSLContext dsl,
        CanonicalGameplayAccountAdmissionFenceIntent intent,
        UUID targetTenantId,
        UUID targetNamespaceId,
        String targetWorkloadNamespace,
        KeyPair keyPair,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
            expectedEvidence) {
      this.schema = schema;
      this.dsl = dsl;
      this.intent = intent;
      this.targetTenantId = targetTenantId;
      this.targetNamespaceId = targetNamespaceId;
      this.targetWorkloadNamespace = targetWorkloadNamespace;
      this.keyPair = keyPair;
      this.expectedEvidence = expectedEvidence;
    }

    private static Fixture open() throws Exception {
      String schema = "gs_incarnation_" + UUID.randomUUID().toString().replace("-", "");
      DriverManagerDataSource dataSource = dataSource(schema);
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      UUID tenantId = UUID.randomUUID();
      var runtime =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest
          .seedSyntheticVerifiedLegacyDisposition(dsl);

      UUID accountId = UUID.randomUUID();
      UUID transitionId = UUID.randomUUID();
      UUID characterId = UUID.randomUUID();
      String sessionId = UUID.randomUUID().toString();
      CanonicalGameplayBindingIdentity identity =
          new CanonicalGameplayBindingIdentity(
              accountId,
              runtime.canonicalTenantId(),
              runtime.playableStateNamespaceId(),
              characterId,
              "SHARED",
              runtime.gameInstanceUuid(),
              runtime.gameInstanceId(),
              sessionId,
              UUID.randomUUID(),
              BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.valueOf(16),
              BigInteger.valueOf(32));
      CanonicalIssuerReservationFenceEvidence reservation =
          new CanonicalIssuerReservationFenceEvidence(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE);
      var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
      inventory.prepare(
          new CanonicalGameplayBindingTransitionRequest(
              transitionId, UUID.randomUUID(), identity, reservation, null, null));
      CanonicalGameplayAccountAdmissionFenceIntent intent =
          new CanonicalGameplayAccountAdmissionBarrierRepository(dsl)
              .beginFenceIntent(accountId, UUID.randomUUID());

      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      var expectedEvidence =
          new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
              "controlled-target-deployment",
              "generation-7",
              List.of("redis-node-a"),
              "redis-test-build",
              "registry-test-v1",
              "sha256:" + "d".repeat(64),
              "READY",
              "boot-7",
              "startup-7",
              "storage-7",
              "aof-7");
      return new Fixture(
          schema,
          dsl,
          intent,
          runtime.canonicalTenantId(),
          runtime.playableStateNamespaceId(),
          runtime.targetNamespace(),
          keyPair,
          expectedEvidence);
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel) {
      return receiver(
          channel,
          configuration(
              Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) keyPair.getPublic()),
              expectedEvidence));
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel,
        CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration configuration) {
      return new CanonicalGameplayProtectedIncarnationReceiver(
          new CanonicalGameplayAccountAdmissionBarrierRepository(dsl),
          new CanonicalGameplayTenantGameplayTagRepository(dsl, targetWorkloadNamespace, 1),
          channel,
          configuration,
          Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration configuration(
        Map<String, java.security.interfaces.RSAPublicKey> keys,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence evidence) {
      return new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
          ATTESTOR_BINDING,
          ACCEPTED_ATTESTATION_DIGEST,
          keys,
          evidence,
          ACL_DIGEST,
          CONFIG_DIGEST,
          16 * 1024,
          1,
          Duration.ofSeconds(30));
    }

    private CanonicalGameplayAccountAdmissionFenceIntent intent() {
      return intent;
    }

    private UUID targetTenantId() {
      return targetTenantId;
    }

    private UUID targetNamespaceId() {
      return targetNamespaceId;
    }

    private KeyPair keyPair() {
      return keyPair;
    }

    private DSLContext dsl() {
      return dsl;
    }

    private CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence() {
      return expectedEvidence;
    }

    @Override
    public void close() {
      dropSchema(schema);
    }

  private static final class InitialFixture implements AutoCloseable {
    private final String schema;
    private final DSLContext dsl;
    private final InitialGameplayLoginAttemptCoordinates original;
    private final UUID accountId;
    private final UUID tenantId;
    private final UUID namespaceId;
    private final String targetWorkloadNamespace;
    private final KeyPair keyPair;
    private final CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence;
    private final CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository;
    private final CanonicalGameplayTenantGameplayTagRepository tenantTags;

    private InitialFixture(
        String schema,
        DSLContext dsl,
        InitialGameplayLoginAttemptCoordinates original,
        UUID accountId,
        UUID tenantId,
        UUID namespaceId,
        String targetWorkloadNamespace,
        KeyPair keyPair,
        CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
            expectedEvidence,
        CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository,
        CanonicalGameplayTenantGameplayTagRepository tenantTags) {
      this.schema = schema;
      this.dsl = dsl;
      this.original = original;
      this.accountId = accountId;
      this.tenantId = tenantId;
      this.namespaceId = namespaceId;
      this.targetWorkloadNamespace = targetWorkloadNamespace;
      this.keyPair = keyPair;
      this.expectedEvidence = expectedEvidence;
      this.initialRepository = initialRepository;
      this.tenantTags = tenantTags;
    }

    private static InitialFixture open() throws Exception {
      String schema = "gs_initial_incarnation_" + UUID.randomUUID().toString().replace("-", "");
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.postgresql.Driver");
      String jdbcUrl = postgres.getJdbcUrl();
      String separator = jdbcUrl.contains("?") ? "&" : "?";
      dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
      dataSource.setUsername(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      Flyway.configure()
          .dataSource(dataSource)
          .schemas(schema)
          .defaultSchema(schema)
          .table("flyway_schema_history")
          .locations(MIGRATION_LOCATION)
          .load()
          .migrate();
      DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);
      UUID tenantId = UUID.randomUUID();
      var runtime =
          CanonicalGameplayBindingRuntimeTestFixtures.seedRunningLaunch(dataSource, tenantId);
      CanonicalGameplayBindingInventoryRepositoryIntegrationTest
          .seedSyntheticVerifiedLegacyDisposition(dsl);

      UUID accountId = UUID.randomUUID();
      UUID transitionId = UUID.randomUUID();
      UUID characterId = UUID.randomUUID();
      var identity =
          new CanonicalGameplayBindingIdentity(
              accountId,
              runtime.canonicalTenantId(),
              runtime.playableStateNamespaceId(),
              characterId,
              "SHARED",
              runtime.gameInstanceUuid(),
              runtime.gameInstanceId(),
              UUID.randomUUID().toString(),
              UUID.randomUUID(),
              BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.valueOf(16),
              BigInteger.valueOf(32));
      var reservation =
          new CanonicalIssuerReservationFenceEvidence(
              UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigInteger.ONE, BigInteger.ONE, BigInteger.ONE);
      new CanonicalGameplayBindingInventoryRepository(dsl)
          .prepare(
              new CanonicalGameplayBindingTransitionRequest(
                  transitionId, UUID.randomUUID(), identity, reservation, null, null));
      dsl.execute(
          "UPDATE game_session_canonical_account_coverage_control"
              + " SET last_operation_fence = 8 WHERE account_id = ?",
          accountId);

      InitialGameplayLoginAttemptCoordinates original =
          new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 17L);
      var accountLogin = mockInitialLoginResult(original, accountId, UUID.randomUUID());
      dsl.execute(
          "INSERT INTO game_session_initial_gameplay_login_journal"
              + " (request_id, caller_context_id, expected_attempt_fence) VALUES (?, ?, ?)",
          original.requestId(),
          original.callerContextId(),
          original.expectedAttemptFence());
      dsl.execute(
          "UPDATE game_session_initial_gameplay_login_journal"
              + " SET verified_token_jti = ?, verified_token_sha256 = ?,"
              + " verified_token_generation = 1, verified_issuance_fence = 1"
              + " WHERE request_id = ?",
          accountLogin.evidence().tokenJti(),
          accountLogin.evidence().tokenSha256(),
          original.requestId());
      CanonicalGameplayAccountInitialBarrierInitializerRepository initialRepository =
          new CanonicalGameplayAccountInitialBarrierInitializerRepository(dsl);
      initialRepository.beginOrRead(
          original, accountLogin, runtime.canonicalTenantId(), runtime.playableStateNamespaceId());
      accountLogin.close();

      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair keyPair = generator.generateKeyPair();
      var expectedEvidence = controlledExpectedEvidence();
      var tenantTags =
          new CanonicalGameplayTenantGameplayTagRepository(
              dsl, runtime.targetNamespace(), 1);
      return new InitialFixture(
          schema,
          dsl,
          original,
          accountId,
          runtime.canonicalTenantId(),
          runtime.playableStateNamespaceId(),
          runtime.targetNamespace(),
          keyPair,
          expectedEvidence,
          initialRepository,
          tenantTags);
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel) {
      return receiver(
          channel,
          new CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration(
              ATTESTOR_BINDING,
              ACCEPTED_ATTESTATION_DIGEST,
              Map.of(KEY_ID, (java.security.interfaces.RSAPublicKey) keyPair.getPublic()),
              expectedEvidence,
              ACL_DIGEST,
              CONFIG_DIGEST,
              16 * 1024,
              1,
              Duration.ofSeconds(30)));
    }

    private CanonicalGameplayProtectedIncarnationReceiver receiver(
        CanonicalGameplayProtectedIncarnationReceiver.ProtectedObservationChannel channel,
        CanonicalGameplayProtectedIncarnationReceiver.TrustedConfiguration configuration) {
      return new CanonicalGameplayProtectedIncarnationReceiver(
          initialRepository, tenantTags, channel, configuration, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private InitialGameplayLoginAttemptCoordinates original() {
      return original;
    }

    private UUID accountId() {
      return accountId;
    }

    private KeyPair keyPair() {
      return keyPair;
    }

    private DSLContext dsl() {
      return dsl;
    }

    private CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
        expectedEvidence() {
      return expectedEvidence;
    }

    @Override
    public void close() {
      try (var connection =
              java.sql.DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException("Could not dispose INITIAL observation schema", failure);
      }
    }
  }

  private static CanonicalGameplayLoginResult mockInitialLoginResult(
      InitialGameplayLoginAttemptCoordinates original, UUID accountId, UUID tokenJti) {
    var evidence =
        new CanonicalGameplayLoginResult.NonSecretEvidence(
            accountId,
            original.requestId(),
            original.callerContextId(),
            tokenJti,
            "e".repeat(64),
            GameSessionAccountDelegationProfile.PROFILE,
            NOW.getEpochSecond(),
            NOW.getEpochSecond(),
            NOW.plusSeconds(120).getEpochSecond(),
            "1",
            "1",
            ByteString.copyFromUtf8("{}"),
            ByteString.copyFromUtf8("[]"),
            ByteString.copyFromUtf8("[]"));
    CanonicalGameplayLoginResult result = mock(CanonicalGameplayLoginResult.class);
    when(result.evidence()).thenReturn(evidence);
    return result;
  }

  private static
      CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence
          controlledExpectedEvidence() {
    return new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
        "controlled-target-deployment",
        "generation-7",
        List.of("redis-node-a"),
        "redis-test-build",
        "registry-test-v1",
        "sha256:" + "d".repeat(64),
        "READY",
        "boot-7",
        "startup-7",
        "storage-7",
        "aof-7");
  }

    private static DriverManagerDataSource dataSource(String schema) {
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.postgresql.Driver");
      String jdbcUrl = postgres.getJdbcUrl();
      String separator = jdbcUrl.contains("?") ? "&" : "?";
      dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
      dataSource.setUsername(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      return dataSource;
    }

    private static void dropSchema(String schema) {
      try (var connection =
              java.sql.DriverManager.getConnection(
                  postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
          var statement = connection.createStatement()) {
        statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      } catch (Exception failure) {
        throw new IllegalStateException(
            "Could not dispose protected incarnation test schema", failure);
      }
    }
  }
}
