package unit.net.firedevops.firemud.accountservice.tenantrole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.PairAuthority;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountTenantRoleOperationRepository;
import net.firedevops.firemud.accountservice.tenantrole.TransferCaptureCodec;
import net.firedevops.firemud.accountservice.tenantrole.TransferCaptureCodec.Member;
import net.firedevops.firemud.accountservice.tenantrole.TransferCaptureCodec.Pair;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import org.junit.jupiter.api.Test;

/** Controlled records test consistency/encoding only, never actual owner provenance. */
class TransferCaptureCodecTest {
  private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final VerifiedTenantProvenance PROVENANCE =
      new VerifiedTenantProvenance(
          null,
          TenantProvenanceKind.FRESH_GAME_DESIGN,
          UUID.fromString("20000000-0000-0000-0000-000000000001"),
          "sha256:" + "a".repeat(64));

  @Test
  void exactTwoMembersRoundTripWithoutRefreshingOrGrantingAuthority() {
    Pair pair = new Pair(member(UUID.randomUUID()), member(UUID.randomUUID()));
    byte[] bytes = TransferCaptureCodec.encode(pair);
    Pair read = TransferCaptureCodec.decode(bytes);
    assertThat(TransferCaptureCodec.encode(read)).containsExactly(bytes);
    assertThat(read.actor().event().canonicalJsonUtf8())
        .containsExactly(pair.actor().event().canonicalJsonUtf8());
    assertThat(read.actor().provenance()).isEqualTo(PROVENANCE);
    assertThat(read.actor().membershipSourceVersion()).isEqualTo(4);
    assertThat(read.target().issuanceFenceSourceVersion()).isEqualTo(5);
  }

