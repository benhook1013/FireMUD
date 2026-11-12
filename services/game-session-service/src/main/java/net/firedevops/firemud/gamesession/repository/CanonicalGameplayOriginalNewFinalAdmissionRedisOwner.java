package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unregistered manual Redis owner for the final original-NEW session compare-and-set.
 *
 * <p>The caller supplies the complete flow evidence. This owner checks its exact target and OPEN
 * source values, performs one six-key tenant-slot CAS, then independently reads back the finalized
 * session, three installed indexes, and both unchanged OPEN barriers. A script reply alone never
 * yields a {@link Readback}; this component does not prove the complete Account or issuer flow.
 */
final class CanonicalGameplayOriginalNewFinalAdmissionRedisOwner {
  private static final String PRINCIPAL = "gamesession_coord_app";
  private static final int MAX_SCRIPT_BYTES = 48 * 1024;
  private static final int MAX_BARRIER_JSON_BYTES = 16 * 1024;
  private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
  private static final long MAX_CANONICAL_SESSION_DURATION_MILLIS =
      CanonicalGameplayOriginalNewAuthenticatedSession.MAX_CANONICAL_SESSION_DURATION_MILLIS;
  private static final int MAX_ISSUER_PAYLOAD_BYTES =
      CanonicalGameplayIssuerAdmissionBarrierValue.MAX_BYTES;
  private static final String SESSION_PREFIX = "session:game:";
  private static final String ACCOUNT_PREFIX = "session:game:account-admission:";
  private static final String ISSUER_PREFIX = "session:game:issuer-admission:";
  private static final Set<String> ACCOUNT_FIELDS =
      CanonicalGameplayAccountInitialBarrierValue.FIELDS;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private static final List<RedisScriptDescriptor.KeySpec> EXPECTED_KEYS =
      List.of(
          key("session", SESSION_PREFIX),
          key("characterIndex", "session:game:index:character:"),
          key("accountTenantIndex", "session:game:index:account-tenant:"),
          key("tenantIndex", "session:game:index:tenant:"),
          key("accountAdmissionBarrier", ACCOUNT_PREFIX),
          key("issuerAdmissionBarrier", ISSUER_PREFIX));
  private static final List<RedisScriptDescriptor.ArgumentSpec> EXPECTED_ARGUMENTS =
      List.of(
          argument("tenantId"),
          argument("accountId"),
          argument("playableStateNamespaceId"),
          argument("characterId"),
          argument("gameInstanceId"),
          argument("sessionId"),
          argument("bindingGeneration"),
          argument("tenantGameplayTag"),
          argument("candidateBytes"),
          argument("characterProjectionBytes"),
          argument("templateBytes"),
          argument("canonicalSessionDurationMillis"),
          argument("accountOpenFieldsJSON"),
          argument("issuerOpenFieldsJSON"),
          argument("absoluteDeadlineMillis"),
          argument("issuerPayloadBytes"));
  private static final List<RedisScriptDescriptor.OutcomeSpec> EXPECTED_OUTCOMES =
      List.of(
          outcome(
              "FINAL_ADMISSION_CREATED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "FINAL_ADMISSION_REPLAY",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "CURRENT_PHYSICAL_STATE_REFUSED",
              RedisScriptDescriptor.OutcomeCategory.STALE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "INVALID_ARGUMENTS",
              RedisScriptDescriptor.OutcomeCategory.VALIDATION_FAILURE,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING));

  private final io.lettuce.core.RedisClient redisClient;
  private final RedisScriptCatalog catalog;
  private final Clock clock;
  private final CanonicalGameplayTenantGameplayTagRepository tenantTags;

  CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
      io.lettuce.core.RedisClient redisClient,
      RedisScriptCatalog catalog,
      Clock clock,
      CanonicalGameplayTenantGameplayTagRepository tenantTags) {
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    this.tenantTags = Objects.requireNonNull(tenantTags, "Persisted tenant-tag owner is required");
  }

  /**
   * Applies the provisional source's final tenant-slot CAS or recovers a committed source by exact
   * read-only physical inspection. Only independently verified postimages produce a private
   * readback capability.
   */
  Readback finalizeOriginalNewOnce(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
      long absoluteDeadlineMillis) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(source, "Durable original NEW final-admission source is required");
    Objects.requireNonNull(template, "Authenticated session template is required");
    Objects.requireNonNull(accountOpen, "Current Account INITIAL OPEN proof is required");
    Objects.requireNonNull(issuerOpen, "Current issuer OPEN readback is required");

    byte[] candidateBytes = null;
    byte[] characterBytes = null;
    byte[] templateBytes = null;
    byte[] accountJson = null;
    byte[] issuerJson = null;
    byte[] issuerPayload = null;
    byte[] script = null;
    CanonicalGameplayOriginalNewAuthenticatedSession.Finalized finalized = null;
    try {
      CanonicalGameplayOriginalNewCandidate candidate = requireSource(source);
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
          tenantTags.assignmentForTransition(candidate.transition().transitionId());
      requireAssignment(assignment, candidate);
      Keys keys = requireKeys(candidate, assignment, accountOpen.key());
      CanonicalGameplayIssuerAdmissionBarrierValue issuerValue =
          requireIssuerOpen(issuerOpen, candidate, assignment, keys.issuerAdmissionBarrier());
      requireAccountOpen(accountOpen, candidate, assignment, keys.accountAdmissionBarrier());

      long durationMillis = template.canonicalSessionDurationMillis();
      requireDuration(durationMillis);
      long nowMillis = clock.millis();
      requireDeadline(nowMillis, absoluteDeadlineMillis);
      requireCurrentOpenWindow(accountOpen, nowMillis);

      candidateBytes = candidate.canonicalBytes();
      if (candidateBytes.length == 0
          || candidateBytes.length > CanonicalGameplayOriginalNewCandidateCodec.MAX_CANDIDATE_BYTES
          || !candidate.exactlyMatches(candidateBytes)
          || !CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes)
              .equals(candidate)) {
        throw unavailable();
      }
      characterBytes = characterProjection(candidate);
      templateBytes = template.finalCasTemplateBytes();
      if (templateBytes.length == 0
          || templateBytes.length
              > CanonicalGameplayOriginalNewAuthenticatedSession.MAX_SESSION_BYTES) {
        throw unavailable();
      }
      accountJson = canonicalJson(accountOpen.exactFields());
      issuerJson = issuerMetadataJson(issuerValue);
      issuerPayload = issuerOpen.exactPhysicalPayload();
      if (issuerPayload.length == 0
          || issuerPayload.length > MAX_ISSUER_PAYLOAD_BYTES
          || !Arrays.equals(issuerPayload, issuerValue.exactBytes())) {
        throw unavailable();
      }

      byte[] finalBytes;
      if (source.transitionStatus()
          == net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
              .Status.COMMITTED) {
        // A committed source has an immutable SQL final receipt. It can only recover the exact
        // complete session read-only; it must never recreate or replace from historical bytes.
        finalBytes = readExactPostimages(keys, candidate, accountOpen, issuerValue, characterBytes);
      } else {
        RedisScriptDescriptor descriptor = requireDescriptor();
        script = scriptSource(descriptor);
        long result =
            execute(
                script,
                keys,
                candidate,
                assignment,
                candidateBytes,
                characterBytes,
                templateBytes,
                durationMillis,
                accountJson,
                issuerJson,
                absoluteDeadlineMillis,
                issuerPayload);
        if (result != 1L && result != 2L) throw unavailable();
        finalBytes = readExactPostimages(keys, candidate, accountOpen, issuerValue, characterBytes);
      }
      try {
        requireDeadline(clock.millis(), absoluteDeadlineMillis);
        requireCurrentOpenWindow(accountOpen, clock.millis());
        finalized = template.requireFinalizedReadback(finalBytes);
        if (!candidate.equals(finalized.candidate())
            || finalized.canonicalSessionDurationMillis() != durationMillis) {
          throw unavailable();
        }
        if (source.transitionStatus()
            == net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
                .Status.COMMITTED) {
          CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionReceipt receipt =
              source
                  .finalReceipt()
                  .orElseThrow(CanonicalGameplayOriginalNewFinalAdmissionRedisOwner::unavailable);
          if (!receipt.finalSessionSha256().equals(digest("SHA-256", finalBytes))
              || receipt.admissionAtMillis() != finalized.admissionAtMillis()
              || receipt.continuityExpiresAtMillis() != finalized.continuityExpiresAtMillis()
              || receipt.sessionDurationMillis() != finalized.canonicalSessionDurationMillis()) {
            throw unavailable();
          }
        }
        Readback readback = new Readback(source, finalized);
        finalized = null;
        return readback;
      } finally {
        wipe(finalBytes);
      }
    } catch (RuntimeException failure) {
      if (finalized != null) finalized.close();
      throw unavailable();
    } finally {
      wipe(candidateBytes);
      wipe(characterBytes);
      wipe(templateBytes);
      wipe(accountJson);
      wipe(issuerJson);
      wipe(issuerPayload);
      wipe(script);
    }
  }

  private CanonicalGameplayOriginalNewCandidate requireSource(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source) {
    CanonicalGameplayOriginalNewCandidate candidate = source.candidate();
    if (source.candidateInstallationProvenance()
            != CanonicalGameplayBindingInventoryRepository
                .OriginalNewCandidateInstallationProvenance.HISTORICAL_READBACK_CONFIRMED
        || !candidate.bindingDecisionId().equals(candidate.transition().transitionId())
        || candidate.leaseEvidence().leaseKind()
            != net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence
                .LeaseKind.NEW_BINDING
        || !"PUBLIC_PRODUCTION".equals(candidate.leaseEvidence().carrier().get("mode"))) {
      throw unavailable();
    }
    byte[] bytes = candidate.canonicalBytes();
    try {
      if (bytes.length == 0
          || bytes.length > CanonicalGameplayOriginalNewCandidateCodec.MAX_CANDIDATE_BYTES
          || !source.candidateDigest().equals(CanonicalGameplayIssuerMemberV2Codec.sha256(bytes))
          || !candidate.equals(CanonicalGameplayOriginalNewCandidate.parseCanonical(bytes))) {
        throw unavailable();
      }
      boolean committed =
          source.transitionStatus()
              == net.firedevops.firemud.gamesession.binding
                  .CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED;
      if (committed != source.finalReceipt().isPresent()) throw unavailable();
      return candidate;
    } finally {
      wipe(bytes);
    }
  }

  private static void requireAssignment(
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      CanonicalGameplayOriginalNewCandidate candidate) {
    CanonicalGameplayBindingIdentity binding = candidate.bindingIdentity();
    try {
      CanonicalGameplayTenantGameplayTagRepository.requireCanonicalTag(
          assignment.tenantGameplayTag());
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
    if (!assignment.isBindingTransition()
        || !candidate.transition().transitionId().equals(assignment.transitionId())
        || !assignment.tenantId().equals(binding.tenantId())
        || !assignment.accountId().equals(binding.accountId())
        || !assignment.playableStateNamespaceId().equals(binding.playableStateNamespaceId())
        || !assignment.characterId().equals(binding.characterId())
        || !assignment.gameInstanceId().equals(binding.gameInstanceId())
        || !assignment.sessionId().equals(binding.sessionId())
        || !assignment.bindingRef().equals(binding.bindingRef())) {
      throw unavailable();
    }
  }

  private static Keys requireKeys(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      String exactAccountOpenKey) {
    CanonicalGameplayBindingIdentity binding = candidate.bindingIdentity();
    String session = CanonicalGameplaySessionRedisKeys.session(assignment, binding.bindingRef());
    String character =
        CanonicalGameplaySessionRedisKeys.characterIndex(assignment, binding.bindingRef());
    String accountTenant =
        CanonicalGameplaySessionRedisKeys.accountTenantIndex(assignment, binding.bindingRef());
    String tenant = CanonicalGameplaySessionRedisKeys.tenantIndex(assignment, binding.bindingRef());
    String issuerBarrier =
        CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(binding.issuerId(), assignment);
    String accountBarrier =
        ACCOUNT_PREFIX
            + "{"
            + assignment.tenantGameplayTag()
            + "}:"
            + binding.accountId()
            + ":"
            + binding.playableStateNamespaceId();
    if (!accountBarrier.equals(exactAccountOpenKey)
        || !assignment.isBindingTransition()
        || !candidate.transition().transitionId().equals(assignment.transitionId())) {
      throw unavailable();
    }
    return new Keys(session, character, accountTenant, tenant, exactAccountOpenKey, issuerBarrier);
  }

  private static void requireAccountOpen(
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen proof,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      String expectedKey) {
    CanonicalGameplayBindingIdentity binding = candidate.bindingIdentity();
    Map<String, String> fields = proof.exactFields();
    if (!proof.key().equals(expectedKey)
        || !expectedKey.startsWith(ACCOUNT_PREFIX)
        || !proof.accountId().equals(binding.accountId())
        || !proof.tenantId().equals(binding.tenantId())
        || !proof.playableStateNamespaceId().equals(binding.playableStateNamespaceId())
        || !fields.keySet().equals(ACCOUNT_FIELDS)
        || !CanonicalGameplayAccountInitialBarrierValue.SCHEMA.equals(fields.get("schema"))
        || !CanonicalGameplayAccountInitialBarrierValue.OPEN.equals(fields.get("state"))
        || !binding.accountId().toString().equals(fields.get("accountId"))
        || !binding.tenantId().toString().equals(fields.get("tenantId"))
        || !binding
            .playableStateNamespaceId()
            .toString()
            .equals(fields.get("playableStateNamespaceId"))
        || !assignment.tenantGameplayTag().equals(fields.get("tenantGameplayTag"))
        || !proof.initializerId().toString().equals(fields.get("ownerOperationId"))
        || !proof.sourceControlEpoch().toString().equals(fields.get("ownerFenceEpoch"))
        || !proof.incarnationDigest().equals(fields.get("incarnationDigest"))
        || proof.inventorySnapshotRevision().signum() <= 0) {
      throw unavailable();
    }
  }

  private void requireCurrentOpenWindow(
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen proof, long nowMillis) {
    if (nowMillis < proof.issuedAtEpochMillis() || nowMillis >= proof.expiresAtEpochMillis()) {
      throw unavailable();
    }
  }

  private static CanonicalGameplayIssuerAdmissionBarrierValue requireIssuerOpen(
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      String expectedKey) {
    CanonicalGameplayIssuerAdmissionBarrierValue value = readback.value();
    CanonicalGameplayIssuerAdmissionBarrierValue.Initializer initializer = value.initializer();
    CanonicalGameplayBindingIdentity binding = candidate.bindingIdentity();
    byte[] expectedPayload = null;
    byte[] actualPayload = null;
    try {
      expectedPayload = value.exactBytes();
      actualPayload = readback.exactPhysicalPayload();
      // A tenant OPEN initializer is retained and reused by later exact NEW bindings.
      // Its originating account/transition is not the new candidate's identity; the
      // candidate's own issuer acknowledgement is proved by the durable source owner.
      if (value.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
          || initializer == null
          || !CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(binding.issuerId(), assignment)
              .equals(expectedKey)
          || !CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
                  value.issuerId(),
                  value.tenantId(),
                  value.playableStateNamespaceId(),
                  value.tenantGameplayTag())
              .equals(expectedKey)
          || !binding.issuerId().equals(value.issuerId())
          || !binding.tenantId().equals(value.tenantId())
          || !binding.playableStateNamespaceId().equals(value.playableStateNamespaceId())
          || !assignment.tenantGameplayTag().equals(value.tenantGameplayTag())
          || !value.ownerOperationId().equals(initializer.operationId())
          || !value.admissionEpoch().equals(initializer.admissionEpoch())
          || !candidate
              .reservation()
              .reservationId()
              .equals(candidate.transition().issuerReservationId())
          || !candidate.reservation().transitionId().equals(candidate.transition().transitionId())
          || !candidate
              .reservation()
              .reservationFence()
              .equals(candidate.transition().reservationFence())
          || !candidate.reservation().ownerBindingRef().equals(binding.bindingRef())
          || !candidate
              .reservation()
              .bindingGeneration()
              .equals(candidate.transition().bindingGeneration())
          || !Arrays.equals(expectedPayload, actualPayload)) {
        throw unavailable();
      }
      return value;
    } finally {
      wipe(expectedPayload);
      wipe(actualPayload);
    }
  }

  private static byte[] characterProjection(CanonicalGameplayOriginalNewCandidate candidate) {
    CanonicalGameplayBindingIdentity binding = candidate.bindingIdentity();
    String value =
        "{\"sessionId\":\""
            + binding.sessionId()
            + "\",\"gameInstanceId\":\""
            + binding.gameInstanceId()
            + "\",\"bindingGeneration\":\""
            + candidate.transition().bindingGeneration()
            + "\"}";
    return ascii(value);
  }

  private static byte[] canonicalJson(Object value) {
    try {
      byte[] result = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (result.length == 0 || result.length > MAX_BARRIER_JSON_BYTES) {
        wipe(result);
        throw unavailable();
      }
      return result;
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private static byte[] issuerMetadataJson(CanonicalGameplayIssuerAdmissionBarrierValue value) {
    Map<String, byte[]> physical =
        CanonicalGameplayIssuerAdmissionBarrierRedisOwner.physicalFields(value);
    Map<String, String> metadata = new LinkedHashMap<>();
    try {
      if (physical.size() != 13 || !physical.containsKey("payload")) throw unavailable();
      for (Map.Entry<String, byte[]> entry : physical.entrySet()) {
        if (!"payload".equals(entry.getKey())) {
          metadata.put(entry.getKey(), strictAscii(entry.getValue()));
        }
      }
      return canonicalJson(metadata);
    } finally {
      wipe(physical);
    }
  }

  private RedisScriptDescriptor requireDescriptor() {
    try {
      RedisScriptDescriptor descriptor =
          catalog.require(
              CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution.SCRIPT_ID);
      if (!CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution.SCRIPT_ID.equals(
              descriptor.scriptId())
          || !CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution.RESOURCE_PATH
              .equals(descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !EXPECTED_KEYS.equals(descriptor.keys())
          || !EXPECTED_ARGUMENTS.equals(descriptor.arguments())
          || descriptor.category() != RedisScriptDescriptor.ScriptCategory.SESSION_CAS
          || !EXPECTED_OUTCOMES.equals(descriptor.outcomes())
          || descriptor.resetSensitivity() != RedisScriptDescriptor.ResetSensitivity.CLUSTER
          || descriptor.lossClass()
              != RedisScriptDescriptor.LossClass.EMPTY_REDIS_COLD_START_OR_RESET
          || descriptor.compatibilityLevel()
              != RedisScriptDescriptor.CompatibilityLevel.REQUIRES_CLUSTER_RESET
          || !descriptor
              .supportedCoexistence()
              .equals(List.of(new RedisScriptDescriptor.SupportedCoexistence("v1", "none")))
          || descriptor.legacyPayloadShape() != null) {
        throw unavailable();
      }
      return descriptor;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private static byte[] scriptSource(RedisScriptDescriptor descriptor) {
    try (InputStream input =
        CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] bytes = input.readNBytes(MAX_SCRIPT_BYTES + 1);
      if (bytes.length == 0
          || bytes.length > MAX_SCRIPT_BYTES
          || !descriptor.sha256().equals(digest("SHA-256", bytes))) {
        wipe(bytes);
        throw unavailable();
      }
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw unavailable();
    }
  }

  private long execute(
      byte[] script,
      Keys keys,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      byte[] candidateBytes,
      byte[] characterBytes,
      byte[] templateBytes,
      long durationMillis,
      byte[] accountJson,
      byte[] issuerJson,
      long absoluteDeadlineMillis,
      byte[] issuerPayload) {
    requireNoAmbientTransaction();
    try (StatefulRedisConnection<byte[], byte[]> connection =
        redisClient.connect(ByteArrayCodec.INSTANCE)) {
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(await(commands.aclWhoami()));
      String sha = await(commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(sha)) throw unavailable();
      Long result =
          await(
              commands.evalsha(
                  sha,
                  ScriptOutputType.INTEGER,
                  keyBytes(keys),
                  ascii(assignment.tenantId().toString()),
                  ascii(assignment.accountId().toString()),
                  ascii(assignment.playableStateNamespaceId().toString()),
                  ascii(candidate.bindingIdentity().characterId().toString()),
                  ascii(assignment.gameInstanceId().toString()),
                  ascii(assignment.sessionId()),
                  ascii(candidate.transition().bindingGeneration().toString()),
                  ascii(assignment.tenantGameplayTag()),
                  candidateBytes,
                  characterBytes,
                  templateBytes,
                  ascii(Long.toString(durationMillis)),
                  accountJson,
                  issuerJson,
                  ascii(Long.toString(absoluteDeadlineMillis)),
                  issuerPayload));
      return result;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private byte[] readExactPostimages(
      Keys keys,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierValue issuerOpen,
      byte[] expectedCharacter) {
    requireNoAmbientTransaction();
    byte[] sessionKey = ascii(keys.session());
    byte[] characterKey = ascii(keys.characterIndex());
    byte[] accountTenantKey = ascii(keys.accountTenantIndex());
    byte[] tenantKey = ascii(keys.tenantIndex());
    byte[] accountBarrierKey = ascii(keys.accountAdmissionBarrier());
    byte[] issuerBarrierKey = ascii(keys.issuerAdmissionBarrier());
    byte[] sessionBytes = null;
    Map<String, byte[]> issuerFields = null;
    Map<String, byte[]> accountFields = new HashMap<>();
    for (Map.Entry<String, String> entry : accountOpen.exactFields().entrySet()) {
      accountFields.put(entry.getKey(), ascii(entry.getValue()));
    }
    try {
      issuerFields = CanonicalGameplayIssuerAdmissionBarrierRedisOwner.physicalFields(issuerOpen);
      try (StatefulRedisConnection<byte[], byte[]> connection =
          redisClient.connect(ByteArrayCodec.INSTANCE)) {
        RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
        requirePrincipal(await(commands.aclWhoami()));
        if (!persistentString(commands, sessionKey)
            || !persistentString(commands, characterKey)
            || !Arrays.equals(expectedCharacter, await(commands.get(characterKey)))
            || !persistentMember(
                commands, accountTenantKey, ascii(candidate.bindingIdentity().sessionId()))
            || !persistentMember(
                commands, tenantKey, ascii(candidate.bindingIdentity().sessionId()))) {
          throw unavailable();
        }
        sessionBytes = await(commands.get(sessionKey));
        requireExactHash(commands, accountBarrierKey, accountFields);
        requireExactHash(commands, issuerBarrierKey, issuerFields);
        if (sessionBytes == null
            || sessionBytes.length == 0
            || sessionBytes.length
                > CanonicalGameplayOriginalNewAuthenticatedSession.MAX_SESSION_BYTES) {
          throw unavailable();
        }
        return sessionBytes.clone();
      }
    } catch (RuntimeException failure) {
      throw unavailable();
    } finally {
      wipe(sessionBytes);
      wipe(issuerFields);
      wipe(accountFields);
      wipe(sessionKey);
      wipe(characterKey);
      wipe(accountTenantKey);
      wipe(tenantKey);
      wipe(accountBarrierKey);
      wipe(issuerBarrierKey);
    }
  }

  private static boolean persistentString(RedisAsyncCommands<byte[], byte[]> commands, byte[] key) {
    return "string".equals(await(commands.type(key)))
        && Long.valueOf(-1L).equals(await(commands.pttl(key)));
  }

  private static boolean persistentMember(
      RedisAsyncCommands<byte[], byte[]> commands, byte[] key, byte[] member) {
    return "set".equals(await(commands.type(key)))
        && Long.valueOf(-1L).equals(await(commands.pttl(key)))
        && Boolean.TRUE.equals(await(commands.sismember(key, member)));
  }

  private static void requireExactHash(
      RedisAsyncCommands<byte[], byte[]> commands, byte[] key, Map<String, byte[]> expected) {
    if (!"hash".equals(await(commands.type(key)))
        || !Long.valueOf(-1L).equals(await(commands.pttl(key)))) {
      throw unavailable();
    }
    Long length = await(commands.hlen(key));
    if (length == null || length != expected.size()) throw unavailable();
    Map<byte[], byte[]> raw = await(commands.hgetall(key));
    if (raw == null || raw.size() != expected.size()) throw unavailable();
    Map<String, byte[]> actual = new HashMap<>();
    try {
      for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) {
        String field = strictAscii(entry.getKey());
        if (!expected.containsKey(field)
            || actual.putIfAbsent(field, entry.getValue().clone()) != null) {
          throw unavailable();
        }
      }
      if (actual.size() != expected.size()) throw unavailable();
      for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
        if (!Arrays.equals(entry.getValue(), actual.get(entry.getKey()))) throw unavailable();
      }
    } finally {
      wipe(actual);
      for (Map.Entry<byte[], byte[]> entry : raw.entrySet()) wipe(entry.getValue());
    }
  }

  private static byte[][] keyBytes(Keys keys) {
    return new byte[][] {
      ascii(keys.session()),
      ascii(keys.characterIndex()),
      ascii(keys.accountTenantIndex()),
      ascii(keys.tenantIndex()),
      ascii(keys.accountAdmissionBarrier()),
      ascii(keys.issuerAdmissionBarrier())
    };
  }

  private static RedisAsyncCommands<byte[], byte[]> requireCommands(
      StatefulRedisConnection<byte[], byte[]> connection) {
    if (connection == null || !connection.isOpen()) throw unavailable();
    RedisAsyncCommands<byte[], byte[]> commands = connection.async();
    if (commands == null) throw unavailable();
    return commands;
  }

  private static <T> T await(RedisFuture<T> future) {
    if (future == null) throw unavailable();
    try {
      T result = future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      return result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException failure) {
      throw unavailable();
    }
  }

  private static void requirePrincipal(String actual) {
    if (!PRINCIPAL.equals(actual)) throw unavailable();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static void requireDeadline(long nowMillis, long absoluteDeadlineMillis) {
    if (nowMillis <= 0L || absoluteDeadlineMillis <= nowMillis) throw unavailable();
  }

  private static void requireDuration(long durationMillis) {
    if (durationMillis <= 0L || durationMillis > MAX_CANONICAL_SESSION_DURATION_MILLIS) {
      throw unavailable();
    }
  }

  private static String strictAscii(byte[] value) {
    if (value == null) throw unavailable();
    for (byte item : value) if ((item & 0x80) != 0) throw unavailable();
    return new String(value, StandardCharsets.US_ASCII);
  }

  private static byte[] ascii(String value) {
    if (value == null) throw unavailable();
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    if (!value.equals(new String(bytes, StandardCharsets.US_ASCII))) throw unavailable();
    return bytes;
  }

  private static String digest(String algorithm, byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private static RedisScriptDescriptor.KeySpec key(String name, String prefix) {
    return new RedisScriptDescriptor.KeySpec(
        name, prefix, RedisScriptDescriptor.HashTagDeclaration.REQUIRED);
  }

  private static RedisScriptDescriptor.ArgumentSpec argument(String name) {
    return new RedisScriptDescriptor.ArgumentSpec(name);
  }

  private static RedisScriptDescriptor.OutcomeSpec outcome(
      String code,
      RedisScriptDescriptor.OutcomeCategory category,
      RedisScriptDescriptor.MutationEffect effect) {
    return new RedisScriptDescriptor.OutcomeSpec(code, category, effect);
  }

  private static void wipe(byte[] value) {
    if (value != null) Arrays.fill(value, (byte) 0);
  }

  private static void wipe(Map<String, byte[]> values) {
    if (values != null)
      values.values().forEach(CanonicalGameplayOriginalNewFinalAdmissionRedisOwner::wipe);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original NEW final admission Redis readback is unavailable");
  }

  private record Keys(
      String session,
      String characterIndex,
      String accountTenantIndex,
      String tenantIndex,
      String accountAdmissionBarrier,
      String issuerAdmissionBarrier) {}

  /**
   * Private-constructor capability tied to this owner's exact six-key physical readback. Closing it
   * clears the finalized session and opaque-handle copies held by its typed value.
   */
  static final class Readback implements AutoCloseable {
    private final CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
        source;
    private CanonicalGameplayOriginalNewAuthenticatedSession.Finalized session;
    private final String finalSessionSha256;

    private Readback(
        CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
        CanonicalGameplayOriginalNewAuthenticatedSession.Finalized session) {
      this.source = Objects.requireNonNull(source, "durable final-admission source");
      this.session = Objects.requireNonNull(session, "finalized session");
      byte[] exact = session.canonicalBytes();
      try {
        this.finalSessionSha256 = digest("SHA-256", exact);
      } finally {
        wipe(exact);
      }
    }

    CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source() {
      return source;
    }

    CanonicalGameplayOriginalNewAuthenticatedSession.Finalized session() {
      if (session == null) throw new IllegalStateException("Final admission readback is closed");
      return session;
    }

    String finalSessionSha256() {
      return finalSessionSha256;
    }

    long admissionAtMillis() {
      return session().admissionAtMillis();
    }

    long continuityExpiresAtMillis() {
      return session().continuityExpiresAtMillis();
    }

    long canonicalSessionDurationMillis() {
      return session().canonicalSessionDurationMillis();
    }

    byte[] exactCanonicalBytes() {
      return session().canonicalBytes();
    }

    @Override
    public void close() {
      if (session != null) {
        session.close();
        session = null;
      }
    }

    @Override
    public String toString() {
      return "CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback[physical,redacted]";
    }
  }
}
