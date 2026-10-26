package net.firedevops.firemud.gamesession.repository;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRef;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * Persists the opaque tenant tag used to co-locate canonical gameplay keys.
 *
 * <p>The assignment is topology only. It does not establish current admission, install a barrier,
 * or grant permission to read or mutate a gameplay key. Each public lookup reloads its tenant from
 * an owned durable source before it can reuse or create the immutable tag mapping.
 */
public final class CanonicalGameplayTenantGameplayTagRepository {
  private static final String MAPPING = "game_session_canonical_tenant_gameplay_tag";
  private static final Set<String> TRANSITION_STATES =
      Set.of("PREPARED", "PROVISIONAL", "COMMITTED");
  private static final Set<String> INVENTORY_STATES =
      Set.of("CANDIDATE_PREPARED", "PROVISIONAL", "ACTIVE");
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final DSLContext dsl;
  private final String workloadNamespace;
  private final int allocationVersion;

  public CanonicalGameplayTenantGameplayTagRepository(
      DSLContext dsl, String workloadNamespace, int allocationVersion) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException(
          "A fixed valid Game Session workload namespace is required");
    }
    if (allocationVersion <= 0) {
      throw new IllegalArgumentException("allocationVersion must be positive");
    }
    this.workloadNamespace = workloadNamespace;
    this.allocationVersion = allocationVersion;
  }

  /** Loads or allocates a mapping only for the persisted candidate of this exact transition. */
  public Assignment assignmentForTransition(UUID transitionId) {
    requireNonNil(transitionId, "transitionId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BindingTarget source = loadBindingTarget(transaction, transitionId);
          requireTenantClaim(transaction, source.tenantId());
          return resolveMapping(
              transaction, source, allocationVersion, tagFor(source.tenantId(), allocationVersion));
        });
  }

  /**
   * Resolves one still-current V1027 acquisition-intent target. A REQUIRED row is only a durable
   * target; returning its tag does not mean that a Redis barrier was written or read back.
   */
  public Assignment assignmentForBarrierTarget(UUID operationId, long targetOrdinal) {
    requireNonNil(operationId, "operationId");
    if (targetOrdinal < 0) {
      throw new IllegalArgumentException("targetOrdinal must not be negative");
    }
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          Record lockedControl =
              transaction.fetchOne(
                  "SELECT control.account_id"
                      + " FROM game_session_canonical_account_admission_fence_intent intent"
                      + " JOIN game_session_canonical_account_coverage_control control"
                      + " ON control.account_id = intent.account_id"
                      + " WHERE intent.operation_id = ?"
                      + " AND intent.intent_state = 'ACQUISITION_INTENT'"
                      + " AND control.state = 'ACQUISITION_INTENT'"
                      + " AND control.current_operation_id IS NULL"
                      + " AND control.current_fence_intent_id = intent.operation_id"
                      + " AND control.current_operation_fence = intent.operation_fence"
                      + " AND control.last_operation_fence = intent.operation_fence"
                      + " AND control.account_admission_fence = intent.planned_account_admission_fence"
                      + " AND control.inventory_snapshot_revision = intent.inventory_snapshot_revision"
                      + " AND control.coverage_fence IS NULL AND control.coverage_generation IS NULL"
                      + " FOR UPDATE OF control",
                  operationId);
          if (lockedControl == null) {
            throw conflict("The exact current acquisition-intent barrier target is unavailable");
          }
          Record row =
              transaction.fetchOne(
                  "SELECT target.operation_id, target.target_ordinal, target.account_id,"
                      + " target.tenant_id, target.playable_state_namespace_id"
                      + " FROM game_session_canonical_account_admission_fence_intent_barrier_target target"
                      + " JOIN game_session_canonical_account_admission_fence_intent intent"
                      + " ON intent.operation_id = target.operation_id"
                      + " AND intent.account_id = target.account_id"
                      + " JOIN game_session_canonical_account_coverage_control control"
                      + " ON control.account_id = intent.account_id"
                      + " WHERE target.operation_id = ? AND target.target_ordinal = ?"
                      + " AND target.target_state = 'REQUIRED'"
                      + " AND intent.intent_state = 'ACQUISITION_INTENT'"
                      + " AND control.state = 'ACQUISITION_INTENT'"
                      + " AND control.current_operation_id IS NULL"
                      + " AND control.current_fence_intent_id = intent.operation_id"
                      + " AND control.current_operation_fence = intent.operation_fence"
                      + " AND control.last_operation_fence = intent.operation_fence"
                      + " AND control.account_admission_fence = intent.planned_account_admission_fence"
                      + " AND control.inventory_snapshot_revision = intent.inventory_snapshot_revision"
                      + " AND control.coverage_fence IS NULL AND control.coverage_generation IS NULL"
                      + " FOR SHARE OF target, intent",
                  operationId,
                  targetOrdinal);
          if (row == null) {
            throw conflict("The exact current acquisition-intent barrier target is unavailable");
          }
          BarrierTarget source =
              new BarrierTarget(
                  requiredUuid(row, "operation_id"),
                  requiredLong(row, "target_ordinal"),
                  requiredUuid(row, "account_id"),
                  requiredUuid(row, "tenant_id"),
                  requiredUuid(row, "playable_state_namespace_id"));
          requireTenantClaim(transaction, source.tenantId());
          return resolveMapping(
              transaction, source, allocationVersion, tagFor(source.tenantId(), allocationVersion));
        });
  }

  /** Resolves topology only for the persisted exact original-attempt INITIAL target. */
  Assignment assignmentForInitialBarrierTarget(UUID originalRequestId) {
    requireNonNil(originalRequestId, "originalRequestId");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          Record row =
              transaction.fetchOne(
                  "SELECT i.initializer_id, i.account_id, i.source_control_epoch,"
                      + " t.target_ordinal, t.tenant_id,"
                      + " t.playable_state_namespace_id, t.target_state, control.state AS control_state,"
                      + " control.last_operation_fence, control.current_operation_id,"
                      + " control.current_fence_intent_id, control.current_operation_fence,"
                      + " control.account_admission_fence, control.historical_account_admission_fence,"
                      + " control.coverage_fence, control.inventory_snapshot_revision,"
                      + " control.coverage_generation"
                      + " FROM game_session_canonical_initial_account_barrier_initializer i"
                      + " JOIN game_session_canonical_initial_account_barrier_target t"
                      + " ON t.initializer_id = i.initializer_id"
                      + " AND t.original_request_id = i.original_request_id"
                      + " JOIN game_session_canonical_account_coverage_control control"
                      + " ON control.account_id = i.account_id"
                      + " WHERE i.original_request_id = ? AND t.target_ordinal = 0"
                      + " FOR UPDATE OF i, t, control",
                  originalRequestId);
          if (row == null
              || !"NO_ACTIVE_ACCOUNT_WIDE_FLOW".equals(row.get("control_state", String.class))
              || row.get("last_operation_fence", java.math.BigDecimal.class) == null
              || row.get("last_operation_fence", java.math.BigDecimal.class)
                      .toBigIntegerExact()
                      .signum()
                  < 0
              || row.get("source_control_epoch", java.math.BigDecimal.class) == null
              || !row.get("source_control_epoch", java.math.BigDecimal.class)
                  .toBigIntegerExact()
                  .equals(
                      row.get("last_operation_fence", java.math.BigDecimal.class)
                          .toBigIntegerExact())
              || row.get("current_operation_id", UUID.class) != null
              || row.get("current_fence_intent_id", UUID.class) != null
              || row.get("current_operation_fence") != null
              || row.get("account_admission_fence", UUID.class) != null
              || row.get("historical_account_admission_fence", UUID.class) != null
              || row.get("coverage_fence", UUID.class) != null
              || row.get("inventory_snapshot_revision") != null
              || row.get("coverage_generation") != null) {
            throw conflict(
                "The exact INITIAL target or its positive NO_ACTIVE control is unavailable");
          }
          UUID initializerId = requiredUuid(row, "initializer_id");
          UUID accountId = requiredUuid(row, "account_id");
          UUID tenantId = requiredUuid(row, "tenant_id");
          UUID namespaceId = requiredUuid(row, "playable_state_namespace_id");
          if (requiredLong(row, "target_ordinal") != 0L
              || !Set.of("NEVER_DISPATCHED", "DISPATCH_POSSIBLE", "OPEN_INSTALLED")
                  .contains(requiredString(row, "target_state"))) {
            throw conflict("The INITIAL target state is not a retained original-attempt state");
          }
          requireTenantClaim(transaction, tenantId);
          InitialTarget source =
              new InitialTarget(initializerId, originalRequestId, accountId, tenantId, namespaceId);
          return resolveMapping(
              transaction, source, allocationVersion, tagFor(tenantId, allocationVersion));
        });
  }

  /**
   * Narrow package-private collision path for native database proof. The tenant still comes from
   * the exact persisted transition; only the candidate output is controlled to exercise the global
   * unique-tag conflict branch.
   */
  Assignment assignmentForTransitionWithCandidate(
      UUID transitionId, int candidateVersion, String candidateTag) {
    requireNonNil(transitionId, "transitionId");
    requirePositive(candidateVersion, "candidateVersion");
    requireCanonicalTag(candidateTag);
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          BindingTarget source = loadBindingTarget(transaction, transitionId);
          requireTenantClaim(transaction, source.tenantId());
          return resolveMapping(transaction, source, candidateVersion, candidateTag);
        });
  }

  private BindingTarget loadBindingTarget(DSLContext transaction, UUID transitionId) {
    Record row =
        transaction.fetchOne(
            "SELECT transition.transition_id, transition.tenant_id AS transition_tenant_id,"
                + " transition.playable_state_namespace_id AS transition_namespace_id,"
                + " transition.character_id AS transition_character_id,"
                + " transition.candidate_binding_ref, transition.candidate_binding_generation,"
                + " transition.candidate_account_index_fence AS transition_account_index_fence,"
                + " transition.issuer_reservation_id AS transition_reservation_id,"
                + " transition.inventory_revision AS transition_inventory_revision,"
                + " transition.status, inventory.binding_ref, inventory.account_id,"
                + " inventory.tenant_id AS inventory_tenant_id,"
                + " inventory.playable_state_namespace_id AS inventory_namespace_id,"
                + " inventory.character_id AS inventory_character_id, inventory.game_instance_id,"
                + " inventory.session_id, inventory.binding_generation, inventory.account_index_fence,"
                + " inventory.issuer_reservation_id AS inventory_reservation_id,"
                + " inventory.inventory_revision, inventory.lifecycle"
                + " FROM game_session_canonical_binding_transition transition"
                + " JOIN game_session_canonical_gameplay_binding_inventory inventory"
                + " ON inventory.transition_id = transition.transition_id"
                + " AND inventory.binding_ref = transition.candidate_binding_ref"
                + " WHERE transition.transition_id = ? FOR UPDATE OF transition, inventory",
            transitionId);
    if (row == null) {
      throw conflict("The exact durable canonical binding transition is unavailable");
    }

    UUID tenantId = requiredUuid(row, "transition_tenant_id");
    UUID namespaceId = requiredUuid(row, "transition_namespace_id");
    UUID characterId = requiredUuid(row, "transition_character_id");
    UUID inventoryTenantId = requiredUuid(row, "inventory_tenant_id");
    UUID inventoryNamespaceId = requiredUuid(row, "inventory_namespace_id");
    UUID inventoryCharacterId = requiredUuid(row, "inventory_character_id");
    UUID accountId = requiredUuid(row, "account_id");
    UUID gameInstanceId = requiredUuid(row, "game_instance_id");
    String sessionId = requiredString(row, "session_id");
    byte[] transitionRefBytes = requiredBytes(row, "candidate_binding_ref");
    byte[] inventoryRefBytes = requiredBytes(row, "binding_ref");
    String transitionState = requiredString(row, "status");
    String inventoryState = requiredString(row, "lifecycle");
    if (!tenantId.equals(inventoryTenantId)
        || !namespaceId.equals(inventoryNamespaceId)
        || !characterId.equals(inventoryCharacterId)
        || !requiredUuid(row, "transition_account_index_fence")
            .equals(requiredUuid(row, "account_index_fence"))
        || !requiredUuid(row, "transition_reservation_id")
            .equals(requiredUuid(row, "inventory_reservation_id"))
        || !TRANSITION_STATES.contains(transitionState)
        || !INVENTORY_STATES.contains(inventoryState)
        || !transitionRefMatches(
            transitionRefBytes, inventoryRefBytes, tenantId, gameInstanceId, sessionId)
        || !requiredBigInteger(row, "candidate_binding_generation")
            .equals(requiredBigInteger(row, "binding_generation"))
        || !requiredBigInteger(row, "transition_inventory_revision")
            .equals(requiredBigInteger(row, "inventory_revision"))) {
      throw conflict(
          "The persisted transition and candidate inventory do not form one exact target");
    }

    if (("PREPARED".equals(transitionState) && !"CANDIDATE_PREPARED".equals(inventoryState))
        || ("PROVISIONAL".equals(transitionState) && !"PROVISIONAL".equals(inventoryState))
        || ("COMMITTED".equals(transitionState) && !"ACTIVE".equals(inventoryState))) {
      throw conflict("The durable transition and candidate inventory lifecycle disagree");
    }

    CanonicalGameplayBindingRef bindingRef =
        CanonicalGameplayBindingRef.fromExactBytes(transitionRefBytes);
    return new BindingTarget(
        requiredUuid(row, "transition_id"),
        accountId,
        tenantId,
        namespaceId,
        characterId,
        gameInstanceId,
        sessionId,
        bindingRef);
  }

  private void requireTenantClaim(DSLContext transaction, UUID tenantId) {
    Record row =
        transaction.fetchOne(
            "SELECT claim.association_kind"
                + " FROM game_session_tenant_canonical_claim claim"
                + " WHERE claim.target_namespace = ? AND claim.canonical_tenant_id = ?"
                + " AND ((claim.association_kind = 'FRESH_SOURCE_BOUND' AND EXISTS ("
                + " SELECT 1 FROM game_session_fresh_tenant_association fresh"
                + " WHERE fresh.target_namespace = claim.target_namespace"
                + " AND fresh.canonical_tenant_id = claim.canonical_tenant_id"
                + " AND fresh.association_operation_id = claim.association_operation_id"
                + " AND fresh.association_request_id = claim.association_request_id"
                + " AND fresh.association_kind = claim.association_kind))"
                + " OR (claim.association_kind = 'RETAINED_V8_1' AND EXISTS ("
                + " SELECT 1 FROM game_session_retained_tenant_association retained"
                + " WHERE retained.target_namespace = claim.target_namespace"
                + " AND retained.canonical_tenant_id = claim.canonical_tenant_id"
                + " AND retained.operation_id = claim.association_operation_id"
                + " AND retained.association_request_id = claim.association_request_id)))",
            workloadNamespace,
            tenantId);
    if (row == null) {
      throw conflict(
          "The durable tenant claim is not associated with this configured Game Session workload");
    }
  }

  private Assignment resolveMapping(
      DSLContext transaction, Target source, int candidateVersion, String candidateTag) {
    Record mapping =
        transaction.fetchOne(
            "SELECT allocation_version, tenant_gameplay_tag FROM "
                + MAPPING
                + " WHERE tenant_id = ? FOR UPDATE",
            source.tenantId());
    if (mapping == null) {
      transaction.fetchOne(
          "INSERT INTO "
              + MAPPING
              + " (tenant_id, allocation_version, tenant_gameplay_tag) VALUES (?, ?, ?)"
              + " ON CONFLICT DO NOTHING RETURNING tenant_id",
          source.tenantId(),
          candidateVersion,
          candidateTag);
      mapping =
          transaction.fetchOne(
              "SELECT allocation_version, tenant_gameplay_tag FROM "
                  + MAPPING
                  + " WHERE tenant_id = ? FOR UPDATE",
              source.tenantId());
    }
    if (mapping == null) {
      throw conflict("A gameplay tag candidate is already owned by a different tenant");
    }

    Integer storedVersion = mapping.get("allocation_version", Integer.class);
    String storedTag = mapping.get("tenant_gameplay_tag", String.class);
    if (storedVersion == null || storedVersion <= 0) {
      throw conflict("Persisted tenant gameplay tag allocation version is invalid");
    }
    requireCanonicalTag(storedTag);
    if (!tagFor(source.tenantId(), storedVersion).equals(storedTag)) {
      throw conflict("Persisted tenant tag differs from its immutable tenant/version assignment");
    }
    return new Assignment(source, workloadNamespace, storedVersion, storedTag);
  }

  private static boolean transitionRefMatches(
      byte[] transitionBytes,
      byte[] inventoryBytes,
      UUID tenantId,
      UUID gameInstanceId,
      String sessionId) {
    if (!java.util.Arrays.equals(transitionBytes, inventoryBytes)) {
      return false;
    }
    UUID parsedSession;
    try {
      parsedSession = UUID.fromString(sessionId);
    } catch (IllegalArgumentException malformed) {
      return false;
    }
    if (!parsedSession.toString().equals(sessionId) || NIL_UUID.equals(parsedSession)) {
      return false;
    }
    CanonicalGameplayBindingRef persisted =
        CanonicalGameplayBindingRef.fromExactBytes(transitionBytes);
    return persisted.equals(CanonicalGameplayBindingRef.of(tenantId, gameInstanceId, sessionId));
  }

  private static String tagFor(UUID tenantId, int version) {
    return "g" + version + "-" + tenantId.toString().replace("-", "");
  }

  static void requireCanonicalTag(String tag) {
    if (tag == null
        || tag.length() > 44
        || !tag.matches("g[1-9][0-9]{0,9}-[0-9a-f]{32}")
        || tag.indexOf('{') >= 0
        || tag.indexOf('}') >= 0
        || tag.indexOf(':') >= 0) {
      throw conflict("Tenant gameplay tag is not a bounded canonical opaque value");
    }
  }

  private static UUID requiredUuid(Record row, String name) {
    UUID value = row.get(name, UUID.class);
    if (value == null || NIL_UUID.equals(value)) {
      throw conflict("Persisted canonical target has a missing or nil " + name);
    }
    return value;
  }

  private static String requiredString(Record row, String name) {
    String value = row.get(name, String.class);
    if (value == null || value.isEmpty()) {
      throw conflict("Persisted canonical target is missing " + name);
    }
    return value;
  }

  private static byte[] requiredBytes(Record row, String name) {
    byte[] value = row.get(name, byte[].class);
    if (value == null || value.length == 0) {
      throw conflict("Persisted canonical target is missing " + name);
    }
    return value;
  }

  private static long requiredLong(Record row, String name) {
    Long value = row.get(name, Long.class);
    if (value == null || value < 0L) {
      throw conflict("Persisted canonical target has an invalid " + name);
    }
    return value;
  }

  private static java.math.BigInteger requiredBigInteger(Record row, String name) {
    java.math.BigDecimal value = row.get(name, java.math.BigDecimal.class);
    if (value == null || value.signum() <= 0) {
      throw conflict("Persisted canonical target has an invalid " + name);
    }
    return value.toBigIntegerExact();
  }

  private static void requirePositive(int value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }

  private sealed interface Target permits BindingTarget, BarrierTarget, InitialTarget {
    UUID tenantId();

    UUID accountId();

    UUID namespaceId();
  }

  private record BindingTarget(
      UUID sourceId,
      UUID accountId,
      UUID tenantId,
      UUID namespaceId,
      UUID characterId,
      UUID gameInstanceId,
      String sessionId,
      CanonicalGameplayBindingRef bindingRef)
      implements Target {}

  private record BarrierTarget(
      UUID sourceId, long ordinal, UUID accountId, UUID tenantId, UUID namespaceId)
      implements Target {}

  private record InitialTarget(
      UUID initializerId, UUID originalRequestId, UUID accountId, UUID tenantId, UUID namespaceId)
      implements Target {}

  /** Owner-issued immutable mapping plus the exact SQL source needed by the key builder. */
  public static final class Assignment {
    private final Target source;
    private final String workloadNamespace;
    private final int allocationVersion;
    private final String tenantGameplayTag;

    private Assignment(
        Target source, String workloadNamespace, int allocationVersion, String tenantGameplayTag) {
      this.source = Objects.requireNonNull(source, "source");
      this.workloadNamespace = Objects.requireNonNull(workloadNamespace, "workloadNamespace");
      this.allocationVersion = allocationVersion;
      this.tenantGameplayTag = Objects.requireNonNull(tenantGameplayTag, "tenantGameplayTag");
    }

    public UUID tenantId() {
      return source.tenantId();
    }

    public UUID accountId() {
      return source.accountId();
    }

    public UUID playableStateNamespaceId() {
      return source.namespaceId();
    }

    public int allocationVersion() {
      return allocationVersion;
    }

    public String tenantGameplayTag() {
      return tenantGameplayTag;
    }

    public String workloadNamespace() {
      return workloadNamespace;
    }

    public boolean isBindingTransition() {
      return source instanceof BindingTarget;
    }

    boolean isInitialBarrierTarget() {
      return source instanceof InitialTarget;
    }

    UUID initialBarrierInitializerId() {
      return source instanceof InitialTarget initial ? initial.initializerId() : null;
    }

    UUID initialBarrierOriginalRequestId() {
      return source instanceof InitialTarget initial ? initial.originalRequestId() : null;
    }

    public UUID transitionId() {
      return source instanceof BindingTarget binding ? binding.sourceId() : null;
    }

    public UUID admissionFenceIntentOperationId() {
      return source instanceof BarrierTarget barrier ? barrier.sourceId() : null;
    }

    public Long barrierTargetOrdinal() {
      return source instanceof BarrierTarget barrier ? barrier.ordinal() : null;
    }

    public UUID characterId() {
      return source instanceof BindingTarget binding ? binding.characterId() : null;
    }

    public UUID gameInstanceId() {
      return source instanceof BindingTarget binding ? binding.gameInstanceId() : null;
    }

    public String sessionId() {
      return source instanceof BindingTarget binding ? binding.sessionId() : null;
    }

    public CanonicalGameplayBindingRef bindingRef() {
      return source instanceof BindingTarget binding ? binding.bindingRef() : null;
    }
  }
}
