package net.firedevops.firemud.common.account.authority;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;

/**
 * Validates the content and cross-binding of one current runtime membership evidence snapshot.
 *
 * <p>This does not authenticate the source or authorize gameplay admission. The membership event is
 * immutable historical evidence; a caller supplies the separate current tuple, fence and
 * checkpoints. Private-realm grant and tenant-billing tuple evidence are not supported by this
 * candidate and are rejected until their source contracts are available. An empty result for a
 * missing membership is only a local statement about the supplied snapshot, not proof that the
 * account has never joined.
 */
public final class RuntimeMembershipAuthorityEvidenceValidator {
  private static final String EVENT_STREAM_PREFIX =
      MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;
  private static final Set<String> MEMBERSHIP_LIFECYCLES = Set.of("ACTIVE", "INACTIVE");

  private RuntimeMembershipAuthorityEvidenceValidator() {}

  /**
   * Verifies every supplied source event and binds the immutable membership event to the current
   * recipient carrier. Returns empty only for a well-formed snapshot whose membership checkpoint is
   * zero and whose current membership state is explicitly missing and nonadmitting.
   *
   * @throws IllegalArgumentException if the snapshot is malformed, incomplete, inconsistent, or
   *     contains unsupported authority evidence
   */
  public static Optional<MembershipEvent> validate(Snapshot evidence) {
    Objects.requireNonNull(evidence, "evidence must not be null");

    requireCanonicalUuid(evidence.accountId(), "accountId");
    requireCanonicalUuid(evidence.tenantId(), "tenantId");
    requireNonBlank(evidence.issuerId(), "issuerId");
    requirePositiveDecimal(
        evidence.membershipAuthorityGeneration(), "membershipAuthorityGeneration");
    requirePositiveDecimal(evidence.issuanceFence(), "issuanceFence");
    requireExactTenantValue(evidence.membershipVersion(), evidence.tenantId(), "membershipVersion");

    AuthorityTuple currentTuple = evidence.authorityTuple();
    requireTupleShape(
        currentTuple,
        evidence.accountId(),
        evidence.tenantId(),
        evidence.membershipAuthorityGeneration(),
        "authorityTuple");

    String issuerStream = EVENT_STREAM_PREFIX + "issuer/" + evidence.issuerId();
    String accountStream = EVENT_STREAM_PREFIX + "account/" + evidence.accountId();
    String tenantStream = EVENT_STREAM_PREFIX + "tenant/" + evidence.tenantId();
    String membershipStream =
        EVENT_STREAM_PREFIX + "membership/" + evidence.accountId() + "/" + evidence.tenantId();
    List<String> expectedStreams =
        new ArrayList<>(List.of(issuerStream, accountStream, tenantStream, membershipStream));
    expectedStreams.sort(RuntimeMembershipAuthorityEvidenceValidator::compareUtf8);

    Map<String, Checkpoint> checkpoints =
        validateCheckpoints(evidence.checkpoints(), expectedStreams);
    Map<String, SourceEvidence> sources =
        validateSourceInventory(evidence.sourceEvidence(), checkpoints);

    verifyIssuerSource(
        sources.get(issuerStream),
        checkpoints.get(issuerStream),
        evidence.issuerId(),
        currentTuple.issuerAuthGeneration());
    AccountSource accountSource =
        verifyAccountSource(
            sources.get(accountStream),
            checkpoints.get(accountStream),
            evidence.accountId(),
            currentTuple.accountAuthorityGeneration());
    verifyTenantSource(
        sources.get(tenantStream),
        checkpoints.get(tenantStream),
        evidence.tenantId(),
        currentTuple.tenantAuthorityGeneration().get(evidence.tenantId()));

    validateCurrentAccountCutoff(
        currentTuple, accountSource, checkpoints.get(accountStream), accountStream);

    Checkpoint membershipCheckpoint = checkpoints.get(membershipStream);
    if (membershipCheckpoint.outboxSequence().equals("0")) {
      validateMissingMembership(evidence);
      return Optional.empty();
    }

    MembershipEvent membershipEvent =
        verifyMembershipSource(
            sources.get(membershipStream),
            membershipCheckpoint,
            evidence.accountId(),
            evidence.tenantId());
    validateMembershipCarrier(evidence, membershipEvent, currentTuple, accountSource);

    return Optional.of(membershipEvent);
  }

