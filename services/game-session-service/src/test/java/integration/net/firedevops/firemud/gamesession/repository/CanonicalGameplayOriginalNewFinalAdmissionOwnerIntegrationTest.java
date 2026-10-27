package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.gamesession.CanonicalGameplayAdmissionDecisionReadGrpcCodec.Candidate;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.test.TestContainerImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Native component composition: actual GS SQL/candidate/account-member/issuer/region/final owners.
 * Account lease, signer/JWKS, physical Account OPEN, World/Entity RPC response DTOs, incarnation,
 * launch and empty legacy cohort are controlled fixtures. Real mTLS clients mint private Entity
 * references; real region owners mint grants and ACKs. This does not exercise Account callbacks,
 * INITIAL protected observation, the full coordinator, external source custody or activation.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class CanonicalGameplayOriginalNewFinalAdmissionOwnerIntegrationTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final String PRINCIPAL = "gamesession_coord_app";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis()).withExposedPorts(6379);

  @TempDir Path directory;

  private RedisClient administrator;
  private RedisClient ownerClient;
  private StatefulRedisConnection<byte[], byte[]> adminConnection;
  private RedisCommands<byte[], byte[]> admin;
  private CanonicalGameplayAdmissionAbortCompositionFixture fixture;
  private CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources sources;
  private CanonicalGameplayBindingInventoryRepository inventory;
  private CanonicalGameplayTenantGameplayTagRepository tags;
  private CanonicalGameplayAdmissionDecision decision;
  private CanonicalGameplayOriginalNewCandidateRedisOwner.Installation installation;
  private RedisScriptCatalog catalog;

  @BeforeEach
  void setUp() throws Exception {
    administrator = client(null, null);
    adminConnection = administrator.connect(ByteArrayCodec.INSTANCE);
    admin = adminConnection.sync();
    admin.flushdb();
    String password = UUID.randomUUID().toString();
    admin.aclSetuser(
        PRINCIPAL,
        new AclSetuserArgs()
            .reset()
            .on()
            .addPassword(password)
            .keyPattern("session:game:*")
            .keyPattern("tick:*")
            .keyPattern("tick-executor-lease:*")
            .addCommand(CommandType.HELLO)
            .addCommand(CommandType.PING)
            .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
            .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
            .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
            .addCommand(CommandType.EVALSHA)
            .addCommand(CommandType.TYPE)
            .addCommand(CommandType.PTTL)
            .addCommand(CommandType.GET)
            .addCommand(CommandType.SET)
            .addCommand(CommandType.SADD)
            .addCommand(CommandType.SISMEMBER)
            .addCommand(CommandType.HGET)
            .addCommand(CommandType.HGETALL)
            .addCommand(CommandType.HSET)
            .addCommand(CommandType.HLEN)
            .addCommand(CommandType.PEXPIRE)
            .addCommand(CommandType.TIME));
    ownerClient = client(PRINCIPAL, password);
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    String jdbcUrl = POSTGRES.getJdbcUrl();
    dataSource.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=public");
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    fixture =
        new CanonicalGameplayAdmissionAbortCompositionFixture(dataSource, candidate(), ownerClient);
    inventory = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
    tags = new CanonicalGameplayTenantGameplayTagRepository(fixture.dsl(), "test", 1);
    catalog = RedisScriptCatalog.loadInstalled(getClass().getClassLoader());
    // Allocate test PKI and start controlled source servers before the short lease starts.
    sources =
        new CanonicalGameplayOriginalNewFinalAdmissionTestFixture.ControlledRegionSources(
            fixture.dsl(), fixture.originalPreparedDecisionWithControlledAccountLease(), directory);
    decision = fixture.originalPreparedDecisionWithControlledAccountLease();
    installation = candidateOwner(inventory).installOnce(decision);
    fixture.acknowledgeOriginalIssuer();
    new CanonicalGameplayAccountMemberRepairOwner(fixture.dsl(), ownerClient, catalog)
        .repair(fixture.transitionId());
  }

  @AfterEach
  void tearDown() throws Exception {
    try {
      if (sources != null) sources.close();
    } finally {
      try {
        if (fixture != null) fixture.close();
      } finally {
        if (adminConnection != null) adminConnection.close();
        if (ownerClient != null) ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
        if (administrator != null) administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      }
    }
  }

  @Test
  void sameNativeCandidateFinalizesCommitsReadsAndRecoversExactTimesAndRows() throws Exception {
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(source, template, accountOpen, issuerOpen, deadline())) {
      assertThat(credential.boundJti().toString()).isNotEqualTo(credential.initialLoginJti());
      assertThat(new String(template.finalCasTemplateBytes(), StandardCharsets.UTF_8))
          .doesNotContain(credential.jwt())
          .contains("__ORIGINAL_NEW_ADMISSION_AT_MS__");
      assertThat(admin.get(ascii(installation.sessionKey())))
          .containsExactly(actual.exactCanonicalBytes());
      try (var provisionalReplay =
          finalOwner()
              .finalizeOriginalNewOnce(
                  inventory.readOriginalNewFinalAdmissionSource(decision),
                  template,
                  accountOpen,
                  issuerOpen,
                  deadline())) {
        assertThat(provisionalReplay.exactCanonicalBytes())
            .containsExactly(actual.exactCanonicalBytes());
        assertThat(provisionalReplay.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
        assertThat(provisionalReplay.continuityExpiresAtMillis())
            .isEqualTo(actual.continuityExpiresAtMillis());
      }
      String beforeCommitRows = sqlImage();
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isInstanceOf(RuntimeException.class);
      assertThat(sqlImage()).isEqualTo(beforeCommitRows);
      assertThat(inventory.commitOriginalNewFinalAdmission(actual).status())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      // Inspect the production cohort query independently of the migration-test projections.
      // The real committed receipt must remain material in the captured transition source.
      var persistedReceipt =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT original_new_final_session_sha256, original_new_admission_at_ms,"
                      + " original_new_continuity_expires_at_ms, original_new_session_duration_ms"
                      + " FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                  fixture.transitionId());
      assertThat(persistedReceipt.get("original_new_final_session_sha256", String.class))
          .isEqualTo(actual.finalSessionSha256());
      assertThat(persistedReceipt.get("original_new_admission_at_ms", Long.class))
          .isEqualTo(actual.admissionAtMillis());
      assertThat(persistedReceipt.get("original_new_continuity_expires_at_ms", Long.class))
          .isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(persistedReceipt.get("original_new_session_duration_ms", Long.class))
          .isEqualTo(actual.canonicalSessionDurationMillis());
      var revision =
          fixture
              .dsl()
              .fetchSingle(
                  "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                      + " WHERE singleton_id = 1")
              .get(0, java.math.BigDecimal.class);
      var cohortTransition =
          fixture
              .dsl()
              .fetch(
                  CanonicalGameplayIssuerCohortSql.cohortSql(),
                  CanonicalGameplayIssuerCohortSql.cohortBindings(
                      decision.candidate().issuerId(), revision))
              .stream()
              .filter(
                  row ->
                      "TRANSITION".equals(row.get("source_kind", String.class))
                          && fixture
                              .transitionId()
                              .toString()
                              .equals(row.get("source_key", String.class)))
              .findFirst()
              .orElseThrow();
      var capturedTransition =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(cohortTransition.get("source_row_json", String.class));
      for (String field :
          List.of(
              "original_new_final_session_sha256",
              "original_new_admission_at_ms",
              "original_new_continuity_expires_at_ms",
              "original_new_session_duration_ms")) {
        assertThat(capturedTransition.hasNonNull(field))
            .as("material final receipt field %s", field)
            .isTrue();
        assertThat(capturedTransition.get(field).asText())
            .isEqualTo(persistedReceipt.get(field).toString());
      }
      var first = reader().readActualOriginalFinalAdmission(credential.selector(), "test");
      assertThat(first.admissionAtMillis()).isEqualTo(actual.admissionAtMillis());
      assertThat(first.continuityExpiresAtMillis()).isEqualTo(actual.continuityExpiresAtMillis());
      assertThat(first.canonicalSessionSha256()).isEqualTo(actual.finalSessionSha256());
      assertThat(first.actualAnchors().rebindHandleSha256())
          .isEqualTo(credential.selector().rebindHandleSha256());
      String rows = sqlImage();
      Map<String, String> physical = physicalImage();
      assertThatThrownBy(
              () ->
                  reader()
                      .readActualOriginalFinalAdmission(credential.wrongTokenSelector(), "test"))
          .isInstanceOf(RuntimeException.class);
      assertThat(sqlImage()).isEqualTo(rows);
      assertThat(physicalImage()).isEqualTo(physical);
      var recoveredInventory = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
      var recoveredSource = recoveredInventory.readOriginalNewFinalAdmissionSource(decision);
      assertThat(recoveredSource.transitionStatus())
          .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      assertThat(recoveredSource.finalReceipt()).isPresent();
      try (var replay =
          finalOwner()
              .finalizeOriginalNewOnce(
                  recoveredSource, template, accountOpen, issuerOpen, deadline())) {
        assertThat(replay.exactCanonicalBytes()).containsExactly(actual.exactCanonicalBytes());
        assertThat(recoveredInventory.commitOriginalNewFinalAdmission(replay).status())
            .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
      }
      assertThat(reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isEqualTo(first);
      assertThat(sqlImage()).isEqualTo(rows);
      assertThat(physicalImage()).isEqualTo(physical);
    }
  }

  @Test
  void genuineAccountAndIssuerAcknowledgementsCannotSubstituteForMissingRegionAcknowledgement()
      throws Exception {
    String rows = sqlImage();
    Map<String, String> physical = physicalImage();
    assertThatThrownBy(() -> inventory.readOriginalNewFinalAdmissionSource(decision))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    var recovered = new CanonicalGameplayBindingInventoryRepository(fixture.dsl());
    assertThatThrownBy(() -> recovered.readOriginalNewFinalAdmissionSource(decision))
        .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
    assertThat(sqlImage()).isEqualTo(rows);
    assertThat(physicalImage()).isEqualTo(physical);
    bridge();
    assertThat(recovered.readOriginalNewFinalAdmissionSource(decision).transitionStatus())
        .isEqualTo(CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL);
  }

  @Test
  void missingOrChangedIndexesAndBarriersNeverRecreateCommittedPostimagesOrExtendTimes()
      throws Exception {
    bridge();
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(source, template, accountOpen, issuerOpen, deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      var committed = inventory.readOriginalNewFinalAdmissionSource(decision);
      byte[] finalBytes = actual.exactCanonicalBytes();
      String rows = sqlImage();
      for (String key :
          List.of(
              installation.characterIndexKey(),
              installation.accountTenantIndexKey(),
              installation.tenantIndexKey(),
              accountOpen.key(),
              issuerKey(issuerOpen))) {
        byte[] retained = admin.dump(ascii(key));
        admin.del(ascii(key));
        assertThatThrownBy(
                () ->
                    finalOwner()
                        .finalizeOriginalNewOnce(
                            committed, template, accountOpen, issuerOpen, deadline()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(admin.exists(ascii(key))).isZero();
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
        assertThat(sqlImage()).isEqualTo(rows);
        admin.restore(ascii(key), 0L, retained);
      }
      admin.hset(ascii(accountOpen.key()), ascii("state"), ascii("CLOSED"));
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          committed, template, accountOpen, issuerOpen, deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(admin.hget(ascii(accountOpen.key()), ascii("state")))
          .containsExactly(ascii("CLOSED"));
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(finalBytes);
      assertThat(sqlImage()).isEqualTo(rows);
      admin.flushdb();
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          committed, template, accountOpen, issuerOpen, deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isInstanceOf(RuntimeException.class);
      assertThat(admin.dbsize()).isZero();
      assertThat(sqlImage()).isEqualTo(rows);
    }
  }

  @Test
  void provisionalFinalCasRefusesMissingIndexesAndChangedBarriersWithoutAdmission()
      throws Exception {
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template()) {
      String rows = sqlImage();
      byte[] candidateBytes = installation.exactCandidateBytes();
      for (String key :
          List.of(
              installation.characterIndexKey(),
              installation.accountTenantIndexKey(),
              installation.tenantIndexKey(),
              accountOpen.key(),
              issuerKey(issuerOpen))) {
        byte[] retained = admin.dump(ascii(key));
        admin.del(ascii(key));
        assertThatThrownBy(
                () ->
                    finalOwner()
                        .finalizeOriginalNewOnce(
                            source, template, accountOpen, issuerOpen, deadline()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(admin.exists(ascii(key))).isZero();
        assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidateBytes);
        assertThat(sqlImage()).isEqualTo(rows);
        admin.restore(ascii(key), 0L, retained);
      }
      admin.hset(ascii(issuerKey(issuerOpen)), ascii("state"), ascii("CLOSED"));
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          source, template, accountOpen, issuerOpen, deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThat(admin.hget(ascii(issuerKey(issuerOpen)), ascii("state")))
          .containsExactly(ascii("CLOSED"));
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(candidateBytes);
      assertThat(sqlImage()).isEqualTo(rows);
      fixture.moveIssuerSourceCheckpoint();
      assertThatThrownBy(this::issuerOpen).isInstanceOf(RuntimeException.class);
      assertThat(sqlImage()).isEqualTo(rows);
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isInstanceOf(RuntimeException.class);
    }
  }

  @Test
  void changedPersistedSessionCannotRecoverAgainstTheActualCommittedReceipt() throws Exception {
    bridge();
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    var accountOpen = controlledAccountOpen();
    var issuerOpen = issuerOpen();
    try (var credential =
            new CanonicalGameplayOriginalNewFinalAdmissionTestFixture(source.candidate(), CLOCK);
        var template = credential.template();
        var actual =
            finalOwner()
                .finalizeOriginalNewOnce(source, template, accountOpen, issuerOpen, deadline())) {
      inventory.commitOriginalNewFinalAdmission(actual);
      var committed =
          new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
              .readOriginalNewFinalAdmissionSource(decision);
      String rows = sqlImage();
      var json = new com.fasterxml.jackson.databind.ObjectMapper();
      var changed =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              json.readTree(actual.exactCanonicalBytes());
      changed.put("admissionAt", Long.toString(actual.admissionAtMillis() + 1L));
      changed.put(
          "continuityBindingExpiresAt", Long.toString(actual.continuityExpiresAtMillis() + 1L));
      byte[] changedBytes =
          net.firedevops.firemud.common.json.Rfc8785CanonicalJson.canonicalizeUtf8(
              json.writeValueAsString(changed));
      admin.set(ascii(installation.sessionKey()), changedBytes);
      assertThatThrownBy(
              () ->
                  finalOwner()
                      .finalizeOriginalNewOnce(
                          committed, template, accountOpen, issuerOpen, deadline()))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () -> reader().readActualOriginalFinalAdmission(credential.selector(), "test"))
          .isInstanceOf(RuntimeException.class);
      assertThat(admin.get(ascii(installation.sessionKey()))).containsExactly(changedBytes);
      assertThat(sqlImage()).isEqualTo(rows);
      admin.set(ascii(installation.sessionKey()), actual.exactCanonicalBytes());
      assertThat(
              reader()
                  .readActualOriginalFinalAdmission(credential.selector(), "test")
                  .admissionAtMillis())
          .isEqualTo(actual.admissionAtMillis());
      fixture.stopRuntime();
      String stoppedRows = sqlImage();
      assertThatThrownBy(
              () ->
                  new CanonicalGameplayBindingInventoryRepository(fixture.dsl())
                      .readOriginalNewFinalAdmissionSource(decision))
          .isInstanceOf(CanonicalGameplayBindingInventoryConflictException.class);
      assertThat(sqlImage()).isEqualTo(stoppedRows);
      assertThat(admin.get(ascii(installation.sessionKey())))
          .containsExactly(actual.exactCanonicalBytes());
    }
  }

  private void bridge() {
    var region =
        CanonicalGameplayRegionOwnerCompositionFixture.installExistingOriginalCandidate(
            fixture.dsl(),
            ownerClient,
            fixture.transitionId(),
            sources.request,
            sources.resolver,
            sources.entity);
    assertThat(region.owner().bridge(region.installation()).obligation())
        .isEqualTo(region.acknowledgement().obligation());
  }

  private CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen() {
    var repository =
        new CanonicalGameplayIssuerAdmissionBarrierRepository(
            fixture.dsl(), tags, fixture.currentIssuerSource());
    return new CanonicalGameplayIssuerAdmissionBarrierRedisOwner(
            ownerClient, catalog, repository, fixture.currentIssuerSource())
        .initializeAndRead(fixture.originalOperationId());
  }

  /**
   * Controlled upstream OPEN record and physical hash, without INITIAL owner/currentness claims.
   */
  private CanonicalGameplayAccountInitialOpenProof.CurrentOpen controlledAccountOpen() {
    var binding = decision.candidate();
    var assignment = tags.assignmentForTransition(fixture.transitionId());
    UUID initializer = UUID.randomUUID();
    String incarnation = "sha256:" + "a".repeat(64);
    String key =
        "session:game:account-admission:{"
            + assignment.tenantGameplayTag()
            + "}:"
            + binding.accountId()
            + ":"
            + binding.playableStateNamespaceId();
    Map<String, String> fields =
        Map.of(
            "schema",
            CanonicalGameplayAccountInitialBarrierValue.SCHEMA,
            "state",
            "OPEN",
            "accountId",
            binding.accountId().toString(),
            "tenantId",
            binding.tenantId().toString(),
            "playableStateNamespaceId",
            binding.playableStateNamespaceId().toString(),
            "tenantGameplayTag",
            assignment.tenantGameplayTag(),
            "ownerOperationId",
            initializer.toString(),
            "ownerFenceEpoch",
            "1",
            "incarnationDigest",
            incarnation);
    fields.forEach((field, value) -> admin.hset(ascii(key), ascii(field), ascii(value)));
    long now = CLOCK.millis();
    return new CanonicalGameplayAccountInitialOpenProof.CurrentOpen(
        initializer,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        binding.accountId(),
        binding.tenantId(),
        binding.playableStateNamespaceId(),
        key,
        BigInteger.ONE,
        BigInteger.ONE,
        incarnation,
        incarnation,
        incarnation,
        now,
        now + 60_000L,
        fields);
  }

  private String issuerKey(CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback readback) {
    var value = readback.value();
    return CanonicalGameplayIssuerAdmissionBarrierKey.forTarget(
        value.issuerId(),
        value.tenantId(),
        value.playableStateNamespaceId(),
        value.tenantGameplayTag());
  }

  private CanonicalGameplayOriginalNewCandidateRedisOwner candidateOwner(
      CanonicalGameplayBindingInventoryRepository repository) {
    return CanonicalGameplayOriginalNewCandidateRedisOwnerFactory.create(
        ownerClient, catalog, repository, tags);
  }

  private CanonicalGameplayOriginalNewFinalAdmissionRedisOwner finalOwner() {
    return new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
        ownerClient, catalog, CLOCK, tags);
  }

  private CanonicalGameplayOriginalNewFinalAdmissionReadRepository reader() {
    return new CanonicalGameplayOriginalNewFinalAdmissionReadRepository(
        fixture.dsl(),
        ownerClient,
        new CanonicalGameplayTenantGameplayTagRepository(fixture.dsl(), "test", 1),
        "test",
        CLOCK);
  }

  private String sqlImage() {
    Map<String, List<String>> rows = new LinkedHashMap<>();
    for (String table :
        List.of(
            "game_session_canonical_binding_transition",
            "game_session_canonical_gameplay_binding_inventory",
            "game_session_canonical_issuer_partition_reservation",
            "game_session_canonical_binding_account_index_obligation",
            "game_session_canonical_binding_issuer_index_obligation",
            "game_session_canonical_binding_region_bridge_obligation",
            "game_session_canonical_issuer_binding_local_acknowledgement",
            "game_session_canonical_account_member_repair_operation",
            "game_session_region_runtime_status",
            "game_session_region_timeline_preparation",
            "game_session_canonical_binding_inventory_clock",
            "game_session_canonical_account_coverage_control",
            "game_session_canonical_tenant_gameplay_tag",
            "game_session_canonical_issuer_admission_initializer",
            "game_session_canonical_issuer_admission_initializer_receipt")) {
      rows.put(
          table,
          fixture
              .dsl()
              .fetch(
                  "SELECT to_jsonb(t)::text AS image FROM "
                      + table
                      + " t ORDER BY to_jsonb(t)::text")
              .getValues("image", String.class));
    }
    return rows.toString();
  }

  private Map<String, String> physicalImage() {
    Map<String, String> image = new java.util.TreeMap<>();
    for (byte[] key : admin.keys("session:game:*")) {
      image.put(
          new String(key, StandardCharsets.US_ASCII),
          java.util.Base64.getEncoder().encodeToString(admin.dump(key)));
    }
    return image;
  }

  private static Candidate candidate() {
    return new Candidate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SHARED",
        UUID.randomUUID(),
        1L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        BigInteger.ONE,
        GameSessionAccountDelegationProfile.ISSUER,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.ONE,
        BigInteger.valueOf(64));
  }

  private RedisClient client(String username, String password) {
    var uri =
        RedisURI.Builder.redis(REDIS.getHost(), REDIS.getMappedPort(6379))
            .withTimeout(Duration.ofSeconds(5));
    if (username != null) uri.withAuthentication(username, password);
    var client = RedisClient.create(uri.build());
    client.setOptions(ClientOptions.builder().autoReconnect(false).build());
    return client;
  }

  private static long deadline() {
    return CLOCK.millis() + 10_000L;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }
}
