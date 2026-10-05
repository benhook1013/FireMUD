package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository.SourceChange;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable request, result, and current-event evidence for unregistered tenant-role mutations. */
@Repository
public class AccountTenantRoleOperationRepository {
  private static final String OPERATIONS = "account_tenant_role_operations";
  private static final String MEMBERS = "account_tenant_role_operation_members";
  private static final int SCHEMA_VERSION = 1;
  private static final String AUDIT_EVENT_TYPE = "ACCOUNT_TENANT_ROLE_CHANGED";

  private final DSLContext dsl;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The constructor validates injected internal collaborators; it neither publishes this "
              + "nor invokes overridable methods.")
  public AccountTenantRoleOperationRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
  }

  /** Claims a request, or returns its exact committed result or durable pending intent. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Claim claim(Request request) {
    requireWriteOwnerTransaction();
    Objects.requireNonNull(request, "Tenant-role request is required");
    byte[] requestPayload = request.payload();
    String requestDigest = AccountTenantRoleMutationDigest.sha256(requestPayload);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + OPERATIONS
                + " (request_id, schema_version, actor_account_uuid, tenant_uuid, "
                + "target_account_uuid, action, expected_actor_membership_version, "
                + "expected_target_membership_version, request_payload, request_digest, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS') ON CONFLICT DO NOTHING",
            request.requestId(),
            SCHEMA_VERSION,
            request.actorAccountUuid(),
            request.tenantUuid(),
            request.targetAccountUuid(),
            request.action().name(),
            request.expectedActorMembershipVersion(),
            request.expectedTargetMembershipVersion(),
            requestPayload,
            requestDigest);
    if (inserted == 1) {
      return new Claim(true, Optional.empty());
    }
    if (inserted != 0) {
      throw new IllegalStateException("Tenant-role request claim was not singular");
    }

    OperationEvidence existing =
        readOperationForUpdate(request.requestId())
            .orElseThrow(
                () -> new IllegalStateException("Conflicting tenant-role request disappeared"));
    if (!existing.request().equals(request)
        || !Arrays.equals(existing.requestPayload(), requestPayload)
        || !existing.requestDigest().equals(requestDigest)) {
      throw new OperationConflictException(
          "Tenant-role request ID was reused with changed immutable input");
    }
    if (!"COMMITTED".equals(existing.status()) && findSourceChangeForUpdate(request).isEmpty()) {
      throw new IllegalStateException(
          "Incomplete tenant-role request has no original source intent");
    }
    return new Claim(false, Optional.of(existing));
  }

  /** Binds the original V57 vector to the request before its WAITING transaction may commit. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void captureSourceChange(Request request, SourceChange change) {
    requireWriteOwnerTransaction();
    requireSourceRequest(request, change);
    int updated =
        dsl.execute(
            "UPDATE "
                + OPERATIONS
                + " SET source_change_binding = ? "
                + "WHERE request_id = ? AND status = 'IN_PROGRESS' "
                + "AND request_payload = ? AND source_change_binding IS NULL",
            change.canonicalBytes(),
            request.requestId(),
            request.payload());
    if (updated != 1
        || !Arrays.equals(
            findSourceChangeForUpdate(request).orElseThrow().canonicalBytes(),
            change.canonicalBytes())) {
      throw new IllegalStateException("Tenant-role original source capture differs");
    }
  }

  /** Returns the immutable original capture, including after process or response loss. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<SourceChange> findSourceChangeForUpdate(Request request) {
    requireWriteOwnerTransaction();
    Record row =
        dsl.fetchOne(
            "SELECT request_payload, source_change_binding FROM "
                + OPERATIONS
                + " WHERE request_id = ? FOR UPDATE",
            request.requestId());
    if (row == null
        || !Arrays.equals(row.get("request_payload", byte[].class), request.payload())) {
      throw new OperationConflictException("Tenant-role source request binding differs");
    }
    byte[] stored = row.get("source_change_binding", byte[].class);
    if (stored == null) {
      return Optional.empty();
    }
    SourceChange change = SourceChange.fromStored(stored);
    requireSourceRequest(request, change);
    return Optional.of(change);
  }

  private static void requireSourceRequest(Request request, SourceChange change) {
    if (!request.requestId().equals(change.changeId())
        || !Arrays.equals(request.payload(), change.mutation())) {
      throw new OperationConflictException(
          "Tenant-role original source intent differs from request");
    }
  }

  /** Completes a claimed request only after all mutation, event, and audit writes read back. */
  @Transactional(propagation = Propagation.MANDATORY)
  public OperationEvidence complete(
      Request request,
      AuditEvidence audit,
      List<AccountTenantRoleMutationDigest.MemberResult> members) {
    requireWriteOwnerTransaction();
    Objects.requireNonNull(request, "Tenant-role request is required");
    Objects.requireNonNull(audit, "Tenant-role audit evidence is required");
    List<AccountTenantRoleMutationDigest.MemberResult> orderedMembers =
        members == null ? List.of() : members.stream().sorted().toList();
    requireResultShape(request, orderedMembers);

    String requestDigest = AccountTenantRoleMutationDigest.sha256(request.payload());
    byte[] resultPayload =
        AccountTenantRoleMutationDigest.resultBytes(
            request.requestId(),
            requestDigest,
            request.tenantUuid(),
            audit.auditEventId(),
            audit.eventType(),
            audit.occurredAt().toString(),
            audit.payloadDigest(),
            audit.payload(),
            orderedMembers);
    String resultDigest = AccountTenantRoleMutationDigest.sha256(resultPayload);
    int inserted = 0;
    for (AccountTenantRoleMutationDigest.MemberResult member : orderedMembers) {
      inserted +=
          dsl.execute(
              "INSERT INTO "
                  + MEMBERS
                  + " (request_id, account_uuid, tenant_uuid, membership_version, "
                  + "membership_authority_generation, outbox_stream_key, event_request_id, "
                  + "event_sequence, event_id, event_digest, caller_bound_authority_invalidated, "
                  + "event_payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
              request.requestId(),
              member.accountUuid(),
              member.tenantUuid(),
              member.membershipVersion(),
              member.membershipAuthorityGeneration(),
              eventStreamKey(member.accountUuid(), member.tenantUuid()),
              member.eventRequestId(),
              member.eventSequence(),
              member.eventId(),
              member.eventDigest(),
              member.callerBoundAuthorityInvalidated(),
              member.eventPayload());
    }
    if (inserted != orderedMembers.size()) {
      throw new IllegalStateException("Tenant-role member evidence write count differs");
    }

    int updated =
        dsl.execute(
            "UPDATE "
                + OPERATIONS
                + " SET status = 'COMMITTED', audit_event_id = ?, audit_event_type = ?, "
                + "audit_occurred_at = CAST(? AS TIMESTAMPTZ), audit_payload_digest = ?, "
                + "audit_payload = ?, "
                + "result_payload = ?, result_digest = ? "
                + "WHERE request_id = ? AND status = 'IN_PROGRESS' AND request_digest = ?",
            audit.auditEventId(),
            audit.eventType(),
            audit.occurredAt().toString(),
            audit.payloadDigest(),
            audit.payload(),
            resultPayload,
            resultDigest,
            request.requestId(),
            requestDigest);
    if (updated != 1) {
      throw new IllegalStateException("Tenant-role operation completion was stale or ambiguous");
    }

    OperationEvidence exact =
        readOperationForUpdate(request.requestId())
            .orElseThrow(() -> new IllegalStateException("Tenant-role result readback is absent"));
    requireExactCompletedResult(
        request, requestDigest, resultPayload, resultDigest, audit, orderedMembers, exact);
    return exact;
  }

  /** Resolves the exact immutable operation which emitted a current membership event. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<CurrentMemberEvidence> findCurrentMemberEvidenceForUpdate(
      UUID accountUuid, UUID tenantUuid, String eventRequestId) {
    requireWriteOwnerTransaction();
    if (accountUuid == null
        || tenantUuid == null
        || eventRequestId == null
        || eventRequestId.isBlank()) {
      throw new IllegalArgumentException("Exact current tenant-role event identity is required");
    }
    Record row =
        dsl.fetchOne(
            "SELECT request_id FROM "
                + MEMBERS
                + " WHERE account_uuid = ? AND tenant_uuid = ? AND event_request_id = ? FOR UPDATE",
            accountUuid,
            tenantUuid,
            eventRequestId);
    if (row == null) {
      return Optional.empty();
    }
    UUID requestId = row.get("request_id", UUID.class);
    OperationEvidence operation =
        readOperationForUpdate(requestId)
            .orElseThrow(
                () -> new IllegalStateException("Tenant-role member row has no operation header"));
    if (!"COMMITTED".equals(operation.status())) {
      throw new IllegalStateException("Current tenant-role operation is not committed");
    }
    AccountTenantRoleMutationDigest.MemberResult member =
        operation.members().stream()
            .filter(
                candidate ->
                    candidate.accountUuid().equals(accountUuid)
                        && candidate.eventRequestId().equals(eventRequestId))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Current tenant-role event is absent from its exact operation result"));
    return Optional.of(new CurrentMemberEvidence(operation, member));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OperationEvidence> findForUpdate(UUID requestId) {
    requireWriteOwnerTransaction();
    Objects.requireNonNull(requestId, "Tenant-role request ID is required");
    return readOperationForUpdate(requestId);
  }

  private Optional<OperationEvidence> readOperationForUpdate(UUID requestId) {
    Record row =
        dsl.fetchOne(
            "SELECT request_id, schema_version, actor_account_uuid, tenant_uuid, "
                + "target_account_uuid, action, expected_actor_membership_version, "
                + "expected_target_membership_version, request_payload, request_digest, status, "
                + "audit_event_id, audit_event_type, audit_occurred_at, audit_payload_digest, "
                + "audit_payload, result_payload, result_digest "
                + "FROM "
                + OPERATIONS
                + " WHERE request_id = ? FOR UPDATE",
            requestId);
    if (row == null) {
      return Optional.empty();
    }
    Short schemaVersion = row.get("schema_version", Short.class);
    if (schemaVersion == null || schemaVersion != SCHEMA_VERSION) {
      throw new IllegalStateException("Tenant-role request schema version is unsupported");
    }
    Request request =
        new Request(
            row.get("request_id", UUID.class),
            row.get("actor_account_uuid", UUID.class),
            row.get("tenant_uuid", UUID.class),
            row.get("target_account_uuid", UUID.class),
            Action.parse(row.get("action", String.class)),
            requiredPositive(row, "expected_actor_membership_version"),
            requiredPositive(row, "expected_target_membership_version"));
    byte[] requestPayload = requiredBytes(row, "request_payload");
    String requestDigest = requiredText(row, "request_digest");
    if (!Arrays.equals(request.payload(), requestPayload)
        || !AccountTenantRoleMutationDigest.sha256(requestPayload).equals(requestDigest)) {
      throw new IllegalStateException("Tenant-role request bytes or digest are contradictory");
    }

    AuditEvidence audit = null;
    byte[] resultPayload = null;
    String resultDigest = null;
    String status = requiredText(row, "status");
    if ("COMMITTED".equals(status)) {
      audit =
          new AuditEvidence(
              row.get("audit_event_id", UUID.class),
              requiredText(row, "audit_event_type"),
              requiredInstant(row, "audit_occurred_at"),
              requiredText(row, "audit_payload_digest"),
              requiredBytes(row, "audit_payload"));
      resultPayload = requiredBytes(row, "result_payload");
      resultDigest = requiredText(row, "result_digest");
    } else if (!"IN_PROGRESS".equals(status)) {
      throw new IllegalStateException("Tenant-role request status is unsupported");
    }

    List<AccountTenantRoleMutationDigest.MemberResult> members = readMembers(requestId);
    if ("COMMITTED".equals(status)) {
      requireResultShape(request, members);
      byte[] expectedResult =
          AccountTenantRoleMutationDigest.resultBytes(
              request.requestId(),
              requestDigest,
              request.tenantUuid(),
              audit.auditEventId(),
              audit.eventType(),
              audit.occurredAt().toString(),
              audit.payloadDigest(),
              audit.payload(),
              members);
      if (!Arrays.equals(expectedResult, resultPayload)
          || !AccountTenantRoleMutationDigest.sha256(resultPayload).equals(resultDigest)) {
        throw new IllegalStateException("Tenant-role immutable result bytes or digest differ");
      }
    } else if (!members.isEmpty()) {
      throw new IllegalStateException("Incomplete tenant-role operation has member results");
    }

    return Optional.of(
        new OperationEvidence(
            request,
            requestPayload,
            requestDigest,
            status,
            audit,
            resultPayload,
            resultDigest,
            members));
  }

  private List<AccountTenantRoleMutationDigest.MemberResult> readMembers(UUID requestId) {
    List<AccountTenantRoleMutationDigest.MemberResult> members = new ArrayList<>();
    for (Record row :
        dsl.fetch(
            "SELECT account_uuid, tenant_uuid, membership_version, membership_authority_generation, "
                + "event_sequence, event_request_id, event_id, event_digest, "
                + "caller_bound_authority_invalidated, event_payload FROM "
                + MEMBERS
                + " WHERE request_id = ? ORDER BY account_uuid",
            requestId)) {
      members.add(
          new AccountTenantRoleMutationDigest.MemberResult(
              row.get("account_uuid", UUID.class),
              row.get("tenant_uuid", UUID.class),
              requiredPositive(row, "membership_version"),
              requiredPositive(row, "membership_authority_generation"),
              requiredPositive(row, "event_sequence"),
              requiredText(row, "event_request_id"),
              requiredText(row, "event_id"),
              requiredText(row, "event_digest"),
              Boolean.TRUE.equals(row.get("caller_bound_authority_invalidated", Boolean.class)),
              requiredBytes(row, "event_payload")));
    }
    return List.copyOf(members);
  }

  private static void requireResultShape(
      Request request, List<AccountTenantRoleMutationDigest.MemberResult> members) {
    int expectedCount = request.action() == Action.TRANSFER_TENANT_ADMIN ? 2 : 1;
    if (members.size() != expectedCount) {
      throw new IllegalStateException("Tenant-role operation member result count differs");
    }
    for (AccountTenantRoleMutationDigest.MemberResult member : members) {
      if (!request.tenantUuid().equals(member.tenantUuid())) {
        throw new IllegalStateException("Tenant-role member result belongs to another tenant");
      }
      String expectedRequestId = eventRequestId(request.requestId(), member.accountUuid());
      if (!expectedRequestId.equals(member.eventRequestId())) {
        throw new IllegalStateException("Tenant-role member event request ID differs");
      }
      boolean actorMember = member.accountUuid().equals(request.actorAccountUuid());
      boolean targetMember = member.accountUuid().equals(request.targetAccountUuid());
      if ((!actorMember && !targetMember)
          || (request.action() == Action.TRANSFER_TENANT_ADMIN
              && (member.callerBoundAuthorityInvalidated() != actorMember))
          || (request.action() == Action.GRANT_DESIGNER && member.callerBoundAuthorityInvalidated())
          || (request.action() == Action.REVOKE_DESIGNER
              && !member.callerBoundAuthorityInvalidated())) {
        throw new IllegalStateException(
            "Tenant-role member invalidation or identity differs from action");
      }
      long expectedVersion =
          increment(
              actorMember
                  ? request.expectedActorMembershipVersion()
                  : request.expectedTargetMembershipVersion());
      if (member.membershipVersion() != expectedVersion) {
        throw new IllegalStateException(
            "Tenant-role membership version did not advance exactly once");
      }
      requireExactEvent(request, member);
    }
    if (request.action() != Action.TRANSFER_TENANT_ADMIN
        && !members.getFirst().accountUuid().equals(request.targetAccountUuid())) {
      throw new IllegalStateException("Designer operation must change only its target membership");
    }
  }

  private static void requireExactEvent(
      Request request, AccountTenantRoleMutationDigest.MemberResult member) {
    String expectedRequestId = eventRequestId(request.requestId(), member.accountUuid());
    String expectedEventId =
        UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + expectedRequestId)
                    .getBytes(StandardCharsets.UTF_8))
            .toString();
    MembershipAuthorityEventV1Codec.MembershipEvent event;
    try {
      event =
          MembershipAuthorityEventV1Codec.verify(
              new String(member.eventPayload(), StandardCharsets.UTF_8));
    } catch (RuntimeException malformed) {
      throw new IllegalStateException("Tenant-role journal member event is malformed", malformed);
    }
    if (!expectedRequestId.equals(member.eventRequestId())
        || !expectedEventId.equals(member.eventId())
        || !eventStreamKey(member.accountUuid(), request.tenantUuid())
            .equals(event.outboxStreamKey())
        || !expectedRequestId.equals(event.requestId())
        || !expectedEventId.equals(event.eventId())
        || !member.eventDigest().equals(event.eventDigest())
        || !Long.toString(member.eventSequence()).equals(event.outboxSequence())
        || !member.accountUuid().toString().equals(event.accountId())
        || !request.tenantUuid().toString().equals(event.tenantId())
        || !Map.of(request.tenantUuid().toString(), Long.toString(member.membershipVersion()))
            .equals(event.membershipVersion())
        || !Long.toString(member.membershipAuthorityGeneration())
            .equals(event.membershipAuthorityGeneration())
        || member.callerBoundAuthorityInvalidated() != event.callerBoundAuthorityInvalidated()
        || !"ACTIVE".equals(event.membershipLifecycleState())
        || !event.gameplayAdmissionAllowed()
        || !event.roles().contains("player")) {
      throw new IllegalStateException(
          "Tenant-role journal member event differs from result identity");
    }
  }

  private static long increment(long value) {
    try {
      return Math.addExact(value, 1L);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "Tenant-role expected membership version is exhausted", overflow);
    }
  }

  private static void requireExactCompletedResult(
      Request request,
      String requestDigest,
      byte[] resultPayload,
      String resultDigest,
      AuditEvidence audit,
      List<AccountTenantRoleMutationDigest.MemberResult> members,
      OperationEvidence actual) {
    if (!request.equals(actual.request())
        || !requestDigest.equals(actual.requestDigest())
        || !"COMMITTED".equals(actual.status())
        || !Arrays.equals(resultPayload, actual.resultPayload())
        || !resultDigest.equals(actual.resultDigest())
        || !auditEquals(audit, actual.audit())
        || !membersEqual(members, actual.members())) {
      throw new IllegalStateException("Tenant-role result readback differs from its exact commit");
    }
  }

  private static boolean auditEquals(AuditEvidence expected, AuditEvidence actual) {
    return actual != null
        && expected.auditEventId().equals(actual.auditEventId())
        && expected.eventType().equals(actual.eventType())
        && expected.occurredAt().equals(actual.occurredAt())
        && expected.payloadDigest().equals(actual.payloadDigest())
        && Arrays.equals(expected.payload(), actual.payload());
  }

  private static boolean membersEqual(
      List<AccountTenantRoleMutationDigest.MemberResult> expected,
      List<AccountTenantRoleMutationDigest.MemberResult> actual) {
    if (expected.size() != actual.size()) {
      return false;
    }
    for (int index = 0; index < expected.size(); index++) {
      AccountTenantRoleMutationDigest.MemberResult left = expected.get(index);
      AccountTenantRoleMutationDigest.MemberResult right = actual.get(index);
      if (!left.accountUuid().equals(right.accountUuid())
          || !left.tenantUuid().equals(right.tenantUuid())
          || left.membershipVersion() != right.membershipVersion()
          || left.membershipAuthorityGeneration() != right.membershipAuthorityGeneration()
          || left.eventSequence() != right.eventSequence()
          || !left.eventRequestId().equals(right.eventRequestId())
          || !left.eventId().equals(right.eventId())
          || !left.eventDigest().equals(right.eventDigest())
          || left.callerBoundAuthorityInvalidated() != right.callerBoundAuthorityInvalidated()
          || !Arrays.equals(left.eventPayload(), right.eventPayload())) {
        return false;
      }
    }
    return true;
  }

  private static long requiredPositive(Record row, String name) {
    Long value = row.get(name, Long.class);
    if (value == null || value <= 0L) {
      throw new IllegalStateException("Tenant-role " + name + " is malformed");
    }
    return value;
  }

  private static String requiredText(Record row, String name) {
    String value = row.get(name, String.class);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Tenant-role " + name + " is missing");
    }
    return value;
  }

  private static byte[] requiredBytes(Record row, String name) {
    byte[] value = row.get(name, byte[].class);
    if (value == null) {
      throw new IllegalStateException("Tenant-role " + name + " is missing");
    }
    return value;
  }

  private static Instant requiredInstant(Record row, String name) {
    Object value = row.get(name);
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof java.time.OffsetDateTime offsetDateTime) {
      return offsetDateTime.toInstant();
    }
    throw new IllegalStateException("Tenant-role " + name + " is malformed");
  }

  private void requireWriteOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Tenant-role operation access requires an active Account write transaction");
    }
  }

  public static String eventRequestId(UUID requestId, UUID accountUuid) {
    if (requestId == null || accountUuid == null) {
      throw new IllegalArgumentException("Tenant-role event request identity is required");
    }
    return "tenant-role:" + requestId + ":" + accountUuid;
  }

  public static String eventStreamKey(UUID accountUuid, UUID tenantUuid) {
    if (accountUuid == null || tenantUuid == null) {
      throw new IllegalArgumentException("Tenant-role event stream identity is required");
    }
    return "account:auth-authority:v1:membership/" + accountUuid + "/" + tenantUuid;
  }

  public record Request(
      UUID requestId,
      UUID actorAccountUuid,
      UUID tenantUuid,
      UUID targetAccountUuid,
      Action action,
      long expectedActorMembershipVersion,
      long expectedTargetMembershipVersion) {
    public Request {
      requireCanonical(requestId, "request ID");
      requireCanonical(actorAccountUuid, "actor Account UUID");
      requireCanonical(tenantUuid, "tenant UUID");
      requireCanonical(targetAccountUuid, "target Account UUID");
      Objects.requireNonNull(action, "tenant-role action is required");
      if (expectedActorMembershipVersion <= 0 || expectedTargetMembershipVersion <= 0) {
        throw new IllegalArgumentException("Expected membership versions must be positive");
      }
      if (action == Action.TRANSFER_TENANT_ADMIN && actorAccountUuid.equals(targetAccountUuid)) {
        throw new IllegalArgumentException("Tenant-admin transfer requires distinct members");
      }
      if (actorAccountUuid.equals(targetAccountUuid)
          && expectedActorMembershipVersion != expectedTargetMembershipVersion) {
        throw new IllegalArgumentException("Self-targeted role request versions must match");
      }
    }

    public byte[] payload() {
      return AccountTenantRoleMutationDigest.requestBytes(
          requestId,
          actorAccountUuid,
          tenantUuid,
          targetAccountUuid,
          action.name(),
          expectedActorMembershipVersion,
          expectedTargetMembershipVersion);
    }

    private static void requireCanonical(UUID value, String label) {
      if (value == null || value.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
      }
    }
  }

  public enum Action {
    GRANT_DESIGNER,
    REVOKE_DESIGNER,
    TRANSFER_TENANT_ADMIN;

    private static Action parse(String value) {
      try {
        return Action.valueOf(value);
      } catch (RuntimeException malformed) {
        throw new IllegalStateException("Tenant-role action is unsupported", malformed);
      }
    }
  }

  public record AuditEvidence(
      UUID auditEventId,
      String eventType,
      Instant occurredAt,
      String payloadDigest,
      byte[] payload) {
    public AuditEvidence {
      Objects.requireNonNull(auditEventId, "audit event ID is required");
      Objects.requireNonNull(eventType, "audit event type is required");
      Objects.requireNonNull(occurredAt, "audit occurrence time is required");
      Objects.requireNonNull(payloadDigest, "audit payload digest is required");
      Objects.requireNonNull(payload, "audit payload is required");
      payload = payload.clone();
      if (!AUDIT_EVENT_TYPE.equals(eventType)
          || !payloadDigest.matches("sha256:[0-9a-f]{64}")
          || !payloadDigest.equals(
              net.firedevops.firemud.accountservice.dto.AccountAuditDigest.ofPayload(
                  new String(payload, StandardCharsets.UTF_8)))) {
        throw new IllegalArgumentException("Tenant-role audit evidence digest or type is invalid");
      }
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }

  public record OperationEvidence(
      Request request,
      byte[] requestPayload,
      String requestDigest,
      String status,
      AuditEvidence audit,
      byte[] resultPayload,
      String resultDigest,
      List<AccountTenantRoleMutationDigest.MemberResult> members) {
    public OperationEvidence {
      requestPayload = requestPayload.clone();
      resultPayload = resultPayload == null ? null : resultPayload.clone();
      members = List.copyOf(members);
    }

    @Override
    public byte[] requestPayload() {
      return requestPayload.clone();
    }

    @Override
    public byte[] resultPayload() {
      return resultPayload == null ? null : resultPayload.clone();
    }

    /** Pending is a retained intent only; it carries no event, result, or authorization. */
    public boolean pending() {
      return "IN_PROGRESS".equals(status);
    }
  }

  public record Claim(boolean claimed, Optional<OperationEvidence> replay) {
    public Claim {
      replay = Objects.requireNonNull(replay, "replay evidence presence is required");
      if (claimed == replay.isPresent()) {
        throw new IllegalArgumentException("Tenant-role claim result has contradictory state");
      }
    }
  }

  public record CurrentMemberEvidence(
      OperationEvidence operation, AccountTenantRoleMutationDigest.MemberResult member) {}

  public static final class OperationConflictException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public OperationConflictException(String message) {
      super(message);
    }
  }
}
