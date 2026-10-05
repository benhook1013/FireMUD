package unit.net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto;
import net.firedevops.firemud.accountservice.dto.RuntimeMembershipSnapshotDto.MembershipBaseline;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjectionRedisContract;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.AccountMembershipSourceReader.MembershipSourceSnapshot;
import net.firedevops.firemud.accountservice.service.MembershipGenerationProjection;
import net.firedevops.firemud.accountservice.service.MembershipGenerationProjectionRedisContract;
import net.firedevops.firemud.accountservice.service.TenantGenerationProjectionRedisContract;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.junit.jupiter.api.Test;

class MembershipGenerationProjectionTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("c980fa44-619e-4ca4-8ad6-75b0538a66a3");
  private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final String ACCOUNT_TEXT = ACCOUNT_ID.toString();
  private static final String TENANT_TEXT = TENANT_ID.toString();
  private static final String KEY =
      "session:auth:generation:membership:" + ACCOUNT_TEXT + ":" + TENANT_TEXT;
  private static final String STREAM_KEY =
      "account:auth-authority:v1:membership/" + ACCOUNT_TEXT + "/" + TENANT_TEXT;

  @Test
  void emitsExactExistingSequenceZeroBaselineAndNoEventMirror() {
    MembershipGenerationProjection projection =
        MembershipGenerationProjection.fromSource(baselineSource(1L, 1L));

    assertThat(projection.key()).isEqualTo(KEY);
    assertThat(projection.toJson())
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-membership-generation-projection/v1\","
                + "\"accountId\":\""
                + ACCOUNT_TEXT
                + "\",\"tenantId\":\""
                + TENANT_TEXT
                + "\",\"membershipAuthorityGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"membershipVersion\":{\""
                + TENANT_TEXT
                + "\":\"1\"},\"outboxStreamKey\":\""
                + STREAM_KEY
                + "\",\"outboxSequence\":\"0\"}");
    assertThat(MembershipGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
    assertThat(projection.toJson()).doesNotContain("sourceEvent");
  }

  @Test
  void registersOnlyTheExactMembershipPairPrefixOnTheExistingCasScript() {
    var descriptor = MembershipGenerationProjectionRedisContract.descriptor();

    assertThat(descriptor.scriptId()).isEqualTo("account.membership-generation-projection.v1");
    assertThat(descriptor.resourcePath())
        .isEqualTo(AccountGenerationProjectionRedisContract.RESOURCE_PATH);
    assertThat(descriptor.sha256())
        .isEqualTo(AccountGenerationProjectionRedisContract.descriptor().sha256())
        .isEqualTo(TenantGenerationProjectionRedisContract.descriptor().sha256());
    assertThat(descriptor.owner()).isEqualTo("account-service");
    assertThat(descriptor.principal()).isEqualTo("account_coord_app");
    assertThat(descriptor.keys()).hasSize(1);
    assertThat(descriptor.keys().getFirst().ownedPrefix())
        .isEqualTo(MembershipGenerationProjection.KEY_PREFIX)
        .isNotEqualTo(AccountGenerationProjectionRedisContract.KEY_PREFIX)
        .isNotEqualTo(TenantGenerationProjectionRedisContract.KEY_PREFIX);
    assertThat(descriptor.keys().getFirst().hashTagDeclaration().name()).isEqualTo("NOT_REQUIRED");
  }

  @Test
  void storesWholeCanonicalMembershipEventAsStringWithIndependentCounters() throws Exception {
    MembershipEvent event = membershipEvent("1", "2", "1");
    MembershipGenerationProjection projection =
        MembershipGenerationProjection.fromSource(positiveSource(1L, 1L, 2L, 1L, event));
    MembershipGenerationProjection parsed =
        MembershipGenerationProjection.parse(projection.toJson());

    assertThat(parsed).isEqualTo(projection);
    assertThat(parsed.membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(parsed.sourceVersion()).isEqualTo("1");
    assertThat(parsed.membershipVersion()).isEqualTo(Map.of(TENANT_TEXT, "2"));
    assertThat(parsed.outboxSequence()).isEqualTo("1");
    assertThat(parsed.sourceEvent()).contains(event.canonicalJson());
    assertThat(parsed.toJson()).contains("\"sourceEvent\":\"{");
    JsonNode projectionJson = new ObjectMapper().readTree(parsed.toJson());
    Set<String> projectionFields = new HashSet<>();
    projectionJson.fieldNames().forEachRemaining(projectionFields::add);
    assertThat(projectionFields)
        .containsExactlyInAnyOrder(
            "schemaVersion",
            "accountId",
            "tenantId",
            "membershipAuthorityGeneration",
            "sourceVersion",
            "membershipVersion",
            "outboxStreamKey",
            "outboxSequence",
            "sourceEvent");
    assertThat(projectionJson.get("sourceEvent").isTextual()).isTrue();
    assertThat(
            MembershipAuthorityEventV1Codec.verify(parsed.sourceEvent().orElseThrow())
                .canonicalJson())
        .isEqualTo(event.canonicalJson());
  }

  @Test
  void preservesIndependentGenerationVersionAndSequenceAxesWithoutTenantArithmetic() {
    MembershipEvent event = membershipEvent("9", "14", "6");
    MembershipGenerationProjection projection =
        MembershipGenerationProjection.fromSource(positiveSource(9L, 31L, 14L, 6L, event));

    assertThat(projection.generationValue()).isEqualTo(BigInteger.valueOf(9L));
    assertThat(projection.sourceVersionValue()).isEqualTo(BigInteger.valueOf(31L));
    assertThat(projection.membershipVersionValue()).isEqualTo(BigInteger.valueOf(14L));
    assertThat(projection.outboxSequenceValue()).isEqualTo(BigInteger.valueOf(6L));
    assertThat(MembershipGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
  }

  @Test
  void acceptsCanonicalCountersBeyondLongRange() {
    String large = "922337203685477580812345678901234567890";
    MembershipEvent event = membershipEvent("9", "2", "6");
    MembershipGenerationProjection projection =
        new MembershipGenerationProjection(
            ACCOUNT_TEXT,
            TENANT_TEXT,
            "9",
            large,
            Map.of(TENANT_TEXT, "2"),
            STREAM_KEY,
            "6",
            Optional.of(event.canonicalJson()));

    assertThat(projection.sourceVersionValue()).isEqualTo(new BigInteger(large));
    assertThat(MembershipGenerationProjection.parse(projection.toJson())).isEqualTo(projection);
  }

  @Test
  void closesProjectionAndRejectsMalformedMapCountersAndJson() {
    String baseline = MembershipGenerationProjection.fromSource(baselineSource(1L, 1L)).toJson();
    String duplicateAccount =
        baseline.replace(
            "\"accountId\":\"" + ACCOUNT_TEXT + "\"",
            "\"accountId\":\"" + ACCOUNT_TEXT + "\",\"accountId\":\"" + ACCOUNT_TEXT + "\"");
    String unknown = baseline.substring(0, baseline.length() - 1) + ",\"issuanceFence\":\"1\"}";
    String nullEvent = baseline.substring(0, baseline.length() - 1) + ",\"sourceEvent\":null}";
    String badSequence = baseline.replace("\"outboxSequence\":\"0\"", "\"outboxSequence\":\"00\"");
    String wrongMapKey =
        baseline.replace(TENANT_TEXT + "\":\"1", "10000000-0000-0000-0000-000000000002\":\"1");
    String trailing = baseline + " {}";

    assertThatThrownBy(() -> MembershipGenerationProjection.parse(duplicateAccount))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MembershipGenerationProjection.parse(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MembershipGenerationProjection.parse(nullEvent))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MembershipGenerationProjection.parse(badSequence))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MembershipGenerationProjection.parse(wrongMapKey))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MembershipGenerationProjection.parse(trailing))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "1",
                    "1",
                    Map.of(TENANT_TEXT, "1", ACCOUNT_TEXT, "2"),
                    STREAM_KEY,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresSequenceZeroToBeTheOriginalProvedBaseline() {
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "2",
                    "1",
                    Map.of(TENANT_TEXT, "1"),
                    STREAM_KEY,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence zero");
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "1",
                    "2",
                    Map.of(TENANT_TEXT, "1"),
                    STREAM_KEY,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence zero");
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "1",
                    "1",
                    Map.of(TENANT_TEXT, "2"),
                    STREAM_KEY,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence zero");
  }

  @Test
  void rejectsEventIdentityCheckpointGenerationAndVersionDisagreement() {
    String valid = membershipEvent("1", "2", "1").canonicalJson();
    String changedDigest = valid.replace("\"outboxSequence\":\"1\"", "\"outboxSequence\":\"2\"");
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "1",
                    "13",
                    Map.of(TENANT_TEXT, "2"),
                    STREAM_KEY,
                    "1",
                    Optional.of(changedDigest)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "2",
                    "13",
                    Map.of(TENANT_TEXT, "2"),
                    STREAM_KEY,
                    "1",
                    Optional.of(valid)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpoint");
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    ACCOUNT_TEXT,
                    TENANT_TEXT,
                    "1",
                    "13",
                    Map.of(TENANT_TEXT, "3"),
                    STREAM_KEY,
                    "1",
                    Optional.of(valid)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpoint");
    assertThatThrownBy(
            () ->
                new MembershipGenerationProjection(
                    "00000000-0000-0000-0000-000000000000",
                    TENANT_TEXT,
                    "1",
                    "1",
                    Map.of(TENANT_TEXT, "1"),
                    STREAM_KEY,
                    "0",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static MembershipSourceSnapshot baselineSource(long generation, long sourceVersion) {
    RuntimeMembershipSnapshotDto snapshot =
        new RuntimeMembershipSnapshotDto(
            ACCOUNT_TEXT,
            TENANT_TEXT,
            ACCOUNT_TEXT,
            TENANT_TEXT,
            false,
            false,
            new MembershipBaseline("MISSING", Map.of(TENANT_TEXT, "1"), "1"),
            List.of(),
            tuple("1"),
            "1",
            Instant.parse("2026-10-01T00:00:00Z"),
            List.of(
                new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_TEXT, "0"),
                new OutboxCheckpointEntry(
                    "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER,
                    "0"),
                new OutboxCheckpointEntry(STREAM_KEY, "0"),
                new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + TENANT_TEXT, "0")),
            List.of(),
            null);
    return source(snapshot, generation, sourceVersion, 1L);
  }

  private static MembershipSourceSnapshot positiveSource(
      long generation,
      long sourceVersion,
      long membershipVersion,
      long sequence,
      MembershipEvent event) {
    String generationText = Long.toString(generation);
    String versionText = Long.toString(membershipVersion);
    String sequenceText = Long.toString(sequence);
    String stream = event.outboxStreamKey();
    RuntimeMembershipSnapshotDto snapshot =
        new RuntimeMembershipSnapshotDto(
            ACCOUNT_TEXT,
            TENANT_TEXT,
            ACCOUNT_TEXT,
            TENANT_TEXT,
            true,
            true,
            new MembershipBaseline("ACTIVE", Map.of(TENANT_TEXT, versionText), generationText),
            List.of("player"),
            tuple(generationText),
            event.issuanceFence(),
            Instant.parse("2026-10-01T00:00:00Z"),
            List.of(
                new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_TEXT, "0"),
                new OutboxCheckpointEntry(
                    "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER,
                    "0"),
                new OutboxCheckpointEntry(stream, sequenceText),
                new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + TENANT_TEXT, "0")),
            List.of(
                new OutboxSourceEvidence(
                    stream,
                    sequenceText,
                    event.eventId(),
                    event.eventDigest(),
                    event.canonicalJson())),
            event);
    return source(snapshot, generation, sourceVersion, Long.parseLong(event.issuanceFence()));
  }

  private static MembershipSourceSnapshot source(
      RuntimeMembershipSnapshotDto snapshot, long generation, long sourceVersion, long fenceValue) {
    return new MembershipSourceSnapshot(
        ACCOUNT_ID,
        TENANT_ID,
        snapshot,
        new ScopeState(
            AuthorityScope.membership(ACCOUNT_ID, TENANT_ID),
            generation,
            sourceVersion,
            new IssuanceFence(ACCOUNT_ID, fenceValue, 1L)));
  }

  private static AuthorityTuple tuple(String membershipGeneration) {
    return new AuthorityTuple(
        "1",
        "1",
        Map.of(TENANT_TEXT, "1"),
        Map.of(TENANT_TEXT, membershipGeneration),
        List.of(),
        Optional.empty(),
        Optional.empty());
  }

  private static MembershipEvent membershipEvent(
      String generation, String membershipVersion, String sequence) {
    String eventId = "04ef66b4-c0ad-3d5b-b3b2-0e8510e72002";
    return MembershipAuthorityEventV1Codec.seal(
        Map.ofEntries(
            Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
            Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
            Map.entry("eventId", eventId),
            Map.entry("requestId", "join-request-1"),
            Map.entry("outboxStreamKey", STREAM_KEY),
            Map.entry("outboxSequence", sequence),
            Map.entry("sourceScope", "membership/" + ACCOUNT_TEXT + "/" + TENANT_TEXT),
            Map.entry("accountId", ACCOUNT_TEXT),
            Map.entry("tenantId", TENANT_TEXT),
            Map.entry("membershipExists", true),
            Map.entry("membershipLifecycleState", "ACTIVE"),
            Map.entry("membershipVersion", Map.of(TENANT_TEXT, membershipVersion)),
            Map.entry("membershipAuthorityGeneration", generation),
            Map.entry(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", "1",
                    "accountAuthorityGeneration", "1",
                    "tenantAuthorityGeneration", Map.of(TENANT_TEXT, "1"),
                    "membershipAuthorityGeneration", Map.of(TENANT_TEXT, generation),
                    "privateRealmGrantVersions", List.of())),
            Map.entry("issuanceFence", "1"),
            Map.entry("roles", List.of("player")),
            Map.entry("gameplayAdmissionAllowed", true),
            Map.entry("callerBoundAuthorityInvalidated", false)));
  }
}
