package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerPreimage;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerBootstrapMemberOperationRepository.TrustedStableIncarnation;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedAccountIssuerCheckpoint;
import net.firedevops.firemud.gamesession.repository.CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared actual-owner fixtures for issuer bootstrap and ordinary NEW integration proof. */
final class CanonicalGameplayIssuerTestFixtures {
  private CanonicalGameplayIssuerTestFixtures() {}

  static String schemaName(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  static DriverManagerDataSource dataSource(
      String jdbcUrl, String username, String password, String schema) {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String separator = jdbcUrl.contains("?") ? "&" : "?";
    dataSource.setUrl(jdbcUrl + separator + "currentSchema=" + schema);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    return dataSource;
  }

  static void migrateSchema(
      DriverManagerDataSource dataSource,
      String schema,
      String migrationLocation,
      String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .schemas(schema)
            .defaultSchema(schema)
            .table("flyway_schema_history")
            .locations(migrationLocation);
    if (targetVersion != null) configuration.target(MigrationVersion.fromVersion(targetVersion));
    configuration.load().migrate();
  }

  static void dropSchema(
      DriverManagerDataSource publicDataSource, String schema, String failureMessage) {
    try (Connection connection = publicDataSource.getConnection()) {
      DSL.using(connection, SQLDialect.POSTGRES)
          .execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
    } catch (SQLException failure) {
      throw new IllegalStateException(failureMessage, failure);
    }
  }

  static RedisClient createRedisClient(String host, int port, String username, String password) {
    RedisURI.Builder builder = RedisURI.Builder.redis(host, port);
    if (username != null) builder.withAuthentication(username, password);
    RedisURI uri = builder.build();
    uri.setTimeout(Duration.ofSeconds(5));
    RedisClient client = RedisClient.create(uri);
    client.setOptions(
        ClientOptions.builder()
            .autoReconnect(false)
            .protocolVersion(io.lettuce.core.protocol.ProtocolVersion.RESP2)
            .build());
    return client;
  }

  static TrustedLayoutDescriptor descriptor(
      String issuerId, BigInteger version, BigInteger partitionCount, BigInteger capacity) {
    String exact =
        "issuer-layout-descriptor/v1|"
            + issuerId
            + "|"
            + version
            + "|"
            + partitionCount
            + "|"
            + capacity
            + "|"
            + CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    return new TrustedLayoutDescriptor(
        issuerId,
        version,
        partitionCount,
        capacity,
        CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
        exact.getBytes(StandardCharsets.UTF_8));
  }

  static TrustedAccountIssuerCheckpoint sourceCheckpoint(String issuerId, BigInteger generation) {
    long sequence = generation.longValueExact() - 1L;
    var event =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new IssuerPreimage(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                AccountAuthoritySourceEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuerId,
                Long.toString(sequence),
                issuerId,
                generation.toString(),
                generation.toString(),
                "ISSUER_SECURITY_CHANGE"));
    byte[] exactEvent = event.canonicalJsonUtf8();
    return new TrustedAccountIssuerCheckpoint(issuerId, generation, exactEvent, exactEvent);
  }

  static AccountIssuerSourceCheckpoint checkpointShape(String issuerId, BigInteger generation) {
    var trusted = sourceCheckpoint(issuerId, generation);
    byte[] checkpoint = trusted.checkpointBytes();
    byte[] event = trusted.latestEventBytes();
    return new AccountIssuerSourceCheckpoint(
        trusted.issuerId(),
        trusted.issuerAuthGeneration(),
        checkpoint,
        CanonicalGameplayIssuerMemberV2Codec.sha256(checkpoint),
        event,
        CanonicalGameplayIssuerMemberV2Codec.sha256(event));
  }