  private static Map<String, Checkpoint> validateCheckpoints(
      List<Checkpoint> supplied, List<String> expectedStreams) {
    if (supplied.size() != expectedStreams.size()) {
      throw invalid("checkpoints", "must contain exactly the four authority streams");
    }
    Map<String, Checkpoint> checkpoints = new HashMap<>();
    String previousStream = null;
    for (Checkpoint checkpoint : supplied) {
      if (!expectedStreams.contains(checkpoint.outboxStreamKey())) {
        throw invalid("checkpoints", "contains an unexpected authority stream");
      }
      if (previousStream != null
          && compareUtf8(previousStream, checkpoint.outboxStreamKey()) >= 0) {
        throw invalid("checkpoints", "must be sorted by stream key and contain no duplicates");
      }
      if (checkpoints.putIfAbsent(checkpoint.outboxStreamKey(), checkpoint) != null) {
        throw invalid("checkpoints", "contains a duplicate authority stream");
      }
      requireNonNegativeDecimal(checkpoint.outboxSequence(), "checkpoint.outboxSequence");
      previousStream = checkpoint.outboxStreamKey();
    }
    if (!checkpoints.keySet().equals(Set.copyOf(expectedStreams))) {
      throw invalid("checkpoints", "must cover each expected authority stream exactly once");
    }
    return Map.copyOf(checkpoints);
  }

  private static Map<String, SourceEvidence> validateSourceInventory(
      List<SourceEvidence> supplied, Map<String, Checkpoint> checkpoints) {
    Map<String, SourceEvidence> sources = new HashMap<>();
    String previousStream = null;
    for (SourceEvidence source : supplied) {
      if (previousStream != null && compareUtf8(previousStream, source.outboxStreamKey()) >= 0) {
        throw invalid("sourceEvidence", "must be sorted by stream key and contain no duplicates");
      }
      Checkpoint checkpoint = checkpoints.get(source.outboxStreamKey());
      if (checkpoint == null || checkpoint.outboxSequence().equals("0")) {
        throw invalid("sourceEvidence", "contains evidence without a positive checkpoint");
      }
      if (!checkpoint.outboxSequence().equals(source.outboxSequence())) {
        throw invalid("sourceEvidence", "sequence must equal its exact current checkpoint");
      }
      if (sources.putIfAbsent(source.outboxStreamKey(), source) != null) {
        throw invalid("sourceEvidence", "contains a duplicate authority stream");
      }
      previousStream = source.outboxStreamKey();
    }

    for (Checkpoint checkpoint : checkpoints.values()) {
      boolean hasSource = sources.containsKey(checkpoint.outboxStreamKey());
      if (checkpoint.outboxSequence().equals("0") == hasSource) {
        throw invalid(
            "sourceEvidence", "must contain one source for each positive checkpoint only");
      }
    }
    return Map.copyOf(sources);
  }

  private static void verifyIssuerSource(
      SourceEvidence supplied,
      Checkpoint checkpoint,
      String expectedIssuerId,
      String currentGeneration) {
    if (checkpoint.outboxSequence().equals("0")) {
      requireEquals("1", currentGeneration, "authorityTuple.issuerAuthGeneration baseline");
      return;
    }
    var verified = AccountAuthoritySourceEventV1Codec.verify(supplied.canonicalEventJson());
    if (!(verified instanceof AccountAuthoritySourceEventV1Codec.IssuerEvent event)) {
      throw invalid("issuer source", "must use the current issuer owner schema");
    }
    requireSourceIdentity(
        supplied,
        checkpoint,
        event.eventId(),
        event.eventDigest(),
        event.canonicalJson(),
        event.outboxSequence(),
        event.outboxStreamKey());
    requireEquals(expectedIssuerId, event.issuerId(), "issuer source issuerId");
    requireEquals(currentGeneration, event.issuerAuthGeneration(), "current issuer generation");
  }

