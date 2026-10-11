package net.firedevops.firemud.common.gamedesign;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredInitialActorCoreReadEvidence.ReadResult;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence.Request;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreProjection;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreRequest;
import net.firedevops.firemud.gamedesign.v1.ReadAuthoredInitialActorCoreResponse;
import net.firedevops.firemud.gamedesign.v1.ResolveStartSessionLaunchDescriptorResponse;

/** Closed mapping for the authenticated, ordered authored initial actor-core projection. */
public final class AuthoredInitialActorCoreReadGrpcCodec {
  public static final int MAX_RESPONSE_WIRE_BYTES = 16 * 1024 * 1024;

  private AuthoredInitialActorCoreReadGrpcCodec() {}

  public static ReadAuthoredInitialActorCoreRequest toRequest(ReadRequest request) {
    Objects.requireNonNull(request, "request");
    return ReadAuthoredInitialActorCoreRequest.newBuilder()
        .setAssociationRequest(
            StartSessionTemplateAssociationReadGrpcCodec.toRequest(request.associationRequest()))
        .build();
  }

  public static ReadRequest fromRequest(ReadAuthoredInitialActorCoreRequest request) {
    Objects.requireNonNull(request, "request");
    requireNoUnknownFields(request, "ReadAuthoredInitialActorCoreRequest");
    if (!request.hasAssociationRequest()) {
      throw new IllegalArgumentException("Exact association replay request is required");
    }
    return new ReadRequest(
        StartSessionTemplateAssociationReadGrpcCodec.fromRequest(request.getAssociationRequest()));
  }

  public static ReadAuthoredInitialActorCoreResponse toResponse(ReadResult result) {
    Objects.requireNonNull(result, "result");
    ReadAuthoredInitialActorCoreRequest request = toRequest(new ReadRequest(result.request()));
    var associationRead =
        StartSessionTemplateAssociationReadGrpcCodec.toResponse(result.associationRead());
    var descriptor =
        AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(result.descriptor());
    var builder =
        ReadAuthoredInitialActorCoreResponse.newBuilder()
            .setSchemaVersion(AuthoredInitialActorCoreReadEvidence.SCHEMA_VERSION)
            .setRequest(request.getAssociationRequest())
            .setAssociationRead(associationRead)
            .setLaunchDescriptor(descriptor)
            .setCanonicalResultBytes(
                com.google.protobuf.ByteString.copyFrom(result.canonicalBytes()))
            .setResultDigest(result.digest());
    for (AuthoredInitialActorCoreProjection projection : result.projections()) {
      builder.addProjections(
          ReadAuthoredInitialActorCoreProjection.newBuilder()
              .setCanonicalProjectionBytes(
                  com.google.protobuf.ByteString.copyFrom(projection.canonicalBytes()))
              .setProjectionDigest(projection.digest()));
    }
    ReadAuthoredInitialActorCoreResponse response = builder.build();
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) {
      throw new IllegalArgumentException("Authored-core response exceeds its bounded wire size");
    }
    fromResponse(result.request(), response);
    return response;
  }

  public static ReadResult fromResponse(
      Request expectedRequest, ReadAuthoredInitialActorCoreResponse response) {
    Objects.requireNonNull(expectedRequest, "expectedRequest");
    Objects.requireNonNull(response, "response");
    requireNoUnknownFields(response, "ReadAuthoredInitialActorCoreResponse");
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || response.getSchemaVersion() != AuthoredInitialActorCoreReadEvidence.SCHEMA_VERSION
        || !response.hasRequest()
        || !response.hasAssociationRead()
        || !response.hasLaunchDescriptor()
        || response.getCanonicalResultBytes().isEmpty()) {
      throw new IllegalArgumentException("Complete bounded authored-core read response required");
    }
    Request echoed =
        StartSessionTemplateAssociationReadGrpcCodec.fromRequest(response.getRequest());
    if (!expectedRequest.equals(echoed)) {
      throw new IllegalArgumentException("Authored-core read changed the exact request echo");
    }
    var associationRead =
        StartSessionTemplateAssociationReadGrpcCodec.fromResponse(
            expectedRequest, response.getAssociationRead());
    var descriptorEnvelope =
        ResolveStartSessionLaunchDescriptorResponse.newBuilder()
            .setSchemaVersion(StartSessionLaunchDescriptorGrpcCodec.RESPONSE_SCHEMA_VERSION)
            .setAssociationRead(response.getAssociationRead())
            .setLaunchDescriptor(response.getLaunchDescriptor())
            .build();
    var resolved =
        StartSessionLaunchDescriptorGrpcCodec.fromResponse(expectedRequest, descriptorEnvelope);
    var descriptor =
        ((StartSessionLaunchDescriptorGrpcCodec.DescriptorOutcome) resolved.outcome()).descriptor();
    List<AuthoredInitialActorCoreProjection> projections =
        response.getProjectionsList().stream()
            .map(
                projection ->
                    AuthoredInitialActorCoreProjection.fromStored(
                        projection.getCanonicalProjectionBytes().toByteArray(),
                        projection.getProjectionDigest()))
            .toList();
    ReadResult result = new ReadResult(expectedRequest, associationRead, descriptor, projections);
    byte[] canonical = response.getCanonicalResultBytes().toByteArray();
    if (canonical.length > AuthoredInitialActorCoreReadEvidence.MAX_RESULT_BYTES
        || !MessageDigest.isEqual(canonical, result.canonicalBytes())
        || !result.digest().equals(response.getResultDigest())) {
      throw new IllegalArgumentException(
          "Authored-core result digest or canonical complete content differs");
    }
    return result;
  }

  private static void requireNoUnknownFields(Message message, String label) {
    if (!message.getUnknownFields().asMap().isEmpty()) {
      throw new IllegalArgumentException(label + " contains unsupported fields");
    }
    for (Map.Entry<Descriptors.FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
      if (field.getKey().getJavaType() != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue;
      if (field.getKey().isRepeated()) {
        for (Object nested : (List<?>) field.getValue()) {
          requireNoUnknownFields((Message) nested, label + "." + field.getKey().getName());
        }
      } else {
        requireNoUnknownFields((Message) field.getValue(), label + "." + field.getKey().getName());
      }
    }
  }
}
