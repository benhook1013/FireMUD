package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInitialAdmissionHold.HoldIdentity;
import net.firedevops.firemud.gamesession.v1.GetFixtureInitialAdmissionOwnerRequest;
import net.firedevops.firemud.gamesession.v1.GetFixtureInitialAdmissionOwnerResponse;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationRequest;
import net.firedevops.firemud.worldmanagement.v1.FixtureInitialAdmissionHoldOperationResponse;

/**
 * Closed fixture-purpose framing and exact echo validation only. Never authenticates either owner.
 */
public final class FixtureInitialAdmissionGrpcCodec {
  public static final int MAX_FRAME_BYTES = 65536;
  public static final int MAX_WIRE_BYTES = 4 * MAX_FRAME_BYTES + 1024;

  private FixtureInitialAdmissionGrpcCodec() {}

  public record Lookup(
      UUID readRequestId, WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity) {
    public Lookup {
      Objects.requireNonNull(readRequestId, "readRequestId");
      Objects.requireNonNull(request, "request");
      if (readRequestId.equals(new UUID(0L, 0L))
          || readRequestId.equals(request.activation().activationRequestId())
          || readRequestId.toString().equals(request.selector().initialAdmissionRequestId())
          || (identity != null
              && (!identity.request().equals(request.selector())
                  || readRequestId.equals(identity.holdId())
                  || readRequestId.equals(identity.holdFence())))) {
        throw invalid();
      }
    }

    public static Lookup fresh(
        WorldFixtureInitialAdmissionHoldRequest request, HoldIdentity identity) {
      while (true) {
        UUID id = UUID.randomUUID();
        if (!id.equals(request.activation().activationRequestId())
            && !id.toString().equals(request.selector().initialAdmissionRequestId())
            && (identity == null
                || (!id.equals(identity.holdId()) && !id.equals(identity.holdFence())))) {
          return new Lookup(id, request, identity);
        }
      }
    }
  }

  public static FixtureInitialAdmissionHoldOperationRequest toWorldRequest(Lookup lookup) {
    var result =
        FixtureInitialAdmissionHoldOperationRequest.newBuilder()
            .setReadRequestId(lookup.readRequestId().toString())
            .setFixtureHoldRequestBytes(frame(lookup.request().canonicalBytes()));
    if (lookup.identity() != null) {
      result.setHoldIdentityBytes(frame(lookup.identity().canonicalBytes()));
    }
    return result.build();
  }

  public static Lookup fromWorldRequest(
      FixtureInitialAdmissionHoldOperationRequest request, boolean requireIdentity) {
    closed(request);
    WorldFixtureInitialAdmissionHoldRequest fixture =
        WorldFixtureInitialAdmissionHoldRequest.fromStored(
            bytes(request.getFixtureHoldRequestBytes()));
    HoldIdentity identity =
        request.getHoldIdentityBytes().isEmpty()
            ? null
            : HoldIdentity.fromStored(bytes(request.getHoldIdentityBytes()));
    if (requireIdentity != (identity != null)) throw invalid();
    return new Lookup(uuid(request.getReadRequestId()), fixture, identity);
  }

  public static FixtureInitialAdmissionHoldOperationResponse toWorldResponse(
      Lookup lookup, HoldIdentity identity, WorldFixtureInitialAdmissionHoldState state) {
    requireMatches(lookup.request(), identity);
    if (lookup.identity() != null && !lookup.identity().equals(identity)) throw invalid();
    var result =
        FixtureInitialAdmissionHoldOperationResponse.newBuilder()
            .setReadRequestId(lookup.readRequestId().toString())
            .setFixtureHoldRequestBytes(frame(lookup.request().canonicalBytes()))
            .setHoldIdentityBytes(frame(identity.canonicalBytes()));
    if (state != null) {
      if (!state.request().equals(lookup.request()) || !state.holdIdentity().equals(identity)) {
        throw invalid();
      }
      result.setHoldStateBytes(frame(state.canonicalBytes()));
    }
    return result.build();
  }

  public static HoldIdentity requireWorldIdentity(
      Lookup expected, FixtureInitialAdmissionHoldOperationResponse response) {
    closed(response);
    echo(expected, response.getReadRequestId(), response.getFixtureHoldRequestBytes());
    if (!response.getHoldStateBytes().isEmpty()) throw invalid();
    HoldIdentity identity = HoldIdentity.fromStored(bytes(response.getHoldIdentityBytes()));
    requireMatches(expected.request(), identity);
    if (expected.identity() != null && !expected.identity().equals(identity)) throw invalid();
    return identity;
  }

