package net.firedevops.firemud.accountservice.tenantrole;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered fresh-tenant owner observation only. No actor authentication, transfer permission,
 * mutation intent, source change, or admission is produced. Historical decoding cannot replace this
 * owner transaction. Retained tenant provenance is deliberately unsupported in this first slice.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Account owner repositories and DSLContext are explicit internal transaction collaborators, not public data.")
public final class AccountTransferCaptureRepository {
  private final DSLContext dsl;
  private final AccountCanonicalRuntimeMembershipSnapshotRepository current;
  private final AccountAuthorityGenerationRepository generations;
  private final AccountMembershipPairAuthorityRepository pairs;
  private final AccountTenantMembershipRoleSnapshotRepository roles;

  public AccountTransferCaptureRepository(
      DSLContext dsl,
      AccountCanonicalRuntimeMembershipSnapshotRepository current,
      AccountAuthorityGenerationRepository generations,
      AccountMembershipPairAuthorityRepository pairs,
      AccountTenantMembershipRoleSnapshotRepository roles) {
    this.dsl = Objects.requireNonNull(dsl);
    this.current = Objects.requireNonNull(current);
    this.generations = Objects.requireNonNull(generations);
    this.pairs = Objects.requireNonNull(pairs);
    this.roles = Objects.requireNonNull(roles);
  }

  public TransferCaptureCodec.Pair capture(UUID actor, UUID target, UUID tenant) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("Transfer capture requires one writable owner transaction");
    if (tenant == null || tenant.equals(new UUID(0, 0)))
      throw new IllegalArgumentException("Tenant UUID required");
    List<UUID> ordered = TransferCaptureBounds.orderedAccounts(actor, target);
    // All Account owners first, in the SAME UUID-string order as Draft source changes. Never
    // read one member/source/fence and then acquire the other Account owner.
    for (UUID account : ordered) {
      Record row =
          dsl.fetchOne(
              "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", account);
      if (row == null || !account.equals(row.get("account_uuid", UUID.class))) throw unavailable();
    }
    Map<UUID, TransferCaptureCodec.Member> captured = new HashMap<>();
    for (UUID account : ordered) captured.put(account, readMember(account, tenant));
    TransferCaptureCodec.Pair result =
        new TransferCaptureCodec.Pair(captured.get(actor), captured.get(target));
    TransferCaptureCodec.encode(
        result); // Validate the complete bounded representation before return.
    return result;
  }

  private TransferCaptureCodec.Member readMember(UUID account, UUID tenant) {
    var pair =
        pairs
            .readForUpdate(account, tenant)
            .orElseThrow(AccountTransferCaptureRepository::unavailable);
    if (!pair.membershipExists()
        || pair.provenance().kind() != TenantProvenanceKind.FRESH_GAME_DESIGN) throw unavailable();
    Record member =
        dsl.fetchOne(
            "SELECT m.id, m.membership_version, m.lifecycle_state FROM account_tenant_membership m JOIN accounts a ON a.id=m.account_id WHERE a.account_uuid=? AND m.tenant_uuid=? FOR UPDATE OF m",
            account,
            tenant);
    if (member == null || !"ACTIVE".equals(member.get("lifecycle_state", String.class)))
      throw unavailable();
    long membershipId = member.get("id", Long.class);
    Record bounds =
        dsl.fetchOne(
            "SELECT count(*) AS n, coalesce(max(octet_length(role_identifier)),0) AS width FROM account_tenant_membership_role_snapshot_roles WHERE membership_id=?",
            membershipId);
    if (bounds == null
        || bounds.get("n", Long.class) > TransferCaptureBounds.MAX_ROLES_PER_MEMBER
        || bounds.get("width", Integer.class) > TransferCaptureBounds.MAX_ROLE_UTF8_BYTES)
      throw unavailable();
    String stream =
        MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
            + "membership/"
            + account
            + "/"
            + tenant;
    Record size =
        dsl.fetchOne(
            "SELECT octet_length(payload) AS n FROM account_authority_outbox_events WHERE outbox_stream_key=? AND outbox_sequence=?",
            stream,
            pair.eventSequence());
    if (size == null
        || size.get("n", Integer.class) <= 0
        || size.get("n", Integer.class) > TransferCaptureBounds.MAX_COMPONENT_BYTES)
      throw unavailable();
    // Reuse actual owner evidence/provenance/current source/checkpoint verification. It is not
    // caller permission and is intentionally fresh-tenant-only. The two Account locks already hold.
    var snapshot = current.readCurrent(account, tenant);
    if (!snapshot.membershipExists() || !"ACTIVE".equals(snapshot.membershipLifecycleState()))
      throw unavailable();
    var role =
        roles
            .findForCanonicalUpdate(
                account,
                tenant,
                pair.provenance(),
                membershipId,
                member.get("membership_version", Long.class))
            .orElseThrow(AccountTransferCaptureRepository::unavailable);
    var authority =
        generations.readCompositeSnapshot(
            snapshot.issuerId(), account, List.of(tenant), List.of(tenant));
    var evidence =
        snapshot.sourceEvidence().stream().filter(e -> e.outboxStreamKey().equals(stream)).toList();
    if (evidence.size() != 1) throw unavailable();
    var event = MembershipAuthorityEventV1Codec.verify(evidence.getFirst().canonicalEventJson());
    return TransferCaptureCodec.fromObserved(
        role, pair, authority, snapshot.membershipLifecycleState(), event);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Exact current two-member transfer capture is unavailable");
  }
}
