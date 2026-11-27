package net.firedevops.firemud.common.account.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Evidence;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Identity;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Operation;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Owner;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.PlayerContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Shape;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Shared vectors prove content encoding only, never a current Account or runtime grant. */
class CapacityAdmissionDigestTest {
  @Test
  void sharedCorpusFixesCompleteEvidenceAndAbsentZeroPositiveBoundaryPreimages() throws Exception {
    var resource = getClass().getResourceAsStream("/capacity/capacity-admission-v1-vectors.json");
    assertThat(resource).isNotNull();
    try (resource) {
      var corpus = JsonMapper.builder().build().readTree(resource);
      for (var vector : corpus.path("vectors")) {
        Long delta =
            vector.path("capacityDelta").isNull()
                ? null
                : TenantCapacityAdmission.decimal(vector.path("capacityDelta").asText());
        Request base = CapacityAdmissionTestFixture.worldRequest(delta);
        assertThat(Base64.getEncoder().encodeToString(base.evidence().canonicalBytes()))
            .isEqualTo(corpus.path("request").path("canonicalAccountEvidenceBase64").asText());
        Request action = base;
        if (vector.has("operation")) {
          var prior = vector.path("priorShape");
          var requested = vector.path("requestedShape");
          action =
              new Request(
                      base.identity(),
                      base.admissionAttemptId(),
                      base.capacityRearmFence(),
                      base.targetNamespace(),
                      base.context(),
                      Operation.valueOf(vector.path("operation").asText()),
                      new Shape(
                          TenantCapacityAdmission.decimal(prior.get(0).asText()),
                          TenantCapacityAdmission.decimal(prior.get(1).asText()),
                          TenantCapacityAdmission.decimal(prior.get(2).asText())),
                      new Shape(
                          TenantCapacityAdmission.decimal(requested.get(0).asText()),
                          TenantCapacityAdmission.decimal(requested.get(1).asText()),
                          TenantCapacityAdmission.decimal(requested.get(2).asText())),
                      base.evidence(),
                      delta,
                      null)
                  .withDigest();
        }
        assertThat(HexFormat.of().formatHex(CapacityAdmissionDigest.preimage(action)))
            .as(vector.path("name").asText())
            .isEqualTo(vector.path("preimageHex").asText());
        assertThat(CapacityAdmissionDigest.digest(action))
            .isEqualTo(vector.path("sha256").asText());
        Request exact = action;
        if (vector.path("commitAllowed").asBoolean()) exact.requireCommittable();
        else
          assertThatThrownBy(exact::requireCommittable)
              .isInstanceOf(IllegalArgumentException.class);
      }
    }
    assertThat(CapacityAdmissionTestFixture.worldRequest(null).mutationDigest())
        .isNotEqualTo(CapacityAdmissionTestFixture.worldRequest(0L).mutationDigest());
  }

