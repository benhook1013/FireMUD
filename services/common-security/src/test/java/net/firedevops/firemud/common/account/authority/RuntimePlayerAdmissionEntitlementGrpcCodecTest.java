package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.ReadPlayerAdmissionEntitlementResponse;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.TenantBillingCutoff;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.RetainedTargetSelector;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

/** Controlled content and wire fixtures only; no source authentication or owner execution. */
class RuntimePlayerAdmissionEntitlementGrpcCodecTest {
  private static final UUID ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID REQUEST = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final String PEER = "spiffe://firemud/ns/prod/sa/game-session-service";
  private static final String HASH = "a".repeat(64);
  private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
  private static final String TOKEN = "controlled.initial.jwt";
  private static final String SCOPE = "controlled-scope";

  private static RuntimePlayerAdmissionEntitlementGrpcCodec.Request request(
      UUID account, UUID requestId) {
    return new RuntimePlayerAdmissionEntitlementGrpcCodec.Request(
        new RetainedTargetSelector(
            requestId,
            account,
            "prod",
            TENANT,
            "tenant",
            "world",
            REQUEST,
            "production",
            REQUEST,
            "SHARED",
            REQUEST,
            7,
            3),
        SCOPE);
  }

  @Test
  void exactRoundTripPreservesTargetAndNeverEchoesEitherCredential() {
    var request = request(ACCOUNT, REQUEST);
    var wire = RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(request, TOKEN);
    var presented = RuntimePlayerAdmissionEntitlementGrpcCodec.parseRequest(wire);
    assertThat(presented.request()).isEqualTo(request);
    assertThat(presented.initialLoginJwt()).isEqualTo(TOKEN);
    assertThat(presented.toString()).doesNotContain(TOKEN, SCOPE);
    assertThat(request.toString()).doesNotContain(SCOPE);
    var value = capture(target(), facts(), sources(), NOW);
    var response = RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(request, value);
    assertThat(RuntimePlayerAdmissionEntitlementGrpcCodec.requireResponse(request, response))
        .isEqualTo(value);
    assertThat(response.getBinding(0).getSchemaVersionList()).containsExactly(2);
    assertThat(response.getBinding(0).getTargetList())
        .containsExactly(wire.getSelectors(0).getTarget(0));
    assertThat(response.toByteString().toStringUtf8()).doesNotContain(TOKEN, SCOPE);
    assertThat(value.verifiedConnectScopeTokenHash())
        .isEqualTo(RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(SCOPE));
  }

