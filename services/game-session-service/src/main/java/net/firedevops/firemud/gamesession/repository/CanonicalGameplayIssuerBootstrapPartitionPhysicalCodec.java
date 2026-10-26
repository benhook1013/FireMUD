package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;

/**
 * Narrow canonical metadata and exact field-set codec for one initial-bootstrap issuer partition.
 */
final class CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec {
  static final String METADATA_FIELD = "!issuer-partition-bootstrap/v1";
  static final String RESERVATION_FIELD_PREFIX = "!issuer-reservation-capacity/v1:";
  static final String MUTATION_REVISION_FIELD =
      CanonicalGameplayIssuerPartitionMutationRepository.REVISION_FIELD;
  static final int MAX_MEMBER_OPERATIONS = 256;
  static final int MAX_HASH_FIELDS = 2 + 2 * MAX_MEMBER_OPERATIONS;
  static final int MAX_LOCAL_HASH_FIELDS = MAX_HASH_FIELDS + 2 * MAX_MEMBER_OPERATIONS;
  static final int MAX_BINDING_REF_BYTES = 6_144;
  static final int MAX_VALUE_BYTES = CanonicalGameplayIssuerMemberV2Codec.MAX_VALUE_BYTES;
  static final int MAX_METADATA_BYTES = 64 * 1024;
  static final int MAX_TOTAL_HASH_BYTES = 16 * 1024 * 1024;

  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(8)
                  .maxStringLength(MAX_VALUE_BYTES)
                  .maxNumberLength(8)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final ObjectMapper JSON =
      new ObjectMapper(JSON_FACTORY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec() {}

  static PhysicalFieldSet expectedFields(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    Objects.requireNonNull(work, "work");
    validatePreparedWork(work);
    CanonicalGameplayIssuerPartitionBuildPlan plan = work.buildPlan();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    BigInteger plannedAggregateCapacityCount =
        BigInteger.valueOf(
            plan.memberWorks().stream()
                .filter(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::countsTowardCapacity)
                .count());
    long partitionCount = work.memberOperations().size();
    long partitionCapacityCount =
        work.memberOperations().stream()
            .filter(CanonicalGameplayIssuerBootstrapMemberOperation::countsTowardCapacity)
            .count();

    byte[] metadata = encodeMetadata(work, plannedAggregateCapacityCount, partitionCapacityCount);
    if (metadata.length == 0 || metadata.length > MAX_METADATA_BYTES) {
      throw invalid("Issuer bootstrap partition metadata exceeds its resource limit");
    }

    Map<String, byte[]> fields = new LinkedHashMap<>();
    putUnique(fields, METADATA_FIELD, metadata);
    byte[] initialRevision =
        work.initialPhysicalRevision().toString().getBytes(StandardCharsets.US_ASCII);
    putUnique(fields, MUTATION_REVISION_FIELD, initialRevision);
    Set<UUID> reservations = new HashSet<>();
    Set<String> bindings = new HashSet<>();
    int bindingCount = 0;
    long totalBytes =
        METADATA_FIELD.getBytes(StandardCharsets.US_ASCII).length
            + metadata.length
            + MUTATION_REVISION_FIELD.getBytes(StandardCharsets.US_ASCII).length
            + initialRevision.length;
    for (CanonicalGameplayIssuerBootstrapMemberOperation operation : work.memberOperations()) {
      CanonicalGameplayIssuerMemberV2Value value = operation.expectedMemberValue();
      byte[] valueBytes = operation.exactMemberValueBytes();
      if (valueBytes.length == 0 || valueBytes.length > MAX_VALUE_BYTES) {
        throw invalid("Exact SQL-issued v2 member exceeds its resource limit");
      }
      CanonicalGameplayIssuerMemberV2Value decoded =
          CanonicalGameplayIssuerMemberV2Codec.decode(valueBytes);
      if (!decoded.equals(value)
          || !Arrays.equals(valueBytes, CanonicalGameplayIssuerMemberV2Codec.encode(decoded))) {
        throw invalid("SQL-issued v2 member bytes do not round-trip exactly");
      }
      UUID reservationId = value.reservation().reservationId();
      if (!reservations.add(reservationId))
        throw invalid("Duplicate partition reservation identity");

      byte[] bindingRefBytes = value.owner().bindingRef().bytes();
      if (bindingRefBytes.length == 0 || bindingRefBytes.length > MAX_BINDING_REF_BYTES) {
        throw invalid("Canonical bindingRef exceeds its resource limit");
      }
      CanonicalGameplayBindingRef exactBindingRef =
          CanonicalGameplayBindingRef.fromExactBytes(bindingRefBytes);
      String bindingField = strictUtf8(bindingRefBytes);
      if (!Arrays.equals(bindingRefBytes, bindingField.getBytes(StandardCharsets.UTF_8))
          || !exactBindingRef.equals(value.owner().bindingRef())
          || !bindings.add(bindingField)) {
        throw invalid("Binding field identity is malformed or duplicated");
      }
      String capacityField = reservationField(reservationId);
      if (operation.expectedBindingPresent()) {
        putUnique(fields, bindingField, valueBytes);
        bindingCount++;
      }
      putUnique(fields, capacityField, valueBytes);
      totalBytes +=
          bindingField.getBytes(StandardCharsets.UTF_8).length
              + capacityField.getBytes(StandardCharsets.US_ASCII).length
              + (operation.expectedBindingPresent() ? valueBytes.length : 0L)
              + valueBytes.length;
    }
    if (fields.size() > MAX_HASH_FIELDS || totalBytes > MAX_TOTAL_HASH_BYTES) {
      throw invalid("Issuer bootstrap partition field set exceeds its bounded resource limits");
    }
    if (BigInteger.valueOf(partitionCapacityCount)
            .compareTo(intent.layoutDescriptor().partitionCapacity())
        > 0) {
      throw invalid("Prepared issuer partition exceeds its exact finite capacity");
    }
    long aggregateCapacity =
        plan.memberWorks().stream()
            .filter(CanonicalGameplayIssuerPartitionBuildPlan.MemberWork::countsTowardCapacity)
            .count();
    BigInteger aggregateBound =
        intent
            .layoutDescriptor()
            .partitionCount()
            .multiply(intent.layoutDescriptor().partitionCapacity());
    if (BigInteger.valueOf(aggregateCapacity).compareTo(aggregateBound) > 0) {
      throw invalid("Prepared issuer aggregate capacity exceeds its exact finite layout bound");
    }
    return new PhysicalFieldSet(
        fields,
        metadata,
        reservations.size(),
        bindingCount,
        Math.toIntExact(partitionCapacityCount),
        BigInteger.valueOf(aggregateCapacity),
        partitionCount);
  }

  static byte[] encodeMetadata(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    PhysicalFieldSet fields = expectedFields(work);
    return fields.metadataBytes();
  }

  static void requireExactMetadata(byte[] encoded, byte[] expected) {
    Objects.requireNonNull(encoded, "encoded");
    Objects.requireNonNull(expected, "expected");
    if (encoded.length == 0 || encoded.length > MAX_METADATA_BYTES) {
      throw invalid("Stored bootstrap metadata is outside the bounded format");
    }
    try {
      JsonNode decoded = JSON.readTree(strictUtf8(encoded));
      if (!(decoded instanceof ObjectNode root))
        throw invalid("Bootstrap metadata must be an object");
      requireExactFields(
          root,
          Set.of(
              "schemaVersion",
              "issuerId",
              "partitionId",
              "layoutTag",
              "bootstrap",
              "build",
              "layout",
              "anchor",
              "issuerControlEpoch",
              "incarnation",
              "plannedAggregateCapacityCount",
              "plannedPartitionReservationCount",
              "plannedPartitionCapacityCount"),
          "bootstrap metadata");
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (!Arrays.equals(canonical, encoded) || !Arrays.equals(encoded, expected)) {
        throw invalid("Stored bootstrap metadata is noncanonical or belongs to different work");
      }
    } catch (IOException failure) {
      throw new IllegalArgumentException("Stored bootstrap metadata is invalid", failure);
    }
  }

  static String reservationField(UUID reservationId) {
    Objects.requireNonNull(reservationId, "reservationId");
    String canonical = reservationId.toString();
    if (!UUID.fromString(canonical).toString().equals(canonical)
        || reservationId.equals(new UUID(0L, 0L))) {
      throw invalid("Reservation identity must be canonical and nonnil");
    }
    return RESERVATION_FIELD_PREFIX + canonical;
  }

  private static void validatePreparedWork(CanonicalGameplayIssuerBootstrapPartitionWork work) {
    CanonicalGameplayIssuerPartitionBuildPlan plan = work.buildPlan();
    CanonicalGameplayIssuerLayoutBootstrapIntent intent = plan.bootstrapIntent();
    if (plan.state() != CanonicalGameplayIssuerPartitionBuildPlan.State.PREPARED
        || !plan.blockers().isEmpty()
        || work.memberOperations().size() > MAX_MEMBER_OPERATIONS
        || work.memberOperations().size()
            != plan.memberWorks().stream()
                .filter(member -> member.plannedPartitionId().equals(work.partitionId()))
                .count()) {
      throw invalid("Physical partition requires the exact complete positive SQL preparation");
    }
    Set<UUID> reservationIds = new HashSet<>();
    Set<UUID> memberOperationIds = new HashSet<>();
    Set<UUID> issuedOperationIds = new HashSet<>();
    Set<BigInteger> issuedOperationFences = new HashSet<>();
    Set<BigInteger> issuedGenerations = new HashSet<>();
    for (int index = 0; index < plan.memberWorks().size(); index++) {
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork member = plan.memberWorks().get(index);
      CanonicalGameplayBindingRef reference =
          CanonicalGameplayBindingRef.fromExactBytes(member.bindingRef());
      if (member.workOrdinal() != index
          || !member
              .plannedPartitionId()
              .equals(reference.partitionId(intent.layoutDescriptor().partitionCount()))
          || !reservationIds.add(member.reservationId())
          || !memberOperationIds.add(member.memberOperationId())) {
        throw invalid("Prepared issuer plan has a duplicate or mis-mapped cohort member");
      }
    }
    for (CanonicalGameplayIssuerBootstrapMemberOperation operation : work.memberOperations()) {
      CanonicalGameplayIssuerMemberV2Value value = operation.expectedMemberValue();
      if (operation.workOrdinal() >= plan.memberWorks().size()) {
        throw invalid("SQL-issued member ordinal is outside the exact persisted plan");
      }
      CanonicalGameplayIssuerPartitionBuildPlan.MemberWork planned =
          plan.memberWorks().get(Math.toIntExact(operation.workOrdinal()));
      var reservation = value.reservation();
      var memberRef = value.owner().bindingRef();
      if (operation.status()
              != CanonicalGameplayIssuerBootstrapMemberOperation.Status.PENDING_PHYSICAL_PROOF
          || !operation.bootstrapOperationId().equals(intent.operationId())
          || !operation.bootstrapRequestId().equals(intent.requestId())
          || !operation.buildOperationId().equals(plan.buildOperationId())
          || !operation.buildPlanDigest().equals(plan.planDigest())
          || operation.reservationSourceRowOrdinal() != planned.reservationSourceRowOrdinal()
          || !operation.reservationSourceRowDigest().equals(planned.reservationSourceRowDigest())
          || !operation.preparationOperationId().equals(planned.memberOperationId())
          || !operation.preparationFenceId().equals(planned.memberPreparationFenceId())
          || !reservation.reservationId().equals(planned.reservationId())
          || !reservation.transitionId().equals(planned.transitionId())
          || !reservation.reservationFence().equals(planned.reservationFence())
          || !reservation.lifecycle().name().equals(planned.reservationLifecycle())
          || !reservation.partitionId().equals(planned.plannedPartitionId())
          || !reservation.partitionId().equals(planned.originalPartitionId())
          || !reservation
              .issuerCoverageOperationId()
              .equals(planned.originalIssuerCoverageOperationId())
          || !reservation
              .issuerCoverageOperationFence()
              .equals(planned.originalIssuerCoverageOperationFence())
          || !reservation.coverageFence().equals(planned.originalCoverageFence())
          || !reservation
              .inventorySnapshotRevision()
              .equals(planned.originalInventorySnapshotRevision())
          || !value.owner().issuerIndexLayoutVersion().equals(planned.originalLayoutVersion())
          || !value.owner().issuerIndexPartitionCount().equals(planned.originalPartitionCount())
          || !value
              .owner()
              .issuerIndexPartitionCapacity()
              .equals(planned.originalPartitionCapacity())
          || !Arrays.equals(memberRef.bytes(), planned.bindingRef())
          || !value.incarnation().equals(work.incarnation())
          || !value.anchor().issuerCoverageOperationId().equals(intent.operationId())
          || !value.anchor().operationFence().equals(intent.operationFence())
          || !value.anchor().coverageFence().equals(intent.coverageFence())
          || !value
              .anchor()
              .issuerInventorySnapshotRevision()
              .equals(intent.inventorySnapshotRevision())
          || !value
              .layoutDescriptor()
              .descriptorDigest()
              .equals(intent.layoutDescriptor().descriptorDigest())
          || !sameLayout(value.layoutDescriptor(), intent.layoutDescriptor())
          || !sameCheckpoint(value.sourceCheckpoint(), intent.sourceCheckpoint())
          || !value.issuerControlEpoch().equals(intent.controlEpoch())
          || !issuedOperationIds.add(operation.operationId())
          || !issuedOperationFences.add(operation.operationFence())
          || !issuedGenerations.add(operation.localMemberGeneration())
          || operation.countsTowardCapacity()
              != (value.reservation().lifecycle()
                  != net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation
                      .Lifecycle.RELEASED)
          || operation.expectedBindingPresent() != (planned.bindingSourceRowOrdinal() != null)) {
        throw invalid("SQL-issued member differs from the complete frozen preparation tuple");
      }
    }
  }

  private static boolean sameLayout(
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor actual,
      CanonicalGameplayIssuerLayoutBootstrapIntent.LayoutDescriptor expected) {
    return actual.issuerId().equals(expected.issuerId())
        && actual.layoutVersion().equals(expected.layoutVersion())
        && actual.partitionCount().equals(expected.partitionCount())
        && actual.partitionCapacity().equals(expected.partitionCapacity())
        && actual.mappingIdentity().equals(expected.mappingIdentity())
        && actual.descriptorDigest().equals(expected.descriptorDigest())
        && Arrays.equals(actual.exactDescriptorBytes(), expected.exactDescriptorBytes());
  }

  private static boolean sameCheckpoint(
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint actual,
      CanonicalGameplayIssuerLayoutBootstrapIntent.AccountIssuerSourceCheckpoint expected) {
    return actual.issuerId().equals(expected.issuerId())
        && actual.issuerAuthGeneration().equals(expected.issuerAuthGeneration())
        && actual.checkpointDigest().equals(expected.checkpointDigest())
        && Arrays.equals(actual.exactCheckpointBytes(), expected.exactCheckpointBytes())
        && Objects.equals(actual.latestIssuerEventDigest(), expected.latestIssuerEventDigest())
        && Arrays.equals(actual.latestIssuerEventBytes(), expected.latestIssuerEventBytes());
  }

  private static byte[] encodeMetadata(
      CanonicalGameplayIssuerBootstrapPartitionWork work,
      BigInteger plannedAggregateCapacityCount,
      long plannedPartitionCapacityCount) {
    var intent = work.buildPlan().bootstrapIntent();
    var layout = intent.layoutDescriptor();
    var source = intent.sourceCheckpoint();
    var incarnation = work.incarnation();
    var deployment = incarnation.targetDeploymentEvidence();
    try {
      ObjectNode root = JSON.createObjectNode();
      root.put("schemaVersion", 1);
      root.put("issuerId", intent.issuerId());
      root.put("partitionId", work.partitionId().toString());
      root.put("layoutTag", CanonicalGameplayIssuerRedisKeys.layoutTag(layout, work.partitionId()));
      ObjectNode bootstrap = root.putObject("bootstrap");
      bootstrap.put("operationId", intent.operationId().toString());
      bootstrap.put("requestId", intent.requestId().toString());
      bootstrap.put("controlEpoch", intent.controlEpoch().toString());
      bootstrap.put("operationFence", intent.operationFence().toString());
      bootstrap.put("coverageFence", intent.coverageFence().toString());
      bootstrap.put(
          "issuerInventorySnapshotRevision", intent.inventorySnapshotRevision().toString());
      bootstrap.put("cohortDigest", intent.cohortDigest());
      ObjectNode build = root.putObject("build");
      build.put("operationId", work.buildPlan().buildOperationId().toString());
      build.put("planDigest", work.buildPlan().planDigest());
      ObjectNode layoutObject = root.putObject("layout");
      layoutObject.put("version", layout.layoutVersion().toString());
      layoutObject.put("partitionCount", layout.partitionCount().toString());
      layoutObject.put("partitionCapacity", layout.partitionCapacity().toString());
      layoutObject.put("mappingIdentity", layout.mappingIdentity());
      layoutObject.put(
          "descriptorBytes", Base64.getEncoder().encodeToString(layout.exactDescriptorBytes()));
      layoutObject.put("descriptorDigest", layout.descriptorDigest());
      ObjectNode anchor = root.putObject("anchor");
      anchor.put("issuerCoverageOperationId", intent.operationId().toString());
      anchor.put("operationFence", intent.operationFence().toString());
      anchor.put("coverageFence", intent.coverageFence().toString());
      anchor.put("issuerInventorySnapshotRevision", intent.inventorySnapshotRevision().toString());
      anchor.put("issuerAuthGeneration", source.issuerAuthGeneration().toString());
      anchor.put(
          "sourceCheckpointBytes",
          Base64.getEncoder().encodeToString(source.exactCheckpointBytes()));
      anchor.put("sourceCheckpointDigest", source.checkpointDigest());
      putNullableBase64(anchor, "sourceEventBytes", source.latestIssuerEventBytes());
      putNullableText(anchor, "sourceEventDigest", source.latestIssuerEventDigest());
      root.put("issuerControlEpoch", intent.controlEpoch().toString());
      ObjectNode incarnationObject = root.putObject("incarnation");
      incarnationObject.put("attestorBinding", incarnation.attestorBinding());
      incarnationObject.put("acceptedAttestationDigest", incarnation.acceptedAttestationDigest());
      ObjectNode deploymentObject = incarnationObject.putObject("targetDeploymentEvidence");
      deploymentObject.put("deploymentIdentity", deployment.deploymentIdentity());
      deploymentObject.put("deploymentGeneration", deployment.deploymentGeneration());
      ArrayNode nodeSet = deploymentObject.putArray("canonicalNodeSet");
      deployment.canonicalNodeSet().forEach(nodeSet::add);
      deploymentObject.put("redisBuildIdentity", deployment.redisBuildIdentity());
      deploymentObject.put("luaScriptRegistryVersion", deployment.luaScriptRegistryVersion());
      deploymentObject.put("luaScriptRegistryDigest", deployment.luaScriptRegistryDigest());
      deploymentObject.put("startupState", deployment.startupState());
      deploymentObject.put("bootGeneration", deployment.bootGeneration());
      deploymentObject.put("startupGeneration", deployment.startupGeneration());
      deploymentObject.put("storageGeneration", deployment.storageGeneration());
      deploymentObject.put("aofGeneration", deployment.aofGeneration());
      incarnationObject.put("coordOpsRoAclDigest", incarnation.coordOpsRoAclDigest());
      incarnationObject.put(
          "coordRedisConfigObservedDigest", incarnation.coordRedisConfigObservedDigest());
      root.put("plannedAggregateCapacityCount", plannedAggregateCapacityCount.toString());
      root.put(
          "plannedPartitionReservationCount", Integer.toString(work.memberOperations().size()));
      root.put("plannedPartitionCapacityCount", Long.toString(plannedPartitionCapacityCount));
      byte[] encoded = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(root));
      if (encoded.length > MAX_METADATA_BYTES)
        throw invalid("Bootstrap metadata exceeds its resource limit");
      return encoded;
    } catch (IOException failure) {
      throw new IllegalArgumentException("Could not encode canonical bootstrap metadata", failure);
    }
  }