  private static void verifyTenantSource(
      SourceEvidence supplied,
      Checkpoint checkpoint,
      String expectedTenantId,
      String currentGeneration) {
    if (checkpoint.outboxSequence().equals("0")) {
      requireEquals("1", currentGeneration, "authorityTuple.tenantAuthorityGeneration baseline");
      return;
    }
    throw invalid(
        "tenant source", "advanced tenant authority requires its supported owner source contract");
  }

  private static AccountSource verifyAccountSource(
      SourceEvidence supplied,
      Checkpoint checkpoint,
      String expectedAccountId,
      String currentGeneration) {
    if (checkpoint.outboxSequence().equals("0")) {
      requireEquals("1", currentGeneration, "authorityTuple.accountAuthorityGeneration baseline");
      return null;
    }

    List<Function<String, AccountSource>> decoders =
        List.of(
            RuntimeMembershipAuthorityEvidenceValidator::decodeCurrentAccountSource,
            RuntimeMembershipAuthorityEvidenceValidator::decodePasswordResetAccountSource,
            RuntimeMembershipAuthorityEvidenceValidator::decodeLogoutAllAccountSource,
            RuntimeMembershipAuthorityEvidenceValidator::decodeSecurityStateAccountSource);
    List<IllegalArgumentException> failures = new ArrayList<>(decoders.size());
    AccountSource event = null;
    for (Function<String, AccountSource> decoder : decoders) {
      try {
        event = decoder.apply(supplied.canonicalEventJson());
        break;
      } catch (IllegalArgumentException unsupportedSchema) {
        failures.add(unsupportedSchema);
      }
    }
    if (event == null) {
      IllegalArgumentException failure =
          invalid("account source", "does not match any supported Account event schema");
      failures.forEach(failure::addSuppressed);
      throw failure;
    }
    requireSourceIdentity(
        supplied,
        checkpoint,
        event.eventId(),
        event.eventDigest(),
        event.canonicalJson(),
        event.outboxSequence(),
        event.outboxStreamKey());
    requireEquals(expectedAccountId, event.accountId(), "account source accountId");
    requireEquals(
        currentGeneration, event.accountAuthorityGeneration(), "current account generation");
    requireEquals(
        event.accountAuthorityGeneration(), event.cutoffGeneration(), "account cutoff generation");
    requireEquals(event.outboxStreamKey(), event.cutoffStream(), "account cutoff stream key");
    requireEquals(event.outboxSequence(), event.cutoffSequence(), "account cutoff sequence");
    return event;
  }

  private static AccountSource decodeCurrentAccountSource(String canonicalJson) {
    var verified = AccountAuthoritySourceEventV1Codec.verify(canonicalJson);
    if (!(verified instanceof AccountAuthoritySourceEventV1Codec.AccountEvent account)) {
      throw invalid("account source", "must be an Account event");
    }
    return new AccountSource(
        account.eventId(),
        account.eventDigest(),
        account.canonicalJson(),
        account.outboxStreamKey(),
        account.outboxSequence(),
        account.accountId(),
        account.accountAuthorityGeneration(),
        account.accountSecurityCutoff().accountAuthorityGeneration(),
        account.accountSecurityCutoff().outboxStreamKey(),
        account.accountSecurityCutoff().outboxSequence());
  }

  private static AccountSource decodePasswordResetAccountSource(String canonicalJson) {
    var reset = PasswordResetAuthorityEventV1Codec.verify(canonicalJson);
    return new AccountSource(
        reset.eventId(),
        reset.eventDigest(),
        reset.canonicalJson(),
        reset.outboxStreamKey(),
        reset.outboxSequence(),
        reset.accountId(),
        reset.accountAuthorityGeneration(),
        reset.accountSecurityCutoff().accountAuthorityGeneration(),
        reset.accountSecurityCutoff().outboxStreamKey(),
        reset.accountSecurityCutoff().outboxSequence());
  }