  public static WorldFixtureInitialAdmissionHoldState requireWorldState(
      Lookup expected, FixtureInitialAdmissionHoldOperationResponse response) {
    closed(response);
    echo(expected, response.getReadRequestId(), response.getFixtureHoldRequestBytes());
    if (expected.identity() == null) throw invalid();
    HoldIdentity identity = HoldIdentity.fromStored(bytes(response.getHoldIdentityBytes()));
    WorldFixtureInitialAdmissionHoldState state =
        WorldFixtureInitialAdmissionHoldState.fromStored(bytes(response.getHoldStateBytes()));
    if (!identity.equals(expected.identity())
        || !state.holdIdentity().equals(identity)
        || !state.request().equals(expected.request())) throw invalid();
    return state;
  }

  public static GetFixtureInitialAdmissionOwnerRequest toTerminalRequest(Lookup lookup) {
    if (lookup.identity() == null) throw invalid();
    return GetFixtureInitialAdmissionOwnerRequest.newBuilder()
        .setReadRequestId(lookup.readRequestId().toString())
        .setFixtureHoldRequestBytes(frame(lookup.request().canonicalBytes()))
        .setHoldIdentityBytes(frame(lookup.identity().canonicalBytes()))
        .build();
  }

  public static Lookup fromTerminalRequest(GetFixtureInitialAdmissionOwnerRequest request) {
    closed(request);
    return new Lookup(
        uuid(request.getReadRequestId()),
        WorldFixtureInitialAdmissionHoldRequest.fromStored(
            bytes(request.getFixtureHoldRequestBytes())),
        HoldIdentity.fromStored(bytes(request.getHoldIdentityBytes())));
  }

  public static GetFixtureInitialAdmissionOwnerResponse toTerminalResponse(
      Lookup lookup, WorldFixtureInitialAdmissionTerminalProof proof) {
    requireTerminalMatches(lookup, proof);
    return GetFixtureInitialAdmissionOwnerResponse.newBuilder()
        .setReadRequestId(lookup.readRequestId().toString())
        .setFixtureHoldRequestBytes(frame(lookup.request().canonicalBytes()))
        .setHoldIdentityBytes(frame(lookup.identity().canonicalBytes()))
        .setFixtureTerminalProofBytes(frame(proof.canonicalBytes()))
        .build();
  }

  public static WorldFixtureInitialAdmissionTerminalProof requireTerminalResponse(
      Lookup expected, GetFixtureInitialAdmissionOwnerResponse response) {
    closed(response);
    echo(expected, response.getReadRequestId(), response.getFixtureHoldRequestBytes());
    if (expected.identity() == null
        || !Arrays.equals(
            expected.identity().canonicalBytes(), bytes(response.getHoldIdentityBytes()))) {
      throw invalid();
    }
    var proof =
        WorldFixtureInitialAdmissionTerminalProof.fromStored(
            bytes(response.getFixtureTerminalProofBytes()));
    requireTerminalMatches(expected, proof);
    return proof;
  }

  private static void requireTerminalMatches(
      Lookup lookup, WorldFixtureInitialAdmissionTerminalProof proof) {
    if (lookup.identity() == null
        || !lookup.request().equals(proof.request())
        || !lookup.identity().holdId().equals(proof.holdId())
        || !lookup.identity().holdFence().equals(proof.holdFence())) throw invalid();
  }

  private static void requireMatches(
      WorldFixtureInitialAdmissionHoldRequest fixture, HoldIdentity identity) {
    if (!fixture.selector().equals(identity.request())) throw invalid();
  }

  private static void echo(Lookup expected, String id, ByteString fixture) {
    if (!expected.readRequestId().equals(uuid(id))
        || !Arrays.equals(expected.request().canonicalBytes(), bytes(fixture))) throw invalid();
  }

  private static UUID uuid(String value) {
    UUID id = UUID.fromString(value);
    if (!id.toString().equals(value) || id.equals(new UUID(0L, 0L))) throw invalid();
    return id;
  }

  private static ByteString frame(byte[] value) {
    if (value.length == 0 || value.length > MAX_FRAME_BYTES) throw invalid();
    return ByteString.copyFrom(value);
  }

  private static byte[] bytes(ByteString value) {
    if (value.isEmpty() || value.size() > MAX_FRAME_BYTES) throw invalid();
    return value.toByteArray();
  }

  private static void closed(Message message) {
    if (message == null
        || message.getSerializedSize() > MAX_WIRE_BYTES
        || !message.getUnknownFields().asMap().isEmpty()) throw invalid();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException(
        "Invalid or substituted fixture initial-admission owner frame");
  }
}
