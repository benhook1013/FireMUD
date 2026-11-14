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
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
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
 * source values, performs one seven-key tenant-slot CAS, then independently reads back the
 * finalized session, three installed indexes, and both unchanged OPEN barriers. A script reply
 * alone never yields a {@link Readback}; this component does not prove the complete Account or
 * issuer flow.
 */
final class CanonicalGameplayOriginalNewFinalAdmissionRedisOwner {
  static final long CAPACITY_EXECUTION_AUTHORIZED = 3L;
  static final long CAPACITY_EXECUTION_CLOSED = 4L;
  static final long CAPACITY_EXECUTION_FENCED_INDETERMINATE = 5L;
  static final long CAPACITY_EXECUTION_AUTHORIZED_OBSERVED = 6L;
  static final long CAPACITY_EXECUTION_CLOSED_OBSERVED = 7L;
  static final long CAPACITY_EXECUTION_FENCED_INDETERMINATE_OBSERVED = 8L;
  static final long CAPACITY_EXECUTION_COMMITTED_OBSERVED = 9L;

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
          key("issuerAdmissionBarrier", ISSUER_PREFIX),
          key("capacityExecution", "session:game:capacity-execution:"));
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
          argument("issuerPayloadBytes"),
          argument("capacityExecutionBytes"),
          argument("capacityAction"),
          argument("capacityExecutionDigest"));
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
              "CAPACITY_EXECUTION_AUTHORIZED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "CAPACITY_EXECUTION_CLOSED",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "CAPACITY_EXECUTION_FENCED_INDETERMINATE",
              RedisScriptDescriptor.OutcomeCategory.SUCCESS,
              RedisScriptDescriptor.MutationEffect.MUTATING),
          outcome(
              "CAPACITY_EXECUTION_AUTHORIZED_OBSERVED",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "CAPACITY_EXECUTION_CLOSED_OBSERVED",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "CAPACITY_EXECUTION_FENCED_INDETERMINATE_OBSERVED",
              RedisScriptDescriptor.OutcomeCategory.REPLAY,
              RedisScriptDescriptor.MutationEffect.NON_MUTATING),
          outcome(
              "CAPACITY_EXECUTION_COMMITTED_OBSERVED",
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
  private final java.util.function.Supplier<StatefulRedisConnection<byte[], byte[]>>
      scopedConnection;
  private final java.util.function.LongSupplier remainingCommandMillis;
  private final OriginCustody originCustody;
  private final java.util.function.Consumer<java.util.concurrent.CompletableFuture<Void>>
      scopedClose;

  CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
      io.lettuce.core.RedisClient redisClient,
      RedisScriptCatalog catalog,
      Clock clock,
      CanonicalGameplayTenantGameplayTagRepository tenantTags) {
    this(redisClient, catalog, clock, tenantTags, null, null, null, null);
  }

  CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
      io.lettuce.core.RedisClient redisClient,
      RedisScriptCatalog catalog,
      Clock clock,
      CanonicalGameplayTenantGameplayTagRepository tenantTags,
      java.util.function.Supplier<StatefulRedisConnection<byte[], byte[]>> scopedConnection,
      java.util.function.LongSupplier remainingCommandMillis,
      java.util.function.Consumer<java.util.concurrent.CompletableFuture<Void>> scopedClose) {
    this(
        redisClient,
        catalog,
        clock,
        tenantTags,
        scopedConnection,
        remainingCommandMillis,
        scopedClose,
        null);
  }

  private CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
      io.lettuce.core.RedisClient redisClient,
      RedisScriptCatalog catalog,
      Clock clock,
      CanonicalGameplayTenantGameplayTagRepository tenantTags,
      java.util.function.Supplier<StatefulRedisConnection<byte[], byte[]>> scopedConnection,
      java.util.function.LongSupplier remainingCommandMillis,
      java.util.function.Consumer<java.util.concurrent.CompletableFuture<Void>> scopedClose,
      OriginCustody originCustody) {
    this.originCustody = originCustody;
    if ((scopedConnection == null) != (remainingCommandMillis == null)
        || (scopedConnection == null) != (scopedClose == null))
      throw new IllegalArgumentException(
          "Scoped connection, command budget and close must be supplied together");
    this.scopedConnection = scopedConnection;
    this.remainingCommandMillis = remainingCommandMillis;
    this.scopedClose = scopedClose;
    this.redisClient = Objects.requireNonNull(redisClient, "Configured Redis client is required");
    this.catalog = Objects.requireNonNull(catalog, "Manual Redis script catalog is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    this.tenantTags = Objects.requireNonNull(tenantTags, "Persisted tenant-tag owner is required");
  }

  /** Manual receiving composition only; never supplies authority from a fresh owner reread. */
  CanonicalGameplayOriginalNewFinalAdmissionRedisOwner forOrigin(
      CanonicalGameplayCapacityOrigin origin,
      CanonicalGameplayCapacityOriginRepository.Retained custody) {
    Objects.requireNonNull(origin);
    Objects.requireNonNull(custody);
    if (custody.state() != CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED
        && custody.state() != CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED)
      throw unavailable();
    return new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
        redisClient,
        catalog,
        clock,
        tenantTags,
        scopedConnection,
        remainingCommandMillis,
        scopedClose,
        new OriginCustody(origin, custody));
  }

  private record OriginCustody(
      CanonicalGameplayCapacityOrigin origin,
      CanonicalGameplayCapacityOriginRepository.Retained custody) {}

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
      OriginalNewCapacityExecution execution,
      long absoluteDeadlineMillis) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(execution, "Exact Account capacity execution is required");
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
      requireExecutionBinding(source, execution);
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment =
          tenantTags.assignmentForTransition(candidate.transition().transitionId());
      requireAssignment(assignment, candidate);
      Keys keys = requireKeys(candidate, assignment, accountOpen.key(), execution);
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
        finalBytes =
            readExactPostimages(
                keys, candidate, accountOpen, issuerValue, characterBytes, execution);
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
                issuerPayload,
                execution,
                "FINAL");
        if (result != 1L && result != 2L) throw unavailable();
        finalBytes =
            readExactPostimages(
                keys, candidate, accountOpen, issuerValue, characterBytes, execution);
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
        Readback readback = new Readback(source, finalized, execution);
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

  /**
   * Recovers only an already-applied original effect for durable SQL receipt/retention. Unlike
   * admission, recovery requires no fresh OPEN barrier, credential, or live private region grant.
   * It cannot submit Lua or make the recovered session newly admissible.
   */
  Readback readCommittedForRetention(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      OriginalNewCapacityExecution execution) {
    requireNoAmbientTransaction();
    var candidate = requireSource(source);
    requireExecutionBinding(source, execution);
    var assignment = tenantTags.assignmentForTransition(execution.transitionId());
    requireAssignment(assignment, candidate);
    String accountKey =
        ACCOUNT_PREFIX
            + "{"
            + assignment.tenantGameplayTag()
            + "}:"
            + assignment.accountId()
            + ":"
            + assignment.playableStateNamespaceId();
    var keys = requireKeys(candidate, assignment, accountKey, execution);
    byte[] bytes =
        readExactPostimages(keys, candidate, null, null, characterProjection(candidate), execution);
    CanonicalGameplayOriginalNewAuthenticatedSession.Finalized finalized = null;
    try {
      finalized = CanonicalGameplayOriginalNewAuthenticatedSession.parseFinalized(bytes, clock);
      if (!candidate.equals(finalized.candidate())) throw unavailable();
      Readback readback = new Readback(source, finalized, execution);
      finalized = null;
      return readback;
    } catch (RuntimeException failure) {
      throw unavailable();
    } finally {
      if (finalized != null) finalized.close();
      wipe(bytes);
    }
  }

  long closeCapacityExecutionOnce(
      OriginalNewCapacityExecution execution,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {
    return closeCapacityExecutionOnce(
        execution, candidate, assignment, Math.addExact(clock.millis(), COMMAND_TIMEOUT_MILLIS));
  }

  /** Normal-handoff future exclusion only. A result is not an execution outcome. */
  long fenceCapacityExecutionOnce(
      OriginalNewCapacityExecution execution,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      UUID dispatchId,
      long absoluteDeadlineMillis) {
    requireNoAmbientTransaction();
    requireAssignment(assignment, candidate);
    requireExecutionBinding(candidate, execution);
    String accountKey =
        ACCOUNT_PREFIX
            + "{"
            + assignment.tenantGameplayTag()
            + "}:"
            + assignment.accountId()
            + ":"
            + assignment.playableStateNamespaceId();
    var keys = requireKeys(candidate, assignment, accountKey, execution);
    byte[] script = scriptSource(requireDescriptor());
    try {
      return execute(
          script,
          keys,
          candidate,
          assignment,
          candidate.canonicalBytes(),
          characterProjection(candidate),
          ascii("{}"),
          1,
          ascii("{}"),
          ascii("{}"),
          absoluteDeadlineMillis,
          ascii(dispatchId.toString()),
          execution,
          "FENCE");
    } finally {
      wipe(script);
    }
  }

  long closeCapacityExecutionOnce(
      OriginalNewCapacityExecution execution,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      long absoluteDeadlineMillis) {
    requireNoAmbientTransaction();
    requireAssignment(assignment, candidate);
    requireExecutionBinding(candidate, execution);
    String accountKey =
        ACCOUNT_PREFIX
            + "{"
            + assignment.tenantGameplayTag()
            + "}:"
            + assignment.accountId()
            + ":"
            + assignment.playableStateNamespaceId();
    var keys = requireKeys(candidate, assignment, accountKey, execution);
    byte[] script = scriptSource(requireDescriptor());
    try {
      return execute(
          script,
          keys,
          candidate,
          assignment,
          candidate.canonicalBytes(),
          characterProjection(candidate),
          ascii("{}"),
          1,
          ascii("{}"),
          ascii("{}"),
          absoluteDeadlineMillis,
          ascii("closure"),
          execution,
          "CLOSE");
    } finally {
      wipe(script);
    }
  }

  long dispatchCapacityAction(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
      OriginalNewCapacityExecution execution,
      String action,
      long deadline) {
    PreparedInstall prepared =
        prepareInstall(source, template, accountOpen, issuerOpen, execution, action, deadline);
    byte[] script = scriptSource(requireDescriptor());
    byte[] bytes = template.finalCasTemplateBytes();
    try {
      return execute(
          script,
          prepared.keys(),
          prepared.candidate(),
          prepared.assignment(),
          prepared.candidate().canonicalBytes(),
          characterProjection(prepared.candidate()),
          bytes,
          template.canonicalSessionDurationMillis(),
          canonicalJson(accountOpen.exactFields()),
          issuerMetadataJson(prepared.issuerValue()),
          deadline,
          issuerOpen.exactPhysicalPayload(),
          execution,
          action);
    } finally {
      wipe(script);
      wipe(bytes);
    }
  }

  /** Checks deterministic INSTALL inputs without reading or mutating Redis. */
  void validateInstallInputs(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
      OriginalNewCapacityExecution execution,
      long deadline) {
    prepareInstall(source, template, accountOpen, issuerOpen, execution, "INSTALL", deadline);
    byte[] script = scriptSource(requireDescriptor());
    wipe(script);
  }

  private PreparedInstall prepareInstall(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
      OriginalNewCapacityExecution execution,
      String action,
      long deadline) {
    requireNoAmbientTransaction();
    if (!"INSTALL".equals(action)
        || template == null
        || accountOpen == null
        || issuerOpen == null
        || execution == null) throw unavailable();
    requireDeadline(clock.millis(), deadline);
    var candidate = requireSource(source);
    requireExecutionBinding(source, execution);
    var assignment = tenantTags.assignmentForTransition(candidate.transition().transitionId());
    requireAssignment(assignment, candidate);
    Keys keys = requireKeys(candidate, assignment, accountOpen.key(), execution);
    requireAccountOpen(accountOpen, candidate, assignment, keys.accountAdmissionBarrier());
    requireCurrentOpenWindow(accountOpen, clock.millis());
    CanonicalGameplayIssuerAdmissionBarrierValue issuerValue =
        requireIssuerOpen(issuerOpen, candidate, assignment, keys.issuerAdmissionBarrier());
    template.requireCapacityExecutionBinding(candidate, execution);
    return new PreparedInstall(candidate, assignment, keys, issuerValue);
  }

  static void requireExecutionBinding(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      OriginalNewCapacityExecution execution) {
    requireExecutionBinding(source.candidate(), execution);
    if (!source.candidateDigest().equals(execution.candidateDigest())
        || execution.selectedOwnerFence()
            != source.regionAcknowledgement().executorFence().longValueExact()) throw unavailable();
  }

  private static void requireExecutionBinding(
      CanonicalGameplayOriginalNewCandidate candidate, OriginalNewCapacityExecution execution) {
    if (!candidate.bindingDecisionId().equals(execution.transitionId())
        || !candidate.bindingIdentity().tenantId().equals(execution.request().identity().tenantId())
        || !execution
            .candidateDigest()
            .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(candidate.canonicalBytes())))
      throw unavailable();
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
      String exactAccountOpenKey,
      OriginalNewCapacityExecution execution) {
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
    return new Keys(
        session,
        character,
        accountTenant,
        tenant,
        exactAccountOpenKey,
        issuerBarrier,
        "session:game:capacity-execution:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + execution.request().admissionAttemptId());
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
      String expectedId =
          originCustody == null
              ? CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution.SCRIPT_ID
              : "game-session.original-new-origin-final-admission.v1";
      var expectedKeys = new java.util.ArrayList<>(EXPECTED_KEYS);
      var expectedArguments = new java.util.ArrayList<>(EXPECTED_ARGUMENTS);
      if (originCustody != null) {
        expectedKeys.add(key("capacityOrigin", "session:game:capacity-origin:"));
        expectedArguments.add(argument("originDigest"));
        expectedArguments.add(argument("custodyOwnerId"));
        expectedArguments.add(argument("custodyOwnerFence"));
      }
      RedisScriptDescriptor descriptor = catalog.require(expectedId);
      if (!expectedId.equals(descriptor.scriptId())
          || !CanonicalGameplayOriginalNewFinalAdmissionRedisScriptContribution.RESOURCE_PATH
              .equals(descriptor.resourcePath())
          || !"game-session-service".equals(descriptor.owner())
          || !PRINCIPAL.equals(descriptor.principal())
          || descriptor.role() != RedisScriptDescriptor.RedisRole.COORDINATION
          || !expectedKeys.equals(descriptor.keys())
          || !expectedArguments.equals(descriptor.arguments())
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
      byte[] issuerPayload,
      OriginalNewCapacityExecution execution,
      String action) {
    requireNoAmbientTransaction();
    StatefulRedisConnection<byte[], byte[]> connection = null;
    try {
      commandWaitMillis();
      connection =
          scopedConnection == null
              ? redisClient.connect(ByteArrayCodec.INSTANCE)
              : scopedConnection.get();
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(awaitCommand(commands::aclWhoami));
      String sha = awaitCommand(() -> commands.scriptLoad(script));
      if (!digest("SHA-1", script).equals(sha)) throw unavailable();
      byte[][] commandKeys = keyBytes(keys);
      java.util.List<byte[]> arguments =
          new java.util.ArrayList<>(
              java.util.Arrays.asList(
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
                  issuerPayload,
                  execution.canonicalBytes(),
                  ascii(action),
                  ascii(execution.digest())));
      if (originCustody != null) {
        var origin = originCustody.origin();
        if (!java.util.Arrays.equals(
                net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest.preimage(
                    execution.request()),
                net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest.preimage(
                    origin.request()))
            || !execution.transitionId().equals(origin.transitionId())
            || !execution.candidateDigest().equals(origin.candidateDigest())
            || !execution.selectedOwnerId().equals(origin.selectedOwnerId())
            || execution.selectedOwnerFence() != origin.selectedOwnerFence()) throw unavailable();
        commandKeys = java.util.Arrays.copyOf(commandKeys, 8);
        commandKeys[7] = ascii(origin.redisKey());
        arguments.add(ascii(origin.digest()));
        arguments.add(ascii(originCustody.custody().ownerId()));
        arguments.add(ascii(Long.toString(originCustody.custody().ownerFence())));
      }
      final byte[][] exactKeys = commandKeys;
      final byte[][] exactArguments = arguments.toArray(byte[][]::new);
      Long result =
          awaitCommand(
              () -> commands.evalsha(sha, ScriptOutputType.INTEGER, exactKeys, exactArguments));
      return result;
    } catch (RuntimeException failure) {
      throw unavailable();
    } finally {
      if (connection != null) {
        try {
          if (scopedConnection == null) connection.close();
          else
            scopedClose.accept(
                CanonicalGameplayOriginalNewCapacityExecutionBudget.startRedisClose(connection));
        } catch (RuntimeException cleanupFailure) {
          throw unavailable();
        }
      }
    }
  }

  private long commandWaitMillis() {
    long remaining =
        remainingCommandMillis == null
            ? COMMAND_TIMEOUT_MILLIS
            : remainingCommandMillis.getAsLong();
    if (remaining <= 0) throw unavailable();
    return Math.min(COMMAND_TIMEOUT_MILLIS, remaining);
  }

  private <T> T awaitCommand(java.util.function.Supplier<RedisFuture<T>> command) {
    long beforeSubmission = commandWaitMillis();
    RedisFuture<T> future = command.get();
    if (future == null) throw unavailable();
    try {
      T result = future.get(Math.min(beforeSubmission, commandWaitMillis()), TimeUnit.MILLISECONDS);
      commandWaitMillis();
      return result;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (ExecutionException | TimeoutException failure) {
      // A submitted write is indeterminate. Neither a timeout nor cleanup permits a retry.
      throw unavailable();
    }
  }

  private byte[] readExactPostimages(
      Keys keys,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierValue issuerOpen,
      byte[] expectedCharacter,
      OriginalNewCapacityExecution execution) {
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
    if ((accountOpen == null) != (issuerOpen == null)) throw unavailable();
    if (accountOpen != null)
      for (Map.Entry<String, String> entry : accountOpen.exactFields().entrySet()) {
        accountFields.put(entry.getKey(), ascii(entry.getValue()));
      }
    StatefulRedisConnection<byte[], byte[]> connection = null;
    try {
      if (issuerOpen != null)
        issuerFields = CanonicalGameplayIssuerAdmissionBarrierRedisOwner.physicalFields(issuerOpen);
      connection =
          scopedConnection == null
              ? redisClient.connect(ByteArrayCodec.INSTANCE)
              : scopedConnection.get();
      RedisAsyncCommands<byte[], byte[]> commands = requireCommands(connection);
      requirePrincipal(awaitCommand(commands::aclWhoami));
      if (!persistentString(commands, sessionKey)
          || !persistentString(commands, characterKey)
          || !Arrays.equals(expectedCharacter, awaitCommand(() -> commands.get(characterKey)))
          || !persistentMember(
              commands, accountTenantKey, ascii(candidate.bindingIdentity().sessionId()))
          || !persistentMember(
              commands, tenantKey, ascii(candidate.bindingIdentity().sessionId()))) {
        throw unavailable();
      }
      sessionBytes = awaitCommand(() -> commands.get(sessionKey));
      Map<String, byte[]> capacityFields = new LinkedHashMap<>();
      capacityFields.put("binding", execution.canonicalBytes());
      capacityFields.put("digest", ascii(execution.digest()));
      capacityFields.put("state", ascii("COMMITTED"));
      capacityFields.put("sessionSha1", ascii(digest("SHA-1", sessionBytes)));
      try (var parsed =
          CanonicalGameplayOriginalNewAuthenticatedSession.parseFinalized(sessionBytes, clock)) {
        capacityFields.put("admissionAt", ascii(Long.toString(parsed.admissionAtMillis())));
        capacityFields.put(
            "continuityExpiresAt", ascii(Long.toString(parsed.continuityExpiresAtMillis())));
      }
      try {
        requireExactHash(commands, ascii(keys.capacityExecution()), capacityFields);
      } finally {
        wipe(capacityFields);
      }
      if (accountOpen != null) {
        requireExactHash(commands, accountBarrierKey, accountFields);
        requireExactHash(commands, issuerBarrierKey, issuerFields);
      }
      if (sessionBytes == null
          || sessionBytes.length == 0
          || sessionBytes.length
              > CanonicalGameplayOriginalNewAuthenticatedSession.MAX_SESSION_BYTES) {
        throw unavailable();
      }
      return sessionBytes.clone();
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
      if (connection != null) {
        if (scopedConnection == null) connection.close();
        else
          scopedClose.accept(
              CanonicalGameplayOriginalNewCapacityExecutionBudget.startRedisClose(connection));
      }
    }
  }

  private boolean persistentString(RedisAsyncCommands<byte[], byte[]> commands, byte[] key) {
    return "string".equals(awaitCommand(() -> commands.type(key)))
        && Long.valueOf(-1L).equals(awaitCommand(() -> commands.pttl(key)));
  }

  private boolean persistentMember(
      RedisAsyncCommands<byte[], byte[]> commands, byte[] key, byte[] member) {
    return "set".equals(awaitCommand(() -> commands.type(key)))
        && Long.valueOf(-1L).equals(awaitCommand(() -> commands.pttl(key)))
        && Boolean.TRUE.equals(awaitCommand(() -> commands.sismember(key, member)));
  }

  private void requireExactHash(
      RedisAsyncCommands<byte[], byte[]> commands, byte[] key, Map<String, byte[]> expected) {
    if (!"hash".equals(awaitCommand(() -> commands.type(key)))
        || !Long.valueOf(-1L).equals(awaitCommand(() -> commands.pttl(key)))) {
      throw unavailable();
    }
    Long length = awaitCommand(() -> commands.hlen(key));
    if (length == null || length != expected.size()) throw unavailable();
    Map<byte[], byte[]> raw = awaitCommand(() -> commands.hgetall(key));
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
      ascii(keys.issuerAdmissionBarrier()),
      ascii(keys.capacityExecution())
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
      String issuerAdmissionBarrier,
      String capacityExecution) {}

  private record PreparedInstall(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment,
      Keys keys,
      CanonicalGameplayIssuerAdmissionBarrierValue issuerValue) {}

  /**
   * Private-constructor capability tied to this owner's exact seven-key physical readback. Closing
   * it clears the finalized session and opaque-handle copies held by its typed value.
   */
  static final class Readback implements AutoCloseable {
    private final CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
        source;
    private CanonicalGameplayOriginalNewAuthenticatedSession.Finalized session;
    private final String finalSessionSha256;
    private final OriginalNewCapacityExecution execution;

    private Readback(
        CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
        CanonicalGameplayOriginalNewAuthenticatedSession.Finalized session,
        OriginalNewCapacityExecution execution) {
      this.execution = Objects.requireNonNull(execution);
      this.source = Objects.requireNonNull(source, "durable final-admission source");
      this.session = Objects.requireNonNull(session, "finalized session");
      byte[] exact = session.canonicalBytes();
      try {
        if (!execution
            .digest()
            .equals(JSON.readTree(exact).get("capacityExecutionDigest").textValue()))
          throw unavailable();
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

    OriginalNewCapacityExecution execution() {
      return execution;
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
