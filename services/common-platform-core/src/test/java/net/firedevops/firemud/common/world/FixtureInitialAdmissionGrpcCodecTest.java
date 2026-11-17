package net.firedevops.firemud.common.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.world.FixtureInitialAdmissionGrpcCodec.Lookup;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.InitialAdmissionOrigin;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHoldState.HoldStatus;
import net.firedevops.firemud.common.world.WorldFixtureInitialAdmissionTerminalProof.Outcome;
import org.junit.jupiter.api.Test;

class FixtureInitialAdmissionGrpcCodecTest {
  @Test
  void exactAcquireIdentityStateAndAllTerminalOutcomesRoundTrip() {
    var request = request(false);
    var identity = identity(request);
    var acquire = Lookup.fresh(request, null);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.fromWorldRequest(
                FixtureInitialAdmissionGrpcCodec.toWorldRequest(acquire), false))
        .isEqualTo(acquire);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.requireWorldIdentity(
                acquire, FixtureInitialAdmissionGrpcCodec.toWorldResponse(acquire, identity, null)))
        .isEqualTo(identity);
    var read = Lookup.fresh(request, identity);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.fromWorldRequest(
                FixtureInitialAdmissionGrpcCodec.toWorldRequest(read), true))
        .isEqualTo(read);
    var state =
        new WorldFixtureInitialAdmissionHoldState(
            request, identity, HoldStatus.PENDING, "ACTIVE", 2, 1);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.requireWorldState(
                read, FixtureInitialAdmissionGrpcCodec.toWorldResponse(read, identity, state)))
        .isEqualTo(state);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.fromTerminalRequest(
                FixtureInitialAdmissionGrpcCodec.toTerminalRequest(read)))
        .isEqualTo(read);
    for (var outcome : Outcome.values()) {
      var proof = proof(request, identity, outcome);
      assertThat(
              FixtureInitialAdmissionGrpcCodec.requireTerminalResponse(
                  read, FixtureInitialAdmissionGrpcCodec.toTerminalResponse(read, proof)))
          .isEqualTo(proof);
    }
  }

  @Test
  void lookupRejectsReusedReadIdentitiesAndDifferentCompleteRequest() {
    var request = request(false);
    var identity = identity(request);
    for (UUID reused :
        List.of(
            new UUID(0, 0),
            request.activation().activationRequestId(),
            UUID.fromString(request.selector().initialAdmissionRequestId()),
            identity.holdId(),
            identity.holdFence())) {
      assertThatThrownBy(() -> new Lookup(reused, request, identity))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new Lookup(UUID.randomUUID(), request(true), identity))
        .isInstanceOf(IllegalArgumentException.class);
    var fresh = Lookup.fresh(request, identity);
    assertThat(fresh.readRequestId())
        .isNotIn(
            request.activation().activationRequestId(), identity.holdId(), identity.holdFence());
  }

  @Test
  void worldRequestsRejectUnknownMissingOversizedNoncanonicalAndWrongOperationShape() {
    var acquire = Lookup.fresh(request(false), null);
    var valid = FixtureInitialAdmissionGrpcCodec.toWorldRequest(acquire);
    for (var bad :
        List.of(
            valid.toBuilder().setUnknownFields(unknown()).build(),
            valid.toBuilder().clearFixtureHoldRequestBytes().build(),
            valid.toBuilder()
                .setFixtureHoldRequestBytes(ByteString.copyFrom(new byte[65537]))
                .build(),
            valid.toBuilder()
                .setFixtureHoldRequestBytes(
                    valid.getFixtureHoldRequestBytes().concat(ByteString.copyFromUtf8(" ")))
                .build(),
            valid.toBuilder().setReadRequestId("not-a-uuid").build())) {
      assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.fromWorldRequest(bad, false))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.fromWorldRequest(valid, true))
        .isInstanceOf(IllegalArgumentException.class);
    var withIdentity =
        FixtureInitialAdmissionGrpcCodec.toWorldRequest(
            Lookup.fresh(acquire.request(), identity(acquire.request())));
    assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.fromWorldRequest(withIdentity, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void worldResponsesRejectReadEchoRequestHoldFenceAndStateSubstitution() {
    var request = request(false);
    var identity = identity(request);
    var read = Lookup.fresh(request, identity);
    var valid = FixtureInitialAdmissionGrpcCodec.toWorldResponse(read, identity, null);
    var otherHold = new HoldIdentity(request.selector(), UUID.randomUUID(), identity.holdFence());
    var otherFence = new HoldIdentity(request.selector(), identity.holdId(), UUID.randomUUID());
    for (var bad :
        List.of(
            valid.toBuilder().setUnknownFields(unknown()).build(),
            valid.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            valid.toBuilder()
                .setFixtureHoldRequestBytes(ByteString.copyFrom(request(true).canonicalBytes()))
                .build(),
            valid.toBuilder()
                .setHoldIdentityBytes(ByteString.copyFrom(otherHold.canonicalBytes()))
                .build(),
            valid.toBuilder()
                .setHoldIdentityBytes(ByteString.copyFrom(otherFence.canonicalBytes()))
                .build(),
            valid.toBuilder().clearHoldIdentityBytes().build(),
            valid.toBuilder().setHoldStateBytes(ByteString.copyFromUtf8("{}")).build())) {
      assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.requireWorldIdentity(read, bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var state =
        new WorldFixtureInitialAdmissionHoldState(
            request, otherHold, HoldStatus.PENDING, "ACTIVE", 2, 1);
    var substituted =
        valid.toBuilder().setHoldStateBytes(ByteString.copyFrom(state.canonicalBytes())).build();
    assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.requireWorldState(read, substituted))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.requireWorldState(read, valid))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> FixtureInitialAdmissionGrpcCodec.toWorldResponse(read, otherFence, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void terminalRequestsAndResponsesRequireExactFullEchoAndProofIdentity() {
    var request = request(false);
    var identity = identity(request);
    var read = Lookup.fresh(request, identity);
    var wire = FixtureInitialAdmissionGrpcCodec.toTerminalRequest(read);
    for (var bad :
        List.of(
            wire.toBuilder().setUnknownFields(unknown()).build(),
            wire.toBuilder().clearHoldIdentityBytes().build(),
            wire.toBuilder().setReadRequestId(identity.holdFence().toString()).build())) {
      assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.fromTerminalRequest(bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    var valid =
        FixtureInitialAdmissionGrpcCodec.toTerminalResponse(
            read, proof(request, identity, Outcome.COMMITTED));
    var changed = new HoldIdentity(request.selector(), identity.holdId(), UUID.randomUUID());
    for (var bad :
        List.of(
            valid.toBuilder().setUnknownFields(unknown()).build(),
            valid.toBuilder().setReadRequestId(UUID.randomUUID().toString()).build(),
            valid.toBuilder()
                .setFixtureHoldRequestBytes(ByteString.copyFrom(request(true).canonicalBytes()))
                .build(),
            valid.toBuilder()
                .setHoldIdentityBytes(ByteString.copyFrom(changed.canonicalBytes()))
                .build(),
            valid.toBuilder()
                .setFixtureTerminalProofBytes(
                    ByteString.copyFrom(
                        proof(request, changed, Outcome.COMMITTED).canonicalBytes()))
                .build(),
            valid.toBuilder().clearFixtureTerminalProofBytes().build(),
            valid.toBuilder()
                .setFixtureTerminalProofBytes(ByteString.copyFrom(new byte[65537]))
                .build(),
            valid.toBuilder()
                .setFixtureTerminalProofBytes(
                    valid.getFixtureTerminalProofBytes().concat(ByteString.copyFromUtf8(" ")))
                .build())) {
      assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.requireTerminalResponse(read, bad))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                FixtureInitialAdmissionGrpcCodec.toTerminalResponse(
                    read, proof(request, changed, Outcome.COMMITTED)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> FixtureInitialAdmissionGrpcCodec.toTerminalRequest(Lookup.fresh(request, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void expectClosedRemainsAValueCodecWithoutGrantingRuntimeFirstOpenAuthority() {
    var request = request(true);
    var identity = identity(request);
    var read = Lookup.fresh(request, identity);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.fromTerminalRequest(
                FixtureInitialAdmissionGrpcCodec.toTerminalRequest(read)))
        .isEqualTo(read);
    var proof = proof(request, identity, Outcome.COMMITTED);
    assertThat(proof.pointerVersion()).isEqualTo(8L);
    assertThat(
            FixtureInitialAdmissionGrpcCodec.requireTerminalResponse(
                read, FixtureInitialAdmissionGrpcCodec.toTerminalResponse(read, proof)))
        .isEqualTo(proof);
  }

  @Test
  void sameSelectorCannotHideChangedActivationOrPolicySeal() {
    var request = request(false);
    var identity = identity(request);
    var read = Lookup.fresh(request, identity);
    var activation = request.activation();
    var changed =
        new WorldFixtureInitialAdmissionHoldRequest(
            request.selector(),
            new WorldCanonicalInstanceFixtureActivationRequest(
                UUID.randomUUID(),
                activation.canonicalTenantId(),
                activation.canonicalGameInstanceId(),
                "sha256:" + "c".repeat(64),
                1L,
                0L));
    var world =
        FixtureInitialAdmissionGrpcCodec.toWorldResponse(read, identity, null).toBuilder()
            .setFixtureHoldRequestBytes(ByteString.copyFrom(changed.canonicalBytes()))
            .build();
    assertThatThrownBy(() -> FixtureInitialAdmissionGrpcCodec.requireWorldIdentity(read, world))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                FixtureInitialAdmissionGrpcCodec.toTerminalResponse(
                    read, proof(changed, identity, Outcome.COMMITTED)))
        .isInstanceOf(IllegalArgumentException.class);
    var changedState =
        new WorldFixtureInitialAdmissionHoldState(
            changed, identity, HoldStatus.PENDING, "ACTIVE", 2, 1);
    assertThatThrownBy(
            () -> FixtureInitialAdmissionGrpcCodec.toWorldResponse(read, identity, changedState))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static WorldFixtureInitialAdmissionHoldRequest request(boolean closed) {
    UUID tenant = UUID.fromString("11111111-1111-4111-8111-111111111111");
    UUID instance = UUID.fromString("22222222-2222-4222-8222-222222222222");
    var selector =
        new WorldCanonicalInitialAdmissionHold.Request(
            "test",
            tenant,
            "green-hollow",
            UUID.fromString("66666666-6666-4666-8666-666666666666"),
            UUID.fromString("77777777-7777-4777-8777-777777777777"),
            "SHARED",
            instance,
            UUID.fromString("88888888-8888-4888-8888-888888888888"),
            2L,
            "99999999-9999-4999-8999-999999999999",
            "b".repeat(64),
            closed ? InitialAdmissionOrigin.EXPECT_CLOSED : InitialAdmissionOrigin.NO_PRIOR_POINTER,
            1L,
            closed ? 7L : null);
    return new WorldFixtureInitialAdmissionHoldRequest(
        selector,
        new WorldCanonicalInstanceFixtureActivationRequest(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            tenant,
            instance,
            "sha256:" + "a".repeat(64),
            1L,
            0L));
  }

  static HoldIdentity identity(WorldFixtureInitialAdmissionHoldRequest request) {
    return new HoldIdentity(
        request.selector(),
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        UUID.fromString("55555555-5555-4555-8555-555555555555"));
  }

  private static WorldFixtureInitialAdmissionTerminalProof proof(
      WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity, Outcome outcome) {
    return new WorldFixtureInitialAdmissionTerminalProof(
        request,
        identity.holdId(),
        identity.holdFence(),
        outcome,
        outcome == Outcome.COMMITTED
            ? (request.selector().expectedPriorPointerVersion() == null ? 1L : 8L)
            : null,
        outcome == Outcome.COMMITTED ? 9L : null,
        outcome == Outcome.ABORTED,
        outcome == Outcome.PENDING ? null : Instant.parse("2026-11-17T00:00:00Z"));
  }

  private static UnknownFieldSet unknown() {
    return UnknownFieldSet.newBuilder()
        .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(1L).build())
        .build();
  }
}