  private static void putNullableBase64(ObjectNode object, String name, byte[] bytes) {
    if (bytes == null) object.putNull(name);
    else object.put(name, Base64.getEncoder().encodeToString(bytes));
  }

  private static void putNullableText(ObjectNode object, String name, String value) {
    if (value == null) object.putNull(name);
    else object.put(name, value);
  }

  private static void putUnique(Map<String, byte[]> fields, String field, byte[] value) {
    if (field == null
        || field.isEmpty()
        || field.getBytes(StandardCharsets.UTF_8).length > MAX_BINDING_REF_BYTES
        || fields.putIfAbsent(field, value.clone()) != null) {
      throw invalid("Issuer bootstrap field identity is reserved, oversized, or duplicated");
    }
  }

  private static String strictUtf8(byte[] bytes) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(
          "Issuer bootstrap field is not canonical UTF-8", malformed);
    }
  }

  private static void requireExactFields(ObjectNode object, Set<String> expected, String name) {
    Set<String> actual = new HashSet<>();
    object.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expected)) throw invalid(name + " has missing or unknown fields");
  }

  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }

  record PhysicalFieldSet(
      Map<String, byte[]> fields,
      byte[] metadataBytes,
      int reservationCount,
      int bindingCount,
      int countedReservationCount,
      BigInteger plannedAggregateCapacityCount,
      long partitionReservationCount) {
    PhysicalFieldSet {
      Map<String, byte[]> copied = new LinkedHashMap<>();
      fields.forEach((field, value) -> copied.put(field, value.clone()));
      fields = Map.copyOf(copied);
      metadataBytes = metadataBytes.clone();
    }

    @Override
    public Map<String, byte[]> fields() {
      Map<String, byte[]> copied = new LinkedHashMap<>();
      fields.forEach((field, value) -> copied.put(field, value.clone()));
      return Map.copyOf(copied);
    }

    @Override
    public byte[] metadataBytes() {
      return metadataBytes.clone();
    }
  }
}
