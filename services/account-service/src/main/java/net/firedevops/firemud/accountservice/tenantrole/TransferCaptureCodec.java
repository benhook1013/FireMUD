package net.firedevops.firemud.accountservice.tenantrole;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;

/**
 * Bounded immutable observations, NOT authenticated transfer permission or current-owner proof. The
 * owner adapter must obtain and cross-check the supplied records in one ordered transaction.
 * Decoding historical bytes never makes them current and does not admit a source mutation.
 */
public final class TransferCaptureCodec {
  private static final int MAGIC = 0x54524331;

  private TransferCaptureCodec() {}

  public record Pair(Member actor, Member target) {
    public Pair {
      Objects.requireNonNull(actor);
      Objects.requireNonNull(target);
      TransferCaptureBounds.orderedAccounts(actor.account(), target.account());
      if (!actor.tenant().equals(target.tenant())
          || !actor.provenance().equals(target.provenance())
          || !actor.issuerId().equals(target.issuerId())
          || actor.currentIssuerGeneration() != target.currentIssuerGeneration()
          || actor.currentTenantGeneration() != target.currentTenantGeneration()) {
        throw invalid();
      }
    }
  }

  /** Fixed fields: no extensible or caller-selected source-kind map. */
  public record Member(
      long numericAccountId,
      long membershipId,
      VerifiedTenantProvenance provenance,
      String issuerId,
      long issuerSourceVersion,
      long accountSourceVersion,
      long tenantSourceVersion,
      long membershipSourceVersion,
      long issuanceFenceSourceVersion,
      long currentIssuerGeneration,
      long currentAccountGeneration,
      long currentTenantGeneration,
      long currentMembershipGeneration,
      long currentIssuanceFence,
      MembershipEvent event) {
    public Member {
      Objects.requireNonNull(provenance);
      Objects.requireNonNull(event);
      if (numericAccountId <= 0
          || membershipId <= 0
          || issuerSourceVersion <= 0
          || accountSourceVersion <= 0
          || tenantSourceVersion <= 0
          || membershipSourceVersion <= 0
          || issuanceFenceSourceVersion <= 0
          || currentIssuerGeneration <= 0
          || currentAccountGeneration <= 0
          || currentTenantGeneration <= 0
          || currentMembershipGeneration <= 0
          || currentIssuanceFence <= 0
          || issuerId == null
          || issuerId.isBlank()) throw invalid();
      if (issuerId.length() > TransferCaptureBounds.MAX_COMPONENT_BYTES
          || event.canonicalJson().length() > TransferCaptureBounds.MAX_COMPONENT_BYTES)
        throw invalid();
      bounded(issuerId.getBytes(StandardCharsets.UTF_8));
      bounded(event.canonicalJsonUtf8());
      if (!"ACTIVE".equals(event.membershipLifecycleState())
          || currentMembershipGeneration != number(event.membershipAuthorityGeneration())
          || event.roles().size() > TransferCaptureBounds.MAX_ROLES_PER_MEMBER) throw invalid();
      for (String role : event.roles()) {
        if (role.getBytes(StandardCharsets.UTF_8).length
            > TransferCaptureBounds.MAX_ROLE_UTF8_BYTES) throw invalid();
      }
    }

    public UUID account() {
      return UUID.fromString(event.accountId());
    }

    public UUID tenant() {
      return UUID.fromString(event.tenantId());
    }
  }

