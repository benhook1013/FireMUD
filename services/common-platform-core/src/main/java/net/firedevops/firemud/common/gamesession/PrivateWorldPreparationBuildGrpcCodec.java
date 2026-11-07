package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadEvidence.Evidence;
import net.firedevops.firemud.common.gamesession.PrivateWorldPreparationReadGrpcCodec.Request;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationBuildResponse;
import net.firedevops.firemud.gamesession.v1.ReadPrivateWorldPreparationResponse;

/** Authenticated-producer projection of the retained selected build, never execution permission. */
public final class PrivateWorldPreparationBuildGrpcCodec {
  public static final int MAX_RESPONSE_WIRE_BYTES = 5 * 1024 * 1024;
  private static final String DOMAIN = "game-session-private-world-selected-build/v1";

  private PrivateWorldPreparationBuildGrpcCodec() {}

  /** Correlation checks do not authenticate the producer or reauthorize the original publisher. */
  public record Build(Evidence source, WorldPublishedStartLocationEvidence selectedWorld) {
    public Build {
      Objects.requireNonNull(source);
      Objects.requireNonNull(selectedWorld);
      var snapshot = source.snapshot();
      var selector = selectedWorld.request();
      if (!snapshot.scope().targetNamespace().equals(selector.targetNamespace())
          || !snapshot.scope().tenantId().equals(selector.canonicalTenantId())
          || !snapshot.source().canonicalVersionId().equals(selector.canonicalVersionId()))
        throw new IllegalArgumentException("Private selected build differs from its source");
      // Ensure the complete World value satisfies its own closed relation checks and byte bounds.
      selectedWorld =
          WorldPublishedStartLocationEvidence.fromStored(selectedWorld.canonicalBytes());
    }

    public boolean admissionAllowed() {
      return false;
    }
  }

  public static ReadPrivateWorldPreparationBuildResponse toResponse(Request request, Build build) {
    if (!request.equals(build.source().request()))
      throw new IllegalArgumentException("Wrong build read");
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, DOMAIN);
    DraftAuthorizationFenceBinding.frame(
        out, PrivateWorldPreparationReadEvidence.sha256(request.canonicalBytes()));
    DraftAuthorizationFenceBinding.frame(
        out,
        PrivateWorldPreparationReadGrpcCodec.toResponse(request, build.source().snapshot())
            .getCanonicalEvidence()
            .toByteArray());
    DraftAuthorizationFenceBinding.frame(out, build.selectedWorld().canonicalBytes());
    var wire =
        ReadPrivateWorldPreparationBuildResponse.newBuilder()
            .setCanonicalEvidence(ByteString.copyFrom(out.toByteArray()))
            .build();
    bound(wire.getSerializedSize());
    return wire;
  }

  public static Build fromResponse(Request request, ReadPrivateWorldPreparationBuildResponse wire) {
    if (wire == null || !wire.getUnknownFields().asMap().isEmpty())
      throw new IllegalArgumentException("Closed build response required");
    bound(wire.getSerializedSize());
    var reader =
        new DraftAuthorizationFenceBinding.FrameReader(wire.getCanonicalEvidence().toByteArray());
    reader.expect(DOMAIN);
    reader.expect(PrivateWorldPreparationReadEvidence.sha256(request.canonicalBytes()));
    var source =
        PrivateWorldPreparationReadGrpcCodec.fromResponse(
            request,
            ReadPrivateWorldPreparationResponse.newBuilder()
                .setCanonicalEvidence(ByteString.copyFrom(reader.bytes()))
                .build());
    var world = WorldPublishedStartLocationEvidence.fromStored(reader.bytes());
    reader.requireEnd();
    var result = new Build(source, world);
    if (!wire.equals(toResponse(request, result)))
      throw new IllegalArgumentException("Noncanonical build response");
    return result;
  }

  private static void bound(int size) {
    if (size <= 0 || size > MAX_RESPONSE_WIRE_BYTES)
      throw new IllegalArgumentException("Private selected build exceeds response bound");
  }
}
