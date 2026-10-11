package net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.AuthoredSource;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Catalog;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Core;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Descriptor;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.ExpectedVersion;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Mutation;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.PlayableStateScope;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Pointer;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Policy;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Rule;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Scope;
import net.firedevops.firemud.common.operator.CharacterCreateOperatorAction.Target;
import org.junit.jupiter.api.Test;

class CharacterCreateOperatorActionCodecTest {
  private static final String VECTOR_RESOURCE = "/operator/character-create-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void roundTripsAllPublishedVectorsAndMatchesLiteralPreimagesAndDigests() throws IOException {
    JsonNode root = fixture();
    for (JsonNode vector : root.path("vectors")) {
      CharacterCreateOperatorAction action = action(vector);
      byte[] raw = CharacterCreateOperatorActionCodec.encode(action);
      byte[] expectedPreimage = HexFormat.of().parseHex(text(vector, "preimageHex"));

      assertThat(CharacterCreateOperatorActionCodec.decode(raw))
          .as("raw action round trip for %s", text(vector, "name"))
          .isEqualTo(action);
      assertThat(CharacterCreateOperatorActionCodec.canonicalPreimage(action))
          .as("typed preimage for %s", text(vector, "name"))
          .containsExactly(expectedPreimage);
      assertThat(CharacterCreateOperatorActionCodec.canonicalPreimage(raw))
          .as("decoded preimage for %s", text(vector, "name"))
          .containsExactly(expectedPreimage);
      assertThat(CharacterCreateOperatorActionCodec.mutationDigest(raw))
          .as("digest for %s", text(vector, "name"))
          .isEqualTo(text(vector, "digest"));
    }
  }

  @Test
  void rejectsSharedNegativeVectorsWithTheProductionDecoder() throws IOException {
    JsonNode root = fixture();
    JsonNode vectors = root.path("negativeVectors");
    assertThat(vectors.isArray()).isTrue();
    List<String> names = new ArrayList<>();

    for (JsonNode vector : vectors) {
      String name = text(vector, "name");
      names.add(name);
      assertThat(text(vector, "expected")).as(name).isEqualTo("REJECT");
      byte[] base =
          CharacterCreateOperatorActionCodec.encode(action(findVector(text(vector, "baseVector"))));
      byte[] negative = applyNegativeVector(base, vector);

      assertThatThrownBy(() -> CharacterCreateOperatorActionCodec.decode(negative))
          .as("shared negative vector %s", name)
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(names)
        .containsExactlyInAnyOrder(
            "changed-authored-world-selector",
            "changed-canonical-policy-source",
            "changed-descriptor-source",
            "changed-retained-rule-source",
            "changed-creation-input",
            "private-playtest-slot-presence",
            "null-required-value",
            "explicit-absent-audit-slot",
            "reordered-top-level-members",
            "duplicate-top-level-member",
            "descriptor-denies-nonempty-creation-input",
            "noncanonical-base64url-unused-bits",
            "catalog-tenant-cross-namespace",
            "authored-source-owner-uuid-substitution",
            "raw-string-token-over-limit",
            "raw-action-over-limit",
            "normalized-workflow-source-over-limit");
  }

  @Test
  void rejectsRawOrderingDuplicatesUnknownFieldsNullNumbersAndPrivateSlots() throws IOException {
    String raw = rawBase();
    String firstPair =
        "{\"actionFamilySchemaId\":\"firemud.entity.create-character\","
            + "\"actionFamilySchemaVersion\":\"1\",";
    String swappedPair =
        "{\"actionFamilySchemaVersion\":\"1\","
            + "\"actionFamilySchemaId\":\"firemud.entity.create-character\",";
    assertRejected(raw.replace(firstPair, swappedPair));

    String duplicate =
        raw.replace(
            "\"actionFamilySchemaId\":\"firemud.entity.create-character\"",
            "\"actionFamilySchemaId\":\"firemud.entity.create-character\","
                + "\"actionFamilySchemaI\\u0064\":\"firemud.entity.create-character\"");
    assertRejected(duplicate);
    assertRejected(
        raw.replace(
            "\"targetNamespace\":\"firemud\",\"tenantId\":\"a1000000-0000-4000-8000-000000000001\"",
            "\"tenantId\":\"a1000000-0000-4000-8000-000000000001\",\"targetNamespace\":\"firemud\""));
    assertRejected(raw.substring(0, raw.length() - 1) + ",\"auditReason\":\"ABSENT\"}");
    assertRejected(raw.replace("\"targetNamespace\":\"firemud\"", "\"targetNamespace\":null"));
    assertRejected(raw.replace("\"catalogRevision\":\"17\"", "\"catalogRevision\":17"));
    assertRejected(
        raw.replace("\"canonicalCreationInput\":\"{}\"", "\"canonicalCreationInput\":[]"));
    assertRejected(
        raw.replace(
            "\"activeLifecycleEpoch\":\"31\",",
            "\"activeLifecycleEpoch\":\"31\",\"playtestLifecycleId\":\"a9000000-0000-4000-8000-000000000001\","));
  }

  @Test
  void rejectsNoncanonicalBase64InvalidUtf8AndPerSourcePayloadOverflow() throws IOException {
    String raw = rawBase();
    assertRejected(raw.replace("RW1iZXIgUmVhY2g", "RW1iZXIgUmVhY2h"));
    assertRejected(replaceExactUtf8Payload(raw, "publicationWorkflowId", "_w"));

    String tooManyWorkflowBytes =
        Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[1_025]);
    assertRejected(replaceExactUtf8Payload(raw, "publicationWorkflowId", tooManyWorkflowBytes));
  }

  @Test
  void rejectsRawStringAndCompleteActionBoundsBeforeParsing() throws IOException {
    String raw = rawBase();
    String tooLongValue = "x".repeat(CharacterCreateOperatorActionCodec.MAX_RAW_STRING_TOKEN_BYTES);
    assertRejected(
        raw.replace(
            "\"canonicalCreationInput\":\"{}\"",
            "\"canonicalCreationInput\":\"" + tooLongValue + "\""));
    assertThatThrownBy(
            () ->
                CharacterCreateOperatorActionCodec.decode(
                    new byte[CharacterCreateOperatorActionCodec.MAX_RAW_BYTES + 1]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsRepeatedIdentifierSnapshotPolicyAndDescriptorContradictions() throws IOException {
    CharacterCreateOperatorAction base = action(findVector("base-public-player-created"));
    Catalog catalog = base.mutation().catalog();
    Catalog changedCatalog =
        new Catalog(
            "b1000000-0000-4000-8000-000000000001",
            catalog.tenantSlug(),
            catalog.worldSlug(),
            catalog.worldDisplayName(),
            catalog.realmId(),
            catalog.realmSlug(),
            catalog.realmDisplayName(),
            catalog.visible(),
            catalog.publicProduction(),
            catalog.stateScope(),
            catalog.playableStateNamespaceId(),
            catalog.characterCreationPolicy(),
            catalog.catalogRevision());
    assertRejected(withMutation(base, withCatalog(base.mutation(), changedCatalog)));

    Catalog hiddenCatalog =
        new Catalog(
            catalog.tenantId(),
            catalog.tenantSlug(),
            catalog.worldSlug(),
            catalog.worldDisplayName(),
            catalog.realmId(),
            catalog.realmSlug(),
            catalog.realmDisplayName(),
            false,
            catalog.publicProduction(),
            catalog.stateScope(),
            catalog.playableStateNamespaceId(),
            catalog.characterCreationPolicy(),
            catalog.catalogRevision());
    assertRejected(withMutation(base, withCatalog(base.mutation(), hiddenCatalog)));

    Catalog nonProductionCatalog =
        new Catalog(
            catalog.tenantId(),
            catalog.tenantSlug(),
            catalog.worldSlug(),
            catalog.worldDisplayName(),
            catalog.realmId(),
            catalog.realmSlug(),
            catalog.realmDisplayName(),
            catalog.visible(),
            false,
            catalog.stateScope(),
            catalog.playableStateNamespaceId(),
            catalog.characterCreationPolicy(),
            catalog.catalogRevision());
    assertRejected(withMutation(base, withCatalog(base.mutation(), nonProductionCatalog)));

    Pointer pointer = base.mutation().pointer();
    Pointer closedPointer =
        new Pointer(
            pointer.tenantId(),
            pointer.realmId(),
            pointer.worldSlug(),
            pointer.realmSlug(),
            "CLOSED",
            pointer.admissibleGameInstanceId(),
            pointer.pointerVersion(),
            pointer.catalogRevision(),
            pointer.updatedAt());
    Mutation closedPointerMutation =
        new Mutation(
            base.mutation().purpose(),
            base.mutation().entryPolicy(),
            base.mutation().catalog(),
            closedPointer,
            base.mutation().authoredSource(),
            base.mutation().canonicalCreationInput());
    assertRejected(withMutation(base, closedPointerMutation));

    ExpectedVersion expected = base.expectedVersion();
    ExpectedVersion changedExpected =
        new ExpectedVersion(
            expected.catalogRevision(),
            "24",
            expected.activeLifecycleEpoch(),
            expected.evaluatedAt(),
            expected.connectScopeExpiresAt());
    assertRejected(withExpected(base, changedExpected));

    AuthoredSource source = base.mutation().authoredSource();
    assertRejected(
        withMutation(
            base,
            withSource(
                base.mutation(),
                copySource(
                    source,
                    "b1000000-0000-4000-8000-000000000001",
                    null,
                    null,
                    null,
                    null,
                    null))));

    Policy policy = source.policy();
    Policy changedPolicy =
        new Policy(
            policy.commitId(),
            policy.revisionId(),
            policy.logicalRevisionId(),
            policy.canonicalPolicyJson().replace("ember-vale", "other-world"),
            policy.contentDigest());
    assertRejected(
        withMutation(
            base,
            withSource(
                base.mutation(), copySource(source, null, changedPolicy, null, null, null, null))));

    Descriptor descriptor = source.descriptor();
    Descriptor changedDescriptor =
        new Descriptor(descriptor.canonicalJson(), "sha256:" + "0".repeat(64));
    assertRejected(
        withMutation(
            base,
            withSource(
                base.mutation(),
                copySource(source, null, null, null, changedDescriptor, null, null))));

    Core changedCore = new Core("{\"displayName\":\"Wanderer\",\"dispositionKey\":\"newcomer\"}");
    assertRejected(
        withMutation(
            base,
            withSource(
                base.mutation(), copySource(source, null, null, changedCore, null, null, null))));
  }

  private static byte[] applyNegativeVector(byte[] base, JsonNode vector) throws IOException {
    String operation = text(vector, "operation");
    return switch (operation) {
      case "set" -> {
        ObjectNode root = (ObjectNode) JSON.readTree(base);
        setJsonPointer(root, text(vector, "path"), vector.get("value").deepCopy());
        yield JSON.writeValueAsBytes(root);
      }
      case "insert" -> {
        ObjectNode root = (ObjectNode) JSON.readTree(base);
        ObjectNode parent = objectAt(root, text(vector, "objectPath"));
        String key = text(vector, "key");
        if (parent.has(key)) throw new IOException("Negative vector insertion already exists");
        parent.set(key, vector.get("value").deepCopy());
        yield JSON.writeValueAsBytes(root);
      }
      case "replaceExactUtf8Substring" -> {
        ObjectNode root = (ObjectNode) JSON.readTree(base);
        String path = text(vector, "path");
        JsonNode payload = root.at(path);
        if (!payload.isTextual()) throw new IOException("ExactUtf8 bytes path is not textual");
        byte[] sourceBytes = Base64.getUrlDecoder().decode(payload.textValue());
        String source = new String(sourceBytes, StandardCharsets.UTF_8);
        String from = text(vector, "from");
        int first = source.indexOf(from);
        if (first < 0 || source.indexOf(from, first + from.length()) >= 0) {
          throw new IOException("ExactUtf8 replacement must match once");
        }
        String replacement =
            source.substring(0, first)
                + text(vector, "to")
                + source.substring(first + from.length());
        setJsonPointer(
            root,
            path,
            JSON.getNodeFactory()
                .textNode(
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(replacement.getBytes(StandardCharsets.UTF_8))));
        yield JSON.writeValueAsBytes(root);
      }
      case "setExactUtf8Length" -> {
        ObjectNode root = (ObjectNode) JSON.readTree(base);
        int byteCount = vector.path("bytes").intValue();
        if (byteCount < 0
            || byteCount > CharacterCreateOperatorActionCodec.MAX_EXACT_UTF8_SOURCE_BYTES + 1) {
          throw new IOException("ExactUtf8 fixture length is outside the test bound");
        }
        setJsonPointer(
            root,
            text(vector, "path"),
            JSON.getNodeFactory()
                .textNode(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[byteCount])));
        yield JSON.writeValueAsBytes(root);
      }
      case "oversizeString" -> {
        ObjectNode root = (ObjectNode) JSON.readTree(base);
        int characters = vector.path("characters").intValue();
        if (characters < 0 || characters > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES) {
          throw new IOException("Raw token fixture length is outside the test bound");
        }
        setJsonPointer(
            root, text(vector, "path"), JSON.getNodeFactory().textNode("x".repeat(characters)));
        yield JSON.writeValueAsBytes(root);
      }
      case "appendWhitespace" -> {
        int count = vector.path("bytes").intValue();
        if (count < 0 || count > CharacterCreateOperatorActionCodec.MAX_RAW_BYTES + 1) {
          throw new IOException("Raw action fixture length is outside the test bound");
        }
        byte[] oversized = Arrays.copyOf(base, base.length + count);
        Arrays.fill(oversized, base.length, oversized.length, (byte) ' ');
        yield oversized;
      }
      case "swapTopLevelMembers" -> {
        String raw = new String(base, StandardCharsets.UTF_8);
        String first =
            "{\"actionFamilySchemaId\":\"firemud.entity.create-character\","
                + "\"actionFamilySchemaVersion\":\"1\",";
        String swapped =
            "{\"actionFamilySchemaVersion\":\"1\","
                + "\"actionFamilySchemaId\":\"firemud.entity.create-character\",";
        if (!raw.contains(first)) throw new IOException("Top-level order marker not found");
        yield raw.replace(first, swapped).getBytes(StandardCharsets.UTF_8);
      }
      case "duplicateTopLevelSchemaId" -> {
        String raw = new String(base, StandardCharsets.UTF_8);
        String field = "\"actionFamilySchemaId\":\"firemud.entity.create-character\"";
        if (!raw.contains(field)) throw new IOException("Top-level schema marker not found");
        yield raw.replaceFirst(
                java.util.regex.Pattern.quote(field),
                java.util.regex.Matcher.quoteReplacement(field + "," + field))
            .getBytes(StandardCharsets.UTF_8);
      }
      default -> throw new IOException("Unknown shared negative-vector operation: " + operation);
    };
  }

  private static void setJsonPointer(ObjectNode root, String pointer, JsonNode value)
      throws IOException {
    int separator = pointer.lastIndexOf('/');
    if (separator < 0) throw new IOException("Invalid fixture JSON pointer");
    String parentPath = pointer.substring(0, separator);
    String field = pointer.substring(separator + 1).replace("~1", "/").replace("~0", "~");
    ObjectNode parent = objectAt(root, parentPath);
    if (!parent.has(field)) throw new IOException("Negative vector target field is absent");
    parent.set(field, value);
  }

  private static ObjectNode objectAt(ObjectNode root, String pointer) throws IOException {
    JsonNode node = pointer.isEmpty() ? root : root.at(pointer);
    if (!(node instanceof ObjectNode object))
      throw new IOException("Fixture path is not an object");
    return object;
  }

  private static CharacterCreateOperatorAction action(JsonNode vector) {
    JsonNode scopeNode = vector.path("scope");
    JsonNode targetNode = vector.path("target");
    JsonNode expectedNode = vector.path("expectedVersion");
    JsonNode mutationNode = vector.path("mutation");
    JsonNode catalogNode = mutationNode.path("catalog");
    JsonNode pointerNode = mutationNode.path("pointer");
    JsonNode authoredNode = mutationNode.path("authoredSource");
    JsonNode policyNode = authoredNode.path("policy");
    JsonNode coreNode = authoredNode.path("core");
    JsonNode descriptorNode = authoredNode.path("descriptor");

    Scope scope =
        new Scope(
            text(scopeNode, "targetNamespace"),
            text(scopeNode, "tenantId"),
            text(scopeNode, "playableStateNamespaceId"),
            PlayableStateScope.valueOf(text(scopeNode, "playableStateScope")));
    Target target =
        new Target(
            text(targetNode, "accountId"),
            text(targetNode, "realmId"),
            text(targetNode, "worldSlug"),
            text(targetNode, "realmSlug"),
            text(targetNode, "gameInstanceId"),
            text(targetNode, "versionId"));
    ExpectedVersion expected =
        new ExpectedVersion(
            text(expectedNode, "catalogRevision"),
            text(expectedNode, "pointerVersion"),
            text(expectedNode, "activeLifecycleEpoch"),
            Instant.parse(text(expectedNode, "evaluatedAt")),
            Instant.parse(text(expectedNode, "connectScopeExpiresAt")));
    Catalog catalog =
        new Catalog(
            text(catalogNode, "tenantId"),
            text(catalogNode, "tenantSlug"),
            text(catalogNode, "worldSlug"),
            text(catalogNode, "worldDisplayName"),
            text(catalogNode, "realmId"),
            text(catalogNode, "realmSlug"),
            text(catalogNode, "realmDisplayName"),
            catalogNode.path("visible").booleanValue(),
            catalogNode.path("publicProduction").booleanValue(),
            text(catalogNode, "stateScope"),
            text(catalogNode, "playableStateNamespaceId"),
            text(catalogNode, "characterCreationPolicy"),
            text(catalogNode, "catalogRevision"));
    Pointer pointer =
        new Pointer(
            text(pointerNode, "tenantId"),
            text(pointerNode, "realmId"),
            text(pointerNode, "worldSlug"),
            text(pointerNode, "realmSlug"),
            text(pointerNode, "admissionState"),
            text(pointerNode, "admissibleGameInstanceId"),
            text(pointerNode, "pointerVersion"),
            text(pointerNode, "catalogRevision"),
            Instant.parse(text(pointerNode, "updatedAt")));
    Policy policy =
        new Policy(
            text(policyNode, "commitId"),
            text(policyNode, "revisionId"),
            text(policyNode, "logicalRevisionId"),
            text(policyNode, "canonicalPolicyJson"),
            text(policyNode, "contentDigest"));
    Core core = new Core(text(coreNode, "canonicalJson"));
    Descriptor descriptor =
        new Descriptor(text(descriptorNode, "canonicalJson"), text(descriptorNode, "digest"));
    AuthoredSource authoredSource =
        new AuthoredSource(
            text(authoredNode, "schema"),
            text(authoredNode, "bindingId"),
            text(authoredNode, "bindingDigest"),
            text(authoredNode, "canonicalTenantId"),
            text(authoredNode, "canonicalVersionId"),
            text(authoredNode, "worldSourceOperationId"),
            text(authoredNode, "worldSourceEvidenceDigest"),
            text(authoredNode, "worldSlug"),
            text(authoredNode, "publicationWorkflowId"),
            text(authoredNode, "selectedCommitId"),
            text(authoredNode, "selectedCommitDigest"),
            policy,
            core,
            descriptor,
            rule(authoredNode.path("disposition")),
            rule(authoredNode.path("safeFeedback")));
    Mutation mutation =
        new Mutation(
            text(mutationNode, "purpose"),
            text(mutationNode, "entryPolicy"),
            catalog,
            pointer,
            authoredSource,
            text(mutationNode, "canonicalCreationInput"));
    return new CharacterCreateOperatorAction(
        scope, target, expected, mutation, CharacterCreateOperatorAction.AuditReason.ABSENT);
  }

  private static Rule rule(JsonNode node) {
    return new Rule(
        text(node, "key"),
        text(node, "definitionJson"),
        text(node, "definitionDigest"),
        text(node, "sourceCommitId"),
        text(node, "sourceCommitDigest"),
        text(node, "revisionId"),
        text(node, "revisionOrder"));
  }

  private static JsonNode fixture() throws IOException {
    try (InputStream input =
        CharacterCreateOperatorActionCodecTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (input == null) throw new IOException("Missing character-create vector resource");
      return JSON.readTree(input);
    }
  }

  private static JsonNode findVector(String name) throws IOException {
    for (JsonNode vector : fixture().path("vectors")) {
      if (name.equals(text(vector, "name"))) return vector;
    }
    throw new IOException("Missing character-create vector: " + name);
  }

  private static String text(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isTextual())
      throw new IllegalArgumentException("Missing text " + name);
    return value.textValue();
  }

  private static String rawBase() throws IOException {
    return new String(
        CharacterCreateOperatorActionCodec.encode(action(findVector("base-public-player-created"))),
        StandardCharsets.US_ASCII);
  }

  private static void assertRejected(String raw) {
    assertThatThrownBy(
            () ->
                CharacterCreateOperatorActionCodec.decode(raw.getBytes(StandardCharsets.US_ASCII)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertRejected(CharacterCreateOperatorAction action) {
    assertThatThrownBy(() -> CharacterCreateOperatorActionCodec.encode(action))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String replaceExactUtf8Payload(String raw, String field, String payload) {
    String marker = "\"" + field + "\":{\"encoding\":\"utf8-base64url-unpadded\",\"bytes\":\"";
    int start = raw.indexOf(marker);
    if (start < 0) throw new IllegalStateException("ExactUtf8 field not found: " + field);
    start += marker.length();
    int end = raw.indexOf('"', start);
    return raw.substring(0, start) + payload + raw.substring(end);
  }

  private static CharacterCreateOperatorAction withExpected(
      CharacterCreateOperatorAction action, ExpectedVersion expected) {
    return new CharacterCreateOperatorAction(
        action.scope(), action.target(), expected, action.mutation(), action.auditReason());
  }

  private static Mutation withCatalog(Mutation mutation, Catalog catalog) {
    return new Mutation(
        mutation.purpose(),
        mutation.entryPolicy(),
        catalog,
        mutation.pointer(),
        mutation.authoredSource(),
        mutation.canonicalCreationInput());
  }

  private static Mutation withSource(Mutation mutation, AuthoredSource source) {
    return new Mutation(
        mutation.purpose(),
        mutation.entryPolicy(),
        mutation.catalog(),
        mutation.pointer(),
        source,
        mutation.canonicalCreationInput());
  }

  private static CharacterCreateOperatorAction withMutation(
      CharacterCreateOperatorAction action, Mutation mutation) {
    return new CharacterCreateOperatorAction(
        action.scope(), action.target(), action.expectedVersion(), mutation, action.auditReason());
  }

  private static AuthoredSource copySource(
      AuthoredSource source,
      String canonicalTenantId,
      Policy policy,
      Core core,
      Descriptor descriptor,
      Rule disposition,
      Rule safeFeedback) {
    return new AuthoredSource(
        source.schema(),
        source.bindingId(),
        source.bindingDigest(),
        canonicalTenantId == null ? source.canonicalTenantId() : canonicalTenantId,
        source.canonicalVersionId(),
        source.worldSourceOperationId(),
        source.worldSourceEvidenceDigest(),
        source.worldSlug(),
        source.publicationWorkflowId(),
        source.selectedCommitId(),
        source.selectedCommitDigest(),
        policy == null ? source.policy() : policy,
        core == null ? source.core() : core,
        descriptor == null ? source.descriptor() : descriptor,
        disposition == null ? source.disposition() : disposition,
        safeFeedback == null ? source.safeFeedback() : safeFeedback);
  }
}