  @Test
  void sharedPlayerCorpusFixesIndependentPreimageAndDistinctCharacterAndSessionBindings()
      throws Exception {
    var resource = getClass().getResourceAsStream("/capacity/capacity-admission-v1-vectors.json");
    assertThat(resource).isNotNull();
    try (resource) {
      var corpus = JsonMapper.builder().build().readTree(resource);
      var request = corpus.path("playerRequest");
      var context = request.path("ownerContext");
      var vector = corpus.path("playerVector");
      var player =
          new PlayerContext(
              UUID.fromString(context.path("accountId").asText()),
              UUID.fromString(context.path("tenantId").asText()),
              UUID.fromString(context.path("playableStateNamespaceId").asText()),
              UUID.fromString(context.path("gameInstanceId").asText()),
              UUID.fromString(context.path("characterId").asText()),
              UUID.fromString(context.path("sessionId").asText()),
              UUID.fromString(context.path("realmId").asText()),
              UUID.fromString(context.path("requestId").asText()),
              context.path("playableStateScope").asText());
      assertThat(
              List.of(
                  player.accountId(),
                  player.tenantId(),
                  player.playableStateNamespaceId(),
                  player.gameInstanceId(),
                  player.characterId(),
                  player.sessionId(),
                  player.realmId(),
                  player.requestId()))
          .doesNotHaveDuplicates();
      assertThat(request.path("ownerService").asText()).isEqualTo(player.owner().service());
      Request action =
          new Request(
                  new Identity(
                      UUID.fromString(request.path("tenantId").asText()),
                      UUID.fromString(request.path("requestId").asText()),
                      UUID.fromString(request.path("admissionId").asText())),
                  UUID.fromString(request.path("admissionAttemptId").asText()),
                  TenantCapacityAdmission.decimal(request.path("capacityRearmFence").asText()),
                  request.path("targetNamespace").asText(),
                  player,
                  Operation.valueOf(request.path("operation").asText()),
                  vectorShape(request.path("priorShape")),
                  vectorShape(request.path("requestedShape")),
                  Evidence.parse(
                      Base64.getDecoder()
                          .decode(request.path("canonicalAccountEvidenceBase64").asText()),
                      Owner.GAME_SESSION),
                  TenantCapacityAdmission.decimal(vector.path("capacityDelta").asText()),
                  null)
              .withDigest();
      action.requireCommittable();
      assertThat(HexFormat.of().formatHex(CapacityAdmissionDigest.preimage(action)))
          .isEqualTo(vector.path("preimageHex").asText());
      assertThat(CapacityAdmissionDigest.digest(action)).isEqualTo(vector.path("sha256").asText());
      UUID changed = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
      for (var ids :
          List.of(
              List.of(changed, player.sessionId()),
              List.of(player.characterId(), changed),
              List.of(player.sessionId(), player.characterId()))) {
        var mutation =
            new PlayerContext(
                player.accountId(),
                player.tenantId(),
                player.playableStateNamespaceId(),
                player.gameInstanceId(),
                ids.get(0),
                ids.get(1),
                player.realmId(),
                player.requestId(),
                player.playableStateScope());
        Request altered =
            new Request(
                    action.identity(),
                    action.admissionAttemptId(),
                    action.capacityRearmFence(),
                    action.targetNamespace(),
                    mutation,
                    action.operation(),
                    action.priorShape(),
                    action.requestedShape(),
                    action.evidence(),
                    action.capacityDelta(),
                    null)
                .withDigest();
        altered.requireCommittable();
        assertThat(altered.mutationDigest()).isNotEqualTo(action.mutationDigest());
      }
    }
  }

  private static Shape vectorShape(tools.jackson.databind.JsonNode shape) {
    return new Shape(
        TenantCapacityAdmission.decimal(shape.get(0).asText()),
        TenantCapacityAdmission.decimal(shape.get(1).asText()),
        TenantCapacityAdmission.decimal(shape.get(2).asText()));
  }

  @Test
  void sharedRejectedEvidenceAndRequestsReachCanonicalValidators() throws Exception {
    var resource = getClass().getResourceAsStream("/capacity/capacity-admission-v1-vectors.json");
    assertThat(resource).isNotNull();
    try (resource) {
      var corpus = JsonMapper.builder().build().readTree(resource);
      for (var vector : corpus.path("rejectedEvidence")) {
        byte[] bytes =
            Base64.getDecoder().decode(vector.path("canonicalAccountEvidenceBase64").asText());
        assertThatThrownBy(() -> Evidence.parse(bytes, Owner.WORLD_MANAGEMENT))
            .as(vector.path("name").asText())
            .isInstanceOf(IllegalArgumentException.class);
      }
      for (var vector : corpus.path("rejectedRequests")) {
        Request base = CapacityAdmissionTestFixture.worldRequest(17L);
        Shape requested = base.requestedShape();
        if (vector.has("requestedShape")) {
          var shape = vector.path("requestedShape");
          requested =
              new Shape(
                  TenantCapacityAdmission.decimal(shape.get(0).asText()),
                  TenantCapacityAdmission.decimal(shape.get(1).asText()),
                  TenantCapacityAdmission.decimal(shape.get(2).asText()));
        }
        Long delta =
            vector.has("capacityDelta")
                ? (vector.path("capacityDelta").isNull()
                    ? null
                    : TenantCapacityAdmission.decimal(vector.path("capacityDelta").asText()))
                : base.capacityDelta();
        Request rejected =
            new Request(
                    base.identity(),
                    base.admissionAttemptId(),
                    base.capacityRearmFence(),
                    base.targetNamespace(),
                    base.context(),
                    base.operation(),
                    base.priorShape(),
                    requested,
                    base.evidence(),
                    delta,
                    null)
                .withDigest();
        assertThatThrownBy(rejected::requireStructurallyValid)
            .as(vector.path("name").asText())
            .isInstanceOf(IllegalArgumentException.class);
      }
    }
  }