  /**
   * Checks agreement, not provenance: none of these caller-supplied values authenticates itself.
   */
  public static Member fromObserved(
      RoleSnapshot roles,
      PairAuthority pair,
      CompositeSnapshot authority,
      String membershipLifecycle,
      MembershipEvent event) {
    Objects.requireNonNull(roles);
    Objects.requireNonNull(pair);
    Objects.requireNonNull(authority);
    Objects.requireNonNull(event);
    UUID account = UUID.fromString(event.accountId());
    UUID tenant = UUID.fromString(event.tenantId());
    if (!"ACTIVE".equals(membershipLifecycle)
        || !pair.membershipExists()
        || !account.equals(pair.accountUuid())
        || !tenant.equals(pair.tenantUuid())
        || !account.equals(roles.accountUuid())
        || !tenant.equals(roles.tenantUuid())
        || !pair.provenance().equals(roles.tenantProvenance())
        || roles.snapshotVersion() != pair.membershipVersion()
        || !roles.roles().equals(event.roles())
        || pair.membershipVersion() != number(event.membershipVersion().get(tenant.toString()))
        || pair.membershipAuthorityGeneration() != number(event.membershipAuthorityGeneration())
        || pair.eventSequence() != number(event.outboxSequence())
        || !pair.eventId().equals(event.eventId())
        || !pair.eventDigest().equals(event.eventDigest())
        || pair.lastTransitionInvalidated() != event.callerBoundAuthorityInvalidated())
      throw invalid();
    if (authority.tenants().size() != 1 || authority.memberships().size() != 1) throw invalid();
    ScopeState tenantState = authority.tenants().getFirst();
    ScopeState memberState = authority.memberships().getFirst();
    if (authority.issuer().scope().kind()
            != net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                .ScopeKind.ISSUER
        || authority.account().scope().kind()
            != net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                .ScopeKind.ACCOUNT
        || tenantState.scope().kind()
            != net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                .ScopeKind.TENANT
        || memberState.scope().kind()
            != net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
                .ScopeKind.MEMBERSHIP
        || !account.equals(authority.account().scope().accountId())
        || !account.equals(authority.issuanceFence().accountId())
        || !tenant.equals(tenantState.scope().tenantId())
        || !account.equals(memberState.scope().accountId())
        || !tenant.equals(memberState.scope().tenantId())
        || memberState.generation() != pair.membershipAuthorityGeneration()
        || !authority.issuanceFence().equals(authority.account().issuanceFence())
        || !authority.issuanceFence().equals(memberState.issuanceFence())) throw invalid();
    return new Member(
        roles.accountId(),
        roles.membershipId(),
        pair.provenance(),
        authority.issuer().scope().issuerId(),
        authority.issuer().sourceVersion(),
        authority.account().sourceVersion(),
        tenantState.sourceVersion(),
        memberState.sourceVersion(),
        authority.issuanceFence().sourceVersion(),
        authority.issuer().generation(),
        authority.account().generation(),
        tenantState.generation(),
        memberState.generation(),
        authority.issuanceFence().value(),
        event);
  }

  public static byte[] encode(Pair pair) {
    Objects.requireNonNull(pair);
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(MAGIC);
      write(out, pair.actor());
      write(out, pair.target());
      return TransferCaptureBounds.copyEnvelope(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static Pair decode(byte[] encoded) {
    byte[] stable = TransferCaptureBounds.copyEnvelope(encoded);
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(stable));
      if (in.readInt() != MAGIC) throw invalid();
      Pair pair = new Pair(read(in), read(in));
      if (in.available() != 0 || !Arrays.equals(stable, encode(pair))) throw invalid();
      return pair;
    } catch (IOException | IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Invalid bounded transfer capture", malformed);
    }
  }

  private static void write(DataOutputStream out, Member m) throws IOException {
    out.writeLong(m.numericAccountId());
    out.writeLong(m.membershipId());
    text(out, m.provenance().kind().name());
    out.writeLong(m.provenance().legacyTenantId() == null ? 0 : m.provenance().legacyTenantId());
    text(out, m.provenance().sourceOperationId().toString());
    text(out, m.provenance().digest());
    text(out, m.issuerId());
    out.writeLong(m.issuerSourceVersion());
    out.writeLong(m.accountSourceVersion());
    out.writeLong(m.tenantSourceVersion());
    out.writeLong(m.membershipSourceVersion());
    out.writeLong(m.issuanceFenceSourceVersion());
    out.writeLong(m.currentIssuerGeneration());
    out.writeLong(m.currentAccountGeneration());
    out.writeLong(m.currentTenantGeneration());
    out.writeLong(m.currentMembershipGeneration());
    out.writeLong(m.currentIssuanceFence());
    bytes(out, m.event().canonicalJsonUtf8());
  }

  private static Member read(DataInputStream in) throws IOException {
    long account = in.readLong(), membership = in.readLong();
    TenantProvenanceKind kind = TenantProvenanceKind.valueOf(text(in));
    long legacy = in.readLong();
    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            legacy == 0 ? null : legacy, kind, UUID.fromString(text(in)), text(in));
    String issuer = text(in);
    long is = in.readLong(),
        as = in.readLong(),
        ts = in.readLong(),
        ms = in.readLong(),
        fs = in.readLong();
    long ig = in.readLong(),
        ag = in.readLong(),
        tg = in.readLong(),
        mg = in.readLong(),
        fv = in.readLong();
    MembershipEvent event = MembershipAuthorityEventV1Codec.verify(text(in));
    return new Member(
        account, membership, provenance, issuer, is, as, ts, ms, fs, ig, ag, tg, mg, fv, event);
  }

  private static void text(DataOutputStream out, String s) throws IOException {
    bytes(out, s.getBytes(StandardCharsets.UTF_8));
  }

  private static void bytes(DataOutputStream out, byte[] b) throws IOException {
    bounded(b);
    out.writeInt(b.length);
    out.write(b);
  }

  private static String text(DataInputStream in) throws IOException {
    int length = TransferCaptureBounds.componentLength(in.readInt(), in.available());
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static void bounded(byte[] b) {
    TransferCaptureBounds.componentLength(b.length, TransferCaptureBounds.MAX_COMPONENT_BYTES);
  }

  private static long number(String s) {
    try {
      long n = Long.parseLong(s);
      if (n <= 0) throw invalid();
      return n;
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Capture evidence mismatch");
  }
}