  private static AccountSource decodeLogoutAllAccountSource(String canonicalJson) {
    var logout = AccountLogoutAllAuthorityEventV1Codec.verify(canonicalJson);
    return new AccountSource(
        logout.eventId(),
        logout.eventDigest(),
        logout.canonicalJson(),
        logout.outboxStreamKey(),
        logout.outboxSequence(),
        logout.accountId(),
        logout.accountAuthorityGeneration(),
        logout.accountSecurityCutoff().accountAuthorityGeneration(),
        logout.accountSecurityCutoff().outboxStreamKey(),
        logout.accountSecurityCutoff().outboxSequence());
  }

  private static AccountSource decodeSecurityStateAccountSource(String canonicalJson) {
    var security = AccountSecurityStateAuthorityEventV1Codec.verify(canonicalJson);
    return new AccountSource(
        security.eventId(),
        security.eventDigest(),
        security.canonicalJson(),
        security.outboxStreamKey(),
        security.outboxSequence(),
        security.accountId(),
        security.accountAuthorityGeneration(),
        security.accountSecurityCutoff().accountAuthorityGeneration(),
        security.accountSecurityCutoff().outboxStreamKey(),
        security.accountSecurityCutoff().outboxSequence());
  }

  private static MembershipEvent verifyMembershipSource(
      SourceEvidence supplied,
      Checkpoint checkpoint,
      String expectedAccountId,
      String expectedTenantId) {
    MembershipEvent event = MembershipAuthorityEventV1Codec.verify(supplied.canonicalEventJson());
    requireSourceIdentity(
        supplied,
        checkpoint,
        event.eventId(),
        event.eventDigest(),
        event.canonicalJson(),
        event.outboxSequence(),
        event.outboxStreamKey());
    requireEquals(expectedAccountId, event.accountId(), "membership source accountId");
    requireEquals(expectedTenantId, event.tenantId(), "membership source tenantId");
    return event;
  }

  private static void requireSourceIdentity(
      SourceEvidence supplied,
      Checkpoint checkpoint,
      String eventId,
      String eventDigest,
      String canonicalJson,
      String eventSequence,
      String eventStreamKey) {
    requireEquals(checkpoint.outboxStreamKey(), eventStreamKey, "source event stream key");
    requireEquals(
        checkpoint.outboxStreamKey(), supplied.outboxStreamKey(), "source carrier stream key");
    requireEquals(
        checkpoint.outboxSequence(), supplied.outboxSequence(), "source carrier sequence");
    requireEquals(checkpoint.outboxSequence(), eventSequence, "source event sequence");
    requireEquals(eventId, supplied.eventId(), "source carrier eventId");
    requireEquals(eventDigest, supplied.eventDigest(), "source carrier eventDigest");
    requireEquals(
        canonicalJson, supplied.canonicalEventJson(), "source carrier canonical event bytes");
  }

  private static void validateCurrentAccountCutoff(
      AuthorityTuple tuple,
      AccountSource source,
      Checkpoint checkpoint,
      String expectedAccountStream) {
    Optional<AccountSecurityCutoff> cutoff = tuple.accountSecurityCutoff();
    if (checkpoint.outboxSequence().equals("0")) {
      if (cutoff.isPresent()) {
        throw invalid(
            "authorityTuple.accountSecurityCutoff", "must be absent at the account baseline");
      }
      return;
    }
    if (cutoff.isEmpty() || source == null) {
      throw invalid(
          "authorityTuple.accountSecurityCutoff", "must bind the current account source event");
    }
    AccountSecurityCutoff current = cutoff.orElseThrow();
    requireEquals(
        tuple.accountAuthorityGeneration(),
        current.accountAuthorityGeneration(),
        "current account cutoff generation");
    requireEquals(
        expectedAccountStream, current.outboxStreamKey(), "current account cutoff stream key");
    requireEquals(
        checkpoint.outboxSequence(), current.outboxSequence(), "current account cutoff sequence");
    requireEquals(
        source.cutoffGeneration(),
        current.accountAuthorityGeneration(),
        "current account cutoff source generation");
    requireEquals(
        source.cutoffStream(), current.outboxStreamKey(), "current account cutoff source stream");
    requireEquals(
        source.cutoffSequence(),
        current.outboxSequence(),
        "current account cutoff source sequence");
  }