  @Test
  void missingDuplicateUnknownAndOversizedMembersAreRejected() {
    var request = request(ACCOUNT, REQUEST);
    var wire = RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(request, TOKEN);
    var unknown =
        com.google.protobuf.UnknownFieldSet.newBuilder()
            .addField(
                99, com.google.protobuf.UnknownFieldSet.Field.newBuilder().addVarint(1).build())
            .build();
    for (var malformed :
        List.of(
            wire.getDefaultInstanceForType(),
            wire.toBuilder().addSelectors(wire.getSelectors(0)).build(),
            wire.toBuilder().addInitialLoginJwt(TOKEN).build(),
            wire.toBuilder().setUnknownFields(unknown).build(),
            wire.toBuilder()
                .setSelectors(0, wire.getSelectors(0).toBuilder().addSchemaVersion(1))
                .build(),
            wire.toBuilder()
                .setSelectors(0, wire.getSelectors(0).toBuilder().setUnknownFields(unknown))
                .build(),
            wire.toBuilder().setInitialLoginJwt(0, "a".repeat(45000)).build())) {
      assertThatThrownBy(() -> RuntimePlayerAdmissionEntitlementGrpcCodec.parseRequest(malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var response =
        RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(
            request, capture(target(), facts(), sources(), NOW));
    for (var malformed :
        List.of(
            response.getDefaultInstanceForType(),
            response.toBuilder().addBinding(response.getBinding(0)).build(),
            response.toBuilder()
                .setBinding(0, response.getBinding(0).toBuilder().clearTarget())
                .build(),
            response.toBuilder()
                .setBinding(0, response.getBinding(0).toBuilder().setSchemaVersion(0, 1))
                .build(),
            response.toBuilder()
                .setBinding(0, response.getBinding(0).toBuilder().setUnknownFields(unknown))
                .build(),
            response.toBuilder().addCanonicalEvidence(response.getCanonicalEvidence(0)).build(),
            response.toBuilder().setUnknownFields(unknown).build(),
            response.toBuilder()
                .setCanonicalEvidence(0, com.google.protobuf.ByteString.copyFromUtf8("{}"))
                .build())) {
      assertThatThrownBy(
              () -> RuntimePlayerAdmissionEntitlementGrpcCodec.requireResponse(request, malformed))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsLegacyCredentialBearingResponseAndWrongCanonicalHashOrBinding() throws Exception {
    var request = request(ACCOUNT, REQUEST);
    var value = capture(target(), facts(), sources(), NOW);
    var response = RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(request, value);
    var legacy =
        ReadPlayerAdmissionEntitlementResponse.newBuilder()
            .addCanonicalEvidence(response.getCanonicalEvidence(0))
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        1,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(
                                RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(request, TOKEN)
                                    .getSelectors(0)
                                    .toByteString())
                            .build())
                    .build())
            .build();
    var wrongTarget =
        RuntimePlayerAdmissionEntitlementGrpcCodec.toRequest(
                request(ACCOUNT, UUID.randomUUID()), TOKEN)
            .getSelectors(0)
            .getTarget(0);
    String canonical = response.getCanonicalEvidence(0).toStringUtf8();
    for (var invalid :
        List.of(
            ReadPlayerAdmissionEntitlementResponse.parseFrom(legacy.toByteArray()),
            ReadPlayerAdmissionEntitlementResponse.parseFrom(
                response.toBuilder()
                    .setUnknownFields(legacy.getUnknownFields())
                    .build()
                    .toByteArray()),
            response.toBuilder()
                .setBinding(0, response.getBinding(0).toBuilder().setTarget(0, wrongTarget))
                .build(),
            response.toBuilder()
                .setCanonicalEvidence(
                    0,
                    ByteString.copyFromUtf8(
                        canonical.replace(value.verifiedConnectScopeTokenHash(), "sha256:" + HASH)))
                .build(),
            response.toBuilder()
                .setCanonicalEvidence(
                    0,
                    ByteString.copyFromUtf8(
                        canonical.replace(value.verifiedConnectScopeTokenHash(), SCOPE)))
                .build())) {
      assertThatThrownBy(
              () -> RuntimePlayerAdmissionEntitlementGrpcCodec.requireResponse(request, invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void changedCallerRequestOrScopeCannotBeReturnedAsCorrelatedEvidence() {
    var request = request(ACCOUNT, REQUEST);
    var value = capture(target(), facts(), sources(), NOW);
    var response = RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(request, value);
    for (var other :
        List.of(
            request(UUID.randomUUID(), REQUEST),
            request(ACCOUNT, UUID.randomUUID()),
            new RuntimePlayerAdmissionEntitlementGrpcCodec.Request(
                request.target(), "another-scope"))) {
      assertThatThrownBy(() -> RuntimePlayerAdmissionEntitlementGrpcCodec.toResponse(other, value))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> RuntimePlayerAdmissionEntitlementGrpcCodec.requireResponse(other, response))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static RuntimePlayerAdmissionEntitlementEvidence capture(
      Map<String, Object> target,
      Map<String, Object> facts,
      Map<String, Object> source,
      Instant at) {
    return RuntimePlayerAdmissionEntitlementEvidence.fromOwnerEvaluation(
        REQUEST,
        PEER,
        RuntimePlayerAdmissionEntitlementEvidence.connectScopeTokenHash(SCOPE),
        membership(),
        target,
        facts,
        source,
        at);
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
