package net.firedevops.firemud.gamesession.repository;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.gamesession.dto.CanonicalLaunchPreparationSnapshot;
import net.firedevops.firemud.gamesession.dto.CanonicalRealmCatalogSnapshot;
import net.firedevops.firemud.gamesession.dto.CreateCanonicalLaunchPreparationRequest;
import net.firedevops.firemud.gamesession.repository.GameSessionAuthoredWorldSourceRepository.IntakeReceipt;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Game Session-owned immutable persistence for source-qualified launch preparation evidence. */
@Repository
public class GameSessionCanonicalLaunchPreparationRepository {
  private static final int SCHEMA_VERSION = 1;
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final String REQUEST_DOMAIN =
      "game-session-canonical-launch-preparation-request/v1";
  private static final String RECEIPT_DOMAIN =
      "game-session-canonical-launch-preparation-receipt/v1";
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private static final Table<?> PREPARATION =
      DSL.table(DSL.name("game_session_canonical_launch_preparation"));
  private static final Field<String> TARGET_NAMESPACE =
      DSL.field(DSL.name("target_namespace"), String.class);
  private static final Field<String> CONTROL_PLANE_REQUEST_ID =
      DSL.field(DSL.name("control_plane_request_id"), String.class);
  private static final Field<UUID> OPERATION_ID = DSL.field(DSL.name("operation_id"), UUID.class);
  private static final Field<UUID> ACTING_ACCOUNT_UUID =
      DSL.field(DSL.name("acting_account_uuid"), UUID.class);
  private static final Field<UUID> CANONICAL_TENANT_ID =
      DSL.field(DSL.name("canonical_tenant_id"), UUID.class);
  private static final Field<UUID> REALM_ID = DSL.field(DSL.name("realm_id"), UUID.class);
  private static final Field<UUID> CATALOG_CREATION_REQUEST_ID =
      DSL.field(DSL.name("catalog_creation_request_id"), UUID.class);
  private static final Field<Long> CATALOG_REVISION =
      DSL.field(DSL.name("catalog_revision"), Long.class);
  private static final Field<UUID> SOURCE_INTAKE_OPERATION_ID =
      DSL.field(DSL.name("source_intake_operation_id"), UUID.class);
  private static final Field<UUID> SOURCE_INTAKE_REQUEST_ID =
      DSL.field(DSL.name("source_intake_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_REGISTRATION_REQUEST_ID =
      DSL.field(DSL.name("source_registration_request_id"), UUID.class);
  private static final Field<UUID> SOURCE_OPERATION_ID =
      DSL.field(DSL.name("source_operation_id"), UUID.class);
  private static final Field<String> CATALOG_REQUEST_DIGEST =
      DSL.field(DSL.name("catalog_request_digest"), String.class);
  private static final Field<String> CATALOG_RECEIPT_DIGEST =
      DSL.field(DSL.name("catalog_receipt_digest"), String.class);
  private static final Field<String> SOURCE_INTAKE_REQUEST_DIGEST =
      DSL.field(DSL.name("source_intake_request_digest"), String.class);
  private static final Field<String> SOURCE_INTAKE_RECEIPT_DIGEST =
      DSL.field(DSL.name("source_intake_receipt_digest"), String.class);
  private static final Field<String> SOURCE_EVIDENCE_DIGEST =
      DSL.field(DSL.name("source_evidence_digest"), String.class);
  private static final Field<String> DESCRIPTOR_REQUEST_DIGEST =
      DSL.field(DSL.name("descriptor_request_digest"), String.class);
  private static final Field<String> DESCRIPTOR_RESULT_DIGEST =
      DSL.field(DSL.name("descriptor_result_digest"), String.class);
  private static final Field<String> REQUEST_DIGEST =
      DSL.field(DSL.name("request_digest"), String.class);
  private static final Field<String> RECEIPT_DIGEST =
      DSL.field(DSL.name("receipt_digest"), String.class);
  private static final Field<JSONB> REQUEST_EVIDENCE_JSON =
      DSL.field(DSL.name("request_evidence_json"), JSONB.class);
  private static final Field<JSONB> CATALOG_EVIDENCE_JSON =
      DSL.field(DSL.name("catalog_evidence_json"), JSONB.class);
  private static final Field<JSONB> SOURCE_INTAKE_EVIDENCE_JSON =
      DSL.field(DSL.name("source_intake_evidence_json"), JSONB.class);
  private static final Field<JSONB> LAUNCH_DESCRIPTOR_EVIDENCE_JSON =
      DSL.field(DSL.name("launch_descriptor_evidence_json"), JSONB.class);

  private final DSLContext dsl;
  private final GameSessionCanonicalRealmCatalogRepository catalogRepository;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Injected owner collaborators are validated before use; construction acquires no resources.")
  public GameSessionCanonicalLaunchPreparationRepository(
      DSLContext dsl, GameSessionCanonicalRealmCatalogRepository catalogRepository) {
    this.dsl = Objects.requireNonNull(dsl, "dsl must not be null");
    this.catalogRepository = Objects.requireNonNull(catalogRepository, "catalogRepository");
  }

  /** Reads one complete committed preparation without repairing or re-resolving evidence. */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public Optional<CanonicalLaunchPreparationSnapshot> readByControlPlaneRequestId(
      String targetNamespace, String controlPlaneRequestId) {
    requireSelector(targetNamespace, controlPlaneRequestId);
    requireCommittedRead();
    Record row = find(targetNamespace, controlPlaneRequestId);
    return row == null ? Optional.empty() : Optional.of(toSnapshot(row));
  }

  /**
   * Persists one exact immutable source/catalog/descriptor composition under a caller-owned
   * writable READ COMMITTED transaction. No network operation is performed while owner rows are
   * locked.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CanonicalLaunchPreparationSnapshot persistPrepared(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot committedCatalog,
      IntakeReceipt committedSource,
      AuthoredWorldLaunchDescriptorEvidence descriptorEvidence) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(committedCatalog, "committedCatalog");
    Objects.requireNonNull(committedSource, "committedSource");
    Objects.requireNonNull(descriptorEvidence, "descriptorEvidence");
    requireWritableReadCommittedOwnerTransaction();
    requireEvidenceMatchesRequest(request, committedCatalog, committedSource, descriptorEvidence);

    Record existing = find(request.targetNamespace(), request.controlPlaneRequestId());
    if (existing != null) {
      return requireExactPrepared(
          existing, request, committedCatalog, committedSource, descriptorEvidence);
    }

    CanonicalRealmCatalogSnapshot lockedCatalog =
        catalogRepository.lockExactInitialPublicProduction(
            request.targetNamespace(),
            request.canonicalTenantId(),
            request.realmId(),
            request.catalogCreationRequestId(),
            request.catalogRevision());
    if (!committedCatalog.equals(lockedCatalog)) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Canonical catalog changed after launch preparation readback");
    }
    IntakeReceipt lockedSource = lockExactSource(committedSource);
    if (!committedSource.equals(lockedSource)
        || !committedCatalog.sourceIntakeReceipt().equals(lockedSource)) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Committed authored-world source changed before launch preparation persistence");
    }

    UUID operationId = UUID.randomUUID();
    requireNonNil(operationId, "operationId");
    String requestDigest =
        requestDigest(request, committedCatalog, committedSource, descriptorEvidence);
    String receiptDigest =
        receiptDigest(
            operationId,
            request,
            committedCatalog,
            committedSource,
            descriptorEvidence,
            requestDigest);
    dsl.insertInto(PREPARATION)
        .set(TARGET_NAMESPACE, request.targetNamespace())
        .set(CONTROL_PLANE_REQUEST_ID, request.controlPlaneRequestId())
        .set(OPERATION_ID, operationId)
        .set(ACTING_ACCOUNT_UUID, request.actingAccountUuid())
        .set(CANONICAL_TENANT_ID, request.canonicalTenantId())
        .set(REALM_ID, request.realmId())
        .set(CATALOG_CREATION_REQUEST_ID, request.catalogCreationRequestId())
        .set(CATALOG_REVISION, request.catalogRevision())
        .set(SOURCE_INTAKE_OPERATION_ID, committedSource.operationId())
        .set(SOURCE_INTAKE_REQUEST_ID, committedSource.intakeRequestId())
        .set(SOURCE_REGISTRATION_REQUEST_ID, committedSource.source().registrationRequestId())
        .set(SOURCE_OPERATION_ID, committedSource.source().operationId())
        .set(CATALOG_REQUEST_DIGEST, committedCatalog.requestDigest())
        .set(CATALOG_RECEIPT_DIGEST, committedCatalog.receiptDigest())
        .set(SOURCE_INTAKE_REQUEST_DIGEST, committedSource.requestDigest())
        .set(SOURCE_INTAKE_RECEIPT_DIGEST, committedSource.receiptDigest())
        .set(SOURCE_EVIDENCE_DIGEST, committedSource.source().evidenceDigest())
        .set(DESCRIPTOR_REQUEST_DIGEST, descriptorEvidence.requestDigest())
        .set(DESCRIPTOR_RESULT_DIGEST, descriptorEvidence.resultDigest())
        .set(REQUEST_DIGEST, requestDigest)
        .set(RECEIPT_DIGEST, receiptDigest)
        .set(REQUEST_EVIDENCE_JSON, toJson(request, "request"))
        .set(CATALOG_EVIDENCE_JSON, toJson(committedCatalog, "catalog"))
        .set(SOURCE_INTAKE_EVIDENCE_JSON, toJson(committedSource, "source intake"))
        .set(LAUNCH_DESCRIPTOR_EVIDENCE_JSON, toJson(descriptorEvidence, "launch descriptor"))
        .onConflictDoNothing()
        .execute();

    Record stored = find(request.targetNamespace(), request.controlPlaneRequestId());
    if (stored == null) {
      throw new LaunchPreparationConflictException(
          "Canonical launch preparation conflicts with existing immutable owner evidence");
    }
    return requireExactPrepared(
        stored, request, committedCatalog, committedSource, descriptorEvidence);
  }

  public static String requestDigest(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    requireEvidenceMatchesRequest(request, catalog, source, descriptor);
    AuthoredWorldSourceEvidence authoredSource = source.source();
    return digest(
        REQUEST_DOMAIN,
        Integer.toString(SCHEMA_VERSION),
        request.controlPlaneRequestId(),
        request.actingAccountUuid().toString(),
        request.targetNamespace(),
        request.canonicalTenantId().toString(),
        request.realmId().toString(),
        request.catalogCreationRequestId().toString(),
        Long.toString(request.catalogRevision()),
        request.sourceIntakeOperationId().toString(),
        Long.toString(request.gameTemplateId()),
        Boolean.toString(request.requestedScriptPatchVersionPresent()),
        optional(
            request.requestedScriptPatchVersionPresent(), request.requestedScriptPatchVersion()),
        Boolean.toString(request.sourceVersionIdPresent()),
        optional(request.sourceVersionIdPresent(), decimal(request.sourceVersionId())),
        Boolean.toString(request.targetVersionIdPresent()),
        optional(request.targetVersionIdPresent(), decimal(request.targetVersionId())),
        Boolean.toString(request.requestedRuntimeFlagsJsonPresent()),
        optional(request.requestedRuntimeFlagsJsonPresent(), request.requestedRuntimeFlagsJson()),
        catalog.targetNamespace(),
        catalog.tenantId().toString(),
        catalog.tenantSlug(),
        catalog.worldSlug(),
        catalog.realmId().toString(),
        catalog.realmSlug(),
        catalog.realmDisplayName(),
        Boolean.toString(catalog.visible()),
        Boolean.toString(catalog.publicProduction()),
        catalog.stateScope(),
        catalog.playableStateNamespaceId().toString(),
        catalog.characterCreationPolicy(),
        Long.toString(catalog.catalogRevision()),
        catalog.creationRequestId().toString(),
        catalog.requestDigest(),
        catalog.receiptDigest(),
        source.operationId().toString(),
        source.intakeRequestId().toString(),
        source.requestDigest(),
        source.receiptDigest(),
        Integer.toString(authoredSource.schemaVersion()),
        authoredSource.registrationRequestId().toString(),
        authoredSource.operationId().toString(),
        authoredSource.requestDigest(),
        authoredSource.canonicalTenantId().toString(),
        authoredSource.tenantSlug(),
        authoredSource.worldSlug(),
        authoredSource.worldDisplayName(),
        Long.toString(authoredSource.sourceGameRowId()),
        authoredSource.sourceGameTenantKey(),
        authoredSource.provenanceKind(),
        authoredSource.evidenceDigest(),
        descriptor.requestDigest(),
        descriptor.resultDigest());
  }

  public static String receiptDigest(
      UUID operationId,
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor,
      String requestDigest) {
    requireNonNil(operationId, "operationId");
    requireDigest(requestDigest, "requestDigest");
    return digest(
        RECEIPT_DOMAIN,
        Integer.toString(SCHEMA_VERSION),
        operationId.toString(),
        requestDigest,
        request.targetNamespace(),
        request.controlPlaneRequestId(),
        request.actingAccountUuid().toString(),
        request.canonicalTenantId().toString(),
        request.realmId().toString(),
        request.catalogCreationRequestId().toString(),
        Long.toString(request.catalogRevision()),
        catalog.requestDigest(),
        catalog.receiptDigest(),
        source.operationId().toString(),
        source.intakeRequestId().toString(),
        source.requestDigest(),
        source.receiptDigest(),
        source.source().evidenceDigest(),
        descriptor.requestDigest(),
        descriptor.resultDigest());
  }

  private CanonicalLaunchPreparationSnapshot requireExactPrepared(
      Record row,
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    CanonicalLaunchPreparationSnapshot stored = toSnapshot(row);
    if (!stored.request().equals(request)
        || !stored.catalogSnapshot().equals(catalog)
        || !stored.sourceIntakeReceipt().equals(source)
        || !stored.launchDescriptorEvidence().equals(descriptor)) {
      throw new LaunchPreparationConflictException(
          "controlPlaneRequestId was reused with changed launch preparation evidence");
    }
    return stored;
  }

  private CanonicalLaunchPreparationSnapshot toSnapshot(Record row) {
    try {
      CreateCanonicalLaunchPreparationRequest request =
          fromJson(
              row.get(REQUEST_EVIDENCE_JSON, JSONB.class),
              CreateCanonicalLaunchPreparationRequest.class);
      CanonicalRealmCatalogSnapshot catalog =
          fromJson(
              row.get(CATALOG_EVIDENCE_JSON, JSONB.class), CanonicalRealmCatalogSnapshot.class);
      IntakeReceipt source =
          fromJson(row.get(SOURCE_INTAKE_EVIDENCE_JSON, JSONB.class), IntakeReceipt.class);
      AuthoredWorldLaunchDescriptorEvidence descriptor =
          fromJson(
              row.get(LAUNCH_DESCRIPTOR_EVIDENCE_JSON, JSONB.class),
              AuthoredWorldLaunchDescriptorEvidence.class);
      CanonicalLaunchPreparationSnapshot snapshot =
          new CanonicalLaunchPreparationSnapshot(
              required(row.get(OPERATION_ID), "operationId"),
              request,
              catalog,
              source,
              descriptor,
              required(row.get(REQUEST_DIGEST), "requestDigest"),
              required(row.get(RECEIPT_DIGEST), "receiptDigest"));
      snapshot.requireValid();
      String expectedRequestDigest = requestDigest(request, catalog, source, descriptor);
      String expectedReceiptDigest =
          receiptDigest(
              snapshot.operationId(), request, catalog, source, descriptor, expectedRequestDigest);
      if (!request.targetNamespace().equals(required(row.get(TARGET_NAMESPACE), "targetNamespace"))
          || !request
              .controlPlaneRequestId()
              .equals(required(row.get(CONTROL_PLANE_REQUEST_ID), "controlPlaneRequestId"))
          || !request
              .actingAccountUuid()
              .equals(required(row.get(ACTING_ACCOUNT_UUID), "actingAccountUuid"))
          || !request
              .canonicalTenantId()
              .equals(required(row.get(CANONICAL_TENANT_ID), "canonicalTenantId"))
          || !request.realmId().equals(required(row.get(REALM_ID), "realmId"))
          || !request
              .catalogCreationRequestId()
              .equals(required(row.get(CATALOG_CREATION_REQUEST_ID), "catalogCreationRequestId"))
          || request.catalogRevision() != required(row.get(CATALOG_REVISION), "catalogRevision")
          || !source
              .operationId()
              .equals(required(row.get(SOURCE_INTAKE_OPERATION_ID), "sourceIntakeOperationId"))
          || !source
              .intakeRequestId()
              .equals(required(row.get(SOURCE_INTAKE_REQUEST_ID), "sourceIntakeRequestId"))
          || !source
              .source()
              .registrationRequestId()
              .equals(
                  required(row.get(SOURCE_REGISTRATION_REQUEST_ID), "sourceRegistrationRequestId"))
          || !source
              .source()
              .operationId()
              .equals(required(row.get(SOURCE_OPERATION_ID), "sourceOperationId"))
          || !catalog
              .requestDigest()
              .equals(required(row.get(CATALOG_REQUEST_DIGEST), "catalogRequestDigest"))
          || !catalog
              .receiptDigest()
              .equals(required(row.get(CATALOG_RECEIPT_DIGEST), "catalogReceiptDigest"))
          || !source
              .requestDigest()
              .equals(required(row.get(SOURCE_INTAKE_REQUEST_DIGEST), "sourceIntakeRequestDigest"))
          || !source
              .receiptDigest()
              .equals(required(row.get(SOURCE_INTAKE_RECEIPT_DIGEST), "sourceIntakeReceiptDigest"))
          || !source
              .source()
              .evidenceDigest()
              .equals(required(row.get(SOURCE_EVIDENCE_DIGEST), "sourceEvidenceDigest"))
          || !descriptor
              .requestDigest()
              .equals(required(row.get(DESCRIPTOR_REQUEST_DIGEST), "descriptorRequestDigest"))
          || !descriptor
              .resultDigest()
              .equals(required(row.get(DESCRIPTOR_RESULT_DIGEST), "descriptorResultDigest"))
          || !expectedRequestDigest.equals(snapshot.requestDigest())
          || !expectedReceiptDigest.equals(snapshot.receiptDigest())) {
        throw new InvalidLaunchPreparationEvidenceException(
            "Persisted launch preparation evidence does not match its stored identity and digests");
      }
      return snapshot;
    } catch (InvalidLaunchPreparationEvidenceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Persisted canonical launch preparation evidence is invalid", exception);
    }
  }

  private IntakeReceipt lockExactSource(IntakeReceipt expected) {
    Record row =
        dsl.fetchOne(
            "SELECT * FROM game_session_authored_world_source_intake "
                + "WHERE target_namespace = ? AND operation_id = ? FOR UPDATE",
            expected.source().targetNamespace(),
            expected.operationId());
    if (row == null) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Exact committed authored-world source intake is missing at launch preparation commit");
    }
    try {
      AuthoredWorldSourceEvidence source =
          new AuthoredWorldSourceEvidence(
              required(row.get("source_schema_version", Integer.class), "sourceSchemaVersion"),
              required(row.get("target_namespace", String.class), "sourceTargetNamespace"),
              required(
                  row.get("source_registration_request_id", UUID.class),
                  "sourceRegistrationRequestId"),
              required(row.get("source_operation_id", UUID.class), "sourceOperationId"),
              required(row.get("source_request_digest", String.class), "sourceRequestDigest"),
              required(row.get("canonical_tenant_id", UUID.class), "sourceCanonicalTenantId"),
              required(row.get("tenant_slug", String.class), "sourceTenantSlug"),
              required(row.get("world_slug", String.class), "sourceWorldSlug"),
              required(row.get("world_display_name", String.class), "sourceWorldDisplayName"),
              required(row.get("source_game_row_id", Long.class), "sourceGameRowId"),
              required(row.get("source_game_tenant_key", String.class), "sourceGameTenantKey"),
              required(row.get("source_provenance_kind", String.class), "sourceProvenanceKind"),
              required(row.get("source_evidence_digest", String.class), "sourceEvidenceDigest"));
      if (!Integer.valueOf(SCHEMA_VERSION).equals(row.get("schema_version", Integer.class))) {
        throw new IllegalArgumentException("Unsupported source intake schema version");
      }
      IntakeReceipt locked =
          new IntakeReceipt(
              required(row.get("operation_id", UUID.class), "sourceIntakeOperationId"),
              required(row.get("intake_request_id", UUID.class), "sourceIntakeRequestId"),
              required(row.get("request_digest", String.class), "sourceIntakeRequestDigest"),
              source,
              required(row.get("receipt_digest", String.class), "sourceIntakeReceiptDigest"));
      if (!"NEW_GAME_ROW".equals(source.provenanceKind())) {
        throw new InvalidLaunchPreparationEvidenceException(
            "Canonical launch preparation requires exact fresh authored-world source evidence");
      }
      return locked;
    } catch (InvalidLaunchPreparationEvidenceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Persisted authored-world source evidence is invalid", exception);
    }
  }

  private Record find(String targetNamespace, String controlPlaneRequestId) {
    return dsl.selectFrom(PREPARATION)
        .where(
            TARGET_NAMESPACE
                .eq(targetNamespace)
                .and(CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
        .fetchOne();
  }

  private static void requireEvidenceMatchesRequest(
      CreateCanonicalLaunchPreparationRequest request,
      CanonicalRealmCatalogSnapshot catalog,
      IntakeReceipt source,
      AuthoredWorldLaunchDescriptorEvidence descriptor) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(descriptor, "descriptor");
    if (!request.targetNamespace().equals(catalog.targetNamespace())
        || !request.canonicalTenantId().equals(catalog.tenantId())
        || !request.realmId().equals(catalog.realmId())
        || !request.catalogCreationRequestId().equals(catalog.creationRequestId())
        || request.catalogRevision() != catalog.catalogRevision()
        || !request.sourceIntakeOperationId().equals(source.operationId())
        || !source.equals(catalog.sourceIntakeReceipt())
        || !"NEW_GAME_ROW".equals(source.source().provenanceKind())
        || !descriptor.request().equals(request.descriptorRequest(catalog, source.source()))) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Launch preparation does not match exact fresh catalog/source/descriptor evidence");
    }
    descriptor.requireValid();
  }

  private static void requireSelector(String namespace, String controlPlaneRequestId) {
    if (!net.firedevops.firemud.common.grpc.GrpcPeerIdentity.isValidNamespace(namespace)) {
      throw new IllegalArgumentException("targetNamespace must be one canonical DNS label");
    }
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("controlPlaneRequestId is required");
    }
    strictUtf8(controlPlaneRequestId);
  }

  private static void requireCommittedRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Launch preparation read requires committed owner evidence");
    }
  }

  private void requireWritableReadCommittedOwnerTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Launch preparation persistence requires a writable owner transaction");
    }
    dsl.connectionResult(
        connection -> {
          if (connection.getAutoCommit()
              || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalStateException(
                "Launch preparation persistence requires the owner READ COMMITTED connection");
          }
          return null;
        });
    Record isolation = dsl.fetchOne("SHOW transaction_isolation");
    if (isolation == null
        || !"read committed".equalsIgnoreCase(isolation.get(0, String.class).trim())) {
      throw new IllegalStateException("Launch preparation persistence requires READ COMMITTED");
    }
  }

  private static JSONB toJson(Object value, String name) {
    try {
      return JSONB.valueOf(JSON.writeValueAsString(value));
    } catch (IOException exception) {
      throw new IllegalStateException("Could not store exact " + name + " evidence", exception);
    }
  }

  private static <T> T fromJson(JSONB value, Class<T> type) {
    if (value == null) {
      throw new InvalidLaunchPreparationEvidenceException("Persisted JSON evidence is missing");
    }
    try {
      return JSON.readValue(value.data(), type);
    } catch (IOException exception) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Persisted closed launch preparation evidence cannot be decoded", exception);
    }
  }

  private static String digest(String domain, String... values) {
    try {
      ByteArrayOutputStream preimage = new ByteArrayOutputStream();
      writeSegment(preimage, domain);
      for (String value : values) {
        writeSegment(preimage, value);
      }
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray()));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void writeSegment(ByteArrayOutputStream output, String value) {
    byte[] bytes = strictUtf8(value);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static byte[] strictUtf8(String value) {
    Objects.requireNonNull(value, "digest value");
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException("Digest inputs must be well-formed UTF-8", exception);
    }
  }

  private static String decimal(Long value) {
    return value == null ? "" : Long.toString(value);
  }

  private static String optional(boolean present, String value) {
    return present ? Objects.requireNonNull(value, "present optional value") : "";
  }

  private static void requireDigest(String value, String fieldName) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(fieldName + " must be a canonical SHA-256 digest");
    }
  }

  private static void requireNonNil(UUID value, String fieldName) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(fieldName + " must be a non-nil UUID");
    }
  }

  private static <T> T required(T value, String fieldName) {
    if (value == null) {
      throw new InvalidLaunchPreparationEvidenceException(
          "Persisted launch preparation " + fieldName + " is missing");
    }
    return value;
  }

  public static final class LaunchPreparationConflictException extends IllegalStateException {
    public LaunchPreparationConflictException(String message) {
      super(message);
    }
  }

  public static final class InvalidLaunchPreparationEvidenceException
      extends IllegalStateException {
    public InvalidLaunchPreparationEvidenceException(String message) {
      super(message);
    }

    public InvalidLaunchPreparationEvidenceException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