  private static void validateMissingMembership(Snapshot evidence) {
    if (evidence.membershipExists()
        || !"MISSING".equals(evidence.membershipLifecycleState())
        || evidence.gameplayAdmissionAllowed()
        || !evidence.roles().isEmpty()) {
      throw invalid(
          "membership", "zero checkpoint requires MISSING, absent, empty-role, nonadmitting state");
    }
    // The absent-row representation still carries positive persisted defaults. This check is
    // content validation only and cannot establish that a prior membership never existed.
    requirePositiveDecimal(
        evidence.membershipVersion().get(evidence.tenantId()), "membershipVersion");
    requirePositiveDecimal(
        evidence.membershipAuthorityGeneration(), "membershipAuthorityGeneration");
    requirePositiveDecimal(evidence.issuanceFence(), "issuanceFence");
  }

  private static void validateMembershipCarrier(
      Snapshot evidence,
      MembershipEvent event,
      AuthorityTuple currentTuple,
      AccountSource currentAccountSource) {
    if (!evidence.membershipExists()) {
      throw invalid("membershipExists", "must be true for a positive membership checkpoint");
    }
    if (!MEMBERSHIP_LIFECYCLES.contains(evidence.membershipLifecycleState())) {
      throw invalid(
          "membershipLifecycleState", "must be ACTIVE or INACTIVE for an existing membership");
    }
    requireEquals(
        event.membershipLifecycleState(),
        evidence.membershipLifecycleState(),
        "membershipLifecycleState");
    requireEquals(
        event.gameplayAdmissionAllowed(),
        evidence.gameplayAdmissionAllowed(),
        "gameplayAdmissionAllowed");
    requireEquals(event.roles(), evidence.roles(), "roles");
    requireEquals(event.membershipVersion(), evidence.membershipVersion(), "membershipVersion");
    requireEquals(
        event.membershipAuthorityGeneration(),
        evidence.membershipAuthorityGeneration(),
        "membershipAuthorityGeneration");
    requireEquals(
        event.membershipAuthorityGeneration(),
        currentTuple.membershipAuthorityGeneration().get(evidence.tenantId()),
        "current membership authority generation");

    AuthorityTuple eventTuple = event.authorityTuple();
    requireSupportedTupleExtensions(eventTuple, "membership source authorityTuple");
    requireAtMost(
        eventTuple.issuerAuthGeneration(),
        currentTuple.issuerAuthGeneration(),
        "membership source issuerAuthGeneration");
    requireAtMost(
        eventTuple.accountAuthorityGeneration(),
        currentTuple.accountAuthorityGeneration(),
        "membership source accountAuthorityGeneration");
    requireAtMost(
        eventTuple.tenantAuthorityGeneration().get(evidence.tenantId()),
        currentTuple.tenantAuthorityGeneration().get(evidence.tenantId()),
        "membership source tenantAuthorityGeneration");
    requireAtMost(
        event.issuanceFence(), evidence.issuanceFence(), "membership source issuanceFence");
    validateHistoricalAccountCutoff(eventTuple, currentTuple, currentAccountSource);
  }