  @Test
  void malformedLengthTruncationTrailingAndWrongMagicRefuse() {
    byte[] valid =
        TransferCaptureCodec.encode(new Pair(member(UUID.randomUUID()), member(UUID.randomUUID())));
    for (int length : new int[] {-1, 0, 16385, Integer.MAX_VALUE}) {
      byte[] changed = valid.clone();
      ByteBuffer.wrap(changed).putInt(20, length);
      assertThatThrownBy(() -> TransferCaptureCodec.decode(changed))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (int length : new int[] {0, 3, valid.length - 1}) {
      assertThatThrownBy(() -> TransferCaptureCodec.decode(Arrays.copyOf(valid, length)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
    assertThatThrownBy(() -> TransferCaptureCodec.decode(trailing))
        .isInstanceOf(IllegalArgumentException.class);
    valid[0] ^= 1;
    assertThatThrownBy(() -> TransferCaptureCodec.decode(valid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodedMembershipGenerationMustAgreeWithItsCapturedEvent() {
    byte[] valid =
        TransferCaptureCodec.encode(new Pair(member(UUID.randomUUID()), member(UUID.randomUUID())));
    ByteBuffer fields = ByteBuffer.wrap(valid);
    fields.position(20);
    skipText(fields); // provenance kind
    fields.getLong(); // legacy tenant
    skipText(fields); // provenance operation
    skipText(fields); // provenance digest
    skipText(fields); // issuer
    fields.position(fields.position() + 8 * 8); // five source versions and three generations
    fields.putLong(2);
    assertThatThrownBy(() -> TransferCaptureCodec.decode(valid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void skipText(ByteBuffer fields) {
    int length = fields.getInt();
    fields.position(fields.position() + length);
  }

  @Test
  void duplicateMemberAndWrongCurrentEventOrLifecycleRefuse() {
    Member member = member(UUID.randomUUID());
    assertThatThrownBy(() -> new Pair(member, member)).isInstanceOf(IllegalArgumentException.class);
    UUID account = UUID.randomUUID();
    MembershipEvent event = event(account);
    var roles = roles(account);
    var pair = pair(account, event);
    var authority = authority(account);
    assertThatThrownBy(
            () -> TransferCaptureCodec.fromObserved(roles, pair, authority, "LEFT", event))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                TransferCaptureCodec.fromObserved(
                    roles, pair, authority, "ACTIVE", event(UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class);
    var stale = new RoleSnapshot(1, null, 2, 2, List.of("player"), account, TENANT, PROVENANCE);
    assertThatThrownBy(
            () -> TransferCaptureCodec.fromObserved(stale, pair, authority, "ACTIVE", event))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongFence =
        new CompositeSnapshot(
            authority.issuer(),
            authority.account(),
            authority.tenants(),
            authority.memberships(),
            new IssuanceFence(account, 2, 5));
    assertThatThrownBy(
            () -> TransferCaptureCodec.fromObserved(roles, pair, wrongFence, "ACTIVE", event))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void currentAccountAndIssuerRotationDoesNotRewriteHistoricalMembershipEvent() {
    UUID account = UUID.randomUUID();
    var old = authority(account);
    var fence = new IssuanceFence(account, 2, 6);
    var now =
        new CompositeSnapshot(
            new ScopeState(AuthorityScope.issuer("issuer"), 2, 2, null),
            new ScopeState(AuthorityScope.account(account), 2, 3, fence),
            old.tenants(),
            List.of(new ScopeState(AuthorityScope.membership(account, TENANT), 1, 4, fence)),
            fence);
    var event = event(account);
    var captured =
        TransferCaptureCodec.fromObserved(
            roles(account), pair(account, event), now, "ACTIVE", event);
    assertThat(captured.currentIssuerGeneration()).isEqualTo(2);
    assertThat(captured.currentAccountGeneration()).isEqualTo(2);
    assertThat(captured.currentIssuanceFence()).isEqualTo(2);
    assertThat(captured.event().authorityTuple().accountAuthorityGeneration()).isEqualTo("1");
    assertThat(captured.event().issuanceFence()).isEqualTo("1");
  }

  private static Member member(UUID account) {
    MembershipEvent event = event(account);
    return TransferCaptureCodec.fromObserved(
        roles(account), pair(account, event), authority(account), "ACTIVE", event);
  }

  private static RoleSnapshot roles(UUID account) {
    return new RoleSnapshot(1, null, 2, 1, List.of("player"), account, TENANT, PROVENANCE);
  }

  private static PairAuthority pair(UUID account, MembershipEvent event) {
    return new PairAuthority(
        account, TENANT, PROVENANCE, true, 1, 1, 1, event.eventId(), event.eventDigest(), false);
  }

  private static CompositeSnapshot authority(UUID account) {
    IssuanceFence fence = new IssuanceFence(account, 1, 5);
    return new CompositeSnapshot(
        new ScopeState(AuthorityScope.issuer("issuer"), 1, 1, null),
        new ScopeState(AuthorityScope.account(account), 1, 2, fence),
        List.of(new ScopeState(AuthorityScope.tenant(TENANT), 1, 3, null)),
        List.of(new ScopeState(AuthorityScope.membership(account, TENANT), 1, 4, fence)),
        fence);
  }

  private static MembershipEvent event(UUID account) {
    String request =
        AccountTenantRoleOperationRepository.eventRequestId(UUID.randomUUID(), account);
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", "1");
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of(TENANT.toString(), "1"));
    tuple.put("membershipAuthorityGeneration", Map.of(TENANT.toString(), "1"));
    tuple.put("privateRealmGrantVersions", List.of());
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    p.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    p.put(
        "eventId",
        UUID.nameUUIDFromBytes(
                (MembershipAuthorityEventV1Codec.SCHEMA_VERSION + ":" + request)
                    .getBytes(StandardCharsets.UTF_8))
            .toString());
    p.put("requestId", request);
    p.put("outboxStreamKey", AccountTenantRoleOperationRepository.eventStreamKey(account, TENANT));
    p.put("outboxSequence", "1");
    p.put("sourceScope", "membership/" + account + "/" + TENANT);
    p.put("accountId", account.toString());
    p.put("tenantId", TENANT.toString());
    p.put("membershipExists", true);
    p.put("membershipLifecycleState", "ACTIVE");
    p.put("membershipVersion", Map.of(TENANT.toString(), "1"));
    p.put("membershipAuthorityGeneration", "1");
    p.put("authorityTuple", tuple);
    p.put("issuanceFence", "1");
    p.put("roles", List.of("player"));
    p.put("gameplayAdmissionAllowed", true);
    p.put("callerBoundAuthorityInvalidated", false);
    return MembershipAuthorityEventV1Codec.seal(p);
  }
}