  @Test
  void nonCreatingEvidenceIsValidContentButCannotAuthorizeExecution() {
    Request denied = CapacityAdmissionTestFixture.playerRequest(false);
    denied.requireStructurallyValid();
    assertThat(denied.evidence().permitsCreation()).isFalse();
    assertThat(CapacityAdmissionTestFixture.playerRequest().evidence().permitsCreation()).isTrue();
    assertThat(denied.mutationDigest())
        .isNotEqualTo(CapacityAdmissionTestFixture.playerRequest().mutationDigest());
    assertThatThrownBy(denied::requireCommittable).isInstanceOf(IllegalArgumentException.class);
    var reservation =
        new TenantCapacityAdmission.Result(
            denied.identity(),
            denied.admissionAttemptId(),
            denied.capacityRearmFence(),
            denied.mutationDigest(),
            java.util.UUID.fromString("b1111111-1111-4111-8111-111111111111"),
            1,
            denied.evidence().expiresAt(),
            TenantCapacityAdmission.State.RESERVED,
            denied.evidence().snapshotIdentity(),
            denied.evidence().evaluationIdentity());
    assertThatThrownBy(
            () ->
                new OriginalNewCapacityExecution(
                    denied,
                    reservation,
                    denied.identity().admissionId(),
                    "sha256:" + "a".repeat(64),
                    "owner-1",
                    1))
        .isInstanceOf(IllegalArgumentException.class);

    for (Request invalid :
        List.of(
            playerMutation(denied, null, denied.requestedShape(), null).withDigest(),
            playerMutation(denied, 1L, denied.requestedShape(), null),
            playerMutation(denied, 1L, denied.requestedShape(), "b".repeat(64)),
            playerMutation(denied, 0L, denied.requestedShape(), null).withDigest(),
            playerMutation(denied, 2L, denied.requestedShape(), null).withDigest(),
            playerMutation(denied, 1L, denied.priorShape(), null).withDigest(),
            playerMutation(denied, 1L, new Shape(1, 1, 0), null).withDigest(),
            playerMutation(denied, 1L, new Shape(1, 0, 1), null).withDigest())) {
      assertThatThrownBy(invalid::requireStructurallyValid)
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(invalid::requireCommittable).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void nonExpandingRecoveryStillRequiresCreationEligibleEvidence() {
    Request denied = CapacityAdmissionTestFixture.playerRequest(false);
    Request recovery =
        new Request(
                denied.identity(),
                denied.admissionAttemptId(),
                denied.capacityRearmFence(),
                denied.targetNamespace(),
                denied.context(),
                Operation.NON_EXPANDING_RECOVERY,
                denied.priorShape(),
                denied.priorShape(),
                denied.evidence(),
                0L,
                null)
            .withDigest();
    recovery.requireStructurallyValid();
    assertThatThrownBy(recovery::requireCommittable).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void originalExecutionPreservesAndEscapesOpaqueRuntimeOwnerIdentity() throws Exception {
    Request request = CapacityAdmissionTestFixture.playerRequest();
    var reservation =
        new TenantCapacityAdmission.Result(
            request.identity(),
            request.admissionAttemptId(),
            request.capacityRearmFence(),
            request.mutationDigest(),
            UUID.fromString("b1111111-1111-4111-8111-111111111111"),
            1,
            request.evidence().expiresAt(),
            TenantCapacityAdmission.State.RESERVED,
            request.evidence().snapshotIdentity(),
            request.evidence().evaluationIdentity());
    for (String owner :
        List.of("owner-1", "game-session/worker α", "worker\"\\α", "😀".repeat(120))) {
      var execution =
          new OriginalNewCapacityExecution(
              request,
              reservation,
              request.identity().admissionId(),
              "sha256:" + "a".repeat(64),
              owner,
              1);
      var json = JsonMapper.builder().build().readTree(execution.canonicalBytes());
      assertThat(json.get("selectedOwnerId").asText()).isEqualTo(owner);
      assertThat(execution.canonicalBytes()).isEqualTo(execution.canonicalBytes());
    }
    for (String owner :
        List.of("", " ", " owner", "owner\t", "😀".repeat(121), "bad\u0000id", "bad\uD800id")) {
      assertThatThrownBy(
              () ->
                  new OriginalNewCapacityExecution(
                      request,
                      reservation,
                      request.identity().admissionId(),
                      "sha256:" + "a".repeat(64),
                      owner,
                      1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void boundReleasedAndQuarantinedResultsCannotAuthorizeOriginalExecution() {
    Request request = CapacityAdmissionTestFixture.playerRequest();
    for (var state :
        List.of(
            TenantCapacityAdmission.State.RELEASED,
            TenantCapacityAdmission.State.QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION)) {
      var reservation =
          new TenantCapacityAdmission.Result(
              request.identity(),
              request.admissionAttemptId(),
              request.capacityRearmFence(),
              request.mutationDigest(),
              UUID.fromString("b1111111-1111-4111-8111-111111111111"),
              1,
              request.evidence().expiresAt(),
              state,
              request.evidence().snapshotIdentity(),
              request.evidence().evaluationIdentity());
      reservation.requireBinding(request);
      assertThatThrownBy(
              () ->
                  new OriginalNewCapacityExecution(
                      request,
                      reservation,
                      request.identity().admissionId(),
                      "sha256:" + "a".repeat(64),
                      "owner-1",
                      1))
          .as(state.name())
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static Request playerMutation(Request base, Long delta, Shape requested, String digest) {
    return new Request(
        base.identity(),
        base.admissionAttemptId(),
        base.capacityRearmFence(),
        base.targetNamespace(),
        base.context(),
        base.operation(),
        base.priorShape(),
        requested,
        base.evidence(),
        delta,
        digest);
  }

  @Test
  void decimalWireGrammarRejectsNegativesOverflowAndMalformedSpellingsBeforeHash() {
    for (String value :
        List.of("9223372036854775808", "-1", "+1", "01", "1e0", "1.0", " 1", "1 ")) {
      assertThatThrownBy(() -> TenantCapacityAdmission.decimal(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(TenantCapacityAdmission.decimal("0")).isZero();
    assertThat(TenantCapacityAdmission.decimal("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
    assertThatThrownBy(() -> assertThat(Math.addExact(Long.MAX_VALUE, 1)).isPositive())
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void evidenceIsStrictCanonicalAndCompleteChangedFieldsAlterDigest() {
    Request original = CapacityAdmissionTestFixture.worldRequest(17L);
    String json = new String(original.evidence().canonicalBytes(), StandardCharsets.UTF_8);
    for (String changed :
        List.of(
            " " + json,
            json.replaceFirst("\"outboxSequence\":\"3\"", "\"outboxSequence\":\"4\""),
            "{\"bundleVersion\":\"1\"," + json.substring(1))) {
      assertThatThrownBy(
              () ->
                  Evidence.parse(changed.getBytes(StandardCharsets.UTF_8), Owner.WORLD_MANAGEMENT))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Evidence changed =
        Evidence.parse(
            json.replace("\"entitlementVersion\":\"7\"", "\"entitlementVersion\":\"8\"")
                .getBytes(StandardCharsets.UTF_8),
            Owner.WORLD_MANAGEMENT);
    Request mutation =
        new Request(
                original.identity(),
                original.admissionAttemptId(),
                original.capacityRearmFence(),
                original.targetNamespace(),
                original.context(),
                original.operation(),
                original.priorShape(),
                original.requestedShape(),
                changed,
                original.capacityDelta(),
                null)
            .withDigest();
    assertThat(mutation.mutationDigest()).isNotEqualTo(original.mutationDigest());
    byte[] copy = changed.canonicalBytes();
    copy[0] = 0;
    assertThat(changed.canonicalBytes()[0]).isEqualTo((byte) '{');
  }

  @Test
  void zeroCannotHideAnyChangedExpandingShape() {
    Request original = CapacityAdmissionTestFixture.worldRequest(0L);
    Request zero =
        new Request(
                original.identity(),
                original.admissionAttemptId(),
                original.capacityRearmFence(),
                original.targetNamespace(),
                original.context(),
                Operation.NON_EXPANDING_RECOVERY,
                new Shape(0, 1, 0),
                new Shape(0, 1, 0),
                original.evidence(),
                0L,
                null)
            .withDigest();
    zero.requireCommittable();
    for (Shape shape : List.of(new Shape(1, 1, 0), new Shape(0, 2, 0), new Shape(0, 1, 1))) {
      var expanding =
          new Request(
                  zero.identity(),
                  zero.admissionAttemptId(),
                  zero.capacityRearmFence(),
                  zero.targetNamespace(),
                  zero.context(),
                  zero.operation(),
                  zero.priorShape(),
                  shape,
                  zero.evidence(),
                  0L,
                  null)
              .withDigest();
      assertThatThrownBy(expanding::requireCommittable)
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(expanding.mutationDigest()).isNotEqualTo(zero.mutationDigest());
    }
  }
}