  private static void validateHistoricalAccountCutoff(
      AuthorityTuple eventTuple, AuthorityTuple currentTuple, AccountSource currentAccountSource) {
    Optional<AccountSecurityCutoff> historicalCutoff = eventTuple.accountSecurityCutoff();
    Optional<AccountSecurityCutoff> currentCutoff = currentTuple.accountSecurityCutoff();
    int accountGenerationOrder =
        compareDecimal(
            eventTuple.accountAuthorityGeneration(), currentTuple.accountAuthorityGeneration());
    if (accountGenerationOrder == 0) {
      if (historicalCutoff.isPresent() != currentCutoff.isPresent()) {
        throw invalid(
            "membership source accountSecurityCutoff",
            "must match cutoff presence at the same generation");
      }
      if (historicalCutoff.isPresent()
          && !historicalCutoff.orElseThrow().equals(currentCutoff.orElseThrow())) {
        throw invalid(
            "membership source accountSecurityCutoff",
            "must equal the current cutoff at the same generation");
      }
    }
    if (historicalCutoff.isEmpty()) {
      requireEquals(
          "1",
          eventTuple.accountAuthorityGeneration(),
          "membership source accountAuthorityGeneration without a cutoff");
      return;
    }
    if (currentCutoff.isEmpty() || currentAccountSource == null) {
      throw invalid(
          "membership source accountSecurityCutoff",
          "cannot exceed or outlive current account cutoff evidence");
    }
    AccountSecurityCutoff historical = historicalCutoff.orElseThrow();
    AccountSecurityCutoff current = currentCutoff.orElseThrow();
    requireEquals(
        current.outboxStreamKey(),
        historical.outboxStreamKey(),
        "historical account cutoff stream key");
    requireAtMost(
        historical.accountAuthorityGeneration(),
        current.accountAuthorityGeneration(),
        "historical account cutoff generation");
    requireAtMost(
        historical.outboxSequence(),
        current.outboxSequence(),
        "historical account cutoff sequence");
  }

  private static void requireTupleShape(
      AuthorityTuple tuple,
      String accountId,
      String tenantId,
      String membershipGeneration,
      String path) {
    requirePositiveDecimal(tuple.issuerAuthGeneration(), path + ".issuerAuthGeneration");
    requirePositiveDecimal(
        tuple.accountAuthorityGeneration(), path + ".accountAuthorityGeneration");
    requireExactTenantValue(
        tuple.tenantAuthorityGeneration(), tenantId, path + ".tenantAuthorityGeneration");
    requireExactTenantValue(
        tuple.membershipAuthorityGeneration(), tenantId, path + ".membershipAuthorityGeneration");
    requireEquals(
        membershipGeneration,
        tuple.membershipAuthorityGeneration().get(tenantId),
        path + ".membershipAuthorityGeneration");
    requireSupportedTupleExtensions(tuple, path);
    tuple
        .accountSecurityCutoff()
        .ifPresent(
            cutoff -> {
              requirePositiveDecimal(
                  cutoff.accountAuthorityGeneration(),
                  path + ".accountSecurityCutoff.accountAuthorityGeneration");
              requireEquals(
                  EVENT_STREAM_PREFIX + "account/" + accountId,
                  cutoff.outboxStreamKey(),
                  path + ".accountSecurityCutoff.outboxStreamKey");
              requirePositiveDecimal(
                  cutoff.outboxSequence(), path + ".accountSecurityCutoff.outboxSequence");
              requireEquals(
                  tuple.accountAuthorityGeneration(),
                  cutoff.accountAuthorityGeneration(),
                  path + ".accountSecurityCutoff.accountAuthorityGeneration");
            });
  }

  private static void requireSupportedTupleExtensions(AuthorityTuple tuple, String path) {
    if (!tuple.privateRealmGrantVersions().isEmpty()) {
      throw invalid(path + ".privateRealmGrantVersions", "is not supported by this candidate");
    }
    if (tuple.tenantBillingCutoff().isPresent()) {
      throw invalid(path + ".tenantBillingCutoff", "is not supported by this candidate");
    }
  }

  private static void requireExactTenantValue(
      Map<String, String> values, String tenantId, String path) {
    if (values.size() != 1 || !values.containsKey(tenantId)) {
      throw invalid(path, "must contain exactly the current tenantId key");
    }
    requirePositiveDecimal(values.get(tenantId), path + "." + tenantId);
  }

  private static void requireCanonicalUuid(String value, String path) {
    if (!StrictAuthorityEventSupport.isCanonicalUuid(value)) {
      throw invalid(path, "must be a canonical lowercase non-nil UUID");
    }
  }

  private static void requireNonBlank(String value, String path) {
    if (value.isBlank()) {
      throw invalid(path, "must be nonblank");
    }
  }

  private static void requirePositiveDecimal(String value, String path) {
    if (!StrictAuthorityEventSupport.isPositiveCanonicalDecimal(value)) {
      throw invalid(path, "must be a positive canonical decimal string");
    }
  }