  static CanonicalGameplayIssuerMemberV2Value.Incarnation stableIncarnation(String seed) {
    String digest = digest(seed.getBytes(StandardCharsets.UTF_8));
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-deployment-" + seed,
            "deployment-generation-" + seed,
            List.of("controlled-node-" + seed),
            "controlled-redis-build-" + seed,
            "controlled-lua-registry-" + seed,
            digest,
            "READY",
            "boot-" + seed,
            "startup-" + seed,
            "storage-" + seed,
            "aof-" + seed);
    return new CanonicalGameplayIssuerMemberV2Value.Incarnation(
        "controlled-attestor-" + seed, digest, deployment, digest, digest);
  }

  static TrustedStableIncarnation trustedStableIncarnation(String seed) {
    var incarnation = stableIncarnation(seed);
    return new TrustedStableIncarnation(
        incarnation.attestorBinding(),
        incarnation.acceptedAttestationDigest(),
        incarnation.targetDeploymentEvidence(),
        incarnation.coordOpsRoAclDigest(),
        incarnation.coordRedisConfigObservedDigest());
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static AccountGameplayAdmissionLeaseEvidence controlledLeaseEvidence(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      CanonicalPlayableTarget target) {
    return controlledLeaseEvidence(
        candidate,
        bindingGeneration,
        target,
        new ControlledLeaseEvidenceIdentity(
            candidate.issuerId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID()));
  }

  static AccountGameplayAdmissionLeaseEvidence controlledLeaseEvidence(
      CanonicalGameplayBindingIdentity candidate,
      BigInteger bindingGeneration,
      CanonicalPlayableTarget target,
      ControlledLeaseEvidenceIdentity identity) {
    long now = System.currentTimeMillis();
    String nowSeconds = Long.toString(now / 1000L);
    Map<String, Object> carrier = new java.util.LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("leaseKind", AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING.name());
    carrier.put("targetNamespace", target.targetNamespace());
    carrier.put(
        "callerWorkload",
        "spiffe://firemud/ns/" + target.targetNamespace() + "/sa/game-session-service");
    carrier.put("requestId", identity.requestId().toString());
    carrier.put("leaseId", identity.leaseId().toString());
    carrier.put("leaseFence", "1");
    carrier.put(
        "bindingScope",
        Map.ofEntries(
            Map.entry("accountId", candidate.accountId().toString()),
            Map.entry("tenantId", candidate.tenantId().toString()),
            Map.entry("realmId", target.realmId().toString()),
            Map.entry("worldSlug", target.worldSlug()),
            Map.entry("realmSlug", target.realmSlug()),
            Map.entry("playableStateNamespaceId", target.playableStateNamespaceId().toString()),
            Map.entry("playableStateScope", target.playableStateScope()),
            Map.entry("gameInstanceId", target.canonicalGameInstanceId().toString()),
            Map.entry("characterId", candidate.characterId().toString()),
            Map.entry("sessionId", candidate.sessionId()),
            Map.entry("bindingGeneration", bindingGeneration.toString()),
            Map.entry("catalogRevision", Long.toString(target.catalogRevision())),
            Map.entry("pointerVersion", Long.toString(target.pointerVersion())),
            Map.entry("regionId", candidate.regionId().toString()),
            Map.entry("regionEpoch", candidate.regionEpoch().toString())));
    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", candidate.issuerAuthGeneration().toString(),
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(candidate.tenantId().toString(), "1"),
            "membershipAuthorityGeneration", Map.of(candidate.tenantId().toString(), "1"),
            "privateRealmGrantVersions", List.of()));
    carrier.put("issuanceFence", "1");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState", "ACTIVE",
            "membershipVersion", Map.of(candidate.tenantId().toString(), "1"),
            "membershipAuthorityGeneration", "1"));
    String prefix = "account:auth-authority:v1:";
    List<Map<String, Object>> checkpoints = new java.util.ArrayList<>();
    for (String stream :
        List.of(
            "account/" + candidate.accountId(),
            "issuer/" + identity.issuerId(),
            "membership/" + candidate.accountId() + "/" + candidate.tenantId(),
            "tenant/" + candidate.tenantId())) {
      checkpoints.add(
          Map.of(
              "outboxStreamKey",
              prefix + stream,
              "outboxSequence",
              stream.startsWith("membership/") || stream.startsWith("tenant/") ? "1" : "0"));
    }
    carrier.put("outboxCheckpoints", checkpoints);
    carrier.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", candidate.accountId().toString()),
            Map.entry("operationId", identity.operationId().toString()),
            Map.entry("issuanceRequestId", identity.issuanceRequestId().toString()),
            Map.entry("tokenJti", identity.tokenJti().toString()),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("issuedAt", nowSeconds),
            Map.entry("notBefore", nowSeconds),
            Map.entry("expiresAt", Long.toString(now / 1000L + 60L))));
    carrier.put("evaluatedAt", Long.toString(now));
    carrier.put(
        "expiresAt",
        Long.toString(now + AccountGameplayAdmissionLeaseEvidence.MAX_DURATION_MILLIS));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  record ControlledLeaseEvidenceIdentity(
      String issuerId,
      UUID requestId,
      UUID leaseId,
      UUID operationId,
      UUID issuanceRequestId,
      UUID tokenJti) {}

  static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "controlled-cluster-uid",
            "controlled-namespace-uid",
            "controlled-producer-pod-uid",
            "controlled-container-id",
            "controlled-node-uid",
            "123456789",
            42L,
            "controlled-postgres-volume-uid",
            "controlled-redis-run-id",
            "controlled-redis-volume-uid");
    var fencedCohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return storageIdentity;
          }

          @Override
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storageIdentity.equals(expected)) {
              throw new IllegalStateException("Controlled fixture storage identity changed");
            }
          }
        };
    var owner =
        new CanonicalGameplayLegacyMigrationOwner(
            migration,
            inventory,
            () -> fencedCohort,
            ignored ->
                new CanonicalGameplayLegacyMigrationSourceSnapshot(
                    EnumSet.of(
                        Family.TENANT_SESSION_CONTEXT,
                        Family.SESSION_ALIAS_CONTEXT,
                        Family.GAMEPLAY_IDENTITY_CONTEXT,
                        Family.GAMEPLAY_NAME_CONTEXT,
                        Family.MOVEMENT_EFFECT,
                        Family.DURABLE_EFFECT),
                    List.of()),
            new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
              @Override
              public void rebuildExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                  CanonicalGameplayLegacyMigrationSourceSnapshot source,
                  CanonicalGameplayBindingInventorySnapshot canonical) {
                assertThat(canonical.bindings()).isEmpty();
                assertThat(canonical.reservations()).isEmpty();
              }

              @Override
              public CanonicalGameplayLegacyMigrationReadback readBackExact(
                  CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                  CanonicalGameplayBindingInventorySnapshot canonical) {
                assertThat(canonical.bindings()).isEmpty();
                return new CanonicalGameplayLegacyMigrationReadback(
                    List.of(), List.of(), List.of(), List.of());
              }
            });
    assertThat(owner.migrate().state())
        .isEqualTo(CanonicalGameplayLegacyMigrationOperation.State.VERIFIED);
  }
}
