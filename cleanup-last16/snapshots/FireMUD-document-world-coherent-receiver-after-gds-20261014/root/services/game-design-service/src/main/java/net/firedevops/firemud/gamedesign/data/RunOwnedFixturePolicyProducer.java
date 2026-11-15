package net.firedevops.firemud.gamedesign.data;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec;
import net.firedevops.firemud.common.smoke.RunOwnedInitialAdmissionFixtureWireCodec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.gamedesign.dto.ResolvedLaunchDescriptorDto;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Fixed-capability fixture authority only; never emits human selected-publication evidence. */
@Service
final class RunOwnedFixturePolicyProducer {
  private static final String SCHEMA = "firemud.run-owned-fixture-policy-seal.v1";
  private static final String OWNER = "game-design-service";
  private final DSLContext dsl;
  private final RunOwnedFixtureLaunchReadback readback;
  private final ObjectMapper json;
  private final TransactionTemplate write;
  private final TransactionTemplate read;

  RunOwnedFixturePolicyProducer(
      DSLContext dsl,
      RunOwnedFixtureLaunchReadback readback,
      ObjectMapper json,
      PlatformTransactionManager transactions) {
    this.dsl = dsl;
    this.readback = readback;
    this.json = json;
    write = new TransactionTemplate(transactions);
    write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal sealAndRead(
      TestDataSeeder.FixtureCapability trusted,
      FreshTenantCreationEvidence creation,
      RunOwnedFixtureLaunchReadback.Snapshot snapshot,
      ResolvedLaunchDescriptorDto descriptor) {
    var capability = trusted.wire();
    var source = snapshot.sourceEvidence();
    if (!creation.canonicalTenantId().equals(source.canonicalTenantId())
        || creation.sourceGameRowId() != source.sourceGameRowId()
        || !creation.sourceGameTenantKey().equals(source.sourceGameTenantKey())
        || !creation.provenanceKind().equals(source.provenanceKind())
        || !creation.targetNamespace().equals(source.targetNamespace())
        || snapshot.gameTemplateId() != trusted.gameTemplateId()) {
      throw new IllegalStateException("Fixture creation, source and template bindings differ");
    }
    var launch =
        new RunOwnedInitialAdmissionFixtureWireCodec.LaunchReadEvidence(
            RunOwnedInitialAdmissionFixtureWireCodec.LAUNCH_READ_EVIDENCE_SCHEMA,
            snapshot.gameTemplateId(),
            snapshot.bundleProto(),
            snapshot.artifactProto(),
            snapshot.versionProto(),
            AuthoredWorldLaunchDescriptorGrpcCodec.toLaunchDescriptorMessage(
                descriptor.authoredWorldBinding()));
    byte[] request =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestBytes(
            capability, creation, source, launch);
    String requestDigest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyRequestDigest(
            capability, creation, source, launch);
    var binding = new LinkedHashMap<String, Object>();
    binding.put("targetNamespace", source.targetNamespace());
    binding.put("fixtureOperationId", trusted.operationId().toString());
    binding.put("creationOperationId", creation.operationId().toString());
    binding.put("creationEvidenceDigest", creation.evidenceDigest());
    binding.put("sourceOperationId", source.operationId().toString());
    binding.put("sourceEvidenceDigest", source.evidenceDigest());
    binding.put("sourceGameRowId", source.sourceGameRowId());
    binding.put("sourceGameTenantKey", source.sourceGameTenantKey());
    binding.put("gameTemplateId", snapshot.gameTemplateId());
    binding.put("versionStateEpoch", snapshot.versionState().versionStateEpoch());
    binding.put("releaseBundleId", snapshot.bundle().id());
    binding.put("publishedReleaseBundleRef", snapshot.bundle().publishedReleaseBundleRef());
    binding.put("artifactRowId", snapshot.artifact().id());
    binding.put("artifactStateEpoch", snapshot.artifact().stateEpoch());
    binding.put("launchDescriptorId", descriptor.launchDescriptorId());
    binding.put("descriptorRequestDigest", descriptor.authoredWorldBinding().requestDigest());
    binding.put("descriptorResultDigest", descriptor.authoredWorldBinding().resultDigest());
    var policy =
        Map.<String, Object>ofEntries(
            Map.entry("worldSlug", capability.worldSlug()),
            Map.entry("worldDisplayName", capability.worldDisplayName()),
            Map.entry("realmSlug", capability.realmSlug()),
            Map.entry("realmDisplayName", capability.realmDisplayName()),
            Map.entry("visible", capability.visible()),
            Map.entry("publicProductionRealm", capability.publicProductionRealm()),
            Map.entry("requiresCharacterSelection", capability.requiresCharacterSelection()),
            Map.entry("stateScope", capability.stateScope()),
            Map.entry("characterCreationPolicy", capability.characterCreationPolicy()),
            Map.entry("tenantId", capability.tenantId()),
            Map.entry("gameTemplateId", capability.gameTemplateId()),
            Map.entry("ownerAccountId", capability.ownerAccountId()));
    String bindingJson = serialize(binding);
    String policyJson = serialize(policy);
    write.executeWithoutResult(
        ignored -> {
          dsl.fetchSingle(
              "SELECT id FROM game WHERE id=? AND tenant_id=? FOR UPDATE",
              creation.sourceGameRowId(),
              creation.sourceGameTenantKey());
          dsl.fetchSingle(
              "SELECT id FROM version WHERE id=? AND tenant_id=? FOR UPDATE",
              snapshot.version().id(),
              creation.sourceGameTenantKey());
          UUID operationId = trusted.operationId();
          String evidenceDigest =
              RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
                  operationId, requestDigest);
          dsl.execute(
              "INSERT INTO game_design_published_realm_policy_set "
                  + "(canonical_tenant_id, canonical_version_id, game_design_version_row_id, version_number, sealed, "
                  + "fixture_operation_id, fixture_request_bytes, fixture_request_digest, fixture_evidence_digest, "
                  + "fixture_binding_json, fixture_policy_json) VALUES (?, ?, ?, ?, TRUE, ?, ?, ?, ?, ?::jsonb, ?::jsonb) "
                  + "ON CONFLICT (canonical_tenant_id, canonical_version_id) DO NOTHING",
              source.canonicalTenantId(),
              snapshot.version().canonicalVersionId(),
              snapshot.version().id(),
              snapshot.version().versionNumber(),
              operationId,
              request,
              requestDigest,
              evidenceDigest,
              bindingJson,
              policyJson);
          requireExactRow(snapshot, request, requestDigest, bindingJson, policyJson);
        });
    var seal =
        Objects.requireNonNull(
            read.execute(
                ignored ->
                    requireExactRow(snapshot, request, requestDigest, bindingJson, policyJson)));
    // Independent owner read after seal commit; changing upstream rows cannot publish a receipt.
    readback.readCommittedCreation(creation);
    var committedSnapshot = readback.readCommittedOutcome(snapshot);
    var committedDescriptor =
        readback.resolveAndReadLaunchDescriptor(descriptor.authoredWorldBinding().request());
    if (!descriptor.equals(committedDescriptor)) {
      throw new IllegalStateException("Fixture descriptor changed after policy seal commit");
    }
    readback.requireDescriptorMatchesSnapshot(
        committedDescriptor, descriptor.authoredWorldBinding().request(), committedSnapshot);
    return seal;
  }

