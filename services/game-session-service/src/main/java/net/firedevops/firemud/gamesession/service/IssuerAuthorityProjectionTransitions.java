package net.firedevops.firemud.gamesession.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceReadback;
import net.firedevops.firemud.gamesession.client.AccountIssuerAuthorityClient.SourceSnapshot;

/** Pure decision layer for the derived Game Session issuer-authority projection. */
public final class IssuerAuthorityProjectionTransitions {
  public static final String KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  public static final String SCHEMA_VERSION = "game-session-auth-issuer-projection/v1";

  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";
  private static final BigInteger ZERO = BigInteger.ZERO;
  private static final BigInteger ONE = BigInteger.ONE;

  private static final Set<String> BASE_FIELDS =
      Set.of(
          "schemaVersion",
          "issuerId",
          "lastAppliedIssuerGeneration",
          "lastAppliedSourceOutboxSequence",
          "outboxStreamKey",
          "appliedAt",
          "appliedSourceEvidence");
  private static final Set<String> POSITIVE_FIELDS =
      Set.of("lastAppliedSourceEventId", "lastAppliedSourceEventDigest");

  private IssuerAuthorityProjectionTransitions() {}

  /**
   * Creates an initial projection only from Account's complete authenticated current readback.
   * Sequence zero is source absence evidence; it is not recipient admission evidence.
   */
  public static Decision bootstrap(SourceReadback currentReadback, String appliedAt) {
    try {
      VerifiedReadback readback = verifyReadback(currentReadback);
      if (readback.selectedEvent().isPresent()) {
        return new Quarantine(
            Optional.empty(), QuarantineReason.BOOTSTRAP_REQUIRES_CURRENT_READBACK);
      }
      requireAppliedAt(appliedAt);
      return new Mutation(
          Optional.empty(), projectionFor(readback.snapshot(), appliedAt), MutationKind.BOOTSTRAP);
    } catch (EvidenceRejected rejected) {
      return new Quarantine(Optional.empty(), rejected.reason());
    } catch (IllegalArgumentException malformed) {
      return new Quarantine(Optional.empty(), QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
  }

  /**
   * Decides whether one Account-authenticated selected source event advances, duplicates, or
   * contradicts the observed projection. The expected fields and next projection are immutable
   * compare-and-set evidence; this method performs no storage operation.
   */
  public static Decision decide(
      Map<String, ?> observedProjection, SourceReadback sourceReadback, String appliedAt) {
    Optional<Map<String, Object>> observed = immutableObserved(observedProjection);
    final ExistingProjection existing;
    try {
      if (observedProjection == null) {
        return new Quarantine(Optional.empty(), QuarantineReason.MISSING_EXISTING_PROJECTION);
      }
      existing = parseExistingProjection(observedProjection);
    } catch (IllegalArgumentException malformed) {
      return new Quarantine(observed, QuarantineReason.MALFORMED_EXISTING_PROJECTION);
    }

    try {
      VerifiedReadback readback = verifyReadback(sourceReadback);
      if (readback.selectedEvent().isEmpty()) {
        return new Quarantine(observed, QuarantineReason.SELECTED_EVENT_REQUIRED);
      }

      Checkpoint current = readback.snapshot();
      IssuerGenerationAuthorityEvent selected = readback.selectedEvent().orElseThrow();
      if (!existing.issuerId().equals(current.issuerId())
          || !existing.streamKey().equals(current.streamKey())) {
        return new Quarantine(observed, QuarantineReason.ISSUER_OR_STREAM_CHANGED);
      }
      if (current.sequence().compareTo(existing.sequence()) < 0
          || current.generation().compareTo(existing.generation()) < 0
          || current.sourceVersion().compareTo(existing.sourceVersion()) < 0) {
        return new Quarantine(observed, QuarantineReason.CURRENT_CHECKPOINT_REGRESSED);
      }
      if (current.sequence().equals(existing.sequence())) {
        if (existing.latestEvent() == null) {
          if (current.latestEvent() != null
              || !current.generation().equals(ONE)
              || !current.sourceVersion().equals(ONE)) {
            return new Quarantine(observed, QuarantineReason.CURRENT_CHECKPOINT_CONFLICT);
          }
        } else if (current.latestEvent() == null
            || !existing
                .latestEvent()
                .canonicalJson()
                .equals(current.latestEvent().canonicalJson())) {
          return new Quarantine(observed, QuarantineReason.CURRENT_CHECKPOINT_CONFLICT);
        }
      } else if (current.sourceVersion().compareTo(existing.sourceVersion()) <= 0) {
        return new Quarantine(observed, QuarantineReason.CURRENT_CHECKPOINT_REGRESSED);
      }

      BigInteger selectedSequence = positiveDecimal(selected.outboxSequence(), "selected sequence");
      int order = selectedSequence.compareTo(existing.sequence());
      if (order < 0) {
        if (positiveDecimal(selected.issuerAuthGeneration(), "selected generation")
                .compareTo(existing.generation())
            > 0) {
          return new Quarantine(observed, QuarantineReason.HISTORICAL_EVENT_PROGRESS_CONFLICT);
        }
        if (positiveDecimal(selected.sourceVersion(), "selected source version")
                .compareTo(existing.sourceVersion())
            > 0) {
          return new Quarantine(observed, QuarantineReason.HISTORICAL_EVENT_PROGRESS_CONFLICT);
        }
        // The selected event is an exact immutable Account lookup. Historical generations and
        // source versions may be lower than the installed later projection.
        return new NoOp(NoOpReason.VERIFIED_HISTORICAL_DUPLICATE);
      }

      if (order == 0) {
        if (existing.latestEvent() == null
            || !existing.latestEvent().canonicalJson().equals(selected.canonicalJson())) {
          return new Quarantine(observed, QuarantineReason.DUPLICATE_EVENT_CONFLICT);
        }
        return new NoOp(NoOpReason.EXACT_DUPLICATE);
      }

      BigInteger expectedNextSequence = existing.sequence().add(ONE);
      if (!selectedSequence.equals(expectedNextSequence)) {
        return new Quarantine(observed, QuarantineReason.EVENT_SEQUENCE_GAP);
      }

      BigInteger eventGeneration =
          positiveDecimal(selected.issuerAuthGeneration(), "selected generation");
      BigInteger eventSourceVersion =
          positiveDecimal(selected.sourceVersion(), "selected source version");
      if (eventGeneration.compareTo(existing.generation()) < 0) {
        return new Quarantine(observed, QuarantineReason.EVENT_GENERATION_REGRESSED);
      }
      if (eventSourceVersion.compareTo(existing.sourceVersion()) <= 0) {
        return new Quarantine(observed, QuarantineReason.EVENT_SOURCE_VERSION_REGRESSED);
      }
      requireAppliedAt(appliedAt);

      BigInteger nextGeneration = eventGeneration.max(existing.generation());
      return new Mutation(
          observed,
          projectionFor(
              selected, nextGeneration.toString(), selectedSequence.toString(), appliedAt),
          MutationKind.ADVANCE);
    } catch (EvidenceRejected rejected) {
      return new Quarantine(observed, rejected.reason());
    } catch (IllegalArgumentException malformed) {
      return new Quarantine(observed, QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
  }

  private static VerifiedReadback verifyReadback(SourceReadback readback) {
    if (readback == null
        || readback.requestId() == null
        || readback.targetNamespace() == null
        || readback.targetNamespace().isBlank()
        || readback.sourceSnapshot() == null) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    UUID requestId;
    try {
      requestId = UUID.fromString(readback.requestId());
    } catch (IllegalArgumentException malformed) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    if (new UUID(0L, 0L).equals(requestId) || !requestId.toString().equals(readback.requestId())) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }

    Checkpoint snapshot = verifySnapshot(readback.sourceSnapshot());
    Optional<IssuerGenerationAuthorityEvent> selected = readback.requestedEvent();
    if (selected.isPresent()) {
      IssuerGenerationAuthorityEvent event = verifyEvent(selected.orElseThrow());
      if (!sameSource(event, snapshot.issuerId(), snapshot.streamKey())
          || positiveDecimal(event.outboxSequence(), "selected sequence")
                  .compareTo(snapshot.sequence())
              > 0
          || positiveDecimal(event.issuerAuthGeneration(), "selected generation")
                  .compareTo(snapshot.generation())
              > 0
          || positiveDecimal(event.sourceVersion(), "selected source version")
                  .compareTo(snapshot.sourceVersion())
              > 0) {
        throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
      }
      if (snapshot.sequence().equals(positiveDecimal(event.outboxSequence(), "selected sequence"))
          && (snapshot.latestEvent() == null
              || !snapshot.latestEvent().canonicalJson().equals(event.canonicalJson()))) {
        throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
      }
    }
    return new VerifiedReadback(snapshot, selected);
  }

  private static Checkpoint verifySnapshot(SourceSnapshot source) {
    if (source == null || source.issuerId() == null || source.issuerId().isBlank()) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    String expectedScope = "issuer/" + source.issuerId();
    String expectedStream =
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + expectedScope;
    if (!expectedScope.equals(source.sourceScope())
        || !expectedStream.equals(source.outboxStreamKey())) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }

    BigInteger generation = positiveDecimal(source.issuerAuthGeneration(), "issuer generation");
    BigInteger sourceVersion = positiveDecimal(source.sourceVersion(), "source version");
    BigInteger sequence = nonnegativeDecimal(source.outboxSequence(), "outbox sequence");
    IssuerGenerationAuthorityEvent latest =
        source.latestEvent().map(IssuerAuthorityProjectionTransitions::verifyEvent).orElse(null);
    if (sequence.equals(ZERO)) {
      if (!generation.equals(ONE) || !sourceVersion.equals(ONE) || latest != null) {
        throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
      }
    } else if (latest == null
        || !sameSource(latest, source.issuerId(), source.outboxStreamKey())
        || !sequence.equals(positiveDecimal(latest.outboxSequence(), "latest event sequence"))
        || !generation.equals(
            positiveDecimal(latest.issuerAuthGeneration(), "latest event generation"))
        || !sourceVersion.equals(
            positiveDecimal(latest.sourceVersion(), "latest event source version"))) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    return new Checkpoint(
        source.issuerId(), source.outboxStreamKey(), generation, sourceVersion, sequence, latest);
  }

  private static IssuerGenerationAuthorityEvent verifyEvent(
      IssuerGenerationAuthorityEvent supplied) {
    if (supplied == null) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    IssuerGenerationAuthorityEvent verified;
    try {
      verified = IssuerGenerationAuthorityEventV1Codec.verify(supplied.canonicalJson());
    } catch (IllegalArgumentException malformed) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    if (!verified.canonicalJson().equals(supplied.canonicalJson())
        || !verified.eventId().equals(supplied.eventId())
        || !verified.eventDigest().equals(supplied.eventDigest())
        || !verified.issuerId().equals(supplied.issuerId())
        || !verified.sourceScope().equals(supplied.sourceScope())
        || !verified.outboxStreamKey().equals(supplied.outboxStreamKey())
        || !verified.outboxSequence().equals(supplied.outboxSequence())
        || !verified.issuerAuthGeneration().equals(supplied.issuerAuthGeneration())
        || !verified.sourceVersion().equals(supplied.sourceVersion())) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    UUID eventRequestId;
    try {
      eventRequestId = UUID.fromString(verified.requestId());
    } catch (IllegalArgumentException malformed) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    if (new UUID(0L, 0L).equals(eventRequestId)
        || !eventRequestId.toString().equals(verified.requestId())
        || !(EVENT_ID_PREFIX + verified.requestId()).equals(verified.eventId())) {
      throw reject(QuarantineReason.MALFORMED_ACCOUNT_READBACK);
    }
    return verified;
  }

  private static boolean sameSource(
      IssuerGenerationAuthorityEvent event, String issuerId, String streamKey) {
    return issuerId.equals(event.issuerId())
        && ("issuer/" + issuerId).equals(event.sourceScope())
        && streamKey.equals(event.outboxStreamKey());
  }

  private static Map<String, Object> projectionFor(Checkpoint checkpoint, String appliedAt) {
    if (checkpoint.sequence().equals(ZERO)) {
      return projectionMap(
          checkpoint.issuerId(),
          checkpoint.generation().toString(),
          "0",
          checkpoint.streamKey(),
          null,
          appliedAt,
          Map.of());
    }
    return projectionFor(
        checkpoint.latestEvent(),
        checkpoint.generation().toString(),
        checkpoint.sequence().toString(),
        appliedAt);
  }

  private static Map<String, Object> projectionFor(
      IssuerGenerationAuthorityEvent event,
      String issuerGeneration,
      String sequence,
      String appliedAt) {
    return projectionMap(
        event.issuerId(),
        issuerGeneration,
        sequence,
        event.outboxStreamKey(),
        event,
        appliedAt,
        Map.of(sequence, event.canonicalJson()));
  }

  private static Map<String, Object> projectionMap(
      String issuerId,
      String issuerGeneration,
      String sequence,
      String streamKey,
      IssuerGenerationAuthorityEvent latestEvent,
      String appliedAt,
      Map<String, String> appliedSourceEvidence) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", SCHEMA_VERSION);
    fields.put("issuerId", issuerId);
    fields.put("lastAppliedIssuerGeneration", issuerGeneration);
    fields.put("lastAppliedSourceOutboxSequence", sequence);
    fields.put("outboxStreamKey", streamKey);
    if (latestEvent != null) {
      fields.put("lastAppliedSourceEventId", latestEvent.eventId());
      fields.put("lastAppliedSourceEventDigest", latestEvent.eventDigest());
    }
    fields.put("appliedAt", appliedAt);
    fields.put("appliedSourceEvidence", appliedSourceEvidence);
    return immutableMap(fields);
  }

  private static ExistingProjection parseExistingProjection(Map<String, ?> supplied) {
    if (supplied == null) {
      throw new IllegalArgumentException("projection is absent");
    }
    String schema = requiredString(supplied, "schemaVersion");
    String issuer = requiredString(supplied, "issuerId");
    String generationText = requiredString(supplied, "lastAppliedIssuerGeneration");
    String sequenceText = requiredString(supplied, "lastAppliedSourceOutboxSequence");
    String stream = requiredString(supplied, "outboxStreamKey");
    String appliedAt = requiredString(supplied, "appliedAt");
    if (!SCHEMA_VERSION.equals(schema) || issuer.isBlank() || appliedAt.isBlank()) {
      throw new IllegalArgumentException("projection identity or schema is malformed");
    }

    BigInteger generation = positiveDecimal(generationText, "projection generation");
    BigInteger sequence = nonnegativeDecimal(sequenceText, "projection sequence");
    String expectedStream =
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuer;
    if (!expectedStream.equals(stream)) {
      throw new IllegalArgumentException("projection stream identity is malformed");
    }

    Set<String> expectedFields = new java.util.HashSet<>(BASE_FIELDS);
    IssuerGenerationAuthorityEvent latest = null;
    BigInteger sourceVersion = ONE;
    Object evidenceValue = supplied.get("appliedSourceEvidence");
    if (!(evidenceValue instanceof Map<?, ?> evidence)) {
      throw new IllegalArgumentException("projection source evidence is malformed");
    }
    if (sequence.equals(ZERO)) {
      if (!generation.equals(ONE) || !evidence.isEmpty()) {
        throw new IllegalArgumentException("zero checkpoint projection is contradictory");
      }
    } else {
      expectedFields.addAll(POSITIVE_FIELDS);
      if (evidence.size() != 1) {
        throw new IllegalArgumentException("projection must retain its current source checkpoint");
      }
      Object canonicalValue = evidence.get(sequenceText);
      if (!(canonicalValue instanceof String canonicalEvent)) {
        throw new IllegalArgumentException("projection current source evidence is absent");
      }
      latest = verifiedProjectionEvent(canonicalEvent);
      if (!sameSource(latest, issuer, stream)
          || !sequence.equals(positiveDecimal(latest.outboxSequence(), "projection event sequence"))
          || !generation.equals(
              positiveDecimal(latest.issuerAuthGeneration(), "projection event generation"))
          || !requiredString(supplied, "lastAppliedSourceEventId").equals(latest.eventId())
          || !requiredString(supplied, "lastAppliedSourceEventDigest")
              .equals(latest.eventDigest())) {
        throw new IllegalArgumentException("projection event does not match its checkpoint");
      }
      sourceVersion = positiveDecimal(latest.sourceVersion(), "projection event source version");
    }
    if (!supplied.keySet().equals(expectedFields)) {
      throw new IllegalArgumentException("projection fields do not match the declared schema");
    }
    validateEvidenceMapKeys(evidence, sequence, sequenceText);
    return new ExistingProjection(issuer, generation, sourceVersion, sequence, stream, latest);
  }

  private static IssuerGenerationAuthorityEvent verifiedProjectionEvent(String canonicalEvent) {
    try {
      IssuerGenerationAuthorityEvent event =
          IssuerGenerationAuthorityEventV1Codec.verify(canonicalEvent);
      if (!event.canonicalJson().equals(canonicalEvent)) {
        throw new IllegalArgumentException("projection event is not canonical");
      }
      return verifyEvent(event);
    } catch (EvidenceRejected rejected) {
      throw new IllegalArgumentException("projection event evidence is malformed", rejected);
    }
  }

  private static void validateEvidenceMapKeys(
      Map<?, ?> evidence, BigInteger sequence, String sequenceText) {
    for (Map.Entry<?, ?> entry : evidence.entrySet()) {
      if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String)) {
        throw new IllegalArgumentException("projection applied-source evidence is malformed");
      }
      positiveDecimal(key, "projection evidence sequence");
      if (!sequence.equals(ZERO) && !sequenceText.equals(key)) {
        throw new IllegalArgumentException("projection evidence is not the current checkpoint");
      }
    }
  }

  private static String requiredString(Map<String, ?> fields, String name) {
    Object value = fields.get(name);
    if (!(value instanceof String text)) {
      throw new IllegalArgumentException("projection field " + name + " must be a string");
    }
    return text;
  }

  private static BigInteger positiveDecimal(String value, String label) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(label + " must be a positive canonical decimal");
    }
    return new BigInteger(value);
  }

  private static BigInteger nonnegativeDecimal(String value, String label) {
    if (value == null || !value.matches("(?:0|[1-9][0-9]*)")) {
      throw new IllegalArgumentException(label + " must be a canonical nonnegative decimal");
    }
    return new BigInteger(value);
  }

  private static void requireAppliedAt(String appliedAt) {
    if (appliedAt == null || appliedAt.isBlank()) {
      throw reject(QuarantineReason.APPLIED_AT_REQUIRED);
    }
  }

  private static Optional<Map<String, Object>> immutableObserved(Map<String, ?> observed) {
    if (observed == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(immutableMap(observed));
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private static Map<String, Object> immutableMap(Map<?, ?> source) {
    if (source == null) {
      throw new IllegalArgumentException("projection maps must not be null");
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("projection map keys must be strings");
      }
      copy.put(key, immutableValue(entry.getValue()));
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      return immutableMap(map);
    }
    if (value instanceof List<?> list) {
      List<Object> copy = new ArrayList<>(list.size());
      for (Object item : list) {
        copy.add(immutableValue(item));
      }
      return Collections.unmodifiableList(copy);
    }
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    throw new IllegalArgumentException("projection values must be JSON-compatible");
  }

  private static EvidenceRejected reject(QuarantineReason reason) {
    return new EvidenceRejected(reason);
  }

  private record Checkpoint(
      String issuerId,
      String streamKey,
      BigInteger generation,
      BigInteger sourceVersion,
      BigInteger sequence,
      IssuerGenerationAuthorityEvent latestEvent) {}

  private record VerifiedReadback(
      Checkpoint snapshot, Optional<IssuerGenerationAuthorityEvent> selectedEvent) {}

  private record ExistingProjection(
      String issuerId,
      BigInteger generation,
      BigInteger sourceVersion,
      BigInteger sequence,
      String streamKey,
      IssuerGenerationAuthorityEvent latestEvent) {}

  private static final class EvidenceRejected extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final QuarantineReason reason;

    private EvidenceRejected(QuarantineReason reason) {
      this.reason = reason;
    }

    private QuarantineReason reason() {
      return reason;
    }
  }

  public sealed interface Decision permits Mutation, NoOp, Quarantine {}

  public enum MutationKind {
    BOOTSTRAP,
    ADVANCE
  }

  public enum NoOpReason {
    EXACT_DUPLICATE,
    VERIFIED_HISTORICAL_DUPLICATE
  }

  public enum QuarantineReason {
    APPLIED_AT_REQUIRED,
    BOOTSTRAP_REQUIRES_CURRENT_READBACK,
    CURRENT_CHECKPOINT_CONFLICT,
    CURRENT_CHECKPOINT_REGRESSED,
    DUPLICATE_EVENT_CONFLICT,
    EVENT_GENERATION_REGRESSED,
    EVENT_SEQUENCE_GAP,
    EVENT_SOURCE_VERSION_REGRESSED,
    HISTORICAL_EVENT_PROGRESS_CONFLICT,
    ISSUER_OR_STREAM_CHANGED,
    MALFORMED_ACCOUNT_READBACK,
    MALFORMED_EXISTING_PROJECTION,
    MISSING_EXISTING_PROJECTION,
    SELECTED_EVENT_REQUIRED
  }

  /** An immutable atomic compare-and-set proposal. Empty expected state means bootstrap. */
  public record Mutation(
      Optional<Map<String, Object>> expectedProjection,
      Map<String, Object> nextProjection,
      MutationKind kind)
      implements Decision {
    public Mutation {
      expectedProjection = Objects.requireNonNull(expectedProjection, "expected state is required");
      expectedProjection =
          expectedProjection.map(IssuerAuthorityProjectionTransitions::immutableMap);
      nextProjection =
          immutableMap(Objects.requireNonNull(nextProjection, "next state is required"));
      Objects.requireNonNull(kind, "mutation kind is required");
    }

    public Map<String, Object> nextProjection() {
      return immutableMap(nextProjection);
    }
  }

  public record NoOp(NoOpReason reason) implements Decision {
    public NoOp {
      Objects.requireNonNull(reason, "no-op reason is required");
    }
  }

  /**
   * Quarantine contains the exact observed state when it could be represented as immutable JSON.
   */
  public record Quarantine(
      Optional<Map<String, Object>> expectedProjection, QuarantineReason reason)
      implements Decision {
    public Quarantine {
      expectedProjection = Objects.requireNonNull(expectedProjection, "expected state is required");
      expectedProjection =
          expectedProjection.map(IssuerAuthorityProjectionTransitions::immutableMap);
      Objects.requireNonNull(reason, "quarantine reason is required");
    }
  }
}