  private static void requireNonNegativeDecimal(String value, String path) {
    if (!StrictAuthorityEventSupport.isNonNegativeCanonicalDecimal(value)) {
      throw invalid(path, "must be a non-negative canonical decimal string");
    }
  }

  private static void requireAtMost(String lowerOrEqual, String upper, String path) {
    requirePositiveDecimal(lowerOrEqual, path);
    requirePositiveDecimal(upper, path + " current");
    if (compareDecimal(lowerOrEqual, upper) > 0) {
      throw invalid(path, "must not be ahead of current authority");
    }
  }

  private static int compareDecimal(String left, String right) {
    return new BigInteger(left).compareTo(new BigInteger(right));
  }

  private static int compareUtf8(String left, String right) {
    byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
    byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
    int commonLength = Math.min(leftBytes.length, rightBytes.length);
    for (int index = 0; index < commonLength; index++) {
      int comparison =
          Integer.compare(
              Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftBytes.length, rightBytes.length);
  }

  private static void requireEquals(Object expected, Object actual, String path) {
    if (!Objects.equals(expected, actual)) {
      throw invalid(path, "does not match its bound evidence");
    }
  }

  private static IllegalArgumentException invalid(String path, String message) {
    return new IllegalArgumentException(path + " " + message);
  }

  private record AccountSource(
      String eventId,
      String eventDigest,
      String canonicalJson,
      String outboxStreamKey,
      String outboxSequence,
      String accountId,
      String accountAuthorityGeneration,
      String cutoffGeneration,
      String cutoffStream,
      String cutoffSequence) {}

  /** Immutable sequence checkpoint for one exact Account authority stream. */
  public record Checkpoint(String outboxStreamKey, String outboxSequence) {
    public Checkpoint {
      Objects.requireNonNull(outboxStreamKey, "outboxStreamKey must not be null");
      Objects.requireNonNull(outboxSequence, "outboxSequence must not be null");
    }
  }

  /** Immutable source-event carrier bound to its exact stream and checkpoint. */
  public record SourceEvidence(
      String outboxStreamKey,
      String outboxSequence,
      String eventId,
      String eventDigest,
      String canonicalEventJson) {
    public SourceEvidence {
      Objects.requireNonNull(outboxStreamKey, "outboxStreamKey must not be null");
      Objects.requireNonNull(outboxSequence, "outboxSequence must not be null");
      Objects.requireNonNull(eventId, "eventId must not be null");
      Objects.requireNonNull(eventDigest, "eventDigest must not be null");
      Objects.requireNonNull(canonicalEventJson, "canonicalEventJson must not be null");
    }
  }

  /** Immutable current recipient carrier plus the exact source evidence it claims to summarize. */
  public record Snapshot(
      String issuerId,
      String accountId,
      String tenantId,
      boolean membershipExists,
      String membershipLifecycleState,
      boolean gameplayAdmissionAllowed,
      Map<String, String> membershipVersion,
      String membershipAuthorityGeneration,
      List<String> roles,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      List<Checkpoint> checkpoints,
      List<SourceEvidence> sourceEvidence) {
    public Snapshot {
      Objects.requireNonNull(issuerId, "issuerId must not be null");
      Objects.requireNonNull(accountId, "accountId must not be null");
      Objects.requireNonNull(tenantId, "tenantId must not be null");
      Objects.requireNonNull(membershipLifecycleState, "membershipLifecycleState must not be null");
      membershipVersion =
          Map.copyOf(
              Objects.requireNonNull(membershipVersion, "membershipVersion must not be null"));
      Objects.requireNonNull(
          membershipAuthorityGeneration, "membershipAuthorityGeneration must not be null");
      roles = List.copyOf(Objects.requireNonNull(roles, "roles must not be null"));
      Objects.requireNonNull(authorityTuple, "authorityTuple must not be null");
      Objects.requireNonNull(issuanceFence, "issuanceFence must not be null");
      checkpoints =
          List.copyOf(Objects.requireNonNull(checkpoints, "checkpoints must not be null"));
      sourceEvidence =
          List.copyOf(Objects.requireNonNull(sourceEvidence, "sourceEvidence must not be null"));
    }
  }
}