  private RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal requireExactRow(
      RunOwnedFixtureLaunchReadback.Snapshot snapshot,
      byte[] request,
      String requestDigest,
      String bindingJson,
      String policyJson) {
    var row =
        dsl.fetchOne(
            "SELECT * FROM game_design_published_realm_policy_set "
                + "WHERE canonical_tenant_id=? AND canonical_version_id=?",
            snapshot.sourceEvidence().canonicalTenantId(),
            snapshot.version().canonicalVersionId());
    if (row == null
        || !Boolean.TRUE.equals(row.get("sealed", Boolean.class))
        || row.get("fixture_operation_id", UUID.class) == null
        || !Arrays.equals(request, row.get("fixture_request_bytes", byte[].class))
        || !requestDigest.equals(row.get("fixture_request_digest", String.class))
        || !sameJson(bindingJson, row.get("fixture_binding_json", String.class))
        || !sameJson(policyJson, row.get("fixture_policy_json", String.class))) {
      throw new IllegalStateException(
          "Fixture policy seal differs from its exact original evidence");
    }
    UUID operation = row.get("fixture_operation_id", UUID.class);
    if (!operation
        .toString()
        .equals(
            RunOwnedInitialAdmissionFixtureWireCodec.parseFixturePolicyRequestBytes(request)
                .capability()
                .operationId())) {
      throw new IllegalStateException("Fixture policy operation differs from its fixed capability");
    }
    String digest =
        RunOwnedInitialAdmissionFixtureWireCodec.fixturePolicyEvidenceDigest(
            operation, requestDigest);
    if (!digest.equals(row.get("fixture_evidence_digest", String.class))) {
      throw new IllegalStateException("Fixture policy seal committed readback digest differs");
    }
    return new RunOwnedInitialAdmissionFixtureWireCodec.FixturePolicySeal(
        SCHEMA, OWNER, operation, requestDigest, digest);
  }

  private String serialize(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception invalid) {
      throw new IllegalStateException("Fixture policy serialization failed", invalid);
    }
  }

  private boolean sameJson(String expected, String actual) {
    try {
      return actual != null && json.readTree(expected).equals(json.readTree(actual));
    } catch (Exception invalid) {
      throw new IllegalStateException("Fixture policy stored evidence invalid", invalid);
    }
  }
}
