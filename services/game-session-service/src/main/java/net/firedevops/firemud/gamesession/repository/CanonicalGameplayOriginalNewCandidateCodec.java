package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;

/** Closed, bounded RFC 8785 codec for the distinct original-NEW candidate purpose. */
final class CanonicalGameplayOriginalNewCandidateCodec {
  static final int MAX_CANDIDATE_BYTES = 128 * 1024;
  private static final int MAX_COUNTER_DIGITS = 256;
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,255}");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]{0,255})");
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(12)
                  .maxStringLength(MAX_CANDIDATE_BYTES)
                  .maxNumberLength(8)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final ObjectMapper JSON =
      new ObjectMapper(JSON_FACTORY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> ROOT_FIELDS =
      Set.of("purpose", "bindingDecisionId", "leaseEvidence", "binding", "target", "transition", "reservation");
  private static final Set<String> BINDING_FIELDS =
      Set.of(
          "accountId",
          "tenantId",
          "playableStateNamespaceId",
          "characterId",
          "playableStateScope",
          "gameInstanceId",
          "runtimeGameInstanceId",
          "sessionId",
          "regionId",
          "regionEpoch",
          "issuerId",
          "issuerAuthGeneration",
          "issuerIndexLayoutVersion",
          "issuerIndexPartitionCount",
          "issuerIndexPartitionCapacity");
  private static final Set<String> TARGET_FIELDS =
      Set.of(
          "targetNamespace",
          "tenantSlug",
          "canonicalTenantId",
          "worldSlug",
          "worldDisplayName",
          "realmId",
          "realmSlug",
          "realmDisplayName",
          "gameSessionTenantId",
          "gameInstanceId",
          "playableStateNamespaceId",
          "playableStateScope",
          "canonicalGameInstanceId",
          "canonicalVersionId",
          "runtimeVersionId",
          "catalogRevision",
          "pointerVersion",
          "admissionPointerSnapshotDigest",
          "activeWorldEpoch",
          "initialAdmissionRequestId",
          "initialAdmissionRequestDigest",
          "initialAdmissionOriginKind",
          "holdId",
          "holdFence",
          "holdBindingDigest",
          "auditEventId",
          "ownerProofDigest",
          "ownerProofOutcome",
          "positiveDurableAbort",
          "ownerProofTerminalAt",
          "characterCreationPolicy");
  private static final Set<String> TRANSITION_FIELDS =
      Set.of(
          "transitionId",
          "bindingRef",
          "bindingGeneration",
          "issuerReservationId",
          "reservationFence",
          "candidateAccountIndexFence");
  private static final Set<String> RESERVATION_FIELDS =
      Set.of(
          "reservationId",
          "ownerBindingRef",
          "bindingGeneration",
          "transitionId",
          "partitionId",
          "lifecycle",
          "reservationFence",
          "issuerCoverageOperationId",
          "issuerCoverageOperationFence",
          "coverageFence",
          "inventorySnapshotRevision");

  private CanonicalGameplayOriginalNewCandidateCodec() {}

  static byte[] encode(CanonicalGameplayOriginalNewCandidate candidate) {
    Objects.requireNonNull(candidate, "candidate");
    try {
      requireCounterLimit(candidate.bindingIdentity().regionEpoch(), "regionEpoch", false);
      requireCounterLimit(
          candidate.bindingIdentity().issuerAuthGeneration(), "issuerAuthGeneration", false);
      requireCounterLimit(
          candidate.bindingIdentity().issuerIndexLayoutVersion(),
          "issuerIndexLayoutVersion",
          false);
      requireCounterLimit(
          candidate.bindingIdentity().issuerIndexPartitionCount(),
          "issuerIndexPartitionCount",
          false);
      requireCounterLimit(
          candidate.bindingIdentity().issuerIndexPartitionCapacity(),
          "issuerIndexPartitionCapacity",
          false);
      requireCounterLimit(candidate.transition().bindingGeneration(), "bindingGeneration", false);
      requireCounterLimit(
          candidate.reservation().bindingGeneration(), "reservation.bindingGeneration", false);
      requireCounterLimit(candidate.reservation().partitionId(), "partitionId", true);
      requireCounterLimit(
          candidate.reservation().issuerCoverageOperationFence(),
          "issuerCoverageOperationFence",
          false);
      requireCounterLimit(candidate.reservation().coverageFence(), "coverageFence", false);
      requireCounterLimit(candidate.reservation().inventorySnapshotRevision(),
          "inventorySnapshotRevision", false);

      ObjectNode root = JSON.createObjectNode();
      root.put("purpose", CanonicalGameplayOriginalNewCandidate.PURPOSE);
      root.put("bindingDecisionId", candidate.bindingDecisionId().toString());
      root.set("leaseEvidence", parseCanonicalLeaseNode(candidate.leaseEvidence()));
      root.set("binding", bindingNode(candidate.bindingIdentity()));
      root.set("target", targetNode(candidate.targetEvidence()));
      root.set("transition", transitionNode(candidate.transition()));
      root.set("reservation", reservationNode(candidate.reservation()));
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (canonical.length == 0 || canonical.length > MAX_CANDIDATE_BYTES) {
        throw invalid("Original NEW candidate exceeds its 128 KiB resource bound");
      }
      return canonical;
    } catch (IOException failure) {
      throw new IllegalArgumentException("Could not encode original NEW candidate", failure);
    }
  }

  static CanonicalGameplayOriginalNewCandidate decode(byte[] encoded) {
    Objects.requireNonNull(encoded, "encoded");
    if (encoded.length == 0 || encoded.length > MAX_CANDIDATE_BYTES) {
      throw invalid("Original NEW candidate is empty or exceeds its resource bound");
    }
    String json = decodeUtf8(encoded);
    try {
      ObjectNode root = requireObject(JSON.readTree(json), "candidate");
      requireFields(root, ROOT_FIELDS, "candidate");
      if (!text(root, "purpose").equals(CanonicalGameplayOriginalNewCandidate.PURPOSE)) {
        throw invalid("Candidate purpose is unsupported");
      }

      AccountGameplayAdmissionLeaseEvidence leaseEvidence =
          parseLeaseEvidence(root.get("leaseEvidence"));
      CanonicalGameplayBindingIdentity binding = parseBinding(root.get("binding"));
      CanonicalPlayableTarget target = parseTarget(root.get("target"));
      CanonicalGameplayOriginalNewCandidate.TransitionSource transition =
          parseTransition(root.get("transition"));
      CanonicalGameplayOriginalNewCandidate.ReservationSource reservation =
          parseReservation(root.get("reservation"));
      UUID decisionId = uuid(root, "bindingDecisionId");

      var decoded =
          new CanonicalGameplayOriginalNewCandidate(
              decisionId, leaseEvidence, binding, target, transition, reservation);
      validateDecodedSources(decoded);
      if (!Arrays.equals(encode(decoded), encoded)) {
        throw invalid("Original NEW candidate bytes are not canonical");
      }
      return decoded;
    } catch (IOException failure) {
      throw new IllegalArgumentException("Malformed original NEW candidate", failure);
    }
  }

  static boolean matchesOriginalDecision(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayAdmissionDecision decision) {
    return decision != null
        && decision.status() == CanonicalGameplayAdmissionDecision.Status.PROVISIONAL
        && candidate.bindingDecisionId().equals(decision.bindingDecisionId())
        && candidate.leaseEvidence().hasSameIdentity(decision.leaseEvidence())
        && candidate.bindingIdentity().equals(decision.candidate())
        && candidate.transition().bindingGeneration().equals(decision.bindingGeneration())
        && decision.expectedOldBindingGeneration() == null
        && decision.resumeEpisodeId() == null
        && candidate.targetEvidence().equals(decision.targetEvidence())
        && isPublicProductionNewLease(decision.leaseEvidence());
  }

  static boolean matchesProvisionalSources(
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    if (evidence == null) return false;
    try {
      requireCandidateSourceShape(evidence);
      CanonicalGameplayBindingTransitionSnapshot transition = evidence.transition();
      CanonicalGameplayBindingInventoryEntry entry = evidence.candidate();
      CanonicalIssuerPartitionReservation reservation = evidence.candidateReservation();
      return candidate.bindingIdentity().equals(entry.identity())
          && candidate.transition().transitionId().equals(transition.transitionId())
          && candidate.transition().bindingRef().equals(transition.bindingRef())
          && candidate.transition().bindingGeneration().equals(transition.bindingGeneration())
          && candidate.transition().issuerReservationId().equals(transition.issuerReservationId())
          && candidate.transition().reservationFence().equals(transition.reservationFence())
          && candidate.transition()
              .candidateAccountIndexFence()
              .equals(transition.candidateAccountIndexFence())
          && candidate.transition().bindingRef().equals(entry.bindingRef())
          && candidate.transition().bindingGeneration().equals(entry.bindingGeneration())
          && candidate.transition().candidateAccountIndexFence().equals(entry.accountIndexFence())
          && candidate.reservation().equals(reservationSource(reservation));
    } catch (RuntimeException invalidSource) {
      return false;
    }
  }

  static void requireOriginalNewSources(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalGameplayAdmissionDecision decision) {
    requireCandidateSourceShape(evidence);
    if (!isPublicProductionNewLease(decision.leaseEvidence())) {
      throw invalid("Only the selected public-production original NEW lease is supported");
    }
    CanonicalGameplayBindingTransitionSnapshot transition = evidence.transition();
    CanonicalGameplayBindingInventoryEntry candidate = evidence.candidate();
    CanonicalIssuerPartitionReservation reservation = evidence.candidateReservation();
    if (!transition.transitionId().equals(decision.bindingDecisionId())
        || !candidate.identity().equals(decision.candidate())
        || !candidate.bindingGeneration().equals(decision.bindingGeneration())
        || decision.expectedOldBindingGeneration() != null
        || decision.resumeEpisodeId() != null
        || !candidate.identity().equals(reservation.owner())
        || !candidate.bindingGeneration().equals(reservation.bindingGeneration())
        || !transition.transitionId().equals(reservation.transitionId())
        || !transition.reservationFence().equals(reservation.reservationFence())
        || !transition.issuerReservationId().equals(reservation.reservationId())) {
      throw invalid("Decision, candidate, transition, and reservation identities differ");
    }
  }

  private static void requireCandidateSourceShape(
      CanonicalGameplayBindingProvisionalCasEvidence evidence) {
    Objects.requireNonNull(evidence, "provisionalEvidence");
    CanonicalGameplayBindingTransitionSnapshot transition = evidence.transition();
    CanonicalGameplayBindingInventoryEntry candidate = evidence.candidate();
    CanonicalIssuerPartitionReservation reservation = evidence.candidateReservation();
    if (evidence.expectedPrior() != null
        || evidence.expectedPriorReservation() != null
        || (transition.status() != CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED
            && transition.status() != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL)
        || transition.expectedPriorBindingRef() != null
        || transition.expectedPriorBindingGeneration() != null
        || !transition.transitionId().equals(candidate.transitionId())
        || !transition.bindingRef().equals(candidate.bindingRef())
        || !transition.bindingGeneration().equals(candidate.bindingGeneration())
        || !transition.candidate().equals(candidate.identity())
        || !transition.issuerReservationId().equals(candidate.issuerReservationId())
        || !transition.candidateAccountIndexFence().equals(candidate.accountIndexFence())
        || !reservation.owner().equals(candidate.identity())
        || !reservation.bindingGeneration().equals(candidate.bindingGeneration())
        || !reservation.transitionId().equals(transition.transitionId())
        || !reservation.reservationId().equals(transition.issuerReservationId())
        || !reservation.reservationFence().equals(transition.reservationFence())
        || reservation.lifecycle() != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED) {
      throw invalid("Source is not an exact no-prior PREPARED or PROVISIONAL NEW candidate");
    }
  }

  private static boolean isPublicProductionNewLease(
      AccountGameplayAdmissionLeaseEvidence evidence) {
    return evidence != null
        && evidence.leaseKind() == AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        && "PUBLIC_PRODUCTION".equals(evidence.carrier().get("mode"))
        && !evidence.carrier().containsKey("expectedOldBindingGeneration")
        && !evidence.carrier().containsKey("resumeEpisodeId");
  }

  private static void validateDecodedSources(CanonicalGameplayOriginalNewCandidate candidate) {
    var binding = candidate.bindingIdentity();
    var transition = candidate.transition();
    var reservation = candidate.reservation();
    if (!candidate.bindingDecisionId().equals(transition.transitionId())
        || !binding.bindingRef().equals(transition.bindingRef())
        || !binding.bindingRef().equals(reservation.ownerBindingRef())
        || !transition.bindingGeneration().equals(bindingGeneration(candidate.leaseEvidence()))
        || !transition.bindingGeneration().equals(reservation.bindingGeneration())
        || !transition.transitionId().equals(reservation.transitionId())
        || !transition.issuerReservationId().equals(reservation.reservationId())
        || !transition.reservationFence().equals(reservation.reservationFence())
        || !reservation.partitionId().equals(binding.bindingRef().partitionId(binding.issuerIndexPartitionCount()))) {
      throw invalid("Candidate source identities, generation, or fences do not agree");
    }
    if (!isPublicProductionNewLease(candidate.leaseEvidence())) {
      throw invalid("Candidate does not retain the selected public-production NEW lease");
    }
    try {
      // Reuse the existing decision's cross-source checks. This temporary shape check is not
      // returned as an original decision and establishes neither provenance nor readback.
      new CanonicalGameplayAdmissionDecision(
          candidate.bindingDecisionId(),
          candidate.leaseEvidence(),
          binding,
          transition.bindingGeneration(),
          null,
          candidate.targetEvidence());
    } catch (RuntimeException mismatch) {
      throw invalid("Candidate target differs from its complete lease binding", mismatch);
    }
  }

  private static BigInteger bindingGeneration(AccountGameplayAdmissionLeaseEvidence lease) {
    Object scope = lease.carrier().get("bindingScope");
    if (!(scope instanceof java.util.Map<?, ?> map)
        || !(map.get("bindingGeneration") instanceof String generation)) {
      throw invalid("Lease has no binding generation");
    }
    return positiveDecimal(generation, "bindingGeneration");
  }

  static CanonicalGameplayOriginalNewCandidate.ReservationSource reservationSource(
      CanonicalIssuerPartitionReservation reservation) {
    return new CanonicalGameplayOriginalNewCandidate.ReservationSource(
        reservation.reservationId(),
        reservation.owner().bindingRef(),
        reservation.bindingGeneration(),
        reservation.transitionId(),
        reservation.partitionId(),
        reservation.reservationFence(),
        reservation.issuerCoverageOperationId(),
        reservation.issuerCoverageOperationFence(),
        reservation.coverageFence(),
        reservation.inventorySnapshotRevision());
  }

  private static ObjectNode bindingNode(CanonicalGameplayBindingIdentity binding) {
    ObjectNode node = JSON.createObjectNode();
    node.put("accountId", binding.accountId().toString());
    node.put("tenantId", binding.tenantId().toString());
    node.put("playableStateNamespaceId", binding.playableStateNamespaceId().toString());
    node.put("characterId", binding.characterId().toString());
    node.put("playableStateScope", binding.playableStateScope());
    node.put("gameInstanceId", binding.gameInstanceId().toString());
    node.put("runtimeGameInstanceId", Long.toString(binding.runtimeGameInstanceId()));
    node.put("sessionId", binding.sessionId());
    node.put("regionId", binding.regionId().toString());
    node.put("regionEpoch", binding.regionEpoch().toString());
    node.put("issuerId", binding.issuerId());
    node.put("issuerAuthGeneration", binding.issuerAuthGeneration().toString());
    node.put("issuerIndexLayoutVersion", binding.issuerIndexLayoutVersion().toString());
    node.put("issuerIndexPartitionCount", binding.issuerIndexPartitionCount().toString());
    node.put("issuerIndexPartitionCapacity", binding.issuerIndexPartitionCapacity().toString());
    return node;
  }

  private static ObjectNode targetNode(CanonicalPlayableTarget target) {
    ObjectNode node = JSON.createObjectNode();
    node.put("targetNamespace", target.targetNamespace());
    node.put("tenantSlug", target.tenantSlug());
    node.put("canonicalTenantId", target.canonicalTenantId().toString());
    node.put("worldSlug", target.worldSlug());
    node.put("worldDisplayName", target.worldDisplayName());
    node.put("realmId", target.realmId().toString());
    node.put("realmSlug", target.realmSlug());
    node.put("realmDisplayName", target.realmDisplayName());
    node.put("gameSessionTenantId", Long.toString(target.gameSessionTenantId()));
    node.put("gameInstanceId", Long.toString(target.gameInstanceId()));
    node.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    node.put("playableStateScope", target.playableStateScope());
    node.put("canonicalGameInstanceId", target.canonicalGameInstanceId().toString());
    node.put("canonicalVersionId", target.canonicalVersionId().toString());
    node.put("runtimeVersionId", Long.toString(target.runtimeVersionId()));
    node.put("catalogRevision", Long.toString(target.catalogRevision()));
    node.put("pointerVersion", Long.toString(target.pointerVersion()));
    node.put("admissionPointerSnapshotDigest", target.admissionPointerSnapshotDigest());
    node.put("activeWorldEpoch", Long.toString(target.activeWorldEpoch()));
    node.put("initialAdmissionRequestId", target.initialAdmissionRequestId());
    node.put("initialAdmissionRequestDigest", target.initialAdmissionRequestDigest());
    node.put("initialAdmissionOriginKind", target.initialAdmissionOriginKind().name());
    if (target.expectedPriorPointerVersion() != null) {
      node.put("expectedPriorPointerVersion", Long.toString(target.expectedPriorPointerVersion()));
    }
    node.put("holdId", target.holdId().toString());
    node.put("holdFence", target.holdFence().toString());
    node.put("holdBindingDigest", target.holdBindingDigest());
    node.put("auditEventId", Long.toString(target.auditEventId()));
    node.put("ownerProofDigest", target.ownerProofDigest());
    node.put("ownerProofOutcome", target.ownerProofOutcome().name());
    node.put("positiveDurableAbort", target.positiveDurableAbort());
    node.put("ownerProofTerminalAt", target.ownerProofTerminalAt().toString());
    node.put("characterCreationPolicy", target.characterCreationPolicy());
    return node;
  }

  private static ObjectNode transitionNode(
      CanonicalGameplayOriginalNewCandidate.TransitionSource transition) {
    ObjectNode node = JSON.createObjectNode();
    node.put("transitionId", transition.transitionId().toString());
    node.put("bindingRef", base64(transition.bindingRef().bytes()));
    node.put("bindingGeneration", transition.bindingGeneration().toString());
    node.put("issuerReservationId", transition.issuerReservationId().toString());
    node.put("reservationFence", transition.reservationFence().toString());
    node.put("candidateAccountIndexFence", transition.candidateAccountIndexFence().toString());
    return node;
  }

  private static ObjectNode reservationNode(
      CanonicalGameplayOriginalNewCandidate.ReservationSource reservation) {
    ObjectNode node = JSON.createObjectNode();
    node.put("reservationId", reservation.reservationId().toString());
    node.put("ownerBindingRef", base64(reservation.ownerBindingRef().bytes()));
    node.put("bindingGeneration", reservation.bindingGeneration().toString());
    node.put("transitionId", reservation.transitionId().toString());
    node.put("partitionId", reservation.partitionId().toString());
    node.put("lifecycle", CanonicalIssuerPartitionReservation.Lifecycle.RESERVED.name());
    node.put("reservationFence", reservation.reservationFence().toString());
    node.put("issuerCoverageOperationId", reservation.issuerCoverageOperationId().toString());
    node.put(
        "issuerCoverageOperationFence", reservation.issuerCoverageOperationFence().toString());
    node.put("coverageFence", reservation.coverageFence().toString());
    node.put("inventorySnapshotRevision", reservation.inventorySnapshotRevision().toString());
    return node;
  }

  private static JsonNode parseCanonicalLeaseNode(
      AccountGameplayAdmissionLeaseEvidence evidence) throws IOException {
    return JSON.readTree(evidence.canonicalJson());
  }

  private static AccountGameplayAdmissionLeaseEvidence parseLeaseEvidence(JsonNode node)
      throws IOException {
    ObjectNode object = requireObject(node, "leaseEvidence");
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(object));
    if (canonical.length > AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES) {
      throw invalid("Complete Account lease evidence exceeds its local bound");
    }
    return AccountGameplayAdmissionLeaseEvidence.parseCanonical(
        new String(canonical, StandardCharsets.UTF_8));
  }

  private static CanonicalGameplayBindingIdentity parseBinding(JsonNode source)
      throws IOException {
    ObjectNode node = requireObject(source, "binding");
    requireFields(node, BINDING_FIELDS, "binding");
    return new CanonicalGameplayBindingIdentity(
        uuid(node, "accountId"),
        uuid(node, "tenantId"),
        uuid(node, "playableStateNamespaceId"),
        uuid(node, "characterId"),
        text(node, "playableStateScope"),
        uuid(node, "gameInstanceId"),
        positiveLong(node, "runtimeGameInstanceId"),
        text(node, "sessionId"),
        uuid(node, "regionId"),
        positive(node, "regionEpoch"),
        text(node, "issuerId"),
        positive(node, "issuerAuthGeneration"),
        positive(node, "issuerIndexLayoutVersion"),
        positive(node, "issuerIndexPartitionCount"),
        positive(node, "issuerIndexPartitionCapacity"));
  }

  private static CanonicalPlayableTarget parseTarget(JsonNode source) throws IOException {
    ObjectNode node = requireObject(source, "target");
    CanonicalInitialAdmissionRequest.OriginKind originKind =
        enumValue(
            CanonicalInitialAdmissionRequest.OriginKind.class,
            text(node, "initialAdmissionOriginKind"),
            "initialAdmissionOriginKind");
    Set<String> expectedFields = new HashSet<>(TARGET_FIELDS);
    if (originKind == CanonicalInitialAdmissionRequest.OriginKind.EXPECT_CLOSED) {
      expectedFields.add("expectedPriorPointerVersion");
    }
    requireFields(node, expectedFields, "target");
    Long expectedPriorPointerVersion =
        originKind == CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER
            ? null
            : positiveLong(node, "expectedPriorPointerVersion");
    return new CanonicalPlayableTarget(
        text(node, "targetNamespace"),
        text(node, "tenantSlug"),
        uuid(node, "canonicalTenantId"),
        text(node, "worldSlug"),
        text(node, "worldDisplayName"),
        uuid(node, "realmId"),
        text(node, "realmSlug"),
        text(node, "realmDisplayName"),
        positiveLong(node, "gameSessionTenantId"),
        positiveLong(node, "gameInstanceId"),
        uuid(node, "playableStateNamespaceId"),
        text(node, "playableStateScope"),
        uuid(node, "canonicalGameInstanceId"),
        uuid(node, "canonicalVersionId"),
        positiveLong(node, "runtimeVersionId"),
        positiveLong(node, "catalogRevision"),
        positiveLong(node, "pointerVersion"),
        text(node, "admissionPointerSnapshotDigest"),
        positiveLong(node, "activeWorldEpoch"),
        text(node, "initialAdmissionRequestId"),
        text(node, "initialAdmissionRequestDigest"),
        originKind,
        expectedPriorPointerVersion,
        uuid(node, "holdId"),
        uuid(node, "holdFence"),
        text(node, "holdBindingDigest"),
        positiveLong(node, "auditEventId"),
        text(node, "ownerProofDigest"),
        enumValue(
            CanonicalInitialAdmissionOwnerProof.Outcome.class,
            text(node, "ownerProofOutcome"),
            "ownerProofOutcome"),
        bool(node, "positiveDurableAbort"),
        instant(node, "ownerProofTerminalAt"),
        text(node, "characterCreationPolicy"));
  }

  private static CanonicalGameplayOriginalNewCandidate.TransitionSource parseTransition(
      JsonNode source) throws IOException {
    ObjectNode node = requireObject(source, "transition");
    requireFields(node, TRANSITION_FIELDS, "transition");
    return new CanonicalGameplayOriginalNewCandidate.TransitionSource(
        uuid(node, "transitionId"),
        CanonicalGameplayBindingRef.fromExactBytes(base64(node, "bindingRef")),
        positive(node, "bindingGeneration"),
        uuid(node, "issuerReservationId"),
        uuid(node, "reservationFence"),
        uuid(node, "candidateAccountIndexFence"));
  }

  private static CanonicalGameplayOriginalNewCandidate.ReservationSource parseReservation(
      JsonNode source) throws IOException {
    ObjectNode node = requireObject(source, "reservation");
    requireFields(node, RESERVATION_FIELDS, "reservation");
    if (!text(node, "lifecycle").equals(CanonicalIssuerPartitionReservation.Lifecycle.RESERVED.name())) {
      throw invalid("Original NEW candidate reservation must remain RESERVED");
    }
    return new CanonicalGameplayOriginalNewCandidate.ReservationSource(
        uuid(node, "reservationId"),
        CanonicalGameplayBindingRef.fromExactBytes(base64(node, "ownerBindingRef")),
        positive(node, "bindingGeneration"),
        uuid(node, "transitionId"),
        nonNegative(node, "partitionId"),
        uuid(node, "reservationFence"),
        uuid(node, "issuerCoverageOperationId"),
        positive(node, "issuerCoverageOperationFence"),
        positive(node, "coverageFence"),
        positive(node, "inventorySnapshotRevision"));
  }

  private static ObjectNode requireObject(JsonNode node, String name) {
    if (!(node instanceof ObjectNode object)) throw invalid(name + " must be an object");
    return object;
  }

  private static void requireFields(ObjectNode node, Set<String> expected, String name) {
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(expected)) throw invalid(name + " has missing or unknown fields");
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
      throw invalid(field + " must be non-empty text");
    }
    return value.textValue();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) throw invalid(field + " must be a JSON boolean");
    return value.booleanValue();
  }

  private static UUID uuid(JsonNode node, String field) {
    String text = text(node, field);
    try {
      UUID value = UUID.fromString(text);
      if (!value.toString().equals(text) || value.equals(new UUID(0L, 0L))) {
        throw invalid(field + " must be a canonical non-nil UUID");
      }
      return value;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " must be a canonical non-nil UUID", malformed);
    }
  }

  private static BigInteger positive(JsonNode node, String field) {
    return positiveDecimal(text(node, field), field);
  }

  private static BigInteger nonNegative(JsonNode node, String field) {
    String value = text(node, field);
    if (!NON_NEGATIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(field + " must be a bounded canonical non-negative decimal string");
    }
    return new BigInteger(value);
  }

  private static BigInteger positiveDecimal(String value, String field) {
    if (!POSITIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(field + " must be a bounded canonical positive decimal string");
    }
    return new BigInteger(value);
  }

  private static long positiveLong(JsonNode node, String field) {
    String value = text(node, field);
    if (!POSITIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(field + " must be a positive decimal string within the long range");
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException malformed) {
      throw invalid(field + " exceeds the supported long range", malformed);
    }
  }

  private static Instant instant(JsonNode node, String field) {
    String value = text(node, field);
    try {
      Instant parsed = Instant.parse(value);
      if (!parsed.toString().equals(value)) throw invalid(field + " is not canonical UTC time");
      return parsed;
    } catch (RuntimeException malformed) {
      throw invalid(field + " is not canonical UTC time", malformed);
    }
  }

  private static <E extends Enum<E>> E enumValue(
      Class<E> enumType, String value, String field) {
    try {
      return Enum.valueOf(enumType, value);
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is unsupported", malformed);
    }
  }

  private static byte[] base64(JsonNode node, String field) {
    String value = text(node, field);
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      if (!base64(decoded).equals(value)) throw invalid(field + " is not canonical padded Base64");
      return decoded;
    } catch (IllegalArgumentException malformed) {
      throw invalid(field + " is not canonical padded Base64", malformed);
    }
  }

  private static String base64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static void requireCounterLimit(BigInteger value, String name, boolean zeroAllowed) {
    Objects.requireNonNull(value, name);
    int digits = value.toString().length();
    if (digits > MAX_COUNTER_DIGITS || value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) {
      throw invalid(name + " exceeds its canonical integer bounds");
    }
  }

  private static String decodeUtf8(byte[] encoded) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid("Original NEW candidate is not valid UTF-8", malformed);
    }
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  private static IllegalArgumentException invalid(String message, Throwable cause) {
    return new IllegalArgumentException(message, cause);
  }
}
