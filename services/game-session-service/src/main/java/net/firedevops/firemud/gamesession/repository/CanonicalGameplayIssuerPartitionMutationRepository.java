package net.firedevops.firemud.gamesession.repository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.jooq.DSLContext;
import org.jooq.Record;

/** SQL serialization owner for each one-key issuer-partition physical mutation. */
final class CanonicalGameplayIssuerPartitionMutationRepository {
  static final String REVISION_FIELD = "!issuer-partition-mutation-revision/v1";

  private static final String ISSUER = GameSessionAccountDelegationProfile.ISSUER;
  static final String HEAD_TABLE = "game_session_canonical_issuer_partition_mutation_head";
  private static final String HEAD = HEAD_TABLE;
  private static final String MUTATION = "game_session_canonical_issuer_partition_mutation";
  private static final String PROOF = "game_session_canonical_issuer_bootstrap_partition_proof";
  private static final String ANCHOR = "game_session_canonical_issuer_installed_anchor";

  private CanonicalGameplayIssuerPartitionMutationRepository() {}

  /**
   * Allocates the initial opaque physical revision only as part of the existing positive bootstrap
   * owner, before that owner writes the initial field set. A retained proof or anchor with no head
   * is old/missing physical state and cannot be initialized here.
   */
  static UUID ensureInitialRevisionInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, BigInteger partitionId) {
    Objects.requireNonNull(transaction, "transaction");
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requirePartition(partitionId);
    Record existing =
        transaction.fetchOne(
            "SELECT * FROM "
                + HEAD
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? FOR UPDATE",
            ISSUER,
            bootstrapOperationId,
            partitionId);
    if (existing != null) {
      if (!bootstrapOperationId.equals(uuid(existing, "bootstrap_operation_id"))) {
        throw conflict("Issuer partition revision belongs to a different initial anchor");
      }
      UUID revision = uuid(existing, "initial_revision");
      String state = text(existing, "state");
      UUID currentRevision = uuid(existing, "current_revision");
      if ((!"INITIAL_INSTALL_PENDING".equals(state) && !"CURRENT".equals(state))
          || ("INITIAL_INSTALL_PENDING".equals(state) && !revision.equals(currentRevision))) {
        throw conflict("Initial issuer partition revision head is malformed");
      }
      return revision;
    }
    Record proof =
        transaction.fetchOne(
            "SELECT 1 FROM " + PROOF + " WHERE bootstrap_operation_id = ? AND partition_id = ?",
            bootstrapOperationId,
            partitionId);
    Record anchor =
        transaction.fetchOne(
            "SELECT 1 FROM " + ANCHOR + " WHERE bootstrap_operation_id = ?", bootstrapOperationId);
    if (proof != null || anchor != null) {
      throw conflict("Installed issuer partition is missing its positively installed revision");
    }
    UUID initialRevision = freshRevision();
    int inserted =
        transaction.execute(
            "INSERT INTO "
                + HEAD
                + " (issuer_id, partition_id, bootstrap_operation_id, initial_revision,"
                + " current_revision, state) VALUES (?, ?, ?, ?, ?, 'INITIAL_INSTALL_PENDING')",
            ISSUER,
            partitionId,
            bootstrapOperationId,
            initialRevision,
            initialRevision);
    if (inserted != 1) throw conflict("Initial issuer partition revision was not persisted");
    return initialRevision;
  }

  /** Called only after the existing bootstrap owner independently read back the entire key. */
  static void recordInitialPhysicalReadbackInTransaction(
      DSLContext transaction,
      UUID bootstrapOperationId,
      BigInteger partitionId,
      UUID initialRevision,
      byte[] exactFieldSetBytes) {
    Objects.requireNonNull(transaction, "transaction");
    requireNonNil(bootstrapOperationId, "bootstrapOperationId");
    requirePartition(partitionId);
    requireNonNil(initialRevision, "initialRevision");
    Objects.requireNonNull(exactFieldSetBytes, "exactFieldSetBytes");
    Map<String, byte[]> fields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(exactFieldSetBytes);
    byte[] revisionBytes = fields.get(REVISION_FIELD);
    if (!Arrays.equals(
        revisionBytes, initialRevision.toString().getBytes(StandardCharsets.US_ASCII))) {
      throw conflict("Initial partition readback lacks its exact SQL-issued physical revision");
    }
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + HEAD
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? FOR UPDATE",
            ISSUER,
            bootstrapOperationId,
            partitionId);
    if (row == null
        || !bootstrapOperationId.equals(uuid(row, "bootstrap_operation_id"))
        || !initialRevision.equals(uuid(row, "initial_revision"))
        || !initialRevision.equals(uuid(row, "current_revision"))) {
      throw conflict("Initial partition readback differs from its prepared SQL revision");
    }
    String state = text(row, "state");
    if ("CURRENT".equals(state)) return;
    if (!"INITIAL_INSTALL_PENDING".equals(state) || row.get("pending_mutation_id") != null) {
      throw conflict("Initial partition revision is not awaiting bootstrap proof");
    }
    int updated =
        transaction.execute(
            "UPDATE "
                + HEAD
                + " SET state = 'CURRENT' WHERE issuer_id = ? AND partition_id = ?"
                + " AND bootstrap_operation_id = ? AND initial_revision = ?"
                + " AND current_revision = ? AND state = 'INITIAL_INSTALL_PENDING'"
                + " AND pending_mutation_id IS NULL",
            ISSUER,
            partitionId,
            bootstrapOperationId,
            initialRevision,
            initialRevision);
    if (updated != 1)
      throw conflict("Initial partition revision installation compare-and-set refused");
  }

  /** Persists the unique original expected/successor pair and serializes later same-key writers. */
  static CanonicalGameplayIssuerPartitionMutation prepareInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionMutation.Kind kind,
      UUID ownerOperationId,
      BigInteger partitionId,
      byte[] exactIntendedChangeBytes) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(kind, "kind");
    requireNonNil(ownerOperationId, "ownerOperationId");
    requirePartition(partitionId);
    if (exactIntendedChangeBytes == null
        || exactIntendedChangeBytes.length == 0
        || exactIntendedChangeBytes.length > 262_144) {
      throw conflict("Exact intended issuer partition mutation is missing or oversized");
    }
    String digest = CanonicalGameplayIssuerMemberV2Codec.sha256(exactIntendedChangeBytes);
    UUID bootstrapOperationId = installedBootstrapOperationId(transaction);
    Record current = lockCurrentHead(transaction, bootstrapOperationId, partitionId);
    Record existing =
        transaction.fetchOne(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_kind = ?"
                + " AND owner_operation_id = ? FOR UPDATE",
            ISSUER,
            bootstrapOperationId,
            partitionId,
            kind.name(),
            ownerOperationId);
    if (existing != null) {
      CanonicalGameplayIssuerPartitionMutation mutation = readMutation(existing);
      if (!Arrays.equals(mutation.exactIntendedChangeBytes(), exactIntendedChangeBytes)
          || !mutation.exactIntendedChangeDigest().equals(digest)) {
        throw conflict(
            "Original issuer partition mutation retry changed its exact intended change");
      }
      if (mutation.status()
          == CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF) {
        if (!mutation.mutationId().equals(nullableUuid(current, "pending_mutation_id"))
            || !mutation.expectedRevision().equals(uuid(current, "current_revision"))) {
          throw conflict("Pending issuer partition mutation lost its current revision fence");
        }
      }
      return mutation;
    }
    if (nullableUuid(current, "pending_mutation_id") != null) {
      throw conflict("Another same-partition physical mutation remains unresolved");
    }
    UUID expected = uuid(current, "current_revision");
    UUID successor = freshRevision();
    while (successor.equals(expected)) successor = freshRevision();
    UUID mutationId = freshRevision();
    int inserted =
        transaction.execute(
            "INSERT INTO "
                + MUTATION
                + " (mutation_id, issuer_id, bootstrap_operation_id, partition_id, mutation_kind, owner_operation_id,"
                + " expected_revision, successor_revision, exact_intended_change_bytes,"
                + " exact_intended_change_digest, status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_PHYSICAL_PROOF')",
            mutationId,
            ISSUER,
            bootstrapOperationId,
            partitionId,
            kind.name(),
            ownerOperationId,
            expected,
            successor,
            exactIntendedChangeBytes,
            digest);
    if (inserted != 1) throw conflict("Issuer partition mutation intent was not persisted");
    int advanced =
        transaction.execute(
            "UPDATE "
                + HEAD
                + " SET pending_mutation_id = ? WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ?"
                + " AND state = 'CURRENT' AND current_revision = ? AND pending_mutation_id IS NULL",
            mutationId,
            ISSUER,
            bootstrapOperationId,
            partitionId,
            expected);
    if (advanced != 1)
      throw conflict("Issuer partition mutation reservation compare-and-set refused");
    Record saved =
        transaction.fetchOne(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_id = ?",
            ISSUER,
            bootstrapOperationId,
            partitionId,
            mutationId);
    if (saved == null) throw conflict("Issuer partition mutation is unreadable after preparation");
    return readMutation(saved);
  }

  /** Canonical binary carrier for the exact original change, not a caller-supplied digest. */
  static byte[] encodeIntendedChange(
      CanonicalGameplayIssuerPartitionMutation.Kind kind,
      UUID ownerOperationId,
      BigInteger partitionId,
      byte[]... exactSourceFields) {
    Objects.requireNonNull(kind, "kind");
    requireNonNil(ownerOperationId, "ownerOperationId");
    requirePartition(partitionId);
    Objects.requireNonNull(exactSourceFields, "exactSourceFields");
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(buffer)) {
        writeField(
            output, "canonical-issuer-partition-mutation/v1".getBytes(StandardCharsets.US_ASCII));
        writeField(output, kind.name().getBytes(StandardCharsets.US_ASCII));
        writeField(output, ownerOperationId.toString().getBytes(StandardCharsets.US_ASCII));
        writeField(output, partitionId.toString().getBytes(StandardCharsets.US_ASCII));
        output.writeInt(exactSourceFields.length);
        for (byte[] value : exactSourceFields) {
          if (value == null)
            throw conflict("Exact issuer partition mutation source field is missing");
          writeField(output, value);
        }
      }
      byte[] encoded = buffer.toByteArray();
      if (encoded.length == 0 || encoded.length > 262_144) {
        throw conflict("Exact issuer partition mutation tuple exceeds its bound");
      }
      return encoded;
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Could not encode exact issuer partition mutation", impossible);
    }
  }

  /** Commits only a complete readback already obtained on an independent Redis connection. */
  static CanonicalGameplayIssuerPartitionMutation completeInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionMutation expected,
      byte[] exactReadbackFieldSetBytes) {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(expected, "expected");
    if (exactReadbackFieldSetBytes == null
        || exactReadbackFieldSetBytes.length == 0
        || exactReadbackFieldSetBytes.length > 17_825_792) {
      throw conflict("Issuer partition mutation readback is missing or oversized");
    }
    Map<String, byte[]> readbackFields =
        CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
            exactReadbackFieldSetBytes);
    requireRevisionField(readbackFields, expected.successorRevision());
    String readbackDigest = CanonicalGameplayIssuerMemberV2Codec.sha256(exactReadbackFieldSetBytes);
    UUID bootstrapOperationId = installedBootstrapOperationId(transaction);
    Record head = lockCurrentHead(transaction, bootstrapOperationId, expected.partitionId());
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_id = ? FOR UPDATE",
            ISSUER,
            bootstrapOperationId,
            expected.partitionId(),
            expected.mutationId());
    if (row == null || !bootstrapOperationId.equals(uuid(row, "bootstrap_operation_id")))
      throw conflict(
          "Issuer partition mutation disappeared or changed bootstrap lineage before completion");
    CanonicalGameplayIssuerPartitionMutation current = readMutation(row);
    requireSameMutation(expected, current);
    if (current.status() == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED) {
      if (!Arrays.equals(current.exactReadbackFieldSetBytes(), exactReadbackFieldSetBytes)
          || !current.exactReadbackFieldSetDigest().equals(readbackDigest)) {
        throw conflict("Committed issuer partition mutation retry changed its exact readback");
      }
      return current;
    }
    if (!current.mutationId().equals(nullableUuid(head, "pending_mutation_id"))
        || !current.expectedRevision().equals(uuid(head, "current_revision"))) {
      throw conflict("Issuer partition mutation no longer owns its expected revision");
    }
    int committed =
        transaction.execute(
            "UPDATE "
                + MUTATION
                + " SET status = 'COMMITTED', exact_readback_field_set_bytes = ?,"
                + " exact_readback_field_set_digest = ?, completed_at = CURRENT_TIMESTAMP"
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ?"
                + " AND mutation_id = ? AND status = 'PENDING_PHYSICAL_PROOF'"
                + " AND expected_revision = ? AND successor_revision = ?",
            exactReadbackFieldSetBytes,
            readbackDigest,
            ISSUER,
            bootstrapOperationId,
            current.partitionId(),
            current.mutationId(),
            current.expectedRevision(),
            current.successorRevision());
    if (committed != 1)
      throw conflict("Issuer partition mutation completion compare-and-set refused");
    int advanced =
        transaction.execute(
            "UPDATE "
                + HEAD
                + " SET current_revision = ?, pending_mutation_id = NULL"
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND state = 'CURRENT'"
                + " AND current_revision = ? AND pending_mutation_id = ?",
            current.successorRevision(),
            ISSUER,
            bootstrapOperationId,
            current.partitionId(),
            current.expectedRevision(),
            current.mutationId());
    if (advanced != 1)
      throw conflict("Issuer partition mutation head completion compare-and-set refused");
    Record completed =
        transaction.fetchOne(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_id = ?",
            ISSUER,
            bootstrapOperationId,
            current.partitionId(),
            current.mutationId());
    if (completed == null) throw conflict("Committed issuer partition mutation is unreadable");
    return readMutation(completed);
  }

  static CanonicalGameplayIssuerPartitionMutation readInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionMutation.Kind kind,
      UUID ownerOperationId,
      BigInteger partitionId) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_kind = ?"
                + " AND owner_operation_id = ?",
            ISSUER,
            installedBootstrapOperationId(transaction),
            partitionId,
            kind.name(),
            ownerOperationId);
    if (row == null) throw conflict("The original issuer partition mutation evidence is missing");
    return readMutation(row);
  }

  static CanonicalGameplayIssuerPartitionMutation readOptionalInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerPartitionMutation.Kind kind,
      UUID ownerOperationId,
      BigInteger partitionId) {
    Record row =
        transaction.fetchOne(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_kind = ?"
                + " AND owner_operation_id = ?",
            ISSUER,
            installedBootstrapOperationId(transaction),
            partitionId,
            kind.name(),
            ownerOperationId);
    return row == null ? null : readMutation(row);
  }

  static List<CanonicalGameplayIssuerPartitionMutation> readPartitionInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, BigInteger partitionId) {
    if (!bootstrapOperationId.equals(installedBootstrapOperationId(transaction))) {
      throw conflict(
          "Issuer partition mutation history belongs to a different installed bootstrap");
    }
    List<CanonicalGameplayIssuerPartitionMutation> mutations = new ArrayList<>();
    for (Record row :
        transaction.fetch(
            "SELECT * FROM "
                + MUTATION
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ?"
                + " AND mutation_kind IN ('ADD_CAPACITY', 'ADD_BINDING')"
                + " AND owner_operation_id IN (SELECT operation.operation_id"
                + " FROM game_session_canonical_issuer_binding_local_operation operation"
                + " WHERE operation.issuer_id = ? AND operation.partition_id = ?"
                + " AND NOT EXISTS (SELECT 1 FROM game_session_canonical_issuer_binding_local_retirement retirement"
                + " WHERE retirement.original_local_operation_id = operation.operation_id"
                + " AND retirement.status = 'RETIRED'))"
                + " ORDER BY mutation_id",
            ISSUER,
            bootstrapOperationId,
            partitionId,
            ISSUER,
            partitionId)) {
      mutations.add(readMutation(row));
    }
    return List.copyOf(mutations);
  }

  static UUID readCurrentRevisionInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, BigInteger partitionId) {
    Record head = lockCurrentHead(transaction, bootstrapOperationId, partitionId);
    if (nullableUuid(head, "pending_mutation_id") != null) {
      throw conflict("A same-partition physical mutation is still unresolved");
    }
    return uuid(head, "current_revision");
  }

  static PartitionMutationHead readHeadInTransaction(
      DSLContext transaction, UUID bootstrapOperationId, BigInteger partitionId) {
    Record head = lockCurrentHead(transaction, bootstrapOperationId, partitionId);
    UUID pendingMutationId = nullableUuid(head, "pending_mutation_id");
    UUID currentRevision = uuid(head, "current_revision");
    if (pendingMutationId != null) {
      Record pending =
          transaction.fetchOne(
              "SELECT * FROM "
                  + MUTATION
                  + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? AND mutation_id = ? FOR UPDATE",
              ISSUER,
              bootstrapOperationId,
              partitionId,
              pendingMutationId);
      if (pending == null
          || !ISSUER.equals(text(pending, "issuer_id"))
          || !bootstrapOperationId.equals(uuid(pending, "bootstrap_operation_id"))
          || !partitionId.equals(nonnegative(pending, "partition_id"))
          || !currentRevision.equals(uuid(pending, "expected_revision"))
          || !"PENDING_PHYSICAL_PROOF".equals(text(pending, "status"))) {
        throw conflict("Current issuer partition revision has malformed pending mutation custody");
      }
    }
    return new PartitionMutationHead(
        uuid(head, "initial_revision"), currentRevision, pendingMutationId);
  }

  static void requireRevisionField(Map<String, byte[]> fields, UUID expectedRevision) {
    byte[] actual = fields.get(REVISION_FIELD);
    if (!Arrays.equals(actual, expectedRevision.toString().getBytes(StandardCharsets.US_ASCII))) {
      throw conflict("Issuer partition is missing its exact current physical mutation revision");
    }
  }

  private static UUID installedBootstrapOperationId(DSLContext transaction) {
    Record anchor =
        transaction.fetchOne(
            "SELECT bootstrap_operation_id FROM " + ANCHOR + " WHERE issuer_id = ?", ISSUER);
    if (anchor == null)
      throw conflict("Issuer partition mutation requires its installed bootstrap anchor");
    return uuid(anchor, "bootstrap_operation_id");
  }

  private static Record lockCurrentHead(
      DSLContext transaction, UUID bootstrapOperationId, BigInteger partitionId) {
    if (!bootstrapOperationId.equals(installedBootstrapOperationId(transaction))) {
      throw conflict("Issuer partition mutation head belongs to a different installed bootstrap");
    }
    Record head =
        transaction.fetchOne(
            "SELECT * FROM "
                + HEAD
                + " WHERE issuer_id = ? AND bootstrap_operation_id = ? AND partition_id = ? FOR UPDATE",
            ISSUER,
            bootstrapOperationId,
            partitionId);
    if (head == null || !"CURRENT".equals(text(head, "state"))) {
      throw conflict("Issuer partition has no positively installed current mutation revision");
    }
    return head;
  }

  private static CanonicalGameplayIssuerPartitionMutation readMutation(Record row) {
    CanonicalGameplayIssuerPartitionMutation.Kind kind;
    CanonicalGameplayIssuerPartitionMutation.Status status;
    try {
      kind = CanonicalGameplayIssuerPartitionMutation.Kind.valueOf(text(row, "mutation_kind"));
      status = CanonicalGameplayIssuerPartitionMutation.Status.valueOf(text(row, "status"));
    } catch (RuntimeException malformed) {
      throw conflict("Issuer partition mutation kind or status is malformed");
    }
    byte[] intended = bytes(row, "exact_intended_change_bytes");
    String digest = text(row, "exact_intended_change_digest");
    if (!digest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(intended))) {
      throw conflict("Issuer partition mutation intent digest differs from its exact bytes");
    }
    byte[] readback = optionalBytes(row, "exact_readback_field_set_bytes");
    String readbackDigest = row.get("exact_readback_field_set_digest", String.class);
    if ((status == CanonicalGameplayIssuerPartitionMutation.Status.PENDING_PHYSICAL_PROOF
            && (readback != null || readbackDigest != null || row.get("completed_at") != null))
        || (status == CanonicalGameplayIssuerPartitionMutation.Status.COMMITTED
            && (readback == null
                || readbackDigest == null
                || !readbackDigest.equals(CanonicalGameplayIssuerMemberV2Codec.sha256(readback))
                || row.get("completed_at") == null))) {
      throw conflict("Issuer partition mutation proof differs from its durable phase");
    }
    try {
      return new CanonicalGameplayIssuerPartitionMutation(
          uuid(row, "mutation_id"),
          nonnegative(row, "partition_id"),
          kind,
          uuid(row, "owner_operation_id"),
          uuid(row, "expected_revision"),
          uuid(row, "successor_revision"),
          intended,
          digest,
          status,
          readback,
          readbackDigest);
    } catch (RuntimeException malformed) {
      throw conflict("Issuer partition mutation row is malformed");
    }
  }

  private static void requireSameMutation(
      CanonicalGameplayIssuerPartitionMutation expected,
      CanonicalGameplayIssuerPartitionMutation actual) {
    if (!expected.mutationId().equals(actual.mutationId())
        || !expected.kind().equals(actual.kind())
        || !expected.ownerOperationId().equals(actual.ownerOperationId())
        || !expected.partitionId().equals(actual.partitionId())
        || !expected.expectedRevision().equals(actual.expectedRevision())
        || !expected.successorRevision().equals(actual.successorRevision())
        || !Arrays.equals(expected.exactIntendedChangeBytes(), actual.exactIntendedChangeBytes())
        || !expected.exactIntendedChangeDigest().equals(actual.exactIntendedChangeDigest())) {
      throw conflict("Issuer partition mutation changed its original SQL intent");
    }
  }

  private static UUID freshRevision() {
    UUID revision;
    do {
      revision = UUID.randomUUID();
    } while (revision.equals(new UUID(0L, 0L)));
    return revision;
  }

  private static void writeField(DataOutputStream output, byte[] value) throws IOException {
    output.writeInt(value.length);
    output.write(value);
  }

  private static UUID requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) throw conflict(name + " must not be nil");
    return value;
  }

  private static void requirePartition(BigInteger partitionId) {
    Objects.requireNonNull(partitionId, "partitionId");
    if (partitionId.signum() < 0) throw conflict("Issuer partition id must be nonnegative");
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null || value.equals(new UUID(0L, 0L)))
      throw conflict(field + " is missing or nil");
    return value;
  }

  private static UUID nullableUuid(Record row, String field) {
    return row.get(field, UUID.class);
  }

  private static BigInteger nonnegative(Record row, String field) {
    BigDecimal value = row.get(field, BigDecimal.class);
    if (value == null) throw conflict(field + " is missing");
    try {
      BigInteger integer = value.toBigIntegerExact();
      if (integer.signum() < 0) throw conflict(field + " must be nonnegative");
      return integer;
    } catch (ArithmeticException malformed) {
      throw conflict(field + " is not an exact integer");
    }
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw conflict(field + " is missing");
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null || value.length == 0) throw conflict(field + " is missing");
    return value.clone();
  }

  private static byte[] optionalBytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    return value == null ? null : value.clone();
  }

  private static IllegalStateException conflict(String message) {
    return new IllegalStateException(message);
  }

  record PartitionMutationHead(
      UUID initialRevision, UUID currentRevision, UUID pendingMutationId) {}
}
