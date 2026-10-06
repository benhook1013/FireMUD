package net.firedevops.firemud.common.world;

import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.gamedesign.GameDesignPublicationTerminalEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.GameDesignPublicationOperationBinding;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalRequest;
import net.firedevops.firemud.worldmanagement.v1.CompleteWorldPublicationTerminalResponse;

/** Closed canonical carrier codec for World publication terminal completion. */
public final class WorldPublicationTerminalCompletionGrpcCodec {
  private WorldPublicationTerminalCompletionGrpcCodec() {}

  /** Original publication operation and exact Game Design terminal selection. */
  public record Request(
      int schemaVersion,
      String targetNamespace,
      byte[] operationBytes,
      byte[] terminalEvidenceBytes) {
    public static final int SCHEMA_VERSION = 1;
    public static final String SCHEMA = "world-publication-terminal-completion-request/v1";

    public Request {
      if (schemaVersion != SCHEMA_VERSION || !GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException(
            "Closed schema-1 World terminal completion request required");
      }
      operationBytes =
          GameDesignPublicationOperationBinding.fromStored(operationBytes).canonicalBytes();
      var operation = GameDesignPublicationOperationBinding.fromStored(operationBytes);
      var terminal = GameDesignPublicationTerminalEvidence.fromStored(terminalEvidenceBytes);
      if (!targetNamespace.equals(operation.world().request().targetNamespace())
          || !Arrays.equals(operationBytes, terminal.operationBytes())) {
        throw new IllegalArgumentException(
            "World terminal completion differs from its exact original operation or namespace");
      }
      terminalEvidenceBytes = terminal.canonicalBytes();
    }

    @Override
    public byte[] operationBytes() {
      return operationBytes.clone();
    }

    @Override
    public byte[] terminalEvidenceBytes() {
      return terminalEvidenceBytes.clone();
    }

    public GameDesignPublicationTerminalEvidence terminalEvidence() {
      return GameDesignPublicationTerminalEvidence.fromStored(terminalEvidenceBytes);
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, SCHEMA);
      DraftAuthorizationFenceBinding.frame(out, Integer.toString(schemaVersion));
      DraftAuthorizationFenceBinding.frame(out, targetNamespace);
      DraftAuthorizationFenceBinding.frame(out, operationBytes);
      DraftAuthorizationFenceBinding.frame(out, terminalEvidenceBytes);
      return out.toByteArray();
    }

    public static Request fromStored(byte[] bytes) {
      var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
      reader.expect(SCHEMA);
      if (!"1".equals(reader.text())) {
        throw new IllegalArgumentException("Unsupported World terminal completion schema");
      }
      var request = new Request(1, reader.text(), reader.bytes(), reader.bytes());
      reader.requireEnd();
      if (!Arrays.equals(bytes, request.canonicalBytes())) {
        throw new IllegalArgumentException("Noncanonical World terminal completion request");
      }
      return request;
    }
  }

  /** Exact owner result echo; the returned terminal bytes are the submitted immutable bytes. */
  public record Response(Request request, GameDesignPublicationTerminalEvidence terminalEvidence) {
    public static final String SCHEMA = "world-publication-terminal-completion-response/v1";

    public Response {
      request = Request.fromStored(Objects.requireNonNull(request).canonicalBytes());
      terminalEvidence =
          GameDesignPublicationTerminalEvidence.fromStored(
              Objects.requireNonNull(terminalEvidence).canonicalBytes());
      if (!Arrays.equals(request.operationBytes(), terminalEvidence.operationBytes())
          || !Arrays.equals(request.terminalEvidenceBytes(), terminalEvidence.canonicalBytes())) {
        throw new IllegalArgumentException(
            "World terminal completion result differs from the complete original request");
      }
    }

    public byte[] canonicalBytes() {
      var out = new ByteArrayOutputStream();
      DraftAuthorizationFenceBinding.frame(out, SCHEMA);
      DraftAuthorizationFenceBinding.frame(out, request.canonicalBytes());
      DraftAuthorizationFenceBinding.frame(out, terminalEvidence.canonicalBytes());
      return out.toByteArray();
    }

    public static Response fromStored(byte[] bytes) {
      var reader = new DraftAuthorizationFenceBinding.FrameReader(bytes);
      reader.expect(SCHEMA);
      var response =
          new Response(
              Request.fromStored(reader.bytes()),
              GameDesignPublicationTerminalEvidence.fromStored(reader.bytes()));
      reader.requireEnd();
      if (!Arrays.equals(bytes, response.canonicalBytes())) {
        throw new IllegalArgumentException("Noncanonical World terminal completion response");
      }
      return response;
    }
  }

  public static CompleteWorldPublicationTerminalRequest toRequest(Request request) {
    return CompleteWorldPublicationTerminalRequest.newBuilder()
        .setCanonicalRequestBytes(
            ByteString.copyFrom(Objects.requireNonNull(request).canonicalBytes()))
        .build();
  }

  public static Request fromRequest(CompleteWorldPublicationTerminalRequest request) {
    if (!Objects.requireNonNull(request).getUnknownFields().asMap().isEmpty()
        || request.getCanonicalRequestBytes().isEmpty()) {
      throw new IllegalArgumentException("Closed complete World terminal request required");
    }
    return Request.fromStored(request.getCanonicalRequestBytes().toByteArray());
  }

  public static CompleteWorldPublicationTerminalResponse toResponse(
      Request request, GameDesignPublicationTerminalEvidence terminalEvidence) {
    var response = new Response(request, terminalEvidence);
    return CompleteWorldPublicationTerminalResponse.newBuilder()
        .setCanonicalResponseBytes(ByteString.copyFrom(response.canonicalBytes()))
        .build();
  }

  public static Response fromResponse(
      Request request, CompleteWorldPublicationTerminalResponse response) {
    if (!Objects.requireNonNull(response).getUnknownFields().asMap().isEmpty()
        || response.getCanonicalResponseBytes().isEmpty()) {
      throw new IllegalArgumentException("Closed complete World terminal response required");
    }
    Response decoded = Response.fromStored(response.getCanonicalResponseBytes().toByteArray());
    if (!Arrays.equals(
        Objects.requireNonNull(request).canonicalBytes(), decoded.request().canonicalBytes())) {
      throw new IllegalArgumentException(
          "World terminal completion changed the complete original request echo");
    }
    return decoded;
  }
}
