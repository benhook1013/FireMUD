package net.firedevops.firemud.accountservice.service.session;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable original Account tenant-creation request storage; not an authority or source hold. */
public final class AccountTenantCreationIntentRepository {
  private static final String TABLE = "account_tenant_creation_original_intents";
  private static final String REQUEST_DOMAIN = "account-tenant-creation-intent-request/v1";
  private static final String REQUEST_LOCK_NAMESPACE = "tenant-creation-intent-request";
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  private final DSLContext dsl;
  private final AccountRecentReauthenticationRepository reauthentication;

  public AccountTenantCreationIntentRepository(
      DSLContext dsl, AccountRecentReauthenticationRepository reauthentication) {
    this.dsl = Objects.requireNonNull(dsl, "Account tenant-creation SQL owner is required");
    this.reauthentication =
        Objects.requireNonNull(reauthentication, "Account reauthentication owner is required");
  }

  /** Persists exact original request data before any creation authority or external work. */
  public OriginalIntent claim(Request request) {
    requireWritableTransaction();
    Objects.requireNonNull(request, "Account tenant-creation intent request is required");
    validateInput(request);

    byte[] requestPayload = canonicalRequestPayload(request);
    String requestDigest = digest(requestPayload);
    String gameDesignRequestDigest =
        GameTenantCreationDigest.requestDigest(
            request.targetNamespace(),
            request.gameDesignCreationRequestId(),
            request.sourceGameTenantKey(),
            request.name(),
            request.description());

    // Separate Account intent identity serialization from reauthentication request identity.
    lockIntentRequest(request.requestId());
    reauthentication.lockCreationOperation(request.creationOperationId());

    Record existing = findByRequestId(request.requestId());
    if (existing != null) {
      OriginalIntent original = map(existing);
      requireSameRequest(original, requestPayload, requestDigest, gameDesignRequestDigest);
      return original;
    }

    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (request_id, creation_operation_id, game_design_creation_request_id, "
                + "initiating_account_uuid, target_namespace, source_game_tenant_key, name, "
                + "description, recent_reauthentication_evidence_id, creator_party_id, "
                + "consent_operation_id, consent_statement_version, affirmative_consent, "
                + "game_design_request_digest, request_payload, request_digest) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::TEXT, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING",
            request.requestId(),
            request.creationOperationId(),
            request.gameDesignCreationRequestId(),
            request.initiatingAccountId(),
            request.targetNamespace(),
            request.sourceGameTenantKey(),
            request.name(),
            request.description(),
            request.recentReauthenticationEvidenceId(),
            request.creatorPartyId(),
            request.consentOperationId(),
            request.consentStatementVersion(),
            request.affirmativeConsent(),
            gameDesignRequestDigest,
            requestPayload,
            requestDigest);
    if (inserted == 0) {
      existing = findByRequestId(request.requestId());
      if (existing == null) {
        throw conflict("An Account tenant-creation identity is already claimed");
      }
      OriginalIntent original = map(existing);
      requireSameRequest(original, requestPayload, requestDigest, gameDesignRequestDigest);
      return original;
    }

    Record stored = findByRequestId(request.requestId());
    if (stored == null) {
      throw unavailable("The original Account tenant-creation intent was not retained");
    }
    OriginalIntent original = map(stored);
    requireSameRequest(original, requestPayload, requestDigest, gameDesignRequestDigest);
    return original;
  }

  /** Reads the exact persisted original only; absence never authorizes replacement work. */
  public Optional<OriginalIntent> readOriginal(UUID creationOperationId) {
    requireWritableTransaction();
    requireNonNil(creationOperationId, "creation operation ID");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM " + TABLE + " WHERE creation_operation_id = ?", creationOperationId);
    if (row == null) {
      return Optional.empty();
    }
    OriginalIntent original = map(row);
    if (!creationOperationId.equals(original.creationOperationId())) {
      throw unavailable(
          "Stored Account tenant-creation operation differs from the requested original");
    }
    return Optional.of(original);
  }

  private Record findByRequestId(UUID requestId) {
    return dsl.fetchOne("SELECT * FROM " + TABLE + " WHERE request_id = ?", requestId);
  }

