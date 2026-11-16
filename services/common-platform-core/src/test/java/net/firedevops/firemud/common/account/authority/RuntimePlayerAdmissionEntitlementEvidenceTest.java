package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Controlled content fixtures, not source authentication, transport or owner execution. */
class RuntimePlayerAdmissionEntitlementEvidenceTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String PEER = "spiffe://firemud/ns/prod/sa/game-session-service";
  private static final String HASH = "a".repeat(64);
  private static final String SCOPE = "controlled-scope";
  private static final String SCOPE_HASH =
      RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(SCOPE);
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");

  @Test
  void canonicalVariantRetainsExplicitAbsenceAndCompleteApplicableAuthority() {
    var value = capture(target(), facts(), sources(), NOW);
    assertThat(value.fields())
        .containsEntry("variant", "PLAYER_ADMISSION")
        .containsEntry("authorityAvailability", "AVAILABLE")
        .containsEntry("callerAccountId", ACCOUNT.toString())
        .containsEntry("bundleVersion", "2")
        .containsEntry("verifiedConnectScopeTokenHash", SCOPE_HASH)
        .doesNotContainKey("verifiedConnectScopeId")
        .containsEntry("membershipExists", false)
        .containsEntry("membershipLifecycleState", "MISSING");
    assertThat(value.membershipSource().membershipAuthorityGeneration()).isEqualTo("1");
    assertThat(value.membershipSource().checkpoints()).hasSize(4);
    assertThat(value.membershipSource().sourceEvidence()).hasSize(1);
    byte[] copy = value.canonicalBytes();
    copy[0] = 0;
    value.fields().put("callerAccountId", TENANT.toString());
    assertThat(value.fields()).containsEntry("callerAccountId", ACCOUNT.toString());
    assertThat(value.toString()).doesNotContain(SCOPE);
    assertThat(new String(value.canonicalBytes(), StandardCharsets.UTF_8)).doesNotContain(SCOPE);
    assertThat(value.verifiedConnectScopeTokenHash()).isEqualTo(SCOPE_HASH);
    assertThatThrownBy(
            () -> RuntimeTenantOperationEntitlementEvidence.parseCanonical(value.canonicalBytes()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownerEmissionUsesTheDecoderStringLimitBeforeReturningEvidence() {
    var atLimit = target();
    atLimit.put("worldSlug", "a".repeat(65536));
    var value = capture(atLimit, facts(), sources(), NOW);
    assertThat(
            RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(value.canonicalBytes())
                .canonicalBytes())
        .containsExactly(value.canonicalBytes());
    var tooLong = target();
    tooLong.put("worldSlug", "a".repeat(65537));
    assertThatThrownBy(() -> capture(tooLong, facts(), sources(), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Player evidence exceeds decoder constraints");
  }

  @Test
  void ownerEmissionRejectsOversizedRoleInventoryBeforeReturningEvidence() {
    var original = membership();
    var oversized =
        new Snapshot(
            original.issuerId(),
            original.accountId(),
            original.tenantId(),
            original.membershipExists(),
            original.membershipLifecycleState(),
            original.gameplayAdmissionAllowed(),
            original.membershipVersion(),
            original.membershipAuthorityGeneration(),
            java.util.stream.IntStream.range(0, 17).mapToObj(i -> "role" + i).toList(),
            original.authorityTuple(),
            original.issuanceFence(),
            original.checkpoints(),
            original.sourceEvidence());
    assertThatThrownBy(
            () ->
                RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
                    REQUEST, PEER, SCOPE_HASH, oversized, target(), facts(), sources(), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Bounded list required");
  }

  @Test
  void identitiesBindActualInputsAndSeparateSnapshotFromEvaluation() {
    var first = capture(target(), facts(), sources(), NOW);
    var later = capture(target(), facts(), sources(), NOW.plusSeconds(1));
    assertThat(first.fields().get("snapshotIdentity"))
        .isEqualTo(later.fields().get("snapshotIdentity"));
    assertThat(first.fields().get("evaluationIdentity"))
        .isNotEqualTo(later.fields().get("evaluationIdentity"));
    var changed = sources();
    changed.put("worldLifecycleDigest", "sha256:" + "b".repeat(64));
    assertThat(capture(target(), facts(), changed, NOW).fields().get("snapshotIdentity"))
        .isNotEqualTo(first.fields().get("snapshotIdentity"));
  }

  @Test
  void incompleteOrConflictingTargetFactsAndSourcesNeverProduceAValue() {
    for (String field : List.of("activeWorldEpoch", "canonicalVersionId", "targetClass")) {
      var target = target();
      target.remove(field);
      assertThatThrownBy(() -> capture(target, facts(), sources(), NOW))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String field : List.of("tenantAuthorityGeneration", "tenantBillingSequence")) {
      var facts = facts();
      facts.put(field, "9");
      assertThatThrownBy(() -> capture(target(), facts, sources(), NOW))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var unframedScopeSource = sources();
    unframedScopeSource.put("scopeDigest", HASH);
    assertThatThrownBy(() -> capture(target(), facts(), unframedScopeSource, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    var target = target();
    target.put("characterCreationPolicy", "PLAYER_CREATED");
    assertThatThrownBy(() -> capture(target, facts(), sources(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
    var source = sources();
    source.put("callerGeneration", "1");
    assertThatThrownBy(() -> capture(target(), facts(), source, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> capture(target(), facts(), sources(), NOW.plusSeconds(120)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void strictDecoderRoundTripsAndRejectsNoncanonicalUnknownOrConflictingContent() {
    var value = capture(target(), facts(), sources(), NOW);
    assertThat(RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(value.canonicalBytes()))
        .isEqualTo(value);
    String json = new String(value.canonicalBytes(), StandardCharsets.UTF_8);
    for (byte[] malformed :
        List.of(
            (" " + json).getBytes(StandardCharsets.UTF_8),
            json.replace("\"variant\":\"PLAYER_ADMISSION\"", "\"variant\":\"TENANT_OPERATION\"")
                .getBytes(StandardCharsets.UTF_8),
            (json.substring(0, json.length() - 1) + ",\"unknown\":true}")
                .getBytes(StandardCharsets.UTF_8),
            (json.substring(0, json.length() - 1) + ",\"variant\":\"PLAYER_ADMISSION\"}")
                .getBytes(StandardCharsets.UTF_8),
            json.replace("\"membershipExists\":false", "\"membershipExists\":true")
                .getBytes(StandardCharsets.UTF_8),
            json.replace("\"pointerVersion\":\"3\"", "\"pointerVersion\":\"4\"")
                .getBytes(StandardCharsets.UTF_8),
            new byte[] {(byte) 0xc3, (byte) 0x28},
            new byte[RuntimePlayerAdmissionEntitlementEvidence.MAX_CANONICAL_BYTES + 1])) {
      assertThatThrownBy(() -> RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void legacyBearerBundleAndNewBundleWithRawFieldFailClosed() throws Exception {
    var value = capture(target(), facts(), sources(), NOW);
    var legacy = value.fields();
    legacy.put("bundleVersion", "1");
    legacy.remove("verifiedConnectScopeTokenHash");
    legacy.put("verifiedConnectScopeId", SCOPE);
    Object evaluatedAt = legacy.remove("evaluatedAt");
    Object requestId = legacy.remove("requestId");
    legacy.remove("snapshotIdentity");
    legacy.remove("evaluationIdentity");
    String snapshot = RuntimePlayerAdmissionEntitlementEvidence.digest(legacy);
    legacy.put("snapshotIdentity", snapshot);
    legacy.put("evaluatedAt", evaluatedAt);
    legacy.put("requestId", requestId);
    legacy.put(
        "evaluationIdentity",
        RuntimePlayerAdmissionEntitlementEvidence.digest(
            Map.of(
                "snapshotIdentity", snapshot,
                "requestId", requestId,
                "callerWorkload", PEER,
                "evaluatedAt", evaluatedAt)));
    var rawField = value.fields();
    rawField.put("verifiedConnectScopeId", SCOPE);
    var mapper = JsonMapper.builder().build();
    for (var fields : List.of(legacy, rawField)) {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(mapper.writeValueAsString(fields));
      byte[] retained = bytes.clone();
      assertThatThrownBy(() -> RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(bytes))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining(SCOPE);
      assertThat(bytes).containsExactly(retained);
    }
  }

  @Test
  void rawScopeCannotBeSuppliedAsTheVerifiedTokenHash() {
    assertThatThrownBy(
            () ->
                RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
                    REQUEST, PEER, SCOPE, membership(), target(), facts(), sources(), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Verified connect scope token hash required");
  }

  @Test
  void validityIsMinimumOfActualScopeCallerAndPaidReceiptBounds() {
    var source = sources();
    var demo = capture(target(), facts(), source, NOW);
    assertThat(demo.validUntil()).isEqualTo(NOW.plusSeconds(90));
    demo.requireCurrentAt(NOW.plusSeconds(89));
    assertThatThrownBy(() -> demo.requireCurrentAt(NOW.plusSeconds(90)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> demo.requireCurrentAt(NOW.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    var paid = facts();
    paid.put("sourceKind", "PAID_HOSTING");
    paid.put("subscriptionStatus", "active");
    source.put("paidReceiptValidUntil", NOW.plusSeconds(4).toString());
    var value = capture(target(), paid, source, NOW);
    assertThat(value.validUntil()).isEqualTo(NOW.plusSeconds(4));
    assertThat(RuntimePlayerAdmissionEntitlementEvidence.parseCanonical(value.canonicalBytes()))
        .isEqualTo(value);
    source.put("paidReceiptValidUntil", NOW.toString());
    assertThatThrownBy(() -> capture(target(), paid, source, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    source.put("paidReceiptValidUntil", null);
    assertThatThrownBy(() -> capture(target(), paid, source, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RuntimePlayerAdmissionEntitlementEvidence capture(
      Map<String, Object> target,
      Map<String, Object> facts,
      Map<String, Object> source,
      Instant at) {
    return RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
        REQUEST, PEER, SCOPE_HASH, membership(), target, facts, source, at);
  }

  private static Map<String, Object> target() {
    Map<String, Object> target = new LinkedHashMap<>();
    target.put("targetClass", "PUBLIC_PRODUCTION");
    target.put("characterCreationPolicy", "PRESEEDED_ONLY");
    target.put("targetNamespace", "prod");
    target.put("tenantId", TENANT.toString());
    target.put("tenantSlug", "tenant");
    target.put("worldSlug", "world");
    target.put("realmId", REQUEST.toString());
    target.put("realmSlug", "production");
    target.put("playableStateNamespaceId", REQUEST.toString());
    target.put("playableStateScope", "SHARED");
    target.put("gameInstanceId", REQUEST.toString());
    target.put("canonicalVersionId", REQUEST.toString());
    target.put("catalogRevision", "7");
    target.put("pointerVersion", "3");
    target.put("activeWorldEpoch", "5");
    target.put("evaluatedAt", NOW.minusSeconds(20).toString());
    target.put("connectScopeExpiresAt", NOW.plusSeconds(120).toString());
    return target;
  }

  private static Map<String, Object> facts() {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("sourceKind", "NON_PAID_DEMO");
    facts.put("subscriptionStatus", null);
    facts.put("provisioningStatus", null);
    for (String flag :
        List.of(
            "gameplayAvailable",
            "allowPublicJoin",
            "allowNewGameplayBindings",
            "allowNewInstanceStarts")) facts.put(flag, true);
    facts.put(
        "quotas",
        Map.of(
            "maxActiveSessions",
            "3",
            "maxConcurrentGameInstances",
            "2",
            "maxStorageBytes",
            "4096"));
    facts.put("entitlementVersion", "1");
    facts.put("tenantBillingSequence", "1");
    facts.put("tenantAuthorityGeneration", "2");
    return facts;
  }

  private static Map<String, Object> sources() {
    Map<String, Object> source = new LinkedHashMap<>();
    source.put("callerTokenExpiresAt", NOW.plusSeconds(90).toString());
    source.put("paidReceiptValidUntil", null);
    for (String key :
        List.of("callerTokenJti", "callerContextId", "callerOperationId", "worldCaptureId"))
      source.put(key, REQUEST.toString());
    for (String key :
        List.of(
            "callerTokenSha256",
            "callerRegistryRecordSha256",
            "callerCommitProofSha256",
            "admissionPointerSnapshotDigest")) source.put(key, HASH);
    for (String key :
        List.of(
            "scopeDigest",
            "entitlementSourceDigest",
            "gameSessionOwnerProofDigest",
            "publishedPolicySetDigest",
            "worldLifecycleDigest")) source.put(key, "sha256:" + HASH);
    source.put("worldRowVersion", "0");
    return source;
  }

  private static Snapshot membership() {
    String requestDigest =
        GameTenantCreationDigest.requestDigest("prod", REQUEST, "world", "World", null);
    var source =
        new FreshTenantCreationEvidence(
            1,
            "prod",
            REQUEST,
            REQUEST,
            requestDigest,
            TENANT,
            17,
            "world",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "prod", REQUEST, REQUEST, requestDigest, TENANT, 17, "world", "NEW_GAME_ROW"));
    var request =
        new TenantAuthorityEventV1Codec.AuthorityRequest(
            REQUEST, "sha256:" + HASH, TENANT, source.creationRequestId(), source.requestDigest());
    var billing =
        new TenantAuthorityEventV1Codec.BillingReceipt(
            TENANT,
            REQUEST,
            "sha256:" + HASH,
            TenantAuthorityEventV1Codec.tenantBillingStreamKey(TENANT),
            1,
            TenantAuthorityEventV1Codec.tenantBillingEventId(REQUEST),
            "sha256:" + HASH);
    var event = TenantAuthorityEventV1Codec.seal(request, source, 2, 2, 1, billing);
    String prefix = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;
    var tuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(TENANT.toString(), "2"),
            Map.of(TENANT.toString(), "1"),
            List.of(),
            Optional.empty(),
            Optional.of(
                Map.of(
                    TENANT.toString(),
                    new TenantBillingCutoff("2", "1", event.outboxStreamKey(), "1"))));
    return new Snapshot(
        "firemud-account-service",
        ACCOUNT.toString(),
        TENANT.toString(),
        false,
        "MISSING",
        false,
        Map.of(TENANT.toString(), "1"),
        "1",
        List.of(),
        tuple,
        "1",
        List.of(
            new Checkpoint(prefix + "account/" + ACCOUNT, "0"),
            new Checkpoint(prefix + "issuer/firemud-account-service", "0"),
            new Checkpoint(prefix + "membership/" + ACCOUNT + "/" + TENANT, "0"),
            new Checkpoint(event.outboxStreamKey(), "1")),
        List.of(
            new SourceEvidence(
                event.outboxStreamKey(),
                "1",
                event.eventId().toString(),
                event.eventDigest(),
                new String(event.payload(), StandardCharsets.UTF_8))));
  }
}
