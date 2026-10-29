package net.firedevops.firemud.common.grpc;

import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import java.io.InputStream;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.firedevops.firemud.account.v1.AccountStartSessionAdmissionProtectionServiceGrpc;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterOwnerReadServiceGrpc;

/** Closed, server-owned generated-descriptor exceptions to ordinary raw request admission. */
public final class GrpcRequestBytePolicy {
  public static final int ORDINARY_MAXIMUM_BYTES = 4 * 1024 * 1024;
  private final Map<MethodDescriptor<?, ?>, Integer> exceptions;

  record MethodBound(MethodDescriptor<?, ?> method, int maximumBytes) {
    MethodBound {
      Objects.requireNonNull(method);
      if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
        throw new IllegalArgumentException("A finite positive method byte bound is required");
      }
    }
  }

  GrpcRequestBytePolicy(List<MethodBound> bounds) {
    exceptions = new IdentityHashMap<>();
    Set<String> names = new HashSet<>();
    for (MethodBound bound : bounds) {
      if (!names.add(bound.method().getFullMethodName())) {
        throw new IllegalArgumentException("Duplicate gRPC request bound");
      }
      exceptions.put(bound.method(), bound.maximumBytes());
    }
  }

  public static GrpcRequestBytePolicy ordinary() {
    return new GrpcRequestBytePolicy(List.of());
  }

  public static GrpcRequestBytePolicy createdRosterRetirement() {
    return new GrpcRequestBytePolicy(
        List.of(
            new MethodBound(
                CanonicalGameplayRosterOwnerReadServiceGrpc
                    .getAcknowledgeCanonicalGameplayRosterRetirementMethod(),
                CanonicalGameplayRosterRetirementEvidence.MAX_PLAYER_CREATED_REQUEST_BYTES),
            new MethodBound(
                CanonicalGameplayRosterOwnerReadServiceGrpc
                    .getConfirmCanonicalGameplayRosterRetirementAcknowledgementMethod(),
                CanonicalGameplayRosterRetirementEvidence.MAX_PLAYER_CREATED_CONFIRMATION_BYTES)));
  }

  public static GrpcRequestBytePolicy accountStartSessionAdmissionProtectionAcquisition() {
    return new GrpcRequestBytePolicy(
        List.of(
            new MethodBound(
                AccountStartSessionAdmissionProtectionServiceGrpc
                    .getAcquireStartSessionAdmissionProtectionMethod(),
                AccountStartSessionAdmissionProtectionAcquisitionGrpcCodec
                    .MAX_REQUEST_WIRE_BYTES)));
  }

  public int transportMaximumBytes() {
    return Math.max(
        ORDINARY_MAXIMUM_BYTES,
        exceptions.values().stream()
            .mapToInt(Integer::intValue)
            .max()
            .orElse(ORDINARY_MAXIMUM_BYTES));
  }

  void requireRegistered(List<ServerServiceDefinition> services) {
    Set<MethodDescriptor<?, ?>> found =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    for (ServerServiceDefinition service : services) {
      service.getMethods().forEach(method -> found.add(method.getMethodDescriptor()));
    }
    if (!found.containsAll(exceptions.keySet())) {
      throw new IllegalStateException(
          "Configured gRPC request bound has no exact registered descriptor");
    }
  }

  ServerServiceDefinition guard(ServerServiceDefinition service, int transportMaximum) {
    ServerServiceDefinition raw = BoundedGrpcRequestMarshaller.rawMessages(service);
    ServerServiceDefinition.Builder result =
        ServerServiceDefinition.builder(raw.getServiceDescriptor());
    for (ServerMethodDefinition<?, ?> method : service.getMethods()) {
      MethodDescriptor<?, ?> original = method.getMethodDescriptor();
      int maximum =
          Math.min(transportMaximum, exceptions.getOrDefault(original, ORDINARY_MAXIMUM_BYTES));
      ServerMethodDefinition<InputStream, InputStream> rawMethod =
          streamMethod(raw.getMethod(original.getFullMethodName()));
      result.addMethod(
          rawMethod.withServerCallHandler(
              BoundedGrpcRequestMarshaller.guard(rawMethod.getServerCallHandler(), maximum)));
    }
    return result.build();
  }

  @SuppressWarnings("unchecked")
  private static ServerMethodDefinition<InputStream, InputStream> streamMethod(
      ServerMethodDefinition<?, ?> method) {
    // rawMessages uses the public adapter with InputStream request and response marshallers.
    return (ServerMethodDefinition<InputStream, InputStream>) method;
  }
}