  private void lockIntentRequest(UUID requestId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?::text, ?))",
        requestId,
        REQUEST_LOCK_NAMESPACE.hashCode());
  }

  private static void validateInput(Request request) {
    requireNonNil(request.requestId(), "stable Account request ID");
    requireNonNil(request.creationOperationId(), "creation operation ID");
    requireNonNil(request.gameDesignCreationRequestId(), "Game Design creation request ID");
    requireNonNil(request.initiatingAccountId(), "initiating Account ID");
    requireNonNil(
        request.recentReauthenticationEvidenceId(), "recent reauthentication evidence ID");
    requireNonNil(request.creatorPartyId(), "creator party ID");
    requireNonNil(request.consentOperationId(), "consent operation ID");
    Objects.requireNonNull(request.targetNamespace(), "targetNamespace");
    Objects.requireNonNull(request.sourceGameTenantKey(), "sourceGameTenantKey");
    Objects.requireNonNull(request.name(), "name");
    if (request.consentStatementVersion() <= 0) {
      throw new IllegalArgumentException("Consent statement version must be positive");
    }
    if (!request.affirmativeConsent()) {
      throw new IllegalArgumentException("Tenant-creation consent must be affirmative");
    }
    if (!DNS_LABEL.matcher(request.targetNamespace()).matches()) {
      throw new IllegalArgumentException("targetNamespace must be one lowercase DNS label");
    }
    if (request.sourceGameTenantKey().length() > 72
        || request.sourceGameTenantKey().isBlank()
        || request.sourceGameTenantKey().codePointCount(0, request.sourceGameTenantKey().length())
            > 36) {
      throw new IllegalArgumentException("sourceGameTenantKey must fit the game tenant key bound");
    }
    if (request.name().length() > 200
        || request.name().codePointCount(0, request.name().length()) > 100) {
      throw new IllegalArgumentException("name must fit the game name bound");
    }
    if (request.description() != null
        && (request.description().length() > 510
            || request.description().codePointCount(0, request.description().length()) > 255
            || GameTenantCreationDigest.utf8ByteLength(request.description()) > 1020)) {
      throw new IllegalArgumentException("description must fit the game description bounds");
    }
    GameTenantCreationDigest.requestDigest(
        request.targetNamespace(),
        request.gameDesignCreationRequestId(),
        request.sourceGameTenantKey(),
        request.name(),
        request.description());
  }

  private static byte[] canonicalRequestPayload(Request request) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      frame(output, REQUEST_DOMAIN);
      frame(output, "requestId");
      frame(output, request.requestId().toString());
      frame(output, "creationOperationId");
      frame(output, request.creationOperationId().toString());
      frame(output, "gameDesignCreationRequestId");
      frame(output, request.gameDesignCreationRequestId().toString());
      frame(output, "initiatingAccountId");
      frame(output, request.initiatingAccountId().toString());
      frame(output, "targetNamespace");
      frame(output, request.targetNamespace());
      frame(output, "sourceGameTenantKey");
      frame(output, request.sourceGameTenantKey());
      frame(output, "name");
      frame(output, request.name());
      frame(output, "description");
      frame(output, request.description() == null ? "ABSENT" : "PRESENT");
      if (request.description() != null) {
        frame(output, request.description());
      }
      frame(output, "recentReauthenticationEvidenceId");
      frame(output, request.recentReauthenticationEvidenceId().toString());
      frame(output, "creatorPartyId");
      frame(output, request.creatorPartyId().toString());
      frame(output, "consentOperationId");
      frame(output, request.consentOperationId().toString());
      frame(output, "consentStatementVersion");
      frame(output, Integer.toString(request.consentStatementVersion()));
      frame(output, "affirmativeConsent");
      frame(output, Boolean.toString(request.affirmativeConsent()));
    } catch (IOException impossible) {
      throw new IllegalStateException(
          "Unable to encode Account tenant-creation request", impossible);
    }
    return bytes.toByteArray();
  }

  private static void frame(DataOutputStream output, String value) throws IOException {
    int utf8Length = GameTenantCreationDigest.utf8ByteLength(value);
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    if (encoded.length != utf8Length) {
      throw new IllegalArgumentException("Creation inputs must contain valid Unicode");
    }
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static String digest(byte[] payload) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(
          "SHA-256 is unavailable for Account tenant-creation intent", unavailable);
    }
  }

  private OriginalIntent map(Record row) {
    try {
      Request request =
          new Request(
              required(row, "request_id", UUID.class),
              required(row, "creation_operation_id", UUID.class),
              required(row, "game_design_creation_request_id", UUID.class),
              required(row, "initiating_account_uuid", UUID.class),
              required(row, "target_namespace", String.class),
              required(row, "source_game_tenant_key", String.class),
              required(row, "name", String.class),
              row.get("description", String.class),
              required(row, "recent_reauthentication_evidence_id", UUID.class),
              required(row, "creator_party_id", UUID.class),
              required(row, "consent_operation_id", UUID.class),
              required(row, "consent_statement_version", Integer.class),
              required(row, "affirmative_consent", Boolean.class));
      validateInput(request);
      byte[] requestPayload = required(row, "request_payload", byte[].class);
      String requestDigest = required(row, "request_digest", String.class);
      String gameDesignRequestDigest = required(row, "game_design_request_digest", String.class);
      String expectedGameDesignDigest =
          GameTenantCreationDigest.requestDigest(
              request.targetNamespace(),
              request.gameDesignCreationRequestId(),
              request.sourceGameTenantKey(),
              request.name(),
              request.description());
      if (!Arrays.equals(requestPayload, canonicalRequestPayload(request))
          || !requestDigest.equals(digest(requestPayload))
          || !gameDesignRequestDigest.equals(expectedGameDesignDigest)) {
        throw unavailable("Stored Account tenant-creation intent does not match its exact request");
      }
      OffsetDateTime createdAt = required(row, "created_at", OffsetDateTime.class);
      return new OriginalIntent(
          request.requestId(),
          request.creationOperationId(),
          request.gameDesignCreationRequestId(),
          request.initiatingAccountId(),
          request.targetNamespace(),
          request.sourceGameTenantKey(),
          request.name(),
          request.description(),
          request.recentReauthenticationEvidenceId(),
          request.creatorPartyId(),
          request.consentOperationId(),
          request.consentStatementVersion(),
          request.affirmativeConsent(),
          gameDesignRequestDigest,
          requestPayload,
          requestDigest,
          createdAt.toInstant());
    } catch (IllegalArgumentException | IllegalStateException malformed) {
      throw unavailable("Stored Account tenant-creation intent is malformed", malformed);
    }
  }

  private static void requireSameRequest(
      OriginalIntent original,
      byte[] requestPayload,
      String requestDigest,
      String gameDesignRequestDigest) {
    if (!Arrays.equals(original.requestPayload(), requestPayload)
        || !original.requestDigest().equals(requestDigest)
        || !original.gameDesignRequestDigest().equals(gameDesignRequestDigest)) {
      throw conflict("Account tenant-creation request ID was reused with changed input");
    }
  }

  private static void requireWritableTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account tenant-creation intent requires an active writable Account transaction");
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label + " is required");
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be non-nil");
    }
  }

  private static <T> T required(Record row, String field, Class<T> type) {
    T value = row.get(field, type);
    if (value == null) {
      throw new IllegalStateException(
          "Stored Account tenant-creation intent " + field + " is absent");
    }
    return value;
  }

  private static IllegalStateException unavailable(String message) {
    return new IllegalStateException(message);
  }

  private static IllegalStateException unavailable(String message, Throwable cause) {
    return new IllegalStateException(message, cause);
  }

  private static IntentConflictException conflict(String message) {
    return new IntentConflictException(message);
  }

  /** Original request data. The identifiers and consent fields are not authority evidence. */
  public record Request(
      UUID requestId,
      UUID creationOperationId,
      UUID gameDesignCreationRequestId,
      UUID initiatingAccountId,
      String targetNamespace,
      String sourceGameTenantKey,
      String name,
      String description,
      UUID recentReauthenticationEvidenceId,
      UUID creatorPartyId,
      UUID consentOperationId,
      int consentStatementVersion,
      boolean affirmativeConsent) {}

  /** Exact persisted request data, exposed only as a data receipt and not as authorization. */
  public record OriginalIntent(
      UUID requestId,
      UUID creationOperationId,
      UUID gameDesignCreationRequestId,
      UUID initiatingAccountId,
      String targetNamespace,
      String sourceGameTenantKey,
      String name,
      String description,
      UUID recentReauthenticationEvidenceId,
      UUID creatorPartyId,
      UUID consentOperationId,
      int consentStatementVersion,
      boolean affirmativeConsent,
      String gameDesignRequestDigest,
      byte[] requestPayload,
      String requestDigest,
      Instant createdAt) {
    public OriginalIntent {
      requestPayload = Objects.requireNonNull(requestPayload, "requestPayload").clone();
    }

    @Override
    public byte[] requestPayload() {
      return requestPayload.clone();
    }
  }

  public static final class IntentConflictException extends IllegalStateException {
    public IntentConflictException(String message) {
      super(message);
    }
  }
}
