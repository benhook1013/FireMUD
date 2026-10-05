package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.MembershipEvent;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Checkpoint;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.SourceEvidence;
import org.junit.jupiter.api.Test;

class RuntimeMembershipAuthorityEvidenceValidatorTest {
  private static final String ISSUER_ID = "issuer-main";
  private static final String ACCOUNT_ID = "018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a";
  private static final String TENANT_ID = "018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f";
  private static final String STREAM_PREFIX = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;

  @Test
  void verifiesMembershipJoinThenUpstreamAdvancesWithoutRewritingItsHistoricalBytes() {
    Fixture fixture = advancedFixture(false);

    Optional<MembershipEvent> result =
        RuntimeMembershipAuthorityEvidenceValidator.validate(fixture.snapshot());

    assertThat(result).isPresent();
    assertThat(result.orElseThrow().canonicalJson())
        .isEqualTo(fixture.membershipEvent().canonicalJson());
    assertThat(result.orElseThrow().canonicalJson())
        .isEqualTo(
            fixture.snapshot().sourceEvidence().stream()
                .filter(
                    source ->
                        source
                            .outboxStreamKey()
                            .endsWith("membership/" + ACCOUNT_ID + "/" + TENANT_ID))
                .findFirst()
                .orElseThrow()
                .canonicalEventJson());
    assertThat(fixture.membershipEvent().authorityTuple().issuerAuthGeneration()).isEqualTo("1");
    assertThat(fixture.snapshot().authorityTuple().issuerAuthGeneration())
        .isEqualTo("9007199254740993");
    assertThat(fixture.membershipEvent().authorityTuple().accountAuthorityGeneration())
        .isEqualTo("1");
    assertThat(fixture.snapshot().authorityTuple().accountAuthorityGeneration())
        .isEqualTo("18446744073709551616000000000000000001");
  }

  @Test
  void acceptsFixedCountersAndTheDeclaredLogoutAllAccountEvent() {
    Fixture fixture = fixedFixture(true);

    Optional<MembershipEvent> result =
        RuntimeMembershipAuthorityEvidenceValidator.validate(fixture.snapshot());
    assertThat(result).isPresent();
    assertThat(result.orElseThrow().canonicalJson())
        .isEqualTo(fixture.membershipEvent().canonicalJson());
  }

  @Test
  void acceptsAnExplicitMissingNonadmittingMembershipWithPositiveBaselineValues() {
    Snapshot missing = missingSnapshot();

    assertThat(RuntimeMembershipAuthorityEvidenceValidator.validate(missing)).isEmpty();
  }

  @Test
  void rejectsMissingStateThatClaimsAdmissionOrMembership() {
    Snapshot baseline = missingSnapshot();
    Snapshot falselyAdmitting =
        new Snapshot(
            baseline.issuerId(),
            baseline.accountId(),
            baseline.tenantId(),
            true,
            "MISSING",
            true,
            baseline.membershipVersion(),
            baseline.membershipAuthorityGeneration(),
            List.of("player"),
            baseline.authorityTuple(),
            baseline.issuanceFence(),
            baseline.checkpoints(),
            baseline.sourceEvidence());

    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(falselyAdmitting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero checkpoint");
  }

  @Test
  void rejectsCarrierMembershipValuesThatDifferFromTheImmutableEvent() {
    Snapshot baseline = advancedFixture(false).snapshot();
    Snapshot mismatched =
        new Snapshot(
            baseline.issuerId(),
            baseline.accountId(),
            baseline.tenantId(),
            baseline.membershipExists(),
            "INACTIVE",
            false,
            baseline.membershipVersion(),
            baseline.membershipAuthorityGeneration(),
            baseline.roles(),
            baseline.authorityTuple(),
            baseline.issuanceFence(),
            baseline.checkpoints(),
            baseline.sourceEvidence());

    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(mismatched))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("membershipLifecycleState");
  }

