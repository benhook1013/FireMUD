package integration.net.firedevops.firemud.accountservice.charactercreate;

import static org.assertj.core.api.Assertions.*;

import com.google.protobuf.ByteString;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import net.firedevops.firemud.account.v1.*;
import net.firedevops.firemud.entitymanagement.v1.*;
import net.firedevops.firemud.gamesession.v1.CharacterCreateTargetProtection;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.v1.WorldCharacterCreateLifecycleProtection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL storage/binding proof with stipulated source snapshots, not authenticated owners.
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountCharacterCreateStoragePostgresIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> postgres =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @BeforeAll
  static void migrate() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("public")
        .defaultSchema("public")
        .placeholders(Map.of("serviceSchema", "public"))
        .locations("classpath:db/migration", "classpath:db/migration/saga")
        .table("flyway_schema_history_account_service")
        .target("1048.1")
        .load()
        .migrate();
    String existing = "ACCOUNT:" + UUID.randomUUID();
    String before;
    try (var c =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      try (var p =
          c.prepareStatement(
              "INSERT INTO account_draft_authorization_source_locks(source_key) VALUES (?)")) {
        p.setString(1, existing);
        p.executeUpdate();
      }
      try (var p =
          c.prepareStatement(
              "SELECT to_jsonb(s)::text || '/' || xmin::text FROM account_draft_authorization_source_locks s WHERE source_key=?")) {
        p.setString(1, existing);
        try (var result = p.executeQuery()) {
          assertThat(result.next()).isTrue();
          before = result.getString(1);
        }
      }
    }
    var current =
        Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .schemas("public")
            .defaultSchema("public")
            .placeholders(Map.of("serviceSchema", "public"))
            .locations("classpath:db/migration", "classpath:db/migration/saga")
            .table("flyway_schema_history_account_service")
            .load();
    current.migrate();
    current.validate();
    try (var c =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        var p =
            c.prepareStatement(
                "SELECT to_jsonb(s)::text || '/' || xmin::text FROM account_draft_authorization_source_locks s WHERE source_key=?")) {
      p.setString(1, existing);
      try (var result = p.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo(before);
      }
    }
  }

  private Connection connection() throws SQLException {
    return DriverManager.getConnection(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }

  private void execute(Connection c, String sql, Object... args) throws SQLException {
    try (var p = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
      p.execute();
    }
  }

  private record Fixture(
      UUID account,
      UUID tenant,
      UUID operation,
      UUID protection,
      long fence,
      byte[] membership,
      byte[] entitlement,
      ReadOriginalCharacterCreateResponse original,
      ReadOriginalCharacterCreateProtectionResponse proof) {}

  private Fixture fixture(Connection c) throws SQLException {
    UUID account = UUID.randomUUID(),
        tenant = UUID.randomUUID(),
        operation = UUID.randomUUID(),
        request = UUID.randomUUID(),
        protection = UUID.randomUUID();
    byte[] member =
        ("{\"accountId\":\""
                + account
                + "\",\"tenantId\":\""
                + tenant
                + "\",\"issuerId\":\"firemud-account-service\",\"realmGrantApplicability\":\"NOT_APPLICABLE_PUBLIC_PRODUCTION\",\"membershipExists\":true,\"membershipLifecycleState\":\"ACTIVE\",\"gameplayAdmissionAllowed\":true}")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] entitlement = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        action = {1},
        source = {2};
    String digest = "a".repeat(64);
    execute(
        c,
        "INSERT INTO account_character_create_intents(operation_id,target_namespace,account_uuid,tenant_uuid,request_id,connect_scope_id,purpose,action_bytes,mutation_digest,original_source_readback) VALUES (?,'proof',?,?,?,'scope','CHARACTER_CREATE',?,?,?)",
        operation,
        account,
        tenant,
        request,
        action,
        digest,
        source);
    execute(
        c,
        "INSERT INTO account_character_create_protections(protection_id,operation_id,membership_snapshot,entitlement_snapshot) VALUES (?,?,?,?)",
        protection,
        operation,
        member,
        entitlement);
    long fence;
    Instant acquired;
    try (var p =
        c.prepareStatement(
            "SELECT protection_fence,acquired_at FROM account_character_create_protections WHERE protection_id=?")) {
      p.setObject(1, protection);
      try (var rs = p.executeQuery()) {
        assertThat(rs.next()).isTrue();
        fence = rs.getLong(1);
        acquired = rs.getTimestamp(2).toInstant();
      }
    }
    var original =
        ReadOriginalCharacterCreateResponse.newBuilder()
            .setTargetNamespace("proof")
            .setOperationId(operation.toString())
            .setCreateCharacterRequestId(request.toString())
            .setConnectScopeId("scope")
            .setAuthenticatedAccountId(account.toString())
            .setPurpose("CHARACTER_CREATE")
            .setOriginalAction(ByteString.copyFrom(action))
            .setMutationDigest(digest)
            .setOriginalSourceReadback(ByteString.copyFrom(source))
            .build();
    var proof =
        ReadOriginalCharacterCreateProtectionResponse.newBuilder()
            .setOriginal(original)
            .setAccountProtectionId(protection.toString())
            .setAccountProtectionFence(fence)
            .setAcquiredAt(acquired.toString())
            .setOriginalMembershipSnapshot(ByteString.copyFrom(member))
            .setOriginalEntitlementSnapshot(ByteString.copyFrom(entitlement))
            .build();
    return new Fixture(
        account, tenant, operation, protection, fence, member, entitlement, original, proof);
  }

  private EntityCharacterCreateTerminal terminal(Fixture f) {
    var gs =
        CharacterCreateTargetProtection.newBuilder()
            .setAccountProtection(f.proof())
            .setGameSessionProtectionId(UUID.randomUUID().toString())
            .setGameSessionProtectionFence(1)
            .setAcquiredAt(Instant.now().toString())
            .setOriginalTargetEvidence(ByteString.copyFromUtf8("controlled"))
            .build();
    var world =
        WorldCharacterCreateLifecycleProtection.newBuilder()
            .setAccountProtection(f.proof())
            .setWorldProtectionId(UUID.randomUUID().toString())
            .setWorldProtectionFence(1)
            .setAcquiredAt(Instant.now().toString())
            .setOriginalLifecycleEvidence(ByteString.copyFromUtf8("controlled"))
            .build();
    var selector =
        ReadOriginalCharacterCreateRequest.newBuilder()
            .setTargetNamespace("proof")
            .setOperationId(f.operation().toString())
            .setMutationDigest(f.original().getMutationDigest());
    var request =
        CommitProtectedCharacterCreateRequest.newBuilder()
            .setOriginal(selector)
            .setAccountProtection(
                CharacterCreateProtectionReference.newBuilder()
                    .setProtectionId(f.protection().toString())
                    .setProtectionFence(f.fence()))
            .setGameSessionProtection(
                CharacterCreateProtectionReference.newBuilder()
                    .setProtectionId(gs.getGameSessionProtectionId())
                    .setProtectionFence(1))
            .setWorldProtection(
                CharacterCreateProtectionReference.newBuilder()
                    .setProtectionId(world.getWorldProtectionId())
                    .setProtectionFence(1));
    return EntityCharacterCreateTerminal.newBuilder()
        .setOriginalRequest(request)
        .setAccountOriginal(f.original())
        .setOriginalAccountProtection(f.proof().toByteString())
        .setOriginalGameSessionProtection(gs.toByteString())
        .setOriginalWorldProtection(world.toByteString())
        .setEntityIntakeRequestId(UUID.randomUUID().toString())
        .setOutcome(EntityCharacterCreateOutcome.ENTITY_CHARACTER_CREATE_OUTCOME_COMMITTED)
        .setCharacterId(UUID.randomUUID().toString())
        .setTerminalId(UUID.randomUUID().toString())
        .setTerminalFence(1)
        .setCompletedAt(Instant.now().toString())
        .setEntitySourceReceiptDigest("sha256:" + "b".repeat(64))
        .build();
  }

  private void settle(Connection c, Fixture f, EntityCharacterCreateTerminal t, byte[] bytes)
      throws SQLException {
    execute(
        c,
        "INSERT INTO account_character_create_settlements(protection_id,protection_fence,operation_id,original_membership_snapshot,original_entitlement_snapshot,original_action,original_source_readback,entity_terminal_id,entity_terminal_fence,outcome,terminal_bytes,terminal_digest) VALUES (?,?,?,?,?,?,?,?,?, 'COMMITTED',?, 'sha256:' || encode(sha256(?),'hex'))",
        f.protection(),
        f.fence(),
        f.operation(),
        f.membership(),
        f.entitlement(),
        f.original().getOriginalAction().toByteArray(),
        f.original().getOriginalSourceReadback().toByteArray(),
        UUID.fromString(t.getTerminalId()),
        t.getTerminalFence(),
        bytes,
        bytes);
  }

  private void sourceWriteBoundary(Connection c, Fixture f) throws SQLException {
    execute(
        c,
        "SELECT account_control_ui_hold_required_sources(ARRAY[?]::TEXT[])",
        "ACCOUNT:" + f.account());
  }

  @Test
  void unrelatedPendingCreationsCoexistButEachActualSourceWriterRemainsBlocked() throws Exception {
    try (var c = connection()) {
      var a = fixture(c);
      var b = fixture(c);
      assertThat(a.protection()).isNotEqualTo(b.protection());
      assertThatThrownBy(() -> sourceWriteBoundary(c, a))
          .isInstanceOf(SQLException.class)
          .extracting(x -> ((SQLException) x).getSQLState())
          .isEqualTo("55P03");
      assertThatThrownBy(() -> sourceWriteBoundary(c, b))
          .isInstanceOf(SQLException.class)
          .extracting(x -> ((SQLException) x).getSQLState())
          .isEqualTo("55P03");
    }
  }

  @Test
  void malformedOrMismatchedTerminalNeverReleasesSourcesAndExactTerminalDoes() throws Exception {
    try (var c = connection()) {
      var f = fixture(c);
      var t = terminal(f);
      assertThatThrownBy(() -> settle(c, f, t, new byte[] {1})).isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> sourceWriteBoundary(c, f)).isInstanceOf(SQLException.class);
      var wrong = t.toBuilder().setTerminalId(UUID.randomUUID().toString()).build();
      assertThatThrownBy(() -> settle(c, f, t, wrong.toByteArray()))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> sourceWriteBoundary(c, f)).isInstanceOf(SQLException.class);
      settle(c, f, t, t.toByteArray());
      sourceWriteBoundary(c, f);
      assertThatThrownBy(
              () ->
                  execute(
                      c,
                      "DELETE FROM account_character_create_settlements WHERE protection_id=?",
                      f.protection()))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void actualRepositoryAcquiresTwoUnrelatedPendingProtectionsThroughSharedIssuerLock()
      throws Exception {
    var source =
        new org.springframework.jdbc.datasource.DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    var proxy = new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(source);
    var dsl = org.jooq.impl.DSL.using(proxy, org.jooq.SQLDialect.POSTGRES);
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    tx.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    var members =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.accountservice.repository
                .AccountCanonicalRuntimeMembershipSnapshotRepository.class);
    var demos =
        org.mockito.Mockito.mock(
            net.firedevops.firemud.accountservice.repository.AccountDemoTenantEntitlementRepository
                .class);
    var repository =
        new net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreateRepository(
            dsl, members, demos);
    var first =
        net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreateTestActions
            .intent("proof", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(300));
    var second =
        net.firedevops.firemud.accountservice.charactercreate.AccountCharacterCreateTestActions
            .intent("proof", UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(300));
    for (var intent : List.of(first, second)) {
      var member = membership(intent.accountId(), intent.tenantId());
      assertThat(
              net.firedevops.firemud.common.account.authority
                  .RuntimeMembershipAuthorityEvidenceValidator.validate(member))
          .isPresent();
      org.mockito.Mockito.when(members.readCurrent(intent.accountId(), intent.tenantId()))
          .thenReturn(member);
      var demo =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementSnapshot.class);
      var tenant =
          org.mockito.Mockito.mock(
              net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence.class);
      org.mockito.Mockito.when(tenant.targetNamespace()).thenReturn("proof");
      org.mockito.Mockito.when(demo.canonicalTenantId()).thenReturn(intent.tenantId());
      org.mockito.Mockito.when(demo.sourceEvidence()).thenReturn(tenant);
      org.mockito.Mockito.when(demo.gameplayAvailable()).thenReturn(true);
      org.mockito.Mockito.when(demo.allowNewGameplayBindings()).thenReturn(true);
      org.mockito.Mockito.when(demo.status()).thenReturn("ACTIVE");
      org.mockito.Mockito.when(demos.readCurrent(intent.tenantId())).thenReturn(demo);
      tx.executeWithoutResult(status -> repository.retain(intent));
    }
    var a = java.util.Objects.requireNonNull(tx.execute(status -> repository.acquire(first)));
    var b = java.util.Objects.requireNonNull(tx.execute(status -> repository.acquire(second)));
    assertThat(a.protectionId()).isNotEqualTo(b.protectionId());
    assertThat(
            java.util.Objects.requireNonNull(tx.execute(status -> repository.acquire(first)))
                .sameBinding(a))
        .isTrue();
    org.mockito.Mockito.verify(members).readCurrent(first.accountId(), first.tenantId());
    org.mockito.Mockito.verify(members).readCurrent(second.accountId(), second.tenantId());
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status ->
                        dsl.fetch(
                            "SELECT account_control_ui_hold_required_sources(ARRAY[?]::TEXT[])",
                            "ACCOUNT:" + first.accountId())))
        .isInstanceOf(org.jooq.exception.DataAccessException.class);
  }

  private static net.firedevops.firemud.common.account.authority
          .RuntimeMembershipAuthorityEvidenceValidator.Snapshot
      membership(UUID account, UUID tenant) {
    String a = account.toString(), t = tenant.toString();
    String prefix =
        net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
            .EVENT_STREAM_PREFIX;
    String stream = prefix + "membership/" + a + "/" + t;
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", "1");
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of(t, "1"));
    tuple.put("membershipAuthorityGeneration", Map.of(t, "1"));
    tuple.put("privateRealmGrantVersions", List.of());
    Map<String, Object> event = new LinkedHashMap<>();
    event.put(
        "schemaVersion",
        net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
            .SCHEMA_VERSION);
    event.put(
        "eventType",
        net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.EVENT_TYPE);
    event.put("eventId", "membership-event-1");
    event.put("requestId", "membership-request-1");
    event.put("outboxStreamKey", stream);
    event.put("outboxSequence", "1");
    event.put("sourceScope", "membership/" + a + "/" + t);
    event.put("accountId", a);
    event.put("tenantId", t);
    event.put("membershipExists", true);
    event.put("membershipLifecycleState", "ACTIVE");
    event.put("membershipVersion", Map.of(t, "1"));
    event.put("membershipAuthorityGeneration", "1");
    event.put("authorityTuple", tuple);
    event.put("issuanceFence", "1");
    event.put("roles", List.of("player"));
    event.put("gameplayAdmissionAllowed", true);
    event.put("callerBoundAuthorityInvalidated", false);
    var sealed =
        net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.seal(event);
    return new net.firedevops.firemud.common.account.authority
        .RuntimeMembershipAuthorityEvidenceValidator.Snapshot(
        "firemud-account-service",
        a,
        t,
        true,
        "ACTIVE",
        true,
        Map.of(t, "1"),
        "1",
        List.of("player"),
        new net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
            .AuthorityTuple(
            "1",
            "1",
            Map.of(t, "1"),
            Map.of(t, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty()),
        "1",
        List.of(
            new net.firedevops.firemud.common.account.authority
                .RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                prefix + "account/" + a, "0"),
            new net.firedevops.firemud.common.account.authority
                .RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                prefix + "issuer/firemud-account-service", "0"),
            new net.firedevops.firemud.common.account.authority
                .RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(stream, "1"),
            new net.firedevops.firemud.common.account.authority
                .RuntimeMembershipAuthorityEvidenceValidator.Checkpoint(
                prefix + "tenant/" + t, "0")),
        List.of(
            new net.firedevops.firemud.common.account.authority
                .RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence(
                stream, "1", sealed.eventId(), sealed.eventDigest(), sealed.canonicalJson())));
  }
}
