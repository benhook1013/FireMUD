package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Message;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.ExactReplay;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Result;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Closed public integrity value for an authenticated authored-core source read. */
public final class AuthoredInitialActorCoreReadEvidence {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_RESULT_BYTES = 8 * 1024 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuthoredInitialActorCoreReadEvidence() {}

  /** The input is the exact already-pinned original request, never an initial selector. */
  public record ReadRequest(Request associationRequest) {
    public ReadRequest {
      Objects.requireNonNull(associationRequest, "associationRequest");
      if (!(associationRequest.selection() instanceof ExactReplay)) {
        throw new IllegalArgumentException("Exact pinned association replay is required");
      }
    }
  }

  /**
   * Complete public projection with the exact immutable request and owner evidence. Construction
   * validates canonical integrity and cross-correlation only; this value and its digest do not
   * authenticate the producer.
   */
  public static final class ReadResult {
    private final Request request;
    private final Result associationRead;
    private final AuthoredWorldLaunchDescriptorEvidence descriptor;
    private final List<AuthoredInitialActorCoreProjection> projections;
    private final byte[] canonicalBytes;
    private final String digest;

    public ReadResult(
        Request request,
        Result associationRead,
        AuthoredWorldLaunchDescriptorEvidence descriptor,
        List<AuthoredInitialActorCoreProjection> projections) {
      this.request = Objects.requireNonNull(request, "request");
      this.associationRead = Objects.requireNonNull(associationRead, "associationRead");
      this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
      this.projections = List.copyOf(Objects.requireNonNull(projections, "projections"));
      requirePublicCorrelation();
      canonicalBytes = encodeCanonical();
      if (canonicalBytes.length == 0 || canonicalBytes.length > MAX_RESULT_BYTES) {
        throw new IllegalArgumentException("Authored-core read result exceeds its closed bound");
      }
      digest = sha256(canonicalBytes);
    }

    public Request request() {
      return request;
    }

    public Result associationRead() {
      return associationRead;
    }

    public AuthoredWorldLaunchDescriptorEvidence descriptor() {
      return descriptor;
    }

    public List<AuthoredInitialActorCoreProjection> projections() {
      return projections;
    }

    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    public String digest() {
      return digest;
    }

    private void requirePublicCorrelation() {
      if (!request.equals(associationRead.request())
          || !(request.selection() instanceof ExactReplay replay)
          || !replay.canonicalVersionId().equals(associationRead.association().canonicalVersionId())
          || !replay.selectedCommitId().equals(associationRead.association().selectedCommitId())
          || !replay.publishWorkflowId().equals(associationRead.association().publishWorkflowId())
          || !replay
              .associationDigest()
              .equals(associationRead.association().associationDigest())) {
        throw new IllegalArgumentException(
            "Association result differs from exact core-read request");
      }
      StartSessionLaunchDescriptorGrpcCodec.toDescriptorResponse(
          request, associationRead, descriptor);
      Set<UUID> bindingIds = new java.util.HashSet<>();
      var association = associationRead.association();
      for (AuthoredInitialActorCoreProjection projection : projections) {
        Objects.requireNonNull(projection, "projection");
        if (!bindingIds.add(projection.bindingId())
            || !projection.worldSourceOperationId().equals(association.sourceOperationId())
            || !projection.worldSourceEvidenceDigest().equals(association.sourceEvidenceDigest())
            || !projection.worldSlug().equals(association.worldSlug())
            || !projection.publicationWorkflowId().equals(association.publishWorkflowId())
            || !projection.canonicalTenantId().equals(association.canonicalTenantId())
            || !projection.canonicalVersionId().equals(association.canonicalVersionId())
            || !projection.selectedCommitId().equals(association.selectedCommitId())) {
          throw new IllegalArgumentException(
              "Projection differs from exact association or descriptor");
        }
      }
    }

    private byte[] encodeCanonical() {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("schema", "game-design-authored-initial-actor-core-read/v1");
      value.put("request", base64(StartSessionTemplateAssociationReadGrpcCodec.toRequest(request)));
      value.put(
          "associationRead",
          base64(StartSessionTemplateAssociationReadGrpcCodec.toResponse(associationRead)));
      value.put(
          "launchDescriptor",
          base64(AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(descriptor)));
      List<Map<String, Object>> ordered = new ArrayList<>(projections.size());
      for (AuthoredInitialActorCoreProjection projection : projections) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(
            "canonicalProjection", Base64.getEncoder().encodeToString(projection.canonicalBytes()));
        item.put("digest", projection.digest());
        ordered.add(item);
      }
      value.put("projections", ordered);
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      } catch (Exception invalid) {
        throw new IllegalArgumentException("Cannot encode canonical authored-core result", invalid);
      }
    }

    private static String base64(Message message) {
      return Base64.getEncoder().encodeToString(message.toByteArray());
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