  @Test
  void rejectsWellFormedMembershipEventsWhoseUpstreamGenerationOrFenceLeadsCurrent() {
    Snapshot baseline = advancedFixture(false).snapshot();
    AuthorityTuple current = baseline.authorityTuple();
    String memberGeneration = current.membershipAuthorityGeneration().get(TENANT_ID);
    String accountStream = STREAM_PREFIX + "account/" + ACCOUNT_ID;
    String accountSequence = current.accountSecurityCutoff().orElseThrow().outboxSequence();

    List<MembershipEvent> aheadEvents =
        List.of(
            resealedMembership(
                baseline,
                tuple(
                    increment(current.issuerAuthGeneration()),
                    current.accountAuthorityGeneration(),
                    current.tenantAuthorityGeneration().get(TENANT_ID),
                    memberGeneration,
                    current.accountSecurityCutoff()),
                baseline.issuanceFence()),
            resealedMembership(
                baseline,
                tuple(
                    current.issuerAuthGeneration(),
                    increment(current.accountAuthorityGeneration()),
                    current.tenantAuthorityGeneration().get(TENANT_ID),
                    memberGeneration,
                    Optional.of(
                        new AccountSecurityCutoff(
                            increment(current.accountAuthorityGeneration()),
                            accountStream,
                            accountSequence))),
                baseline.issuanceFence()),
            resealedMembership(
                baseline,
                tuple(
                    current.issuerAuthGeneration(),
                    current.accountAuthorityGeneration(),
                    increment(current.tenantAuthorityGeneration().get(TENANT_ID)),
                    memberGeneration,
                    current.accountSecurityCutoff()),
                baseline.issuanceFence()),
            resealedMembership(baseline, current, increment(baseline.issuanceFence())));

    for (MembershipEvent aheadEvent : aheadEvents) {
      // Each case is a valid, freshly sealed event. The validator must reject its authority
      // relationship to the current carrier, not its schema or digest.
      assertThat(MembershipAuthorityEventV1Codec.verify(aheadEvent.canonicalJson()).eventDigest())
          .isEqualTo(aheadEvent.eventDigest());
      assertThatThrownBy(
              () ->
                  RuntimeMembershipAuthorityEvidenceValidator.validate(
                      withMembershipEvent(baseline, aheadEvent)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must not be ahead");
    }
  }

  @Test
  void rejectsPositiveCheckpointsWithoutTheirSourceAndWrongSourceCheckpointSequences() {
    Snapshot baseline = advancedFixture(false).snapshot();
    List<SourceEvidence> missingIssuer = new ArrayList<>(baseline.sourceEvidence());
    missingIssuer.remove(sourceFor(baseline, "issuer/" + ISSUER_ID));

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    withSources(baseline, missingIssuer)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one source for each positive checkpoint");

    String membershipStream = STREAM_PREFIX + "membership/" + ACCOUNT_ID + "/" + TENANT_ID;
    Checkpoint original =
        baseline.checkpoints().stream()
            .filter(checkpoint -> checkpoint.outboxStreamKey().equals(membershipStream))
            .findFirst()
            .orElseThrow();
    Snapshot wrongSequence =
        withCheckpointSequence(baseline, membershipStream, increment(original.outboxSequence()));

    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(wrongSequence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence must equal its exact current checkpoint");
  }

  @Test
  void rejectsExtraTenantAuthorityInTheCurrentCarrier() {
    Snapshot baseline = advancedFixture(false).snapshot();
    AuthorityTuple current = baseline.authorityTuple();
    AuthorityTuple withExtraTenant =
        new AuthorityTuple(
            current.issuerAuthGeneration(),
            current.accountAuthorityGeneration(),
            Map.of(
                TENANT_ID,
                current.tenantAuthorityGeneration().get(TENANT_ID),
                "00000001-0000-0000-0000-000000000000",
                "1"),
            current.membershipAuthorityGeneration(),
            current.privateRealmGrantVersions(),
            current.accountSecurityCutoff(),
            current.tenantBillingCutoff());

    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    withTuple(baseline, withExtraTenant)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantAuthorityGeneration")
        .hasMessageContaining("exactly the current tenantId key");
  }

  @Test
  void validatesPositiveUpstreamEvidenceBeforeReturningAnEmptyMissingMembership() {
    Snapshot baseline = advancedFixture(false).snapshot();
    Snapshot missing = missingMembershipWithUpstream(baseline, false, List.of());

    assertThat(RuntimeMembershipAuthorityEvidenceValidator.validate(missing)).isEmpty();

    Snapshot falselyAdmitting = missingMembershipWithUpstream(baseline, true, List.of("player"));
    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(falselyAdmitting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("zero checkpoint");
  }

  @Test
  void acceptsEqualAndNonRegressingHistoricalAccountCutoffsButRejectsASequenceAhead() {
    Snapshot baseline = advancedFixture(false).snapshot();
    AuthorityTuple current = baseline.authorityTuple();
    String memberGeneration = current.membershipAuthorityGeneration().get(TENANT_ID);
    String currentAccountGeneration = current.accountAuthorityGeneration();
    AccountSecurityCutoff currentCutoff = current.accountSecurityCutoff().orElseThrow();

    MembershipEvent equalCutoffEvent =
        resealedMembership(baseline, current, baseline.issuanceFence());
    assertThat(
            RuntimeMembershipAuthorityEvidenceValidator.validate(
                withMembershipEvent(baseline, equalCutoffEvent)))
        .isPresent();

    String historicalGeneration = decrement(currentAccountGeneration);
    String historicalSequence = decrement(currentCutoff.outboxSequence());
    AuthorityTuple historicalTuple =
        tuple(
            current.issuerAuthGeneration(),
            historicalGeneration,
            current.tenantAuthorityGeneration().get(TENANT_ID),
            memberGeneration,
            Optional.of(
                new AccountSecurityCutoff(
                    historicalGeneration, currentCutoff.outboxStreamKey(), historicalSequence)));
    MembershipEvent historicalEvent =
        resealedMembership(baseline, historicalTuple, decrement(baseline.issuanceFence()));
    assertThat(
            RuntimeMembershipAuthorityEvidenceValidator.validate(
                withMembershipEvent(baseline, historicalEvent)))
        .isPresent();

    AuthorityTuple regressedGenerationButAdvancedSequence =
        tuple(
            current.issuerAuthGeneration(),
            historicalGeneration,
            current.tenantAuthorityGeneration().get(TENANT_ID),
            memberGeneration,
            Optional.of(
                new AccountSecurityCutoff(
                    historicalGeneration,
                    currentCutoff.outboxStreamKey(),
                    increment(currentCutoff.outboxSequence()))));
    MembershipEvent aheadCutoffEvent =
        resealedMembership(
            baseline, regressedGenerationButAdvancedSequence, decrement(baseline.issuanceFence()));
    assertThat(
            MembershipAuthorityEventV1Codec.verify(aheadCutoffEvent.canonicalJson()).eventDigest())
        .isEqualTo(aheadCutoffEvent.eventDigest());
    assertThatThrownBy(
            () ->
                RuntimeMembershipAuthorityEvidenceValidator.validate(
                    withMembershipEvent(baseline, aheadCutoffEvent)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("historical account cutoff sequence")
        .hasMessageContaining("must not be ahead");
  }

  @Test
  void rejectsChangedSourceEventBytesAndCarrierDigest() {
    Snapshot baseline = advancedFixture(false).snapshot();
    SourceEvidence issuerSource = sourceFor(baseline, "issuer/" + ISSUER_ID);
    List<SourceEvidence> changedSources = new ArrayList<>(baseline.sourceEvidence());
    int issuerIndex = changedSources.indexOf(issuerSource);
    changedSources.set(
        issuerIndex,
        new SourceEvidence(
            issuerSource.outboxStreamKey(),
            issuerSource.outboxSequence(),
            issuerSource.eventId(),
            issuerSource.eventDigest(),
            issuerSource.canonicalEventJson().replace("9007199254740993", "9007199254740994")));
    Snapshot changedEvent = withSources(baseline, changedSources);

    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(changedEvent))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventDigest");

    changedSources.set(
        issuerIndex,
        new SourceEvidence(
            issuerSource.outboxStreamKey(),
            issuerSource.outboxSequence(),
            issuerSource.eventId(),
            "sha256:" + "0".repeat(64),
            issuerSource.canonicalEventJson()));
    Snapshot changedCarrier = withSources(baseline, changedSources);
    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(changedCarrier))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("eventDigest");
  }

  @Test
  void rejectsACurrentAccountCutoffThatDoesNotBindTheSuppliedSource() {
    Snapshot baseline = advancedFixture(false).snapshot();
    AuthorityTuple tuple = baseline.authorityTuple();
    AccountSecurityCutoff original = tuple.accountSecurityCutoff().orElseThrow();
    AuthorityTuple wrongCutoff =
        tuple(
            tuple.issuerAuthGeneration(),
            tuple.accountAuthorityGeneration(),
            tuple.tenantAuthorityGeneration().get(TENANT_ID),
            tuple.membershipAuthorityGeneration().get(TENANT_ID),
            Optional.of(
                new AccountSecurityCutoff(
                    original.accountAuthorityGeneration(),
                    original.outboxStreamKey(),
                    "18446744073709551616000000000000000002")));
    Snapshot mismatched = withTuple(baseline, wrongCutoff);

    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(mismatched))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current account cutoff sequence");
  }

  @Test
  void rejectsWrongScopeAndDuplicateSourceEvidence() {
    Snapshot baseline = advancedFixture(false).snapshot();
    var wrongIssuerEvent =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "issuer-event-wrong-scope"),
                Map.entry("requestId", "issuer-request-wrong-scope"),
                Map.entry("issuerId", "another-issuer"),
                Map.entry("sourceScope", "issuer/another-issuer"),
                Map.entry("outboxStreamKey", STREAM_PREFIX + "issuer/another-issuer"),
                Map.entry("outboxSequence", "9007199254740995"),
                Map.entry("issuerAuthGeneration", "9007199254740993"),
                Map.entry("sourceVersion", "2")));
    SourceEvidence expectedIssuer = sourceFor(baseline, "issuer/" + ISSUER_ID);
    List<SourceEvidence> wrongScopeSources = new ArrayList<>(baseline.sourceEvidence());
    wrongScopeSources.set(
        wrongScopeSources.indexOf(expectedIssuer),
        new SourceEvidence(
            expectedIssuer.outboxStreamKey(),
            expectedIssuer.outboxSequence(),
            wrongIssuerEvent.eventId(),
            wrongIssuerEvent.eventDigest(),
            wrongIssuerEvent.canonicalJson()));
    Snapshot wrongScope = withSources(baseline, wrongScopeSources);

    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(wrongScope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source event stream key");

    List<SourceEvidence> duplicateSources = new ArrayList<>(baseline.sourceEvidence());
    duplicateSources.add(0, duplicateSources.get(0));
    Snapshot duplicate = withSources(baseline, duplicateSources);
    assertThatThrownBy(() -> RuntimeMembershipAuthorityEvidenceValidator.validate(duplicate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourceEvidence");
  }

  private static Fixture advancedFixture(boolean logoutAll) {
    String issuerGeneration = "9007199254740993";
    String accountGeneration = "18446744073709551616000000000000000001";
    String tenantGeneration = "340282366920938463463374607431768211457";
    String membershipGeneration = "12";
    String issuerSequence = "9007199254740995";
    String accountSequence = "18446744073709551616000000000000000003";
    String tenantSequence = "340282366920938463463374607431768211459";
    String membershipSequence = "9007199254740997";
    String membershipVersion = "9007199254740999";

    AuthorityTuple joinTuple = tuple("1", "1", "1", membershipGeneration, Optional.empty());
    MembershipEvent joined =
        membershipEvent(
            membershipSequence,
            membershipVersion,
            membershipGeneration,
            "9007199254740996",
            joinTuple,
            "ACTIVE",
            List.of("player"),
            true);

    var issuer = issuerEvent(issuerSequence, issuerGeneration);
    var tenant = tenantEvent(tenantSequence, tenantGeneration);
    AccountSourceFixture account = accountEvent(accountSequence, accountGeneration, logoutAll);
    AuthorityTuple currentTuple =
        tuple(
            issuerGeneration,
            accountGeneration,
            tenantGeneration,
            membershipGeneration,
            Optional.of(
                new AccountSecurityCutoff(
                    accountGeneration, STREAM_PREFIX + "account/" + ACCOUNT_ID, accountSequence)));

    List<SourceEvidence> sources =
        sortedSources(List.of(source(issuer), account.evidence(), source(tenant), source(joined)));
    Snapshot snapshot =
        snapshot(
            true,
            "ACTIVE",
            true,
            Map.of(TENANT_ID, membershipVersion),
            membershipGeneration,
            List.of("player"),
            currentTuple,
            "9007199254741000",
            Map.of(
                STREAM_PREFIX + "issuer/" + ISSUER_ID, issuerSequence,
                STREAM_PREFIX + "account/" + ACCOUNT_ID, accountSequence,
                STREAM_PREFIX + "tenant/" + TENANT_ID, tenantSequence,
                STREAM_PREFIX + "membership/" + ACCOUNT_ID + "/" + TENANT_ID, membershipSequence),
            sources);
    return new Fixture(snapshot, joined);
  }

  private static Fixture fixedFixture(boolean logoutAll) {
    AuthorityTuple eventTuple = tuple("1", "1", "1", "2", Optional.empty());
    MembershipEvent member =
        membershipEvent("1", "1", "2", "1", eventTuple, "ACTIVE", List.of("player"), true);
    var issuer = issuerEvent("1", "2");
    var tenant = tenantEvent("1", "2");
    AccountSourceFixture account = accountEvent("1", "2", logoutAll);
    AuthorityTuple currentTuple =
        tuple(
            "2",
            "2",
            "2",
            "2",
            Optional.of(
                new AccountSecurityCutoff("2", STREAM_PREFIX + "account/" + ACCOUNT_ID, "1")));
    Snapshot snapshot =
        snapshot(
            true,
            "ACTIVE",
            true,
            Map.of(TENANT_ID, "1"),
            "2",
            List.of("player"),
            currentTuple,
            "2",
            Map.of(
                STREAM_PREFIX + "issuer/" + ISSUER_ID, "1",
                STREAM_PREFIX + "account/" + ACCOUNT_ID, "1",
                STREAM_PREFIX + "tenant/" + TENANT_ID, "1",
                STREAM_PREFIX + "membership/" + ACCOUNT_ID + "/" + TENANT_ID, "1"),
            sortedSources(
                List.of(source(issuer), account.evidence(), source(tenant), source(member))));
    return new Fixture(snapshot, member);
  }

  private static Snapshot missingSnapshot() {
    return snapshot(
        false,
        "MISSING",
        false,
        Map.of(TENANT_ID, "1"),
        "1",
        List.of(),
        tuple("1", "1", "1", "1", Optional.empty()),
        "1",
        Map.of(
            STREAM_PREFIX + "issuer/" + ISSUER_ID, "0",
            STREAM_PREFIX + "account/" + ACCOUNT_ID, "0",
            STREAM_PREFIX + "tenant/" + TENANT_ID, "0",
            STREAM_PREFIX + "membership/" + ACCOUNT_ID + "/" + TENANT_ID, "0"),
        List.of());
  }

  private static Snapshot missingMembershipWithUpstream(
      Snapshot baseline, boolean admission, List<String> roles) {
    String membershipStream =
        STREAM_PREFIX + "membership/" + baseline.accountId() + "/" + baseline.tenantId();
    List<Checkpoint> checkpoints =
        baseline.checkpoints().stream()
            .map(
                checkpoint ->
                    checkpoint.outboxStreamKey().equals(membershipStream)
                        ? new Checkpoint(membershipStream, "0")
                        : checkpoint)
            .toList();
    List<SourceEvidence> upstreamSources =
        baseline.sourceEvidence().stream()
            .filter(source -> !source.outboxStreamKey().equals(membershipStream))
            .toList();
    return new Snapshot(
        baseline.issuerId(),
        baseline.accountId(),
        baseline.tenantId(),
        false,
        "MISSING",
        admission,
        Map.of(baseline.tenantId(), "1"),
        baseline.membershipAuthorityGeneration(),
        roles,
        baseline.authorityTuple(),
        baseline.issuanceFence(),
        checkpoints,
        upstreamSources);
  }

  private static Snapshot withCheckpointSequence(
      Snapshot snapshot, String streamKey, String sequence) {
    List<Checkpoint> checkpoints =
        snapshot.checkpoints().stream()
            .map(
                checkpoint ->
                    checkpoint.outboxStreamKey().equals(streamKey)
                        ? new Checkpoint(streamKey, sequence)
                        : checkpoint)
            .toList();
    return new Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        snapshot.authorityTuple(),
        snapshot.issuanceFence(),
        checkpoints,
        snapshot.sourceEvidence());
  }

  private static Snapshot withMembershipEvent(Snapshot snapshot, MembershipEvent event) {
    List<SourceEvidence> sources = new ArrayList<>(snapshot.sourceEvidence());
    SourceEvidence original =
        sourceFor(snapshot, "membership/" + snapshot.accountId() + "/" + snapshot.tenantId());
    sources.set(sources.indexOf(original), source(event));
    return withSources(snapshot, sortedSources(sources));
  }

  private static MembershipEvent resealedMembership(
      Snapshot snapshot, AuthorityTuple tuple, String issuanceFence) {
    SourceEvidence original =
        sourceFor(snapshot, "membership/" + snapshot.accountId() + "/" + snapshot.tenantId());
    return membershipEvent(
        original.outboxSequence(),
        snapshot.membershipVersion().get(snapshot.tenantId()),
        snapshot.membershipAuthorityGeneration(),
        issuanceFence,
        tuple,
        snapshot.membershipLifecycleState(),
        snapshot.roles(),
        snapshot.gameplayAdmissionAllowed());
  }

  private static String increment(String decimal) {
    return new BigInteger(decimal).add(BigInteger.ONE).toString();
  }

  private static String decrement(String decimal) {
    return new BigInteger(decimal).subtract(BigInteger.ONE).toString();
  }

  private static Snapshot snapshot(
      boolean membershipExists,
      String lifecycle,
      boolean admission,
      Map<String, String> membershipVersion,
      String membershipGeneration,
      List<String> roles,
      AuthorityTuple tuple,
      String issuanceFence,
      Map<String, String> checkpointSequences,
      List<SourceEvidence> sources) {
    List<Checkpoint> checkpoints =
        checkpointSequences.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new Checkpoint(entry.getKey(), entry.getValue()))
            .toList();
    return new Snapshot(
        ISSUER_ID,
        ACCOUNT_ID,
        TENANT_ID,
        membershipExists,
        lifecycle,
        admission,
        membershipVersion,
        membershipGeneration,
        roles,
        tuple,
        issuanceFence,
        checkpoints,
        sources);
  }

  private static AuthorityTuple tuple(
      String issuerGeneration,
      String accountGeneration,
      String tenantGeneration,
      String membershipGeneration,
      Optional<AccountSecurityCutoff> cutoff) {
    return new AuthorityTuple(
        issuerGeneration,
        accountGeneration,
        Map.of(TENANT_ID, tenantGeneration),
        Map.of(TENANT_ID, membershipGeneration),
        List.of(),
        cutoff,
        Optional.empty());
  }

  private static MembershipEvent membershipEvent(
      String sequence,
      String version,
      String membershipGeneration,
      String issuanceFence,
      AuthorityTuple tuple,
      String lifecycle,
      List<String> roles,
      boolean admission) {
    String scope = "membership/" + ACCOUNT_ID + "/" + TENANT_ID;
    Map<String, Object> preimage = new HashMap<>();
    preimage.put("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION);
    preimage.put("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE);
    preimage.put("eventId", "membership-event-" + sequence);
    preimage.put("requestId", "membership-request-" + sequence);
    preimage.put("outboxStreamKey", STREAM_PREFIX + scope);
    preimage.put("outboxSequence", sequence);
    preimage.put("sourceScope", scope);
    preimage.put("accountId", ACCOUNT_ID);
    preimage.put("tenantId", TENANT_ID);
    preimage.put("membershipExists", true);
    preimage.put("membershipLifecycleState", lifecycle);
    preimage.put("membershipVersion", Map.of(TENANT_ID, version));
    preimage.put("membershipAuthorityGeneration", membershipGeneration);
    preimage.put("authorityTuple", tupleWire(tuple));
    preimage.put("issuanceFence", issuanceFence);
    preimage.put("roles", roles);
    preimage.put("gameplayAdmissionAllowed", admission);
    preimage.put("callerBoundAuthorityInvalidated", true);
    return MembershipAuthorityEventV1Codec.seal(preimage);
  }

  private static Map<String, Object> tupleWire(AuthorityTuple tuple) {
    Map<String, Object> wire = new HashMap<>();
    wire.put("issuerAuthGeneration", tuple.issuerAuthGeneration());
    wire.put("accountAuthorityGeneration", tuple.accountAuthorityGeneration());
    wire.put("tenantAuthorityGeneration", tuple.tenantAuthorityGeneration());
    wire.put("membershipAuthorityGeneration", tuple.membershipAuthorityGeneration());
    wire.put("privateRealmGrantVersions", tuple.privateRealmGrantVersions());
    tuple
        .accountSecurityCutoff()
        .ifPresent(
            cutoff ->
                wire.put(
                    "accountSecurityCutoff",
                    Map.of(
                        "accountAuthorityGeneration", cutoff.accountAuthorityGeneration(),
                        "outboxStreamKey", cutoff.outboxStreamKey(),
                        "outboxSequence", cutoff.outboxSequence())));
    return wire;
  }

  private static IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent issuerEvent(
      String sequence, String generation) {
    String scope = "issuer/" + ISSUER_ID;
    return IssuerGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            "issuer-event-" + sequence,
            "requestId",
            "issuer-request-" + sequence,
            "issuerId",
            ISSUER_ID,
            "sourceScope",
            scope,
            "outboxStreamKey",
            STREAM_PREFIX + scope,
            "outboxSequence",
            sequence,
            "issuerAuthGeneration",
            generation,
            "sourceVersion",
            "2"));
  }

  private static TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent tenantEvent(
      String sequence, String generation) {
    String requestId = "018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a";
    String scope = "tenant/" + TENANT_ID;
    return TenantGenerationAuthorityEventV1Codec.seal(
        Map.of(
            "schemaVersion",
            TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
            "eventType",
            TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
            "eventId",
            TenantGenerationAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId,
            "requestId",
            requestId,
            "tenantId",
            TENANT_ID,
            "sourceScope",
            scope,
            "outboxStreamKey",
            STREAM_PREFIX + scope,
            "outboxSequence",
            sequence,
            "tenantAuthorityGeneration",
            generation,
            "sourceVersion",
            "2"));
  }

  private static AccountSourceFixture accountEvent(
      String sequence, String generation, boolean logoutAll) {
    String stream = STREAM_PREFIX + "account/" + ACCOUNT_ID;
    String requestId =
        logoutAll ? "018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a" : "reset-request-" + sequence;
    Map<String, Object> preimage = new HashMap<>();
    preimage.put(
        "schemaVersion",
        logoutAll
            ? AccountLogoutAllAuthorityEventV1Codec.SCHEMA_VERSION
            : PasswordResetAuthorityEventV1Codec.SCHEMA_VERSION);
    preimage.put(
        "eventType",
        logoutAll
            ? AccountLogoutAllAuthorityEventV1Codec.EVENT_TYPE
            : PasswordResetAuthorityEventV1Codec.EVENT_TYPE);
    preimage.put(
        "eventId",
        logoutAll
            ? AccountLogoutAllAuthorityEventV1Codec.EVENT_ID_PREFIX + requestId
            : "password-reset-event-" + sequence);
    preimage.put("requestId", requestId);
    preimage.put("accountId", ACCOUNT_ID);
    preimage.put("sourceScope", "account/" + ACCOUNT_ID);
    preimage.put("outboxStreamKey", stream);
    preimage.put("outboxSequence", sequence);
    preimage.put("accountAuthorityGeneration", generation);
    preimage.put("sourceVersion", "2");
    preimage.put(
        "accountSecurityCutoff",
        Map.of(
            "accountAuthorityGeneration", generation,
            "outboxStreamKey", stream,
            "outboxSequence", sequence));
    String canonical;
    String eventId;
    String digest;
    if (logoutAll) {
      var event = AccountLogoutAllAuthorityEventV1Codec.seal(preimage);
      canonical = event.canonicalJson();
      eventId = event.eventId();
      digest = event.eventDigest();
    } else {
      var event = PasswordResetAuthorityEventV1Codec.seal(preimage);
      canonical = event.canonicalJson();
      eventId = event.eventId();
      digest = event.eventDigest();
    }
    return new AccountSourceFixture(
        new SourceEvidence(stream, sequence, eventId, digest, canonical));
  }

  private static SourceEvidence source(
      IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent event) {
    return new SourceEvidence(
        event.outboxStreamKey(),
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        event.canonicalJson());
  }

  private static SourceEvidence source(
      TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent event) {
    return new SourceEvidence(
        event.outboxStreamKey(),
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        event.canonicalJson());
  }

  private static SourceEvidence source(MembershipEvent event) {
    return new SourceEvidence(
        event.outboxStreamKey(),
        event.outboxSequence(),
        event.eventId(),
        event.eventDigest(),
        event.canonicalJson());
  }

  private static List<SourceEvidence> sortedSources(List<SourceEvidence> sources) {
    return sources.stream().sorted(Comparator.comparing(SourceEvidence::outboxStreamKey)).toList();
  }

  private static SourceEvidence sourceFor(Snapshot snapshot, String scope) {
    String expected = STREAM_PREFIX + scope;
    return snapshot.sourceEvidence().stream()
        .filter(source -> source.outboxStreamKey().equals(expected))
        .findFirst()
        .orElseThrow();
  }

  private static Snapshot withSources(Snapshot snapshot, List<SourceEvidence> sources) {
    return new Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        snapshot.authorityTuple(),
        snapshot.issuanceFence(),
        snapshot.checkpoints(),
        sources);
  }

  private static Snapshot withTuple(Snapshot snapshot, AuthorityTuple tuple) {
    return new Snapshot(
        snapshot.issuerId(),
        snapshot.accountId(),
        snapshot.tenantId(),
        snapshot.membershipExists(),
        snapshot.membershipLifecycleState(),
        snapshot.gameplayAdmissionAllowed(),
        snapshot.membershipVersion(),
        snapshot.membershipAuthorityGeneration(),
        snapshot.roles(),
        tuple,
        snapshot.issuanceFence(),
        snapshot.checkpoints(),
        snapshot.sourceEvidence());
  }

  private record AccountSourceFixture(SourceEvidence evidence) {}

  private record Fixture(Snapshot snapshot, MembershipEvent membershipEvent) {}
}
