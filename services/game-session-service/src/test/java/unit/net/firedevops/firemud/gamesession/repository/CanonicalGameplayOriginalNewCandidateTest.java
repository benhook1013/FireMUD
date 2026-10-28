package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAccountCoverageEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationDisposition;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import org.junit.jupiter.api.Test;

class CanonicalGameplayOriginalNewCandidateTest {
  private static final UUID ACCOUNT = id(1);
  private static final UUID TENANT = id(2);
  private static final UUID REALM = id(3);
  private static final UUID NAMESPACE = id(4);
  private static final UUID GAME_INSTANCE = id(5);
  private static final UUID CHARACTER = id(6);
  private static final UUID SESSION = id(7);
  private static final UUID REGION = id(8);
  private static final UUID VERSION = id(9);

  @Test
  void retainsExactNonAdmittingCandidateAndStableRetryBytes() {
    Bundle prepared = bundle(CHARACTER, BigInteger.valueOf(7), "World", false);
    CanonicalGameplayOriginalNewCandidate first =
        CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
            provisionalEvidence(
                prepared.identity(), prepared.bindingGeneration(), BigInteger.valueOf(20), false),
            prepared.decision());
    CanonicalGameplayOriginalNewCandidate retry =
        CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
            provisionalEvidence(
                prepared.identity(), prepared.bindingGeneration(), BigInteger.valueOf(28), true),
            prepared.decision());

    assertThat(retry.canonicalBytes()).isEqualTo(first.canonicalBytes());
    assertThat(first.exactlyMatches(first.canonicalBytes())).isTrue();
    assertThat(first.matchesOriginalDecision(prepared.decision())).isTrue();
    assertThat(first.matchesProvisionalSources(preparedEvidence(prepared))).isTrue();
    assertThat(
            first.matchesProvisionalSources(
                provisionalEvidence(
                    prepared.identity(),
                    prepared.bindingGeneration(),
                    BigInteger.valueOf(28),
                    true)))
        .isTrue();

    CanonicalGameplayOriginalNewCandidate decoded =
        CanonicalGameplayOriginalNewCandidate.parseCanonical(first.canonicalBytes());
    assertThat(decoded).isEqualTo(first);
    assertThat(decoded.canonicalJson()).isEqualTo(first.canonicalJson());
    assertThat(decoded.bindingIdentity()).isEqualTo(prepared.identity());
    assertThat(decoded.leaseEvidence().hasSameIdentity(prepared.leaseEvidence())).isTrue();
    assertThat(decoded.targetEvidence()).isEqualTo(prepared.target());
    assertThat(decoded.transition().transitionId()).isEqualTo(prepared.decision().transitionId());
    assertThat(decoded.reservation().bindingGeneration()).isEqualTo(BigInteger.valueOf(7));
    assertThat(decoded.reservation().inventorySnapshotRevision()).isEqualTo(BigInteger.valueOf(19));
    assertThat(
            first.matchesProvisionalSources(
                provisionalEvidence(
                    prepared.identity(),
                    prepared.bindingGeneration(),
                    BigInteger.valueOf(28),
                    true,
                    BigInteger.valueOf(20))))
        .isFalse();

    String encoded = first.canonicalJson();
    assertThat(encoded)
        .contains("\"purpose\":\"" + CanonicalGameplayOriginalNewCandidate.PURPOSE + "\"")
        .contains("\"membershipBaseline\"")
        .contains("\"authorityTuple\"")
        .contains("\"outboxCheckpoints\"")
        .doesNotContain(
            "\"schemaVersion\":2",
            "authTokenHash",
            "rebindHandle",
            "admissionAt",
            "continuityBindingExpiresAt",
            "raw-original-jwt",
            "private-rebind-handle");
    assertThat(first.toString())
        .doesNotContain(
            prepared.leaseEvidence().sha256(), "raw-original-jwt", "private-rebind-handle");
  }

  @Test
  void rejectsWrongLeaseBindingGenerationAndTargetWhenMatchingTheOriginalDecision() {
    Bundle original = bundle(CHARACTER, BigInteger.valueOf(7), "World", false);
    CanonicalGameplayOriginalNewCandidate candidate =
        CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
            preparedEvidence(original), original.decision());

    Bundle otherLease = bundle(CHARACTER, BigInteger.valueOf(7), "World", true);
    Bundle otherBinding = bundle(id(60), BigInteger.valueOf(7), "World", false);
    Bundle otherGeneration = bundle(CHARACTER, BigInteger.valueOf(8), "World", false);
    Bundle otherTarget = bundle(CHARACTER, BigInteger.valueOf(7), "Changed World", false);

    assertThat(candidate.matchesOriginalDecision(otherLease.decision())).isFalse();
    assertThat(candidate.matchesOriginalDecision(otherBinding.decision())).isFalse();
    assertThat(candidate.matchesOriginalDecision(otherGeneration.decision())).isFalse();
    assertThat(candidate.matchesOriginalDecision(otherTarget.decision())).isFalse();
    assertThat(candidate.matchesProvisionalSources(preparedEvidence(otherBinding))).isFalse();
    assertThat(candidate.matchesProvisionalSources(preparedEvidence(otherGeneration))).isFalse();

    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                    preparedEvidence(original), otherBinding.decision()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                    preparedEvidence(original), otherGeneration.decision()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesResumeAndUnrecognizedTransitionStates() {
    Bundle original = bundle(CHARACTER, BigInteger.valueOf(7), "World", false);
    var resumeCarrier =
        new LinkedHashMap<>(
            leaseCarrier(
                original.identity(), original.target(), false, original.bindingGeneration()));
    resumeCarrier.put("leaseKind", "RESUME");
    resumeCarrier.put("resumeEpisodeId", id(90).toString());
    resumeCarrier.put("expectedOldBindingGeneration", "6");
    AccountGameplayAdmissionLeaseEvidence resumeLease =
        AccountGameplayAdmissionLeaseEvidence.fromCarrier(resumeCarrier);
    var resumeDecision =
        new CanonicalGameplayAdmissionDecision(
            original.decision().bindingDecisionId(),
            resumeLease,
            original.identity(),
            BigInteger.valueOf(7),
            BigInteger.valueOf(6),
            original.target());
    assertThatThrownBy(
            () ->
                CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                    preparedEvidence(original), resumeDecision))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public-production original NEW");

    assertThatThrownBy(() -> evidenceWithStatus(original.identity(), "COMMITTED"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownDuplicateMalformedOversizedNoncanonicalAndOutOfBoundInput() {
    Bundle original = bundle(CHARACTER, BigInteger.valueOf(7), "World", false);
    String canonical =
        CanonicalGameplayOriginalNewCandidate.fromOriginalSources(
                preparedEvidence(original), original.decision())
            .canonicalJson();

    rejects(
        canonical.replace(
            "\"purpose\":\"" + CanonicalGameplayOriginalNewCandidate.PURPOSE + "\"",
            "\"purpose\":\""
                + CanonicalGameplayOriginalNewCandidate.PURPOSE
                + "\",\"extra\":\"x\""));
    rejects(
        canonical.replace(
            "\"purpose\":\"" + CanonicalGameplayOriginalNewCandidate.PURPOSE + "\"",
            "\"purpose\":\""
                + CanonicalGameplayOriginalNewCandidate.PURPOSE
                + "\",\"purpose\":\""
                + CanonicalGameplayOriginalNewCandidate.PURPOSE
                + "\""));
    rejects(
        canonical.replace(
            "\"leaseKind\":\"NEW_BINDING\"",
            "\"leaseKind\":\"NEW_BINDING\",\"leaseKind\":\"NEW_BINDING\""));
    rejects(" " + canonical);
    rejects(canonical.replace("\"bindingGeneration\":\"7\"", "\"bindingGeneration\":\"07\""));
    rejects(
        canonical.replace(
            "\"bindingDecisionId\":\"" + id(500) + "\"", "\"bindingDecisionId\":\"not-a-uuid\""));
    rejects("{");

    byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
    byte[] malformedUtf8 = Arrays.copyOf(canonicalBytes, canonicalBytes.length + 1);
    malformedUtf8[malformedUtf8.length - 1] = (byte) 0xff;
    rejects(malformedUtf8);
    rejects(new byte[CanonicalGameplayOriginalNewCandidateCodec.MAX_CANDIDATE_BYTES + 1]);
    rejects(
        canonical.replace(
            "\"bindingGeneration\":\"7\"", "\"bindingGeneration\":\"" + "9".repeat(257) + "\""));
  }

  private static void rejects(String encoded) {
    rejects(encoded.getBytes(StandardCharsets.UTF_8));
  }

  private static void rejects(byte[] encoded) {
    assertThatThrownBy(() -> CanonicalGameplayOriginalNewCandidate.parseCanonical(encoded))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence preparedEvidence(Bundle bundle) {
    return provisionalEvidence(
        bundle.identity(), bundle.bindingGeneration(), BigInteger.valueOf(20), false);
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence provisionalEvidence(
      CanonicalGameplayBindingIdentity identity,
      BigInteger generation,
      BigInteger inventoryRevision,
      boolean provisional) {
    return provisionalEvidence(
        identity, generation, inventoryRevision, provisional, BigInteger.valueOf(19));
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence provisionalEvidence(
      CanonicalGameplayBindingIdentity identity,
      BigInteger generation,
      BigInteger inventoryRevision,
      boolean provisional,
      BigInteger originalSnapshotRevision) {
    UUID transitionId = id(500);
    UUID reservationId = id(501);
    UUID reservationFence = id(502);
    UUID accountIndexFence = id(503);
    CanonicalGameplayBindingInventoryEntry candidate =
        new CanonicalGameplayBindingInventoryEntry(
            identity,
            identity.bindingRef(),
            generation,
            accountIndexFence,
            reservationId,
            transitionId,
            provisional
                ? CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL
                : CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED,
            provisional
                ? CanonicalGameplayBindingInventoryEntry.IndexState.ACKNOWLEDGED
                : CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            inventoryRevision);
    CanonicalIssuerPartitionReservation reservation =
        new CanonicalIssuerPartitionReservation(
            reservationId,
            identity,
            generation,
            transitionId,
            identity.bindingRef().partitionId(identity.issuerIndexPartitionCount()),
            CanonicalIssuerPartitionReservation.Lifecycle.RESERVED,
            reservationFence,
            id(504),
            BigInteger.valueOf(2),
            BigInteger.valueOf(3),
            originalSnapshotRevision,
            inventoryRevision);
    CanonicalGameplayBindingTransitionSnapshot transition =
        new CanonicalGameplayBindingTransitionSnapshot(
            transitionId,
            identity,
            generation,
            identity.bindingRef(),
            inventoryRevision,
            reservationId,
            reservationFence,
            accountIndexFence,
            null,
            null,
            provisional
                ? CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
                : CanonicalGameplayBindingTransitionSnapshot.Status.PREPARED,
            CanonicalGameplayAccountCoverageEvidence.noActiveFlow());
    return new CanonicalGameplayBindingProvisionalCasEvidence(
        transition,
        candidate,
        reservation,
        null,
        null,
        new CanonicalGameplayLegacyMigrationDisposition(
            id(505),
            id(506),
            id(507),
            BigInteger.ONE,
            BigInteger.TWO,
            BigInteger.valueOf(3),
            "sha256:" + "f".repeat(64)));
  }

  private static CanonicalGameplayBindingProvisionalCasEvidence evidenceWithStatus(
      CanonicalGameplayBindingIdentity identity, String status) {
    UUID transitionId = id(500);
    UUID reservationId = id(501);
    UUID reservationFence = id(502);
    UUID accountIndexFence = id(503);
    var candidate =
        new CanonicalGameplayBindingInventoryEntry(
            identity,
            identity.bindingRef(),
            BigInteger.valueOf(7),
            accountIndexFence,
            reservationId,
            transitionId,
            CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            CanonicalGameplayBindingInventoryEntry.IndexState.REPAIR_REQUIRED,
            BigInteger.valueOf(20));
    var reservation =
        new CanonicalIssuerPartitionReservation(
            reservationId,
            identity,
            BigInteger.valueOf(7),
            transitionId,
            identity.bindingRef().partitionId(identity.issuerIndexPartitionCount()),
            CanonicalIssuerPartitionReservation.Lifecycle.RESERVED,
            reservationFence,
            id(504),
            BigInteger.TWO,
            BigInteger.valueOf(3),
            BigInteger.valueOf(19),
            BigInteger.valueOf(20));
    return new CanonicalGameplayBindingProvisionalCasEvidence(
        new CanonicalGameplayBindingTransitionSnapshot(
            transitionId,
            identity,
            BigInteger.valueOf(7),
            identity.bindingRef(),
            BigInteger.valueOf(20),
            reservationId,
            reservationFence,
            accountIndexFence,
            null,
            null,
            CanonicalGameplayBindingTransitionSnapshot.Status.valueOf(status),
            CanonicalGameplayAccountCoverageEvidence.noActiveFlow()),
        candidate,
        reservation,
        null,
        null,
        new CanonicalGameplayLegacyMigrationDisposition(
            id(505),
            id(506),
            id(507),
            BigInteger.ONE,
            BigInteger.TWO,
            BigInteger.valueOf(3),
            "sha256:" + "f".repeat(64)));
  }

  private static Bundle bundle(
      UUID characterId, BigInteger generation, String worldDisplayName, boolean alternateLease) {
    CanonicalGameplayBindingIdentity identity = identity(characterId);
    CanonicalPlayableTarget target = target(worldDisplayName);
    AccountGameplayAdmissionLeaseEvidence lease =
        AccountGameplayAdmissionLeaseEvidence.fromCarrier(
            leaseCarrier(identity, target, alternateLease, generation));
    var decision =
        new CanonicalGameplayAdmissionDecision(id(500), lease, identity, generation, null, target);
    return new Bundle(identity, generation, target, lease, decision);
  }

  private static CanonicalGameplayBindingIdentity identity(UUID characterId) {
    return new CanonicalGameplayBindingIdentity(
        ACCOUNT,
        TENANT,
        NAMESPACE,
        characterId,
        "SHARED",
        GAME_INSTANCE,
        41L,
        SESSION.toString(),
        REGION,
        BigInteger.valueOf(5),
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.valueOf(16),
        BigInteger.valueOf(100));
  }

  private static CanonicalPlayableTarget target(String worldDisplayName) {
    return new CanonicalPlayableTarget(
        "test",
        "tenant",
        TENANT,
        "world",
        worldDisplayName,
        REALM,
        "production",
        "Production",
        22L,
        41L,
        NAMESPACE,
        "SHARED",
        GAME_INSTANCE,
        VERSION,
        7L,
        3L,
        17L,
        "d".repeat(64),
        1L,
        "initial-request",
        "a".repeat(64),
        CanonicalInitialAdmissionRequest.OriginKind.NO_PRIOR_POINTER,
        null,
        id(30),
        id(31),
        "sha256:" + "b".repeat(64),
        9L,
        "sha256:" + "c".repeat(64),
        CanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED,
        false,
        Instant.parse("2026-10-26T12:00:00Z"),
        "PRESEEDED");
  }

  private static Map<String, Object> leaseCarrier(
      CanonicalGameplayBindingIdentity identity,
      CanonicalPlayableTarget target,
      boolean alternateLease,
      BigInteger generation) {
    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", "test");
    carrier.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    carrier.put("requestId", id(alternateLease ? 701 : 700).toString());
    carrier.put("leaseId", id(alternateLease ? 703 : 702).toString());
    carrier.put("leaseFence", "11");
    carrier.put("leaseKind", "NEW_BINDING");
    carrier.put(
        "bindingScope",
        Map.ofEntries(
            Map.entry("accountId", identity.accountId().toString()),
            Map.entry("tenantId", identity.tenantId().toString()),
            Map.entry("realmId", REALM.toString()),
            Map.entry("worldSlug", "world"),
            Map.entry("realmSlug", "production"),
            Map.entry("playableStateNamespaceId", identity.playableStateNamespaceId().toString()),
            Map.entry("playableStateScope", identity.playableStateScope()),
            Map.entry("gameInstanceId", identity.gameInstanceId().toString()),
            Map.entry("characterId", identity.characterId().toString()),
            Map.entry("sessionId", identity.sessionId()),
            Map.entry("bindingGeneration", generation.toString()),
            Map.entry("catalogRevision", Long.toString(target.catalogRevision())),
            Map.entry("pointerVersion", Long.toString(target.pointerVersion())),
            Map.entry("regionId", identity.regionId().toString()),
            Map.entry("regionEpoch", identity.regionEpoch().toString())));
    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(TENANT.toString(), "2"),
            "membershipAuthorityGeneration", Map.of(TENANT.toString(), "3"),
            "privateRealmGrantVersions", List.of()));
    carrier.put("issuanceFence", "9");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(TENANT.toString(), "4"),
            "membershipAuthorityGeneration",
            "3"));
    List<String> streams =
        new ArrayList<>(
            List.of(
                "account:auth-authority:v1:account/" + ACCOUNT,
                "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER,
                "account:auth-authority:v1:membership/" + ACCOUNT + "/" + TENANT,
                "account:auth-authority:v1:tenant/" + TENANT));
    streams.sort(Comparator.naturalOrder());
    carrier.put(
        "outboxCheckpoints",
        streams.stream()
            .map(
                stream ->
                    Map.of(
                        "outboxStreamKey",
                        stream,
                        "outboxSequence",
                        stream.contains("tenant/") || stream.contains("membership/") ? "4" : "0"))
            .toList());
    carrier.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", identity.accountId().toString()),
            Map.entry("operationId", id(710).toString()),
            Map.entry("issuanceRequestId", id(711).toString()),
            Map.entry("tokenJti", id(712).toString()),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "9"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", GameSessionAccountDelegationProfile.PROFILE),
            Map.entry("issuedAt", "999"),
            Map.entry("notBefore", "999"),
            Map.entry("expiresAt", "1300")));
    carrier.put("evaluatedAt", "1000000");
    carrier.put("expiresAt", "1010000");
    return carrier;
  }

  private static UUID id(long value) {
    return new UUID(0x4000L, Long.MIN_VALUE | value);
  }

  private record Bundle(
      CanonicalGameplayBindingIdentity identity,
      BigInteger bindingGeneration,
      CanonicalPlayableTarget target,
      AccountGameplayAdmissionLeaseEvidence leaseEvidence,
      CanonicalGameplayAdmissionDecision decision) {}
}
