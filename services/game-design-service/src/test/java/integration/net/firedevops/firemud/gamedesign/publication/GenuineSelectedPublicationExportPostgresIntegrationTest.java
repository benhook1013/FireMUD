package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import io.grpc.util.MutableHandlerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.firedevops.firemud.account.v1.AuthorityEvidenceBundleReference;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionRequest;
import net.firedevops.firemud.account.v1.ReadRedeemedOperationProjectionResponse;
import net.firedevops.firemud.account.v1.StartSessionOperatorAuthorizationServiceGrpc;
import net.firedevops.firemud.accountservice.authordraft.AccountDraftCommitOrderReadGrpcService;
import net.firedevops.firemud.accountservice.authordraft.DraftAuthorizationFenceRepository;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeService;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationEmptySelectedSourceIntakeTerminalReadService;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationSelectedSourceIntakeCommandGrpcService;
import net.firedevops.firemud.automationscripting.sourceintake.AutomationSelectedSourceIntakeTerminalReadGrpcService;
import net.firedevops.firemud.common.account.StartSessionRedeemedOperationProjectionClient;
import net.firedevops.firemud.common.account.sourceintake.AccountSelectedOwnerIntakeAuthorizationGrpcClient;
import net.firedevops.firemud.common.account.sourceintake.AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient;
import net.firedevops.firemud.common.account.sourceintake.GrpcSelectedOwnerIntakeSourceReadClient;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationProducerEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSettlementEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadEvidence;
import net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeSourceReadScope;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderClient;
import net.firedevops.firemud.common.authoring.AccountOriginalDraftOrderGrpcCodec;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.AffectedUnit;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.Owner;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.RevisionPayload;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import net.firedevops.firemud.common.authoring.DraftCommitOrderReadClient;
import net.firedevops.firemud.common.authoring.GameDesignDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityClient;
import net.firedevops.firemud.common.authoring.WorldAuthoredVersionIdentityEvidence;
import net.firedevops.firemud.common.authoring.WorldDraftTerminalReadClient;
import net.firedevops.firemud.common.authoring.WorldOriginalDraftGraphApplyClient;
import net.firedevops.firemud.common.automation.AutomationAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.automation.sourceintake.AutomationEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.automation.sourceintake.AutomationSelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.entity.EntityAuthoredSourceInventoryDeclaration;
import net.firedevops.firemud.common.entity.sourceintake.EntityEmptySelectedSourceIntakeReceipt;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeCommandEvidence;
import net.firedevops.firemud.common.entity.sourceintake.EntitySelectedSourceIntakeTerminalReadEvidence;
import net.firedevops.firemud.common.gamedesign.AssetSource;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamedesign.GameplayRuleSource;
import net.firedevops.firemud.common.gamedesign.GrpcSelectedOwnerIntakeSourceClient;
import net.firedevops.firemud.common.gamedesign.RealmPolicySource;
import net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceContent;
import net.firedevops.firemud.common.gamedesign.StartSessionTemplateAssociationReadEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationEvidence;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeTerminalReadClient;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameplayAbilitySchemaProjection;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSourceReadClient;
import net.firedevops.firemud.common.gamelogic.GrpcAccountGameLogicIntakeSettlementReadClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeAuthorizationClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeRetainClient;
import net.firedevops.firemud.common.gamelogic.GrpcGameLogicIntakeSourceReadClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentityInterceptor;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.operator.StartSessionAuthorityEvidenceBundle;
import net.firedevops.firemud.common.operator.StartSessionOperatorAction;
import net.firedevops.firemud.common.operator.StartSessionPostAuthorizationExecutionTuple;
import net.firedevops.firemud.common.operator.StartSessionPreAuthorizationReservationTuple;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationBinding;
import net.firedevops.firemud.common.publication.AccountPublicationAuthorizationReadClient;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionBinding;
import net.firedevops.firemud.common.publication.AuthoredDraftPublishSelectionReadClient;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeClient;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeEvidence;
import net.firedevops.firemud.common.publication.WorldSelectedDraftPublicationFreezeGrpcCodec;
import net.firedevops.firemud.common.publication.WorldSelectedOwnerInventoryGrpcClient;
import net.firedevops.firemud.common.publication.WorldSelectedPublicationArtifactInventoryClient;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.common.security.sourceintake.GrpcAutomationSelectedSourceIntakeCommandClient;
import net.firedevops.firemud.common.security.sourceintake.GrpcEntitySelectedSourceIntakeCommandClient;
import net.firedevops.firemud.common.security.sourceintake.GrpcEntitySelectedSourceIntakeTerminalReadClient;
import net.firedevops.firemud.common.security.sourceintake.GrpcSelectedOwnerIntakeAuthorizationProducerClient;
import net.firedevops.firemud.common.security.sourceintake.GrpcSelectedOwnerIntakeSettlementClient;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeClient;
import net.firedevops.firemud.common.tenant.WorldAuthoredSourceIntakeGrpcCodec;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeRepository;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeService;
import net.firedevops.firemud.entitymanagement.sourceintake.EntityEmptySelectedSourceIntakeTerminalReadService;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedSourceIntakeCommandGrpcService;
import net.firedevops.firemud.entitymanagement.sourceintake.EntitySelectedSourceIntakeTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.client.GameLogicClient;
import net.firedevops.firemud.gamedesign.client.WorldPublishedStartLocationClient;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelection;
import net.firedevops.firemud.gamedesign.draft.AuthoredDraftPublishSelectionRepository;
import net.firedevops.firemud.gamedesign.draft.DraftCommitCoordinatorRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcomeRepository;
import net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalReadGrpcService;
import net.firedevops.firemud.gamedesign.draft.GameDesignSelectedDraftPublicationReadGrpcService;
import net.firedevops.firemud.gamedesign.draft.GameDesignWorldSourceCommitService;
import net.firedevops.firemud.gamedesign.dto.CompleteLaunchBindingDto;
import net.firedevops.firemud.gamedesign.dto.DesignControlPlaneDigestDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.entity.GameAsset;
import net.firedevops.firemud.gamedesign.entity.Version;
import net.firedevops.firemud.gamedesign.mapper.VersionMapper;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;
import net.firedevops.firemud.gamedesign.publication.GameDesignGameplayRuleSourceReadGrpcService;
import net.firedevops.firemud.gamedesign.publication.GameDesignGameplayRuleSourceReadService;
import net.firedevops.firemud.gamedesign.publication.GameDesignSelectedOwnerIntakeSourceGrpcService;
import net.firedevops.firemud.gamedesign.publication.GameDesignSelectedOwnerIntakeSourceReadService;
import net.firedevops.firemud.gamedesign.publication.GameDesignSourceRepository;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventory;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftAssetInventoryReadService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftControlPlaneDigest;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicIntakeCommandService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceipt;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftGameLogicReceiptService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationAdmissionService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftPublicationDigestReadService;
import net.firedevops.firemud.gamedesign.publication.SelectedDraftTemplateWorldSourceAssociationRepository;
import net.firedevops.firemud.gamedesign.publication.StartSessionLaunchDescriptorProducer;
import net.firedevops.firemud.gamedesign.publication.StartSessionTemplateAssociationReadService;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSource;
import net.firedevops.firemud.gamedesign.publication.TemplateConfigSourceRepository;
import net.firedevops.firemud.gamedesign.publication.TemplateReferenceRepository;
import net.firedevops.firemud.gamedesign.repository.GameAssetRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceDeliveryRepository;
import net.firedevops.firemud.gamedesign.repository.GameAuthoredWorldSourceRepository;
import net.firedevops.firemud.gamedesign.repository.GameRepository;
import net.firedevops.firemud.gamedesign.repository.GameTemplateRepository;
import net.firedevops.firemud.gamedesign.repository.GameTenantCreationRepository;
import net.firedevops.firemud.gamedesign.repository.LaunchDescriptorRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptParticipantDigestRepository;
import net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository;
import net.firedevops.firemud.gamedesign.repository.PublishedReleaseBundleRepository;
import net.firedevops.firemud.gamedesign.repository.RevisionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetArtifactRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPublicationRepository;
import net.firedevops.firemud.gamedesign.repository.VersionAssetPurgeWorkflowRepository;
import net.firedevops.firemud.gamedesign.repository.VersionRepository;
import net.firedevops.firemud.gamedesign.repository.VersionTemplateRemapSetRepository;
import net.firedevops.firemud.gamedesign.service.AssetExportService.SelectedExportResult;
import net.firedevops.firemud.gamedesign.service.CompleteLaunchBindingService;
import net.firedevops.firemud.gamedesign.service.ExportedAssetManifest;
import net.firedevops.firemud.gamedesign.service.GameAuthoredHelpTopicService;
import net.firedevops.firemud.gamedesign.service.LaunchDescriptorService;
import net.firedevops.firemud.gamedesign.service.PingService;
import net.firedevops.firemud.gamedesign.service.PublishAttemptService;
import net.firedevops.firemud.gamedesign.service.PublishGateService;
import net.firedevops.firemud.gamedesign.service.RecordedParticipantDigestService;
import net.firedevops.firemud.gamedesign.service.RevisionService;
import net.firedevops.firemud.gamedesign.service.SettingsAuthorityService;
import net.firedevops.firemud.gamedesign.service.TemplateRemapSetService;
import net.firedevops.firemud.gamedesign.service.VersionAssetArtifactService;
import net.firedevops.firemud.gamedesign.service.VersionAssetExportCandidateService;
import net.firedevops.firemud.gamedesign.service.VersionService;
import net.firedevops.firemud.gamedesign.service.impl.AssetExportServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.AuthoredWorldVersionStateService;
import net.firedevops.firemud.gamedesign.service.impl.CompleteLaunchBindingServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.GameDesignGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.LaunchDescriptorServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishAttemptServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.PublishedReleaseBundleServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.TemplateRemapSetServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.TemporalVersionPublishWorkflowMetadataResolver;
import net.firedevops.firemud.gamedesign.service.impl.TenantIdentityGrpcService;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetArtifactServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionAssetExportCandidateServiceImpl;
import net.firedevops.firemud.gamedesign.service.impl.VersionPublishCommandServiceImpl;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.WorldDesignMutationRevision;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.service.impl.GameLogicGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeRepository;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeTerminalReadGrpcService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeTerminalReadService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;
import net.firedevops.firemud.test.TestContainerImages;
import net.firedevops.firemud.worldmanagement.tenant.GenuineWorldSelectedPublicationProofFactory;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredSourceIntakeService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDesignPublicationFenceRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftCommitOrderVerifier;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalOutcomeRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldDraftTerminalReadGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplicationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldOriginalDraftGraphApplyGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldSelectedOwnerInventoryReadProofFixture;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignAggregateType;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignMutationOperation;
import net.firedevops.firemud.worldmanagement.v1.WorldDesignScopeType;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldFreshGraphFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceClosureDeclaration;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamily;
import net.firedevops.firemud.worldmanagement.v1.WorldInboundSourceFamilyCount;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.springframework.boot.jooq.autoconfigure.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.ObjectMapper;

/**
 * Real original GD+World source settlement and Account/GL intake-to-GD-receipt, followed by actual
 * selected inventory/export, the owner-local publication finalizer, descriptor SQL storage, and the
 * owner-local complete launch-binding read. The distinct Account publication order comes from the
 * actual Account owner fixture; its inbound Game Design peer and upstream selection/platform/legal
 * evidence are supplied by test context. The immutable selection read and held-order reads use
 * loopback mTLS. World freeze, immutable inventory and published selector use their actual owner
 * services over loopback mTLS, with the canonical frozen selector captured owner-locally from the
 * committed freeze and retained APPLIED graph. Entity/Automation source disclosure uses the genuine
 * Account issuer and retained preliminary reservations, then loopback mTLS for both the Account
 * permission hop and the Game Design source hop. Automation additionally composes real Account
 * finalization, the actual recipient-specific World closure/inventory reader, the Automation
 * empty-source receipt owner, and Account terminal settlement. Its positive Account authorization
 * command creates a fresh reservation through the native producer. Automation retention and Account
 * settlement each traverse their native command receivers over loopback mTLS. The earlier
 * disclosure/abort setup uses stipulated incoming Game Design peer contexts and is not either
 * positive authorization operation. Entity retention also traverses the native Account, Game
 * Design, Entity, World and settlement boundaries over loopback mTLS, with an isolated Entity
 * PostgreSQL schema and migration path. Entity/Automation publication participant digests and the
 * StartSession Account projection remain test doubles. MinIO object-store I/O is real. The complete
 * launch-binding case wires the actual Game Design handler and World Management mTLS client on this
 * fixture's loopback server to the actual owner implementation in an explicitly established
 * read-only repeatable-read snapshot; unrelated handler dependencies are isolated. This fixture
 * does not provide production-mounted certificates or full application server wiring, production
 * ingress, Account-projection-backed StartSession admission, a complete four-owner authenticated
 * release, runtime launch, activation or registration.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("resource")
class GenuineSelectedPublicationExportPostgresIntegrationTest {
  private static final String NAMESPACE = "firemud";
  private static final String MINIO_IMAGE =
      "ghcr.io/benhook1013/minio-server@sha256:a091800eb1c700ea662634c9ad5d9e4cf6980a1f61027a9b80aef0163e66c22a";
  private static final String MINIO_ACCESS_KEY = "firemud-test";
  private static final String MINIO_SECRET_KEY = "firemud-test-secret";
  private static final String MINIO_BUCKET = "genuine-selected-export-test";
  private static final Region MINIO_REGION = Region.US_EAST_1;
  private static final Network NETWORK = Network.newNetwork();
  private static final Map<String, String> ENTITY_EMPTY_ONLY_PROVIDER_TABLES =
      Map.ofEntries(
          Map.entry(
              "ACTOR_BODY_LAYOUT_ASSIGNMENTS", "entity_empty_source_actor_body_layout_assignments"),
          Map.entry("ARCHETYPE_ASSIGNMENTS", "entity_empty_source_archetype_assignments"),
          Map.entry("ARCHETYPE_CONSTRAINTS", "entity_empty_source_archetype_constraints"),
          Map.entry("ARCHETYPE_ROOTS", "entity_empty_source_archetype_roots"),
          Map.entry("BALANCE_CURVE_ATTACHMENTS", "entity_empty_source_balance_curve_attachments"),
          Map.entry("BALANCE_CURVE_ROOTS", "entity_empty_source_balance_curve_roots"),
          Map.entry("EQUIPMENT_ATTACHMENT_RULES", "entity_empty_source_equipment_attachment_rules"),
          Map.entry("EQUIPMENT_CAPABILITIES", "entity_empty_source_equipment_capabilities"),
          Map.entry(
              "EQUIPMENT_COMPATIBILITY_RULES", "entity_empty_source_equipment_compatibility_rules"),
          Map.entry("EQUIPMENT_OCCUPANCY_RULES", "entity_empty_source_equipment_occupancy_rules"),
          Map.entry("INBOUND_LOOT_BINDINGS", "entity_empty_source_inbound_loot_bindings"),
          Map.entry("LOOT_ITEM_MAPPINGS", "entity_empty_source_loot_item_mappings"),
          Map.entry("LOOT_TABLE_ROOTS", "entity_empty_source_loot_table_roots"),
          Map.entry(
              "OTHER_ACTOR_TEMPLATE_ROOTS", "entity_empty_source_other_actor_template_roots"));

  // Keep this digest aligned with the minio service in docker/docker-compose.yml.
  @Container
  private static final GenericContainer<?> MINIO =
      new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
          .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
          .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
          .withCommand("server", "/data")
          .withExposedPorts(9000)
          .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

  @Container
  private static final PostgreSQLContainer<?> GAME_DESIGN_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> WORLD_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> ACCOUNT_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> GAME_LOGIC_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final PostgreSQLContainer<?> ENTITY_POSTGRES =
      new PostgreSQLContainer<>(TestContainerImages.postgres());

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .withNetworkAliases("selected-export-account-primary")
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always");

  @Container
  private static final GenericContainer<?> REDIS_REPLICA =
      new GenericContainer<>(TestContainerImages.redis())
          .withNetwork(NETWORK)
          .dependsOn(REDIS)
          .withCommand(
              "redis-server",
              "--bind",
              "0.0.0.0",
              "--protected-mode",
              "no",
              "--appendonly",
              "yes",
              "--appendfsync",
              "always",
              "--replicaof",
              "selected-export-account-primary",
              "6379");

  @TempDir Path temporary;

  private MinioConditionalObjectStore objectStore;

  @AfterEach
  void closeMinioClient() {
    if (objectStore != null) {
      objectStore.close();
      objectStore = null;
    }
  }

  @Test
  void realSelectedSourceReceiptInventoryAndExportAreExactAndRetryable() throws Exception {
    var gd = gameDesignStore();
    var world = serviceStore(WORLD_POSTGRES, "world-management");
    var gameLogic = serviceStore(GAME_LOGIC_POSTGRES, "game-logic");
    var automation = serviceStore(GAME_LOGIC_POSTGRES, "automation-scripting");
    var entity = serviceStore(ENTITY_POSTGRES, "entity-management");
    var pki = new TestPki(temporary.resolve("pki"));
    var gdHandlers = new MutableHandlerRegistry();
    var worldHandlers = new MutableHandlerRegistry();
    var accountHandlers = new MutableHandlerRegistry();
    var gameLogicHandlers = new MutableHandlerRegistry();
    var automationHandlers = new MutableHandlerRegistry();
    var entityHandlers = new MutableHandlerRegistry();
    Map<String, StartSessionProjectionBinding> startSessionProjections = new ConcurrentHashMap<>();

    Server gdServer = startServer(pki, "game-design-service", gdHandlers);
    Server worldServer = startServer(pki, "world-management-service", worldHandlers);
    Server accountServer = startServer(pki, "account-service", accountHandlers);
    Server gameLogicServer = startServer(pki, "game-logic-service", gameLogicHandlers);
    Server automationServer = startServer(pki, "automation-scripting-service", automationHandlers);
    Server entityServer = startServer(pki, "entity-management-service", entityHandlers);
    try (var account =
        new AccountControlUiOriginalOrderFixture(
            ACCOUNT_POSTGRES.getJdbcUrl(),
            ACCOUNT_POSTGRES.getUsername(),
            ACCOUNT_POSTGRES.getPassword(),
            REDIS.getHost(),
            REDIS.getMappedPort(6379),
            temporary.resolve("account"),
            gd.target().canonicalTenantId())) {
      var endpoints = new ServiceEndpointsProperties();
      endpoints.setAccountService(loopback(accountServer));
      endpoints.setGameDesignService(loopback(gdServer));
      endpoints.setWorldManagementService(loopback(worldServer));
      endpoints.setGameLogicService(loopback(gameLogicServer));
      endpoints.setAutomationScriptingService(loopback(automationServer));
      endpoints.setEntityManagementService(loopback(entityServer));
      var channels = new GrpcChannelFactory();
      var automationIntakeRepository =
          new AutomationEmptySelectedSourceIntakeRepository(automation.dsl());
      var entityIntakeRepository = new EntityEmptySelectedSourceIntakeRepository(entity.dsl());
      register(
          automationHandlers,
          new AutomationSelectedSourceIntakeTerminalReadGrpcService(
              new AutomationEmptySelectedSourceIntakeTerminalReadService(
                  automationIntakeRepository, NAMESPACE)));
      register(
          entityHandlers,
          new EntitySelectedSourceIntakeTerminalReadGrpcService(
              new EntityEmptySelectedSourceIntakeTerminalReadService(
                  entityIntakeRepository, NAMESPACE)));

      var sourceRepository = new GameAuthoredWorldSourceRepository(gd.dsl());
      register(
          gdHandlers,
          new TenantIdentityGrpcService(
              new GameTenantCreationRepository(gd.dsl(), new GameRepository(gd.dsl())),
              sourceRepository,
              NAMESPACE),
          new AuthoredWorldVersionStateGrpcService(
              new AuthoredWorldVersionStateService(gd.transactions(), sourceRepository),
              NAMESPACE));

      var worldIntakes = new WorldAuthoredSourceIntakeRepository(world.dsl());
      var worldIdentities = new WorldAuthoredVersionIdentityRepository(world.dsl());

      try (var sourceClient =
              new AuthoredWorldSourceClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var versionClient =
              new net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var worldIntakeClient =
              new WorldAuthoredSourceIntakeClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var worldIdentityClient =
              new WorldAuthoredVersionIdentityClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var accountOriginalOrderClient =
              new AccountOriginalDraftOrderClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var accountHeldOrderClient =
              new DraftCommitOrderReadClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var worldApplyClient =
              new WorldOriginalDraftGraphApplyClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdTerminalClient =
              new GameDesignDraftTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var worldTerminalClient =
              new WorldDraftTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var accountToGdSource =
              new GameplayRuleSourceReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var gdToAccountPermission =
              new GrpcGameLogicIntakeSourceReadClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdToAccountSelectedOwnerPermission =
              new GrpcSelectedOwnerIntakeSourceReadClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var accountToGdSelectedOwnerSource =
              new GrpcSelectedOwnerIntakeSourceClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var gdToAccountSelectedOwnerAuthorizationProducer =
              new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var wrongWorkloadSelectedOwnerAuthorizationProducer =
              new GrpcSelectedOwnerIntakeAuthorizationProducerClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var automationToAccountHeld =
              new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
                  endpoints, pki.client("automation-scripting-service"), channels, NAMESPACE);
          var automationToWorldInventory =
              new WorldSelectedOwnerInventoryGrpcClient(
                  endpoints, pki.client("automation-scripting-service"), channels, NAMESPACE);
          var worldToAccountClosure =
              new AccountSelectedOwnerIntakeWorldClosureAuthorizationGrpcClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var accountToAutomationTerminal =
              new AutomationSelectedSourceIntakeTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var automationSelectedSourceCommandClient =
              new GrpcAutomationSelectedSourceIntakeCommandClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var wrongWorkloadAutomationSelectedSourceCommandClient =
              new GrpcAutomationSelectedSourceIntakeCommandClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var entityToAccountHeld =
              new AccountSelectedOwnerIntakeAuthorizationGrpcClient(
                  endpoints, pki.client("entity-management-service"), channels, NAMESPACE);
          var entityToWorldInventory =
              new WorldSelectedOwnerInventoryGrpcClient(
                  endpoints, pki.client("entity-management-service"), channels, NAMESPACE);
          var accountToEntityTerminal =
              new GrpcEntitySelectedSourceIntakeTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var entitySelectedSourceCommandClient =
              new GrpcEntitySelectedSourceIntakeCommandClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var wrongWorkloadEntitySelectedSourceCommandClient =
              new GrpcEntitySelectedSourceIntakeCommandClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var selectedOwnerSettlementClient =
              new GrpcSelectedOwnerIntakeSettlementClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var wrongWorkloadSelectedOwnerSettlementClient =
              new GrpcSelectedOwnerIntakeSettlementClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var glToAccountHeld =
              new GameLogicIntakeAuthorizationReadClient(
                  endpoints, pki.client("game-logic-service"), channels, NAMESPACE);
          var glToGdSource =
              new GameplayRuleSourceReadClient(
                  endpoints, pki.client("game-logic-service"), channels, NAMESPACE);
          var accountToGlTerminal =
              new GameLogicIntakeTerminalReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var gdToAccountIntakeAuthorization =
              new GrpcGameLogicIntakeAuthorizationClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdToGlRetain =
              new GrpcGameLogicIntakeRetainClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var gdToAccountSettlement =
              new GrpcAccountGameLogicIntakeSettlementReadClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var accountSelectionReadClient =
              new AuthoredDraftPublishSelectionReadClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE);
          var worldSelectionReadClient =
              new AuthoredDraftPublishSelectionReadClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var worldAccountReadClient =
              new AccountPublicationAuthorizationReadClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE);
          var gdAccountPublicationReadClient =
              new AccountPublicationAuthorizationReadClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var selectedPublicationFreezeClient =
              new WorldSelectedDraftPublicationFreezeClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var selectedPublicationInventoryClient =
              new WorldSelectedPublicationArtifactInventoryClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE);
          var publishedStartLocationClient =
              new WorldPublishedStartLocationClient(
                  endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
        sourceClient.init();
        versionClient.init();
        var worldIntakeOwner =
            new WorldAuthoredSourceIntakeService(
                sourceClient, worldIntakes, world.transactions(), NAMESPACE);
        var worldIdentityOwner =
            new WorldAuthoredVersionIdentityService(
                versionClient, worldIdentities, worldIntakes, world.transactions(), NAMESPACE);
        register(
            worldHandlers,
            new WorldAuthoredSourceIntakeGrpcService(worldIntakeOwner, NAMESPACE),
            new WorldAuthoredVersionIdentityGrpcService(worldIdentityOwner, NAMESPACE));
        worldIntakeClient.init();
        worldIdentityClient.init();

        var worldSource =
            gd.transaction(
                () ->
                    sourceRepository.register(
                        NAMESPACE,
                        UUID.randomUUID(),
                        gd.target().canonicalTenantId(),
                        "genuine-selected-export-tenant",
                        "genuine-selected-export-world",
                        "Genuine selected export source"));
        assertThat(worldSource.canonicalTenantId()).isEqualTo(gd.target().canonicalTenantId());
        assertThat(worldSource.sourceGameRowId()).isEqualTo(gd.target().sourceGameRowId());
        assertThat(worldSource.sourceGameTenantKey()).isEqualTo(gd.target().sourceGameTenantKey());
        assertThat(worldSource.provenanceKind()).isEqualTo(gd.target().sourceProvenanceKind());
        var worldSourceDeliveryRepository = new GameAuthoredWorldSourceDeliveryRepository(gd.dsl());
        var originalWorldSourceDelivery =
            worldSourceDeliveryRepository.read(worldSource.operationId()).orElseThrow();
        assertThat(originalWorldSourceDelivery.source()).isEqualTo(worldSource);
        assertThat(originalWorldSourceDelivery.acknowledgedReceipt()).isEmpty();
        assertThat(originalWorldSourceDelivery.request().intakeRequestId())
            .isNotEqualTo(worldSource.operationId());
        assertThat(originalWorldSourceDelivery.request().sourceOperationId())
            .isEqualTo(worldSource.operationId());
        assertThat(originalWorldSourceDelivery.request().expectedSourceEvidenceDigest())
            .isEqualTo(worldSource.evidenceDigest());
        var worldIntakeRequest = originalWorldSourceDelivery.request();
        var worldIntakeReceipt = worldIntakeClient.intake(worldIntakeRequest);
        assertThat(worldIntakeReceipt.intakeRequestId())
            .isEqualTo(originalWorldSourceDelivery.request().intakeRequestId());
        assertThat(worldIntakeReceipt.requestDigest())
            .isEqualTo(WorldAuthoredSourceIntakeGrpcCodec.requestDigest(worldIntakeRequest));
        var acknowledgedWorldSourceDelivery =
            gd.transaction(
                () ->
                    worldSourceDeliveryRepository.acknowledge(
                        originalWorldSourceDelivery, worldIntakeReceipt));
        assertThat(acknowledgedWorldSourceDelivery.acknowledgedReceipt())
            .contains(worldIntakeReceipt);
        assertThat(worldSourceDeliveryRepository.read(worldSource.operationId()).orElseThrow())
            .isEqualTo(acknowledgedWorldSourceDelivery);
        var worldIdentityRequest =
            new WorldAuthoredVersionIdentityEvidence.Request(
                1,
                NAMESPACE,
                worldSource.canonicalTenantId(),
                worldSource.worldSlug(),
                worldSource.operationId(),
                worldSource.evidenceDigest(),
                gd.target().canonicalVersionId(),
                gd.target().gameDesignVersionRowId(),
                UUID.randomUUID());
        var worldIdentityReceipt = worldIdentityClient.associate(worldIdentityRequest);
        assertThat(worldIdentityReceipt.sourceIntakeReceipt()).isEqualTo(worldIntakeReceipt);

        var assetBytes = "actual selected resource bytes".getBytes(StandardCharsets.UTF_8);
        GameAsset assetToSave = new GameAsset();
        assetToSave.setTenantId(gd.target().gameDesignVersionTenantKey());
        assetToSave.setFileName("selected-resource.txt");
        assetToSave.setContentType("text/plain");
        assetToSave.setData(assetBytes);
        GameAsset sourceAsset =
            gd.transaction(() -> new GameAssetRepository(gd.dsl()).save(assetToSave));
        var selectedCommit =
            binding(
                gd.target(),
                sourceAsset.getId(),
                sourceAsset.getFileName(),
                worldSource.worldSlug(),
                worldSource.worldDisplayName());
        var originalWorldDeclarations =
            selectedCommit.revisions().stream()
                .filter(revision -> revision.owner() == Owner.WORLD_MANAGEMENT)
                .map(GenuineSelectedPublicationExportPostgresIntegrationTest::worldMutation)
                .filter(WorldDesignMutationRevision::hasFreshGraphDeclaration)
                .map(WorldDesignMutationRevision::getFreshGraphDeclaration)
                .toList();
        assertThat(originalWorldDeclarations)
            .singleElement()
            .satisfies(
                declaration -> {
                  assertThat(declaration.hasInboundSourceClosure()).isTrue();
                  assertThat(declaration.getInboundSourceClosure())
                      .isEqualTo(authoredEmptyInboundSourceClosure());
                });
        var templateConfigUnits =
            selectedCommit.affectedUnits(Owner.GAME_DESIGN_CONTROL_PLANE).stream()
                .filter(unit -> TemplateConfigSource.SCOPE.equals(unit.aggregateType()))
                .toList();
        assertThat(templateConfigUnits)
            .singleElement()
            .satisfies(
                unit -> {
                  assertThat(unit.aggregateId())
                      .isEqualTo(gd.target().canonicalVersionId().toString());
                  assertThat(unit.scopeType()).isEqualTo(TemplateConfigSource.SCOPE);
                  assertThat(unit.scopeId()).isEqualTo(TemplateConfigSource.SCOPE_ID);
                  assertThat(unit.expectedEpoch()).isEqualTo("0");
                });
        var originalOrder = account.prepareOriginalDraftOrder(selectedCommit, NAMESPACE);
        var original = originalOrder.original();
        assertThat(original.schemaVersion()).isEqualTo(DraftAuthorizationFenceBinding.SCHEMA_V2);
        var accountAccess = account.preparedOriginalCreator();
        var accountRepository =
            new AccountGameLogicIntakeAuthorizationRepository(accountAccess.sources().dsl);
        var accountSelectedOwnerSourceRepository =
            new AccountSelectedOwnerIntakeSourceReservationRepository(accountAccess.sources().dsl);
        var accountSelectedOwnerSourceReadOwner =
            new AccountSelectedOwnerIntakeSourceReadService(
                accountSelectedOwnerSourceRepository,
                account.coordination(),
                accountAccess.sources().manager,
                NAMESPACE);
        var accountSelectedSourceReads = new AtomicInteger();
        var observedAccountToGdSource =
            (net.firedevops.firemud.common.gamedesign.SelectedOwnerIntakeSourceClient)
                request -> {
                  accountSelectedSourceReads.incrementAndGet();
                  return accountToGdSelectedOwnerSource.read(request);
                };

        register(
            accountHandlers,
            account.originalDraftOrderProducer(NAMESPACE),
            new AccountDraftCommitOrderReadGrpcService(
                account.heldOrderOwner(NAMESPACE), NAMESPACE),
            new AccountPublicationAuthorizationReadGrpcService(
                account.heldPublicationOwner(NAMESPACE), NAMESPACE),
            new AccountSelectedOwnerIntakeSourceReadGrpcService(
                accountSelectedOwnerSourceReadOwner, NAMESPACE),
            new AccountSelectedOwnerIntakeAuthorizationReadGrpcService(
                new AccountSelectedOwnerIntakeAuthorizationReadService(
                    accountSelectedOwnerSourceRepository,
                    accountAccess.sources().manager,
                    NAMESPACE),
                NAMESPACE),
            new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadGrpcService(
                new AccountSelectedOwnerIntakeWorldClosureAuthorizationReadService(
                    accountSelectedOwnerSourceRepository,
                    accountAccess.sources().manager,
                    NAMESPACE),
                NAMESPACE),
            account.selectedOwnerIntakeAuthorizationProducerReceiver(
                observedAccountToGdSource, NAMESPACE));

        var coordinator = new DraftCommitCoordinatorRepository(gd.dsl());
        var gdTerminals = new GameDesignDraftTerminalOutcomeRepository(gd.dsl());
        var gdSources = new GameDesignSourceRepository(gd.dsl());
        var selectionRepository =
            new AuthoredDraftPublishSelectionRepository(gd.dsl(), coordinator);
        register(
            gdHandlers,
            new GameDesignSelectedDraftPublicationReadGrpcService(selectionRepository, NAMESPACE));
        var worldFence = new WorldDesignPublicationFenceRepository(world.dsl(), worldIntakes);
        register(
            worldHandlers,
            WorldSelectedOwnerInventoryReadProofFixture.receiver(
                NAMESPACE, world.dsl(), worldFence, worldToAccountClosure));
        var worldApplications =
            new WorldDraftGraphApplicationRepository(world.dsl(), worldFence, new ObjectMapper());
        var worldPublicationProof =
            new GenuineWorldSelectedPublicationProofFactory(
                NAMESPACE,
                world.dsl(),
                world.transactions(),
                worldIntakes,
                worldFence,
                worldApplications,
                worldSelectionReadClient,
                versionClient,
                worldAccountReadClient,
                new ObjectMapper());
        register(
            worldHandlers,
            worldPublicationProof.freezeAndInventoryService(),
            worldPublicationProof.publishedStartLocationReadService());
        var worldApplicationService =
            new WorldDraftGraphApplicationService(
                worldApplications,
                world.transactions(),
                new WorldDraftCommitOrderVerifier(accountHeldOrderClient, NAMESPACE));
        var worldOriginalApply =
            new WorldOriginalDraftGraphApplicationService(
                worldIdentities, worldIntakes, worldApplicationService);
        register(
            worldHandlers,
            new WorldOriginalDraftGraphApplyGrpcService(worldOriginalApply, NAMESPACE),
            new WorldDraftTerminalReadGrpcService(
                new WorldDraftTerminalOutcomeRepository(
                    world.dsl(), worldFence, new ObjectMapper()),
                worldApplications,
                NAMESPACE));

        register(
            gdHandlers,
            new GameDesignDraftTerminalReadGrpcService(gdTerminals, NAMESPACE),
            new GameDesignSelectedOwnerIntakeSourceGrpcService(
                new GameDesignSelectedOwnerIntakeSourceReadService(
                    gdSources, gd.transactions(), gdToAccountSelectedOwnerPermission, NAMESPACE),
                NAMESPACE),
            new GameDesignGameplayRuleSourceReadGrpcService(
                new GameDesignGameplayRuleSourceReadService(
                    new net.firedevops.firemud.gamedesign.publication.GameplayRuleSourceRepository(
                        gd.dsl()),
                    gd.transactions(),
                    gdToAccountPermission,
                    NAMESPACE),
                NAMESPACE));

        var glRepository = new GameLogicGameplayRuleIntakeRepository(gameLogic.dsl());
        var glPublicationSourceReader =
            new GameLogicPublicationSourceReadService(glRepository, NAMESPACE);
        var glPublicationReadBindings =
            new CopyOnWriteArrayList<GameLogicPublicationSourceReadBinding>();
        var glPublicationReadResults =
            new CopyOnWriteArrayList<GameLogicPublicationSourceReadService.Result>();
        GameLogicDraftDesignDigestService observingGlPublicationReader =
            binding -> {
              glPublicationReadBindings.add(binding);
              var result = glPublicationSourceReader.read(binding);
              glPublicationReadResults.add(result);
              return result;
            };
        var accountAuthorizationOwner =
            new AccountGameLogicIntakeAuthorizationService(
                accountAccess.actors(),
                accountAccess.sources().fences,
                accountRepository,
                accountToGdSource,
                accountAccess.sources().manager,
                NAMESPACE);
        var accountSourcePermissionOwner =
            new AccountGameLogicIntakeSourceReadService(
                accountRepository,
                account.coordination(),
                accountAccess.sources().manager,
                Clock.systemUTC(),
                NAMESPACE);
        var accountHeldAuthorizationOwner =
            new AccountGameLogicIntakeAuthorizationReadService(
                accountRepository, accountAccess.sources().manager, NAMESPACE);
        var accountSettlementOwner =
            new AccountGameLogicIntakeSettlementService(
                accountRepository, accountToGlTerminal, accountAccess.sources().manager, NAMESPACE);
        register(
            accountHandlers,
            new AccountGameLogicIntakeAuthorizationGrpcService(
                accountAuthorizationOwner, accountAccess.sources().terms, NAMESPACE),
            new AccountGameLogicIntakeSourceReadGrpcService(
                accountSourcePermissionOwner, NAMESPACE),
            new AccountGameLogicIntakeAuthorizationReadGrpcService(
                accountHeldAuthorizationOwner, NAMESPACE),
            new AccountGameLogicIntakeSettlementGrpcService(accountSettlementOwner, NAMESPACE));
        register(accountHandlers, accountProjectionDouble(startSessionProjections));

        var glOwner =
            new GameLogicGameplayRuleIntakeService(
                glRepository, gameLogic.transactions(), glToAccountHeld, glToGdSource, NAMESPACE);
        register(
            gameLogicHandlers,
            new GameLogicGameplayRuleIntakeGrpcService(glOwner, NAMESPACE),
            new GameLogicGameplayRuleIntakeTerminalReadGrpcService(
                new GameLogicGameplayRuleIntakeTerminalReadService(glRepository, NAMESPACE),
                NAMESPACE),
            new GameLogicGrpcService(
                null,
                null,
                null,
                null,
                null,
                null,
                observingGlPublicationReader,
                null,
                new SimpleMeterRegistry(),
                new PublicationReadGuard(NAMESPACE)));

        accountOriginalOrderClient.init();
        accountHeldOrderClient.init();
        worldApplyClient.init();
        gdTerminalClient.init();
        worldTerminalClient.init();
        accountToGdSource.init();
        gdToAccountPermission.init();
        gdToAccountSelectedOwnerPermission.init();
        accountToGdSelectedOwnerSource.init();
        gdToAccountSelectedOwnerAuthorizationProducer.init();
        wrongWorkloadSelectedOwnerAuthorizationProducer.init();
        automationToAccountHeld.init();
        automationToWorldInventory.init();
        worldToAccountClosure.init();
        accountToAutomationTerminal.init();
        automationSelectedSourceCommandClient.init();
        wrongWorkloadAutomationSelectedSourceCommandClient.init();
        entityToAccountHeld.init();
        entityToWorldInventory.init();
        accountToEntityTerminal.init();
        entitySelectedSourceCommandClient.init();
        wrongWorkloadEntitySelectedSourceCommandClient.init();
        selectedOwnerSettlementClient.init();
        wrongWorkloadSelectedOwnerSettlementClient.init();
        glToAccountHeld.init();
        glToGdSource.init();
        accountToGlTerminal.init();
        gdToAccountIntakeAuthorization.init();
        gdToGlRetain.init();
        gdToAccountSettlement.init();
        accountSelectionReadClient.init();
        worldSelectionReadClient.init();
        worldAccountReadClient.init();
        gdAccountPublicationReadClient.init();
        selectedPublicationFreezeClient.init();
        selectedPublicationInventoryClient.init();
        publishedStartLocationClient.init();
        var automationIntakeOwner =
            new AutomationEmptySelectedSourceIntakeService(
                automationIntakeRepository, automationToAccountHeld, automationToWorldInventory);
        register(
            automationHandlers,
            new AutomationSelectedSourceIntakeCommandGrpcService(automationIntakeOwner, NAMESPACE));
        var entityIntakeOwner =
            new EntityEmptySelectedSourceIntakeService(
                NAMESPACE, entityIntakeRepository, entityToAccountHeld, entityToWorldInventory);
        register(
            entityHandlers,
            new EntitySelectedSourceIntakeCommandGrpcService(entityIntakeOwner, NAMESPACE));
        var selectedOwnerSettlementOwner =
            new AccountSelectedOwnerIntakeSettlementService(
                accountSelectedOwnerSourceRepository,
                accountToAutomationTerminal,
                accountToEntityTerminal,
                accountAccess.sources().manager,
                NAMESPACE);
        register(
            accountHandlers,
            new AccountSelectedOwnerIntakeSettlementGrpcService(
                selectedOwnerSettlementOwner, NAMESPACE));

        var sourceCommit =
            new GameDesignWorldSourceCommitService(
                accountOriginalOrderClient,
                worldApplyClient,
                coordinator,
                gdTerminals,
                gdSources,
                gd.transactions(),
                NAMESPACE);
        var originalTerminal =
            sourceCommit.commit(original, originalOrder.originalCreatorCredential());
        assertThat(originalTerminal.result())
            .isEqualTo(
                net.firedevops.firemud.gamedesign.draft.GameDesignDraftTerminalOutcome.Result
                    .COMMITTED);
        account.assertOriginalDraftOrderPending(original);
        account.reconcileOriginalDraft(original, gdTerminalClient, worldTerminalClient, NAMESPACE);
        account.reconcileOriginalDraft(original, gdTerminalClient, worldTerminalClient, NAMESPACE);
        assertThat(
                accountAccess
                    .sources()
                    .tx(() -> accountAccess.sources().fences.readSettlement(original)))
            .isEqualTo(DraftAuthorizationFenceRepository.Settlement.COMMITTED);

        // Entity and Automation are later source readers, not members required by the original
        // Draft's mutation participant vector. These are distinct Account reservations under the
        // same current creator and exact already-settled selected Draft.
        UUID entityIntakeRequest = UUID.randomUUID();
        var entitySourceReservation =
            account.reserveSelectedOwnerSourceRead(
                entityIntakeRequest, Owner.ENTITY_MANAGEMENT, selectedCommit, NAMESPACE);
        var entitySourceReservationRetry =
            account.reserveSelectedOwnerSourceRead(
                entityIntakeRequest, Owner.ENTITY_MANAGEMENT, selectedCommit, NAMESPACE);
        assertThat(entitySourceReservation.owner()).isEqualTo(Owner.ENTITY_MANAGEMENT);
        assertThat(entitySourceReservation.intakeRequestId()).isEqualTo(entityIntakeRequest);
        assertThat(entitySourceReservation.selected().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        assertThat(entitySourceReservation.actorAccountId()).isEqualTo(original.actorAccountId());
        assertThat(entitySourceReservation.operationId()).isNotEqualTo(original.operationId());
        assertThat(entitySourceReservation.fenceId()).isNotEqualTo(original.fenceId());
        assertThat(entitySourceReservationRetry.canonicalBytes())
            .containsExactly(entitySourceReservation.canonicalBytes());
        assertThat(
                account.recoverSelectedOwnerSourceRead(entitySourceReservation, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED);

        UUID automationIntakeRequest = UUID.randomUUID();
        var automationSourceReservation =
            account.reserveSelectedOwnerSourceRead(
                automationIntakeRequest, Owner.AUTOMATION_SCRIPTING, selectedCommit, NAMESPACE);
        var automationSourceReservationRetry =
            account.reserveSelectedOwnerSourceRead(
                automationIntakeRequest, Owner.AUTOMATION_SCRIPTING, selectedCommit, NAMESPACE);
        assertThat(automationSourceReservation.owner()).isEqualTo(Owner.AUTOMATION_SCRIPTING);
        assertThat(automationSourceReservation.intakeRequestId())
            .isEqualTo(automationIntakeRequest);
        assertThat(automationSourceReservation.intakeRequestId())
            .isNotEqualTo(entitySourceReservation.intakeRequestId());
        assertThat(automationSourceReservation.selected().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        assertThat(automationSourceReservation.actorAccountId())
            .isEqualTo(original.actorAccountId());
        assertThat(automationSourceReservation.operationId())
            .isNotEqualTo(original.operationId())
            .isNotEqualTo(entitySourceReservation.operationId());
        assertThat(automationSourceReservation.fenceId())
            .isNotEqualTo(original.fenceId())
            .isNotEqualTo(entitySourceReservation.fenceId());
        assertThat(automationSourceReservationRetry.canonicalBytes())
            .containsExactly(automationSourceReservation.canonicalBytes());
        assertThat(
                account
                    .recoverSelectedOwnerSourceRead(automationSourceReservation, NAMESPACE)
                    .state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED);

        var expectedAccountSourceEvidence =
            original.sources().stream()
                .map(
                    source ->
                        source.key() + ":" + HexFormat.of().formatHex(source.canonicalBytes()))
                .sorted()
                .toList();
        var accountSourceDsl = accountAccess.sources().dsl;
        var entityReservationSourcesBeforeAbort =
            selectedOwnerReservationSourceRows(
                accountSourceDsl, entitySourceReservation.operationId());
        var automationReservationSourcesBeforeAbort =
            selectedOwnerReservationSourceRows(
                accountSourceDsl, automationSourceReservation.operationId());
        assertThat(entityReservationSourcesBeforeAbort)
            .containsExactlyElementsOf(expectedAccountSourceEvidence);
        assertThat(automationReservationSourcesBeforeAbort)
            .containsExactlyElementsOf(expectedAccountSourceEvidence);
        var accountReservationSourcesBeforeExport =
            Map.of(
                Owner.ENTITY_MANAGEMENT,
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, entitySourceReservation.operationId()),
                Owner.AUTOMATION_SCRIPTING,
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()));

        var appliedWorld =
            worldApplications.readCommitted(NAMESPACE, original.canonicalBytes()).orElseThrow();
        assertThat(appliedWorld.status()).isEqualTo("APPLIED");
        assertThat(appliedWorld.startLocationReceipt()).isPresent();
        assertThat(world.dsl().fetchCount(DSL.table("world_draft_graph_application"))).isOne();
        var selectedPolicyRevision =
            selectedCommit.revisions().stream()
                .filter(RealmPolicySource::isPolicyRevision)
                .findFirst()
                .orElseThrow();
        var expectedPolicy = RealmPolicySource.revision(selectedCommit, selectedPolicyRevision);
        var selectedSources =
            new GameDesignSourceRepository(gd.dsl())
                .readSynchronized(gd.target(), selectedCommit.commitId())
                .orElseThrow();
        assertThat(selectedSources.policy().binding()).isEqualTo(selectedCommit);
        assertThat(RealmPolicySource.ordered(selectedSources.policy().policies()))
            .containsExactly(expectedPolicy);

        var ownerIntakeSourceRepository = new GameDesignSourceRepository(gd.dsl());
        var sourceRowsBeforeExport = selectedOwnerSourceRowCounts(gd.dsl());
        for (Owner owner : List.of(Owner.ENTITY_MANAGEMENT, Owner.AUTOMATION_SCRIPTING)) {
          // Account's retained preliminary scope authorizes disclosure only through the two
          // authenticated owner transports; this does not establish owner retention or settlement.
          var scope =
              owner == Owner.ENTITY_MANAGEMENT
                  ? entitySourceReservation
                  : automationSourceReservation;
          var readRequest = SelectedOwnerIntakeSourceReadEvidence.Request.create(NAMESPACE, scope);
          SelectedOwnerIntakeSourceContent exportedSource =
              accountToGdSelectedOwnerSource.read(readRequest);
          SelectedOwnerIntakeSourceContent exactRetry =
              accountToGdSelectedOwnerSource.read(readRequest);

          assertThat(exportedSource.scope().owner()).isEqualTo(owner);
          assertThat(exportedSource.scope().canonicalBytes())
              .containsExactly(scope.canonicalBytes());
          assertThat(exportedSource.scope().selected().canonicalBytes())
              .containsExactly(selectedCommit.canonicalBytes());
          assertThat(exportedSource.snapshotBytes("COMMAND"))
              .containsExactly(selectedSources.command().canonicalBytes());
          assertThat(exportedSource.snapshotBytes("REALM_POLICY"))
              .containsExactly(selectedSources.policy().canonicalBytes());
          assertThat(exportedSource.snapshotBytes("ASSET"))
              .containsExactly(selectedSources.asset().canonicalBytes());
          assertThat(exportedSource.snapshotBytes("GAMEPLAY_RULE"))
              .containsExactly(selectedSources.gameplay().canonicalBytes());
          assertThat(exportedSource.snapshotBytes("BRANDING"))
              .containsExactly(selectedSources.branding().orElseThrow().canonicalBytes());
          assertThat(exportedSource.snapshotBytes("TEMPLATE_CONFIG"))
              .containsExactly(selectedSources.templateConfig().orElseThrow().canonicalBytes());
          assertThat(exportedSource.digest())
              .isEqualTo(DraftAuthorizationFenceBinding.digest(exportedSource.canonicalBytes()));
          assertThat(exactRetry.canonicalBytes()).containsExactly(exportedSource.canonicalBytes());
          assertThat(exactRetry.digest()).isEqualTo(exportedSource.digest());
          assertThat(exactRetry.digest())
              .isEqualTo(DraftAuthorizationFenceBinding.digest(exactRetry.canonicalBytes()));
        }
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, entitySourceReservation.operationId()))
            .containsExactlyElementsOf(
                accountReservationSourcesBeforeExport.get(Owner.ENTITY_MANAGEMENT));
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(
                accountReservationSourcesBeforeExport.get(Owner.AUTOMATION_SCRIPTING));
        assertThat(account.abortSelectedOwnerSourceRead(entitySourceReservation, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                account.recoverSelectedOwnerSourceRead(entitySourceReservation, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                account
                    .recoverSelectedOwnerSourceRead(automationSourceReservation, NAMESPACE)
                    .state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.RESERVED);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, entitySourceReservation.operationId()))
            .containsExactlyElementsOf(entityReservationSourcesBeforeAbort);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);
        assertThat(
                account
                    .abortSelectedOwnerSourceRead(automationSourceReservation, NAMESPACE)
                    .state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                account
                    .recoverSelectedOwnerSourceRead(automationSourceReservation, NAMESPACE)
                    .state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);
        assertThat(
                accountSourceDsl.fetchOne(
                    "SELECT operation_id FROM account_selected_owner_intake_authorizations "
                        + "WHERE operation_id = ?",
                    automationSourceReservation.operationId()))
            .isNull();
        assertThatThrownBy(
                () ->
                    accountToGdSelectedOwnerSource.read(
                        SelectedOwnerIntakeSourceReadEvidence.Request.create(
                            NAMESPACE, entitySourceReservation)))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(io.grpc.Status.fromThrowable(failure).getCode())
                        .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION));
        assertThat(selectedOwnerSourceRowCounts(gd.dsl())).isEqualTo(sourceRowsBeforeExport);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, entitySourceReservation.operationId()))
            .containsExactlyElementsOf(entityReservationSourcesBeforeAbort);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);
        var wrongTarget =
            new TargetProof(
                gd.target().canonicalTenantId(),
                gd.target().canonicalVersionId(),
                gd.target().gameDesignVersionRowId() + 1,
                gd.target().gameDesignVersionTenantKey(),
                gd.target().sourceGameRowId() + 1,
                gd.target().sourceGameTenantKey(),
                gd.target().sourceProvenanceKind());
        var wrongSelectedBinding =
            DraftCommitBinding.create(
                wrongTarget,
                selectedCommit.requestId(),
                selectedCommit.commitId(),
                selectedCommit.baseCommitId(),
                selectedCommit.revisions(),
                selectedCommit.affectedUnits());
        assertThatThrownBy(
                () ->
                    ownerIntakeSourceRepository.requireSelectedOwnerIntakeSource(
                        selectedOwnerSourceScope(Owner.ENTITY_MANAGEMENT, wrongSelectedBinding)))
            .isInstanceOf(IllegalStateException.class);
        var missingTarget =
            new TargetProof(
                UUID.randomUUID(),
                gd.target().canonicalVersionId(),
                gd.target().gameDesignVersionRowId(),
                gd.target().gameDesignVersionTenantKey(),
                gd.target().sourceGameRowId(),
                gd.target().sourceGameTenantKey(),
                gd.target().sourceProvenanceKind());
        var missingSelectedBinding =
            DraftCommitBinding.create(
                missingTarget,
                selectedCommit.requestId(),
                selectedCommit.commitId(),
                selectedCommit.baseCommitId(),
                selectedCommit.revisions(),
                selectedCommit.affectedUnits());
        assertThatThrownBy(
                () ->
                    ownerIntakeSourceRepository.requireSelectedOwnerIntakeSource(
                        selectedOwnerSourceScope(
                            Owner.AUTOMATION_SCRIPTING, missingSelectedBinding)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Selected Game Design source is unavailable");
        assertThat(selectedOwnerSourceRowCounts(gd.dsl())).isEqualTo(sourceRowsBeforeExport);

        var templateSource =
            new TemplateConfigSourceRepository(gd.dsl())
                .readSnapshot(gd.target(), selectedCommit.commitId())
                .orElseThrow();
        assertThat(templateSource.binding()).isEqualTo(selectedCommit);
        assertThat(templateSource.canonicalJson())
            .contains("\"schema\":\"game-design-template-config-source-snapshot/v2\"");
        assertThat(templateSource.entries()).hasSize(1);
        var selectedTemplateMutations = TemplateConfigSource.mutations(selectedCommit);
        var authoredAutomationInventory = automationSourceInventory();
        var authoredEntityInventory = entitySourceInventory();
        var automationInventoryMutation =
            selectedTemplateMutations.stream()
                .filter(mutation -> mutation.declaredOwner() == Owner.AUTOMATION_SCRIPTING)
                .findFirst()
                .orElseThrow();
        var entityInventoryMutation =
            selectedTemplateMutations.stream()
                .filter(mutation -> mutation.declaredOwner() == Owner.ENTITY_MANAGEMENT)
                .findFirst()
                .orElseThrow();
        assertThat(automationInventoryMutation.inventory()).isEqualTo(authoredAutomationInventory);
        assertThat(entityInventoryMutation.entityInventory()).isEqualTo(authoredEntityInventory);
        assertThat(templateSource.ownerSourceInventoryDeclarations())
            .containsExactly(
                automationInventoryMutation.ownerInventoryDeclaration(),
                entityInventoryMutation.ownerInventoryDeclaration());
        long templateId = Long.parseLong(templateSource.entries().getFirst().templateId());
        var enforcedPhase =
            new TemplateReferenceRepository(gd.dsl())
                .readPhase(gd.target().canonicalTenantId())
                .orElseThrow();
        assertThat(enforcedPhase.phase()).isEqualTo(TemplateReferenceRepository.Phase.ENFORCED);
        assertThat(enforcedPhase.inventoryTemplateCount()).isEqualTo(1L);
        var normalizedBase =
            new TemplateReferenceRepository(gd.dsl())
                .readExactBaseReference(gd.target().canonicalTenantId(), templateId)
                .orElseThrow();
        assertThat(normalizedBase.canonicalVersionId()).isEqualTo(gd.target().canonicalVersionId());
        assertThat(normalizedBase.sourceCommitId()).isEqualTo(selectedCommit.commitId());
        assertThat(normalizedBase.sourceRevisionId())
            .isEqualTo(templateSource.entries().getFirst().revisionId());

        String publishRequestId = UUID.randomUUID().toString();
        var intent =
            new AuthoredDraftPublishSelection.PublishIntent(
                gd.target().canonicalTenantId(),
                gd.target().canonicalVersionId(),
                publishRequestId,
                Long.toString(gd.version().getVersionStateEpoch()),
                "genuine selected inventory and export proof",
                selectedCommit.requestId(),
                selectedCommit.commitId(),
                selectedCommit.digest());
        var selection = gd.transaction(() -> selectionRepository.reserve(intent).selection());
        var selectionBinding =
            AuthoredDraftPublishSelectionBinding.fromStored(
                selection.canonicalJson(), selection.digest());
        var accountPublicationOrder =
            account.authorizePublication(selectionBinding, accountSelectionReadClient, NAMESPACE);
        assertThat(accountPublicationOrder.input().selection().canonicalBytes())
            .containsExactly(selection.canonicalBytes());
        assertThat(accountPublicationOrder.operationId()).isNotEqualTo(original.operationId());
        assertThat(accountPublicationOrder.fenceId()).isNotEqualTo(original.fenceId());
        account.assertPublicationSourcesHeld(accountPublicationOrder);
        var intakeRequest =
            GameLogicIntakeAuthorizationEvidence.Request.create(
                NAMESPACE, UUID.randomUUID(), selection.selectedCommit());
        var receiptService =
            new SelectedDraftGameLogicReceiptService(
                gd.dsl(), gd.transactions(), gdToGlRetain, gdToAccountSettlement, NAMESPACE);
        var intakeCommand =
            new SelectedDraftGameLogicIntakeCommandService(
                gd.dsl(), gdToAccountIntakeAuthorization, receiptService, NAMESPACE);
        SelectedDraftGameLogicReceipt retainedReceipt =
            intakeCommand.authorizeAndRetain(
                selection, intakeRequest, originalOrder.originalCreatorCredential());
        SelectedDraftGameLogicReceipt exactRetry =
            intakeCommand.recoverAndRetain(
                selection, intakeRequest, originalOrder.originalCreatorCredential());
        assertThat(exactRetry.selection().canonicalBytes())
            .containsExactly(retainedReceipt.selection().canonicalBytes());
        assertThat(exactRetry.authorization().canonicalBytes())
            .containsExactly(retainedReceipt.authorization().canonicalBytes());
        assertThat(exactRetry.receipt().canonicalBytes())
            .containsExactly(retainedReceipt.receipt().canonicalBytes());
        assertThat(retainedReceipt.selection().canonicalBytes())
            .containsExactly(selection.canonicalBytes());
        assertThat(retainedReceipt.authorization().source().binding().canonicalBytes())
            .containsExactly(selection.selectedCommit().canonicalBytes());
        assertThat(retainedReceipt.receipt().terminal().outcome())
            .isEqualTo(GameLogicGameplayRuleIntakeTerminal.Outcome.RETAINED);
        assertThat(
                glRepository
                    .findTerminal(retainedReceipt.authorization().operationId())
                    .orElseThrow()
                    .canonicalBytes())
            .containsExactly(retainedReceipt.receipt().terminal().canonicalBytes());
        assertThat(
                accountAccess
                    .sources()
                    .dsl
                    .fetchCount(DSL.table("account_game_logic_intake_settlements")))
            .isOne();
        assertThat(gd.dsl().fetchCount(DSL.table("game_design_selected_game_logic_receipt")))
            .isOne();

        var publicationAdmission =
            new SelectedDraftPublicationAdmissionService(
                gd.dsl(),
                gd.transactions(),
                gdAccountPublicationReadClient,
                selectedPublicationFreezeClient,
                selectedPublicationInventoryClient,
                publishedStartLocationClient,
                worldIntakeClient,
                NAMESPACE);
        var publicationReservation =
            publicationAdmission.admitAndReserve(intent, accountPublicationOrder);
        var exactAdmissionRetry =
            publicationAdmission.admitAndReserve(intent, accountPublicationOrder);
        assertThat(exactAdmissionRetry.operation().canonicalBytes())
            .containsExactly(publicationReservation.operation().canonicalBytes());
        assertThat(exactAdmissionRetry.attemptId()).isEqualTo(publicationReservation.attemptId());
        assertThat(publicationReservation.operation().account().canonicalBytes())
            .containsExactly(accountPublicationOrder.canonicalBytes());

        var exactFreezeRequest =
            WorldSelectedDraftPublicationFreezeEvidence.Request.create(
                NAMESPACE,
                intent.canonicalTenantId(),
                intent.canonicalVersionId(),
                intent.publishRequestId(),
                Long.parseLong(intent.expectedVersionStateEpoch()),
                selection.digest().substring("sha256:".length()),
                accountPublicationOrder);
        var actualFreeze = selectedPublicationFreezeClient.begin(exactFreezeRequest);
        assertThat(actualFreeze.acknowledgement().publicationFence())
            .isEqualTo(publicationReservation.operation().world().request().publicationFence());
        assertThat(actualFreeze.acknowledgement().appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        var changedAccountOrder =
            new AccountPublicationAuthorizationBinding(
                UUID.randomUUID(),
                accountPublicationOrder.fenceId(),
                accountPublicationOrder.input(),
                accountPublicationOrder.sources());
        var selectedFreezeRequest =
            WorldSelectedDraftPublicationFreezeEvidence.Request.create(
                NAMESPACE,
                intent.canonicalTenantId(),
                intent.canonicalVersionId(),
                intent.publishRequestId(),
                Long.parseLong(intent.expectedVersionStateEpoch()),
                selection.digest().substring("sha256:".length()),
                changedAccountOrder);
        assertFailedPrecondition(
            () -> selectedPublicationFreezeClient.begin(selectedFreezeRequest));
        var substitutedFreezeAcknowledgement =
            new WorldSelectedDraftPublicationFreezeEvidence.Acknowledgement(
                actualFreeze.request(),
                actualFreeze.acknowledgement().intakeRequestId(),
                actualFreeze.acknowledgement().versionStateEpoch(),
                UUID.randomUUID(),
                WorldSelectedDraftPublicationFreezeEvidence.OwnerFreezePhase.FROZEN,
                actualFreeze.acknowledgement().appliedCommitId(),
                actualFreeze.acknowledgement().contentDigest(),
                actualFreeze.acknowledgement().digestSchemaVersion());
        var substitutedFreeze =
            WorldSelectedDraftPublicationFreezeGrpcCodec.fromResponse(
                actualFreeze.request(),
                WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(
                    substitutedFreezeAcknowledgement));
        assertFailedPrecondition(() -> selectedPublicationInventoryClient.read(substitutedFreeze));
        assertThat(world.dsl().fetchCount(DSL.table("world_design_publication_fence_attempt")))
            .isOne();
        assertThat(
                world.dsl().fetchCount(DSL.table("world_selected_publication_artifact_inventory")))
            .isOne();
        var retainedTemplateAssociations =
            new SelectedDraftTemplateWorldSourceAssociationRepository(gd.dsl())
                .readExact(
                    publicationReservation.operation(),
                    publicationReservation.sourceCapture().templateConfig());
        assertThat(retainedTemplateAssociations).hasSize(1);
        assertThat(retainedTemplateAssociations.getFirst().templateId()).isEqualTo(templateId);

        UUID freshAutomationIntakeRequest = UUID.randomUUID();
        assertThat(freshAutomationIntakeRequest)
            .isNotEqualTo(automationSourceReservation.intakeRequestId())
            .isNotEqualTo(entitySourceReservation.intakeRequestId());
        var automationAuthorizationRequest =
            SelectedOwnerIntakeAuthorizationProducerEvidence.Request.create(
                NAMESPACE,
                freshAutomationIntakeRequest,
                Owner.AUTOMATION_SCRIPTING,
                selectedCommit);
        var automationAuthorizationResult =
            gdToAccountSelectedOwnerAuthorizationProducer.authorize(
                automationAuthorizationRequest, accountAccess.compact());
        var automationAuthorization = automationAuthorizationResult.binding();
        var freshAutomationScope = automationAuthorization.content().scope();
        var freshAutomationReservationSources =
            selectedOwnerReservationSourceRows(
                accountSourceDsl, freshAutomationScope.operationId());
        assertThat(automationAuthorizationResult.request())
            .isEqualTo(automationAuthorizationRequest);
        assertThat(automationAuthorizationResult.canonicalBindingBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(automationAuthorizationResult.digest())
            .isEqualTo(automationAuthorization.digest());
        assertThat(automationAuthorization.owner()).isEqualTo(Owner.AUTOMATION_SCRIPTING);
        assertThat(automationAuthorization.intakeRequestId())
            .isEqualTo(freshAutomationIntakeRequest);
        assertThat(automationAuthorization.operationId())
            .isEqualTo(freshAutomationScope.operationId())
            .isNotEqualTo(automationSourceReservation.operationId());
        assertThat(automationAuthorization.fenceId())
            .isEqualTo(freshAutomationScope.fenceId())
            .isNotEqualTo(automationSourceReservation.fenceId());
        assertThat(freshAutomationScope.owner()).isEqualTo(Owner.AUTOMATION_SCRIPTING);
        assertThat(freshAutomationScope.targetNamespace()).isEqualTo(NAMESPACE);
        assertThat(freshAutomationScope.intakeRequestId()).isEqualTo(freshAutomationIntakeRequest);
        assertThat(freshAutomationScope.selected().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        assertThat(automationAuthorization.content().scope().canonicalBytes())
            .containsExactly(freshAutomationScope.canonicalBytes());
        assertThat(automationAuthorization.content().snapshotBytes("COMMAND"))
            .containsExactly(selectedSources.command().canonicalBytes());
        assertThat(automationAuthorization.content().snapshotBytes("REALM_POLICY"))
            .containsExactly(selectedSources.policy().canonicalBytes());
        assertThat(automationAuthorization.content().snapshotBytes("ASSET"))
            .containsExactly(selectedSources.asset().canonicalBytes());
        assertThat(automationAuthorization.content().snapshotBytes("GAMEPLAY_RULE"))
            .containsExactly(selectedSources.gameplay().canonicalBytes());
        assertThat(automationAuthorization.content().snapshotBytes("BRANDING"))
            .containsExactly(selectedSources.branding().orElseThrow().canonicalBytes());
        assertThat(automationAuthorization.content().snapshotBytes("TEMPLATE_CONFIG"))
            .containsExactly(selectedSources.templateConfig().orElseThrow().canonicalBytes());
        assertThat(automationAuthorization.content().digest())
            .isEqualTo(
                DraftAuthorizationFenceBinding.digest(
                    automationAuthorization.content().canonicalBytes()));
        assertThat(automationAuthorization.selected().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        assertThat(accountSelectedSourceReads).hasValue(1);
        assertThat(freshAutomationReservationSources)
            .containsExactlyElementsOf(expectedAccountSourceEvidence);
        assertThat(
                accountSourceDsl.fetchOne(
                    "SELECT operation_id FROM account_selected_owner_intake_authorizations "
                        + "WHERE operation_id = ?",
                    freshAutomationScope.operationId()))
            .isNotNull();
        assertThat(account.recoverSelectedOwnerSourceRead(freshAutomationScope, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.FINALIZED);
        assertThat(
                account.recoverSelectedOwnerSourceRead(entitySourceReservation, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                account
                    .recoverSelectedOwnerSourceRead(automationSourceReservation, NAMESPACE)
                    .state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);
        assertThatThrownBy(
                () ->
                    wrongWorkloadSelectedOwnerAuthorizationProducer.authorize(
                        automationAuthorizationRequest, accountAccess.compact()))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(io.grpc.Status.fromThrowable(failure).getCode())
                        .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        assertThat(accountSelectedSourceReads).hasValue(1);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, freshAutomationScope.operationId()))
            .containsExactlyElementsOf(freshAutomationReservationSources);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);

        var exactAutomationAuthorizationRetry =
            gdToAccountSelectedOwnerAuthorizationProducer.authorize(
                automationAuthorizationRequest, accountAccess.compact());
        assertThat(exactAutomationAuthorizationRetry.request())
            .isEqualTo(automationAuthorizationRequest);
        assertThat(exactAutomationAuthorizationRetry.canonicalBindingBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(exactAutomationAuthorizationRetry.digest())
            .isEqualTo(automationAuthorization.digest());
        assertThat(accountSelectedSourceReads).hasValue(1);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, freshAutomationScope.operationId()))
            .containsExactlyElementsOf(freshAutomationReservationSources);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);

        var automationRowsBeforeRetention = automationSourceRowCounts(automation.dsl());
        var automationRetainRequest =
            AutomationSelectedSourceIntakeCommandEvidence.Request.create(
                NAMESPACE, automationAuthorization, actualFreeze);
        assertThatThrownBy(
                () ->
                    wrongWorkloadAutomationSelectedSourceCommandClient.retain(
                        automationRetainRequest))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(io.grpc.Status.fromThrowable(failure).getCode())
                        .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        assertThat(automationSourceRowCounts(automation.dsl()))
            .isEqualTo(automationRowsBeforeRetention);
        AutomationSelectedSourceIntakeCommandEvidence automationRetainEvidence =
            automationSelectedSourceCommandClient.retain(automationRetainRequest);
        assertThat(automationRetainEvidence.request()).isEqualTo(automationRetainRequest);
        var automationReceipt = automationRetainEvidence.receipt();
        assertThat(automationReceipt.outcome()).isEqualTo("COMMITTED_EMPTY");
        assertThat(automationReceipt.intakeRequestId()).isEqualTo(freshAutomationIntakeRequest);
        assertThat(automationReceipt.authorizationBindingBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(automationReceipt.authorizationBindingDigest())
            .isEqualTo(automationAuthorization.digest());
        assertThat(automationReceipt.requestDigest())
            .isEqualTo(
                AutomationEmptySelectedSourceIntakeReceipt.requestDigest(
                    NAMESPACE, automationAuthorization, actualFreeze));
        var retainedAutomationFreeze =
            automationReceipt.worldInventoryReadEvidence().request().freezeEvidence();
        assertThat(
                automationReceipt
                    .worldInventoryReadEvidence()
                    .request()
                    .authorizationBinding()
                    .canonicalBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(automationReceipt.worldInventoryReadEvidence().request().closureReadPurpose())
            .isEqualTo("AUTOMATION_INTAKE_WORLD_CLOSURE_READ");
        assertThat(
                automationReceipt
                    .worldInventoryReadEvidence()
                    .request()
                    .authorizationBinding()
                    .intendedReader())
            .isEqualTo("spiffe://firemud/ns/" + NAMESPACE + "/sa/automation-scripting-service");
        assertThat(retainedAutomationFreeze).isEqualTo(actualFreeze);
        assertThat(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(
                        retainedAutomationFreeze.request())
                    .toByteArray())
            .containsExactly(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toRequest(actualFreeze.request())
                    .toByteArray());
        assertThat(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(
                        retainedAutomationFreeze.acknowledgement())
                    .toByteArray())
            .containsExactly(
                WorldSelectedDraftPublicationFreezeGrpcCodec.toResponse(
                        actualFreeze.acknowledgement())
                    .toByteArray());
        assertThat(automationReceipt.worldInventoryBytes())
            .containsExactly(
                automationReceipt.worldInventoryReadEvidence().inventory().canonicalBytes());
        assertThat(automationReceipt.worldInventoryDigest())
            .isEqualTo(automationReceipt.worldInventoryReadEvidence().inventory().digest());
        assertThat(automationReceipt.scriptsRowCount()).isZero();
        assertThat(automationReceipt.eventBindingsRowCount()).isZero();
        assertThat(automationReceipt.patchBaseBindingsRowCount()).isZero();
        assertThat(automationReceipt.unqualifiedScriptsRowCount()).isZero();
        assertThat(automationReceipt.unqualifiedEventBindingsRowCount()).isZero();
        assertThat(automationReceipt.unqualifiedPatchBaseBindingsRowCount()).isZero();
        assertThat(automationReceipt.selectedScopeScriptsRowCount()).isZero();
        assertThat(automationReceipt.selectedScopeEventBindingsRowCount()).isZero();
        assertThat(automationReceipt.selectedScopePatchBaseBindingsRowCount()).isZero();
        assertThat(automationReceipt.receiptDigest())
            .isEqualTo(DraftAuthorizationFenceBinding.digest(automationReceipt.canonicalBytes()));
        var persistedAutomationReceipt =
            automationIntakeRepository.read(NAMESPACE, freshAutomationIntakeRequest).orElseThrow();
        assertThat(persistedAutomationReceipt.canonicalBytes())
            .containsExactly(automationReceipt.canonicalBytes());
        assertThat(persistedAutomationReceipt.receiptDigest())
            .isEqualTo(automationReceipt.receiptDigest());
        var automationRowsAfterRetention = automationSourceRowCounts(automation.dsl());
        assertThat(automationRowsAfterRetention.get("scripts"))
            .isEqualTo(automationRowsBeforeRetention.get("scripts"));
        assertThat(automationRowsAfterRetention.get("script_event_bindings"))
            .isEqualTo(automationRowsBeforeRetention.get("script_event_bindings"));
        assertThat(automationRowsAfterRetention.get("script_patch_base_bindings"))
            .isEqualTo(automationRowsBeforeRetention.get("script_patch_base_bindings"));
        assertThat(automationRowsAfterRetention.get("automation_empty_selected_source_association"))
            .isEqualTo(
                automationRowsBeforeRetention.get("automation_empty_selected_source_association")
                    + 1);
        assertThat(automationRowsAfterRetention.get("automation_empty_selected_source_receipt"))
            .isEqualTo(
                automationRowsBeforeRetention.get("automation_empty_selected_source_receipt") + 1);
        assertThat(
                automationRowsAfterRetention.get("automation_empty_source_numeric_key_reservation"))
            .isEqualTo(
                automationRowsBeforeRetention.get("automation_empty_source_numeric_key_reservation")
                    + 2);

        var automationReceiptRetryEvidence =
            automationSelectedSourceCommandClient.retain(automationRetainRequest);
        assertThat(automationReceiptRetryEvidence.request()).isEqualTo(automationRetainRequest);
        var automationReceiptRetry = automationReceiptRetryEvidence.receipt();
        assertThat(automationReceiptRetry.canonicalBytes())
            .containsExactly(automationReceipt.canonicalBytes());
        assertThat(automationReceiptRetry.receiptDigest())
            .isEqualTo(automationReceipt.receiptDigest());
        assertThat(automationSourceRowCounts(automation.dsl()))
            .isEqualTo(automationRowsAfterRetention);

        var automationRetainNewCorrelationRequest =
            AutomationSelectedSourceIntakeCommandEvidence.Request.create(
                NAMESPACE, automationAuthorization, actualFreeze);
        assertThat(automationRetainNewCorrelationRequest.transportRequestId())
            .isNotEqualTo(automationRetainRequest.transportRequestId());
        assertThat(
                automationRetainNewCorrelationRequest
                    .originalAuthorizationBinding()
                    .canonicalBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(automationRetainNewCorrelationRequest.freezeEvidence()).isEqualTo(actualFreeze);
        var automationRetainNewCorrelationEvidence =
            automationSelectedSourceCommandClient.retain(automationRetainNewCorrelationRequest);
        assertThat(automationRetainNewCorrelationEvidence.request())
            .isEqualTo(automationRetainNewCorrelationRequest);
        assertThat(automationRetainNewCorrelationEvidence.receipt().canonicalBytes())
            .containsExactly(automationReceipt.canonicalBytes());
        assertThat(automationRetainNewCorrelationEvidence.receipt().receiptDigest())
            .isEqualTo(automationReceipt.receiptDigest());
        assertThat(automationSourceRowCounts(automation.dsl()))
            .isEqualTo(automationRowsAfterRetention);

        assertThat(
                accountSourceDsl.fetchCount(DSL.table("account_selected_owner_intake_settlements")))
            .isZero();
        var accountSettlementRequest =
            SelectedOwnerIntakeSettlementEvidence.Request.create(
                NAMESPACE, automationAuthorization);
        assertThatThrownBy(
                () -> wrongWorkloadSelectedOwnerSettlementClient.settle(accountSettlementRequest))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(io.grpc.Status.fromThrowable(failure).getCode())
                        .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        assertThat(
                accountSourceDsl.fetchCount(DSL.table("account_selected_owner_intake_settlements")))
            .isZero();
        var accountSettlementEvidence =
            selectedOwnerSettlementClient.settle(accountSettlementRequest);
        assertThat(accountSettlementEvidence.request()).isEqualTo(accountSettlementRequest);
        var accountSettlement = accountSettlementEvidence.receipt();
        assertThat(accountSettlement.authorizationBinding().canonicalBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(accountSettlement.ownerReceiptBytes())
            .containsExactly(automationReceipt.canonicalBytes());
        assertThat(accountSettlement.ownerReceiptDigest())
            .isEqualTo(automationReceipt.receiptDigest());
        assertThat(accountSettlement.digest())
            .isEqualTo(DraftAuthorizationFenceBinding.digest(accountSettlement.canonicalBytes()));
        var settlementRows =
            accountSourceDsl.fetch(
                "SELECT binding_bytes, binding_digest, terminal_read_request_id, "
                    + "terminal_receipt_bytes, terminal_receipt_digest, receipt_bytes, "
                    + "receipt_digest FROM account_selected_owner_intake_settlements "
                    + "WHERE operation_id = ?",
                automationAuthorization.operationId());
        assertThat(settlementRows).hasSize(1);
        var settlementRow = settlementRows.getFirst();
        assertThat(settlementRow.get("binding_bytes", byte[].class))
            .containsExactly(automationAuthorization.canonicalBytes());
        assertThat(settlementRow.get("binding_digest", String.class))
            .isEqualTo(automationAuthorization.digest());
        assertThat(settlementRow.get("terminal_receipt_bytes", byte[].class))
            .containsExactly(automationReceipt.canonicalBytes());
        assertThat(settlementRow.get("terminal_receipt_digest", String.class))
            .isEqualTo(automationReceipt.receiptDigest());
        assertThat(settlementRow.get("receipt_bytes", byte[].class))
            .containsExactly(accountSettlement.canonicalBytes());
        assertThat(settlementRow.get("receipt_digest", String.class))
            .isEqualTo(accountSettlement.digest());
        assertThat(settlementRow.get("terminal_read_request_id", UUID.class))
            .isEqualTo(accountSettlement.terminalEvidence().request().readRequestId());

        var exactAccountSettlementRetry =
            selectedOwnerSettlementClient.settle(accountSettlementRequest);
        assertThat(exactAccountSettlementRetry.request()).isEqualTo(accountSettlementRequest);
        assertThat(exactAccountSettlementRetry.receipt().canonicalBytes())
            .containsExactly(accountSettlement.canonicalBytes());
        assertThat(
                accountSourceDsl.fetchCount(DSL.table("account_selected_owner_intake_settlements")))
            .isOne();
        var accountSettlementNewCorrelationRequest =
            SelectedOwnerIntakeSettlementEvidence.Request.create(
                NAMESPACE, automationAuthorization);
        assertThat(accountSettlementNewCorrelationRequest.transportRequestId())
            .isNotEqualTo(accountSettlementRequest.transportRequestId());
        assertThat(accountSettlementNewCorrelationRequest.authorizationBindingBytes())
            .containsExactly(automationAuthorization.canonicalBytes());
        var accountSettlementNewCorrelationResult =
            selectedOwnerSettlementClient.settle(accountSettlementNewCorrelationRequest);
        assertThat(accountSettlementNewCorrelationResult.request())
            .isEqualTo(accountSettlementNewCorrelationRequest);
        assertThat(accountSettlementNewCorrelationResult.receipt().canonicalBytes())
            .containsExactly(accountSettlement.canonicalBytes());
        assertThat(accountSettlementNewCorrelationResult.receipt().digest())
            .isEqualTo(accountSettlement.digest());
        assertThat(
                accountSettlementNewCorrelationResult
                    .receipt()
                    .terminalEvidence()
                    .request()
                    .readRequestId())
            .isEqualTo(accountSettlement.terminalEvidence().request().readRequestId());
        assertThat(
                accountSourceDsl.fetchCount(DSL.table("account_selected_owner_intake_settlements")))
            .isOne();
        assertFailedPrecondition(
            () ->
                automationToAccountHeld.read(
                    SelectedOwnerIntakeAuthorizationReadEvidence.Request.create(
                        NAMESPACE, automationAuthorization)));
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, freshAutomationScope.operationId()))
            .containsExactlyElementsOf(freshAutomationReservationSources);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, automationSourceReservation.operationId()))
            .containsExactlyElementsOf(automationReservationSourcesBeforeAbort);

        var automationReceiptAfterSettlementEvidence =
            automationSelectedSourceCommandClient.retain(automationRetainRequest);
        assertThat(automationReceiptAfterSettlementEvidence.request())
            .isEqualTo(automationRetainRequest);
        var automationReceiptAfterSettlement = automationReceiptAfterSettlementEvidence.receipt();
        assertThat(automationReceiptAfterSettlement.canonicalBytes())
            .containsExactly(automationReceipt.canonicalBytes());
        assertThat(automationReceiptAfterSettlement.receiptDigest())
            .isEqualTo(automationReceipt.receiptDigest());
        assertThat(automationSourceRowCounts(automation.dsl()))
            .isEqualTo(automationRowsAfterRetention);

        UUID freshEntityIntakeRequest = UUID.randomUUID();
        assertThat(freshEntityIntakeRequest)
            .isNotEqualTo(entitySourceReservation.intakeRequestId());
        var entityAuthorizationRequest =
            SelectedOwnerIntakeAuthorizationProducerEvidence.Request.create(
                NAMESPACE, freshEntityIntakeRequest, Owner.ENTITY_MANAGEMENT, selectedCommit);
        var entityAuthorizationResult =
            gdToAccountSelectedOwnerAuthorizationProducer.authorize(
                entityAuthorizationRequest, accountAccess.compact());
        var entityAuthorization = entityAuthorizationResult.binding();
        var freshEntityScope = entityAuthorization.content().scope();
        var freshEntityReservationSources =
            selectedOwnerReservationSourceRows(accountSourceDsl, freshEntityScope.operationId());
        assertThat(entityAuthorizationResult.request()).isEqualTo(entityAuthorizationRequest);
        assertThat(entityAuthorizationResult.canonicalBindingBytes())
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entityAuthorizationResult.digest()).isEqualTo(entityAuthorization.digest());
        assertThat(entityAuthorization.owner()).isEqualTo(Owner.ENTITY_MANAGEMENT);
        assertThat(entityAuthorization.intakeRequestId()).isEqualTo(freshEntityIntakeRequest);
        assertThat(entityAuthorization.operationId())
            .isEqualTo(freshEntityScope.operationId())
            .isNotEqualTo(entitySourceReservation.operationId());
        assertThat(entityAuthorization.fenceId())
            .isEqualTo(freshEntityScope.fenceId())
            .isNotEqualTo(entitySourceReservation.fenceId());
        assertThat(freshEntityScope.owner()).isEqualTo(Owner.ENTITY_MANAGEMENT);
        assertThat(freshEntityScope.targetNamespace()).isEqualTo(NAMESPACE);
        assertThat(freshEntityScope.intakeRequestId()).isEqualTo(freshEntityIntakeRequest);
        assertThat(freshEntityScope.selected().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        assertThat(entityAuthorization.content().snapshotBytes("COMMAND"))
            .containsExactly(selectedSources.command().canonicalBytes());
        assertThat(entityAuthorization.content().snapshotBytes("REALM_POLICY"))
            .containsExactly(selectedSources.policy().canonicalBytes());
        assertThat(entityAuthorization.content().snapshotBytes("ASSET"))
            .containsExactly(selectedSources.asset().canonicalBytes());
        assertThat(entityAuthorization.content().snapshotBytes("GAMEPLAY_RULE"))
            .containsExactly(selectedSources.gameplay().canonicalBytes());
        assertThat(entityAuthorization.content().snapshotBytes("BRANDING"))
            .containsExactly(selectedSources.branding().orElseThrow().canonicalBytes());
        assertThat(entityAuthorization.content().snapshotBytes("TEMPLATE_CONFIG"))
            .containsExactly(selectedSources.templateConfig().orElseThrow().canonicalBytes());
        assertThat(entityAuthorization.content().digest())
            .isEqualTo(
                DraftAuthorizationFenceBinding.digest(
                    entityAuthorization.content().canonicalBytes()));
        assertThat(entityAuthorization.selected().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        assertThat(accountSelectedSourceReads).hasValue(2);
        assertThat(freshEntityReservationSources)
            .containsExactlyElementsOf(expectedAccountSourceEvidence);
        assertThat(
                accountSourceDsl.fetchOne(
                    "SELECT operation_id FROM account_selected_owner_intake_authorizations "
                        + "WHERE operation_id = ?",
                    freshEntityScope.operationId()))
            .isNotNull();
        assertThat(account.recoverSelectedOwnerSourceRead(freshEntityScope, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.FINALIZED);
        assertThat(
                account.recoverSelectedOwnerSourceRead(entitySourceReservation, NAMESPACE).state())
            .isEqualTo(AccountSelectedOwnerIntakeSourceReservationRepository.State.ABORTED);
        assertThat(
                selectedOwnerReservationSourceRows(
                    accountSourceDsl, entitySourceReservation.operationId()))
            .containsExactlyElementsOf(entityReservationSourcesBeforeAbort);

        var entityRowsBeforeRetention = entitySourceRowCounts(entity.dsl());
        assertThat(entityRowsBeforeRetention)
            .allSatisfy((table, count) -> assertThat(count).as(table).isZero());
        var entityRetainRequest =
            EntitySelectedSourceIntakeCommandEvidence.Request.create(
                NAMESPACE, entityAuthorization, actualFreeze);
        assertThatThrownBy(
                () -> wrongWorkloadEntitySelectedSourceCommandClient.retain(entityRetainRequest))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(io.grpc.Status.fromThrowable(failure).getCode())
                        .isEqualTo(io.grpc.Status.Code.PERMISSION_DENIED));
        assertThat(entitySourceRowCounts(entity.dsl())).isEqualTo(entityRowsBeforeRetention);

        var entityRetainEvidence = entitySelectedSourceCommandClient.retain(entityRetainRequest);
        assertThat(entityRetainEvidence.request()).isEqualTo(entityRetainRequest);
        var entityReceipt = entityRetainEvidence.receipt();
        assertThat(entityReceipt.outcome()).isEqualTo("COMMITTED_EMPTY");
        assertThat(entityReceipt.intakeRequestId()).isEqualTo(freshEntityIntakeRequest);
        assertThat(entityReceipt.authorizationBindingBytes())
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entityReceipt.authorizationBindingDigest())
            .isEqualTo(entityAuthorization.digest());
        assertThat(entityReceipt.requestDigest())
            .isEqualTo(
                EntityEmptySelectedSourceIntakeReceipt.requestDigest(
                    NAMESPACE, entityAuthorization, actualFreeze));
        assertThat(entityReceipt.selectedSourceBytes())
            .containsExactly(entityAuthorization.content().canonicalBytes());
        assertThat(entityReceipt.selectedSourceDigest())
            .isEqualTo(entityAuthorization.content().digest());
        var entitySourceRevision = entityReceipt.inputs().ownerSourceInventoryDeclaration();
        assertThat(entityReceipt.sourceRevisionId()).isEqualTo(entitySourceRevision.revisionId());
        assertThat(entityReceipt.sourceRevisionOrder())
            .isEqualTo(entitySourceRevision.revisionOrder());
        assertThat(entityReceipt.selectedSourceRevisionBindingDigest())
            .isEqualTo(entitySourceRevision.sourceBinding().digest());
        assertThat(entitySourceRevision.sourceBinding().canonicalBytes())
            .containsExactly(selectedCommit.canonicalBytes());
        var entityWorldRead = entityReceipt.inputs().worldInventoryReadEvidence();
        assertThat(entityWorldRead.request().authorizationBinding().canonicalBytes())
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entityWorldRead.request().freezeEvidence()).isEqualTo(actualFreeze);
        assertThat(entityReceipt.worldReadRequestId())
            .isEqualTo(entityWorldRead.request().readRequestId());
        assertThat(entityReceipt.worldReadRequestBytes())
            .containsExactly(
                net.firedevops.firemud.common.publication.SelectedOwnerWorldInventoryReadGrpcCodec
                    .toRequest(entityWorldRead.request())
                    .toByteArray());
        assertThat(entityReceipt.worldReadRequestDigest())
            .isEqualTo(
                DraftAuthorizationFenceBinding.digest(entityReceipt.worldReadRequestBytes()));
        assertThat(entityReceipt.worldClosureBytes())
            .containsExactly(entityWorldRead.inventory().canonicalBytes());
        assertThat(entityReceipt.worldClosureDigest())
            .isEqualTo(entityWorldRead.inventory().digest());
        assertThat(entityReceipt.familyStates()).hasSize(23);
        assertThat(entityReceipt.familyStates())
            .allSatisfy(
                family -> {
                  assertThat(family.state())
                      .isEqualTo(EntityEmptySelectedSourceIntakeReceipt.FamilyState.EMPTY);
                  assertThat(family.rowCount()).isZero();
                  assertThat(family.unqualifiedRowCount()).isZero();
                  assertThat(family.retainedRowCount()).isZero();
                  assertThat(family.selectedScopeRowCount()).isZero();
                  assertThat(family.referenceCount()).isZero();
                });
        var entityRowsAfterRetention = entitySourceRowCounts(entity.dsl());
        var persistedEntityReceipt =
            entityIntakeRepository.read(NAMESPACE, freshEntityIntakeRequest).orElseThrow();
        assertThat(persistedEntityReceipt.canonicalBytes())
            .containsExactly(entityReceipt.canonicalBytes());
        assertThat(persistedEntityReceipt.receiptDigest()).isEqualTo(entityReceipt.receiptDigest());
        assertPersistedEntityReceipt(
            entity.dsl(), persistedEntityReceipt, entityAuthorization, actualFreeze);

        var entityReceiptRetryEvidence =
            entitySelectedSourceCommandClient.retain(entityRetainRequest);
        assertThat(entityReceiptRetryEvidence.request()).isEqualTo(entityRetainRequest);
        assertThat(entityReceiptRetryEvidence.receipt().canonicalBytes())
            .containsExactly(entityReceipt.canonicalBytes());
        assertThat(entityReceiptRetryEvidence.receipt().receiptDigest())
            .isEqualTo(entityReceipt.receiptDigest());
        var entityRetainNewCorrelationRequest =
            EntitySelectedSourceIntakeCommandEvidence.Request.create(
                NAMESPACE, entityAuthorization, actualFreeze);
        assertThat(entityRetainNewCorrelationRequest.transportRequestId())
            .isNotEqualTo(entityRetainRequest.transportRequestId());
        assertThat(entityRetainNewCorrelationRequest.originalIntakeAuthorizationBinding())
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entityRetainNewCorrelationRequest.freezeEvidence()).isEqualTo(actualFreeze);
        var entityRetainNewCorrelationEvidence =
            entitySelectedSourceCommandClient.retain(entityRetainNewCorrelationRequest);
        assertThat(entityRetainNewCorrelationEvidence.request())
            .isEqualTo(entityRetainNewCorrelationRequest);
        assertThat(entityRetainNewCorrelationEvidence.receipt().canonicalBytes())
            .containsExactly(entityReceipt.canonicalBytes());
        assertThat(entityRetainNewCorrelationEvidence.receipt().receiptDigest())
            .isEqualTo(entityReceipt.receiptDigest());
        assertThat(entitySourceRowCounts(entity.dsl())).isEqualTo(entityRowsAfterRetention);

        var entitySettlementRequest =
            SelectedOwnerIntakeSettlementEvidence.Request.create(NAMESPACE, entityAuthorization);
        var entitySettlementEvidence =
            selectedOwnerSettlementClient.settle(entitySettlementRequest);
        assertThat(entitySettlementEvidence.request()).isEqualTo(entitySettlementRequest);
        var entitySettlement = entitySettlementEvidence.receipt();
        assertThat(entitySettlement.authorizationBinding().canonicalBytes())
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entitySettlement.ownerReceiptBytes())
            .containsExactly(entityReceipt.canonicalBytes());
        assertThat(entitySettlement.ownerReceiptDigest()).isEqualTo(entityReceipt.receiptDigest());
        assertThat(entitySettlement.entityTerminalEvidence().request().binding().canonicalBytes())
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entitySettlement.entityTerminalEvidence().request().terminalReadPurpose())
            .isEqualTo(EntitySelectedSourceIntakeTerminalReadEvidence.TERMINAL_READ_PURPOSE);
        assertThat(entitySettlement.entityTerminalEvidence().receipt().canonicalBytes())
            .containsExactly(entityReceipt.canonicalBytes());
        var entitySettlementRows =
            accountSourceDsl.fetch(
                "SELECT binding_bytes, binding_digest, terminal_read_request_id, "
                    + "terminal_receipt_bytes, terminal_receipt_digest, receipt_bytes, "
                    + "receipt_digest FROM account_selected_owner_intake_settlements "
                    + "WHERE operation_id = ?",
                entityAuthorization.operationId());
        assertThat(entitySettlementRows).hasSize(1);
        var entitySettlementRow = entitySettlementRows.getFirst();
        assertThat(entitySettlementRow.get("binding_bytes", byte[].class))
            .containsExactly(entityAuthorization.canonicalBytes());
        assertThat(entitySettlementRow.get("binding_digest", String.class))
            .isEqualTo(entityAuthorization.digest());
        assertThat(entitySettlementRow.get("terminal_receipt_bytes", byte[].class))
            .containsExactly(entityReceipt.canonicalBytes());
        assertThat(entitySettlementRow.get("terminal_receipt_digest", String.class))
            .isEqualTo(entityReceipt.receiptDigest());
        assertThat(entitySettlementRow.get("receipt_bytes", byte[].class))
            .containsExactly(entitySettlement.canonicalBytes());
        assertThat(entitySettlementRow.get("receipt_digest", String.class))
            .isEqualTo(entitySettlement.digest());
        assertThat(entitySettlementRow.get("terminal_read_request_id", UUID.class))
            .isEqualTo(entitySettlement.entityTerminalEvidence().request().readRequestId());

        var entitySettlementRetry = selectedOwnerSettlementClient.settle(entitySettlementRequest);
        assertThat(entitySettlementRetry.receipt().canonicalBytes())
            .containsExactly(entitySettlement.canonicalBytes());
        var entitySettlementNewCorrelationRequest =
            SelectedOwnerIntakeSettlementEvidence.Request.create(NAMESPACE, entityAuthorization);
        assertThat(entitySettlementNewCorrelationRequest.transportRequestId())
            .isNotEqualTo(entitySettlementRequest.transportRequestId());
        var entitySettlementNewCorrelation =
            selectedOwnerSettlementClient.settle(entitySettlementNewCorrelationRequest);
        assertThat(entitySettlementNewCorrelation.request())
            .isEqualTo(entitySettlementNewCorrelationRequest);
        assertThat(entitySettlementNewCorrelation.receipt().canonicalBytes())
            .containsExactly(entitySettlement.canonicalBytes());
        assertThat(
                entitySettlementNewCorrelation
                    .receipt()
                    .entityTerminalEvidence()
                    .request()
                    .readRequestId())
            .isEqualTo(entitySettlement.entityTerminalEvidence().request().readRequestId());
        assertThat(
                accountSourceDsl.fetch(
                    "SELECT operation_id FROM account_selected_owner_intake_settlements "
                        + "WHERE operation_id = ?",
                    entityAuthorization.operationId()))
            .hasSize(1);
        assertFailedPrecondition(
            () ->
                entityToAccountHeld.read(
                    SelectedOwnerIntakeAuthorizationReadEvidence.Request.create(
                        NAMESPACE, entityAuthorization)));
        var entityReceiptAfterSettlement =
            entitySelectedSourceCommandClient.retain(entityRetainNewCorrelationRequest).receipt();
        assertThat(entityReceiptAfterSettlement.canonicalBytes())
            .containsExactly(entityReceipt.canonicalBytes());
        assertThat(entitySourceRowCounts(entity.dsl())).isEqualTo(entityRowsAfterRetention);

        var publicationRequest =
            PublicationDigestRequestBinding.full(
                gd.target().canonicalTenantId().toString(),
                Long.toString(gd.target().gameDesignVersionRowId()),
                publishRequestId);
        var selectedPublicationDigests =
            new SelectedDraftPublicationDigestReadService(gd.dsl(), gd.transactions(), NAMESPACE)
                .read(NAMESPACE, publicationRequest);
        assertThat(selectedPublicationDigests.requestBinding().canonicalPreimage())
            .containsExactly(publicationRequest.canonicalPreimage());
        assertThat(selectedPublicationDigests.requestDigest())
            .isEqualTo(publicationRequest.requestDigest());
        DesignControlPlaneDigestDto actualDesignDigest =
            selectedPublicationDigests.gameDesignDigest();
        assertThat(actualDesignDigest.tenantId())
            .isEqualTo(gd.target().canonicalTenantId().toString());
        assertThat(actualDesignDigest.scopeValue()).isEqualTo(publicationRequest.versionId());
        assertThat(actualDesignDigest.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(actualDesignDigest.digestSchemaVersion())
            .isEqualTo(SelectedDraftControlPlaneDigest.SCHEMA_VERSION);
        assertThat(actualDesignDigest.contentDigest()).matches("[0-9a-f]{64}");
        var retainedWorldDigest = selectedPublicationDigests.worldManagementDigest();
        assertThat(retainedWorldDigest.participantKey()).isEqualTo("WORLD_MANAGEMENT");
        assertThat(retainedWorldDigest.succeeded()).isTrue();
        assertThat(retainedWorldDigest.baseVersionId()).isNull();
        assertThat(retainedWorldDigest.scopeValue()).isEqualTo(publicationRequest.versionId());
        assertThat(retainedWorldDigest.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(retainedWorldDigest.contentDigest())
            .isEqualTo(publicationReservation.operation().world().request().contentDigest());
        assertThat(retainedWorldDigest.digestSchemaVersion())
            .isEqualTo(publicationReservation.operation().world().request().digestSchemaVersion());
        var inventoryReader =
            new SelectedDraftAssetInventoryReadService(gd.dsl(), gd.transactions());
        SelectedDraftAssetInventory actualInventory = inventoryReader.read(publicationRequest);
        assertThat(actualInventory.assets()).hasSize(1);
        assertThat(actualInventory.assets().getFirst().usageKey())
            .isEqualTo("selected-resource.txt");
        assertThat(actualInventory.assets().getFirst().contentBytes()).containsExactly(assetBytes);
        assertThat(actualInventory.sourceFamilyDeclarations())
            .containsEntry("ORDINARY", "PRESENT")
            .containsEntry("GAMEPLAY_RULE", "PRESENT")
            .containsEntry("TEMPLATE_CONFIG", "PRESENT");
        assertThat(actualInventory.gameLogicReceipt().selection().canonicalBytes())
            .containsExactly(retainedReceipt.selection().canonicalBytes());
        assertThat(actualInventory.gameLogicReceipt().authorization().canonicalBytes())
            .containsExactly(retainedReceipt.authorization().canonicalBytes());
        assertThat(actualInventory.gameLogicReceipt().receipt().canonicalBytes())
            .containsExactly(retainedReceipt.receipt().canonicalBytes());

        var gameLogicDigestClient =
            new GameLogicClient(
                endpoints,
                pki.client("game-design-service"),
                channels,
                BlockingGrpcStubCustomizer.noop());
        ReflectionTestUtils.setField(gameLogicDigestClient, "workloadNamespace", NAMESPACE);
        ReflectionTestUtils.invokeMethod(gameLogicDigestClient, "init");
        PublishParticipantDigestDto actualGameLogicDigest;
        try (gameLogicDigestClient) {
          actualGameLogicDigest =
              gameLogicDigestClient.getDraftDesignDigestForVersion(
                  publicationRequest, retainedReceipt);
          var exactRetryDigest =
              gameLogicDigestClient.getDraftDesignDigestForVersion(
                  publicationRequest, retainedReceipt);
          assertThat(exactRetryDigest).isEqualTo(actualGameLogicDigest);
        }
        var expectedGlSourceReadBinding =
            new GameLogicPublicationSourceReadBinding(
                publicationRequest, retainedReceipt.authorization());
        assertThat(actualGameLogicDigest.succeeded()).isTrue();
        assertThat(actualGameLogicDigest.participantKey()).isEqualTo("GAME_LOGIC");
        assertThat(actualGameLogicDigest.scopeValue()).isEqualTo(publicationRequest.versionId());
        assertThat(actualGameLogicDigest.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(actualGameLogicDigest.contentDigest()).matches("[0-9a-f]{64}");
        assertThat("sha256:" + actualGameLogicDigest.contentDigest())
            .isEqualTo(
                GameplayRuleManifest.sha256(retainedReceipt.receipt().terminal().manifestBytes()));
        assertThat(actualGameLogicDigest.digestSchemaVersion())
            .isEqualTo(GameLogicPublicationSourceReadService.DIGEST_SCHEMA_VERSION);
        assertThat(actualGameLogicDigest.abilitySchemaDigest())
            .isEqualTo(
                GameplayAbilitySchemaProjection.digest(
                    retainedReceipt.authorization().source().manifest()));
        assertThat(glPublicationReadBindings).hasSize(2);
        for (GameLogicPublicationSourceReadBinding observed : glPublicationReadBindings) {
          assertThat(observed.canonicalBytes())
              .containsExactly(expectedGlSourceReadBinding.canonicalBytes());
          assertThat(observed.authorization().canonicalBytes())
              .containsExactly(retainedReceipt.authorization().canonicalBytes());
          assertThat(observed.publicationRequest().canonicalPreimage())
              .containsExactly(publicationRequest.canonicalPreimage());
        }
        assertThat(glPublicationReadResults).hasSize(2);
        for (GameLogicPublicationSourceReadService.Result observed : glPublicationReadResults) {
          assertThat(observed.binding().canonicalBytes())
              .containsExactly(expectedGlSourceReadBinding.canonicalBytes());
          assertThat(observed.terminalBytes())
              .containsExactly(retainedReceipt.receipt().terminal().canonicalBytes());
          assertThat(observed.selectedSourceBytes())
              .containsExactly(retainedReceipt.authorization().source().canonicalBytes());
          assertThat(observed.manifestDigest())
              .isEqualTo("sha256:" + actualGameLogicDigest.contentDigest());
          assertThat(observed.abilitySchemaDigest())
              .isEqualTo(actualGameLogicDigest.abilitySchemaDigest());
        }

        var candidateService =
            new VersionAssetExportCandidateServiceImpl(
                gd.dsl(),
                new GameRepository(gd.dsl()),
                new VersionRepository(gd.dsl()),
                gd.transactions(),
                new ObjectMapper());
        var objectStore =
            this.objectStore =
                new MinioConditionalObjectStore(candidateService, publicationRequest);
        objectStore.requireCandidateBeforeWrites(actualInventory);
        var properties = new AssetStoreProperties();
        properties.setEndpoint(objectStore.endpoint());
        properties.setBucket(objectStore.bucket());
        properties.setRegion(MINIO_REGION.id());
        properties.setAccessKey(MINIO_ACCESS_KEY);
        properties.setSecretKey(MINIO_SECRET_KEY);
        var exporter =
            new AssetExportServiceImpl(
                new VersionAssetPublicationRepository(gd.dsl()),
                objectStore.client(),
                properties,
                new ObjectMapper(),
                gd.dsl(),
                gd.transactions(),
                candidateService);

        SelectedExportResult first = exporter.exportSelectedAssets(publicationRequest);
        assertThat(first.candidateBinding().inventoryBytes())
            .containsExactly(actualInventory.canonicalBytes());
        assertThat(first.candidateBinding().inventoryDigest()).isEqualTo(actualInventory.digest());
        assertThat(first.candidateBinding().selectedCommitDigest())
            .isEqualTo(selectedCommit.digest());
        assertThat(first.manifest().artifactDigests()).hasSize(1);
        assertThat(first.manifest().artifactDigests().getFirst().contentDigest())
            .isEqualTo(actualInventory.assets().getFirst().contentDigest());
        assertThat(first.manifest().artifactDigests().getFirst().contentDigest())
            .isEqualTo("sha256:" + sha256(assetBytes));
        assertThat(objectStore.objects()).hasSize(2);

        SelectedExportResult retry = exporter.exportSelectedAssets(publicationRequest);
        assertThat(retry).isEqualTo(first);
        assertThat(objectStore.putCalls()).isEqualTo(4);
        assertThat(objectStore.getCalls()).isEqualTo(4);
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());
        assertThat(gd.dsl().fetchCount(DSL.table("version_asset_export_candidate"))).isOne();
        var exportedAsset = objectStore.objectAt("artifacts/sha256/" + sha256(assetBytes));
        assertThat(exportedAsset.bytes()).containsExactly(assetBytes);
        assertThat(exportedAsset.contentType()).isEqualTo("text/plain");
        var exportedManifest =
            objectStore.objectAt(
                "manifests/sha256/" + first.manifest().manifestHash().substring(7));
        assertThat(exportedManifest.bytes())
            .containsExactly(first.candidateBinding().manifestBytes());
        assertThat(exportedManifest.contentType()).isEqualTo("application/json");

        byte[] alteredManifestBytes = alteredManifest(first.candidateBinding().manifestBytes());
        var alteredManifest =
            new ExportedAssetManifest(
                "sha256:" + sha256(alteredManifestBytes),
                1,
                first.manifest().requiredManifestAssetKeys(),
                first.manifest().artifactDigests());
        assertThatThrownBy(
                () ->
                    candidateService.recordSelectedCandidate(
                        actualInventory, alteredManifest, alteredManifestBytes))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("SELECTED_ASSET_EXPORT_CANDIDATE_CONFLICT");
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());

        int writesBeforeSubstitution = objectStore.putCalls();
        var changedPublicationRequest =
            PublicationDigestRequestBinding.full(
                publicationRequest.tenantId(),
                publicationRequest.versionId(),
                "different-selected-publication-request");
        assertThatThrownBy(() -> exporter.exportSelectedAssets(changedPublicationRequest))
            .isInstanceOf(io.grpc.StatusRuntimeException.class)
            .satisfies(
                failure ->
                    assertThat(((io.grpc.StatusRuntimeException) failure).getStatus().getCode())
                        .isEqualTo(io.grpc.Status.Code.NOT_FOUND));
        assertThat(objectStore.putCalls()).isEqualTo(writesBeforeSubstitution);
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());
        assertThat(gd.dsl().fetchCount(DSL.table("game_design_selected_game_logic_receipt")))
            .isOne();

        var mapper = Mappers.getMapper(VersionMapper.class);
        var publishAttemptRepository = new PublishAttemptRepository(gd.dsl());
        var releaseRepository = new PublishedReleaseBundleRepository(gd.dsl());
        var releaseService =
            new PublishedReleaseBundleServiceImpl(
                releaseRepository,
                new RevisionRepository(gd.dsl()),
                new VersionRepository(gd.dsl()),
                new ObjectMapper());
        var artifactService =
            new VersionAssetArtifactServiceImpl(
                new VersionAssetArtifactRepository(gd.dsl()),
                new VersionAssetPurgeWorkflowRepository(gd.dsl()),
                new VersionRepository(gd.dsl()),
                releaseRepository,
                new LaunchDescriptorRepository(gd.dsl()),
                new VersionTemplateRemapSetRepository(gd.dsl()),
                exporter,
                releaseService,
                new ObjectMapper());
        var participantDigests =
            selectedSelectorParticipantDigests(
                selectedCommit,
                gd.target().gameDesignVersionRowId(),
                retainedWorldDigest,
                actualDesignDigest,
                actualGameLogicDigest);
        var gate = mock(PublishGateService.class);
        when(gate.collectSelectedFullVersionParticipantDigests(any(), any(), any()))
            .thenReturn(participantDigests);
        var controlPlaneDigests =
            new net.firedevops.firemud.gamedesign.service.impl.ControlPlaneDigestServiceImpl(
                new net.firedevops.firemud.gamedesign.repository.GameTemplateRepository(gd.dsl()),
                new GameAssetRepository(gd.dsl()),
                new RevisionRepository(gd.dsl()),
                new ObjectMapper());
        // Exercise the actual finalizer dependency before it records a message-only failure.
        // Keep its original exception chain visible if an authored input cannot be digested.
        var finalizerDesignDigest =
            gd.transaction(
                () ->
                    controlPlaneDigests.getDigestForVersion(
                        mapper.toDto(
                            new VersionRepository(gd.dsl())
                                .findByTenantIdAndId(
                                    gd.target().gameDesignVersionTenantKey(),
                                    gd.target().gameDesignVersionRowId())
                                .orElseThrow())));
        assertThat(finalizerDesignDigest.contentDigest()).matches("[0-9a-f]{64}");
        var realAttemptPersistence =
            new PublishAttemptServiceImpl(
                publishAttemptRepository, new PublishAttemptParticipantDigestRepository(gd.dsl()));
        var publisher =
            new VersionPublishCommandServiceImpl(
                new VersionRepository(gd.dsl()),
                new GameRepository(gd.dsl()),
                publishAttemptRepository,
                mapper,
                exporter,
                transactionBoundPublishAttempts(gd, realAttemptPersistence),
                gate,
                controlPlaneDigests,
                artifactService,
                releaseService,
                mock(RecordedParticipantDigestService.class));
        var published =
            publisher.publishSelectedDraftFullVersion(
                gd.target().gameDesignVersionTenantKey(),
                gd.target().gameDesignVersionRowId(),
                intent.notes(),
                publishRequestId,
                publicationRequest.derivedWorkflowIdentity());
        assertThat(published.versionState()).isEqualTo(VersionLifecycleState.PUBLISHED);
        assertThat(
                new net.firedevops.firemud.gamedesign.repository.PublishAttemptRepository(gd.dsl())
                    .findByPublishWorkflowId(publicationRequest.derivedWorkflowIdentity())
                    .orElseThrow()
                    .getStatus())
            .isEqualTo(net.firedevops.firemud.gamedesign.model.PublishAttemptStatus.SUCCEEDED);
        var release =
            releaseService.getPublishedReleaseBundle(
                gd.target().gameDesignVersionTenantKey(), gd.target().gameDesignVersionRowId());
        assertThat(release.attestationSchemaVersion()).isEqualTo("v3");
        assertThat(release.worldPublishedStartLocationEvidence())
            .isEqualTo(publicationReservation.operation().world());
        assertThat(release.worldPublishedStartLocationEvidence().request().digestSchemaVersion())
            .isEqualTo(4);
        assertThat(release.participantDigests().getFirst().digestSchemaVersion()).isEqualTo(4);
        var retainedGameLogicParticipant =
            release.participantDigests().stream()
                .filter(digest -> "GAME_LOGIC".equals(digest.participantKey()))
                .findFirst()
                .orElseThrow();
        assertThat(retainedGameLogicParticipant.contentDigest())
            .isEqualTo(actualGameLogicDigest.contentDigest());
        assertThat(retainedGameLogicParticipant.abilitySchemaDigest())
            .isEqualTo(actualGameLogicDigest.abilitySchemaDigest());
        assertThat(retainedGameLogicParticipant.appliedCommitId())
            .isEqualTo(selectedCommit.commitId().toString());
        assertThat(retainedGameLogicParticipant.digestSchemaVersion())
            .isEqualTo(actualGameLogicDigest.digestSchemaVersion());
        var retainedDesignParticipant =
            release.participantDigests().stream()
                .filter(digest -> "GAME_DESIGN_CONTROL_PLANE".equals(digest.participantKey()))
                .findFirst()
                .orElseThrow();
        assertThat(retainedDesignParticipant.scopeValue())
            .isEqualTo(actualDesignDigest.scopeValue());
        assertThat(retainedDesignParticipant.appliedCommitId())
            .isEqualTo(actualDesignDigest.appliedCommitId());
        assertThat(retainedDesignParticipant.contentDigest())
            .isEqualTo(actualDesignDigest.contentDigest());
        assertThat(retainedDesignParticipant.digestSchemaVersion())
            .isEqualTo(actualDesignDigest.digestSchemaVersion());
        assertThat(gd.dsl().fetchCount(DSL.table("published_release_bundle"))).isOne();
        assertThat(
                gd.dsl()
                    .fetchCount(DSL.table("game_design_start_session_launch_descriptor_binding")))
            .isZero();

        var association = retainedTemplateAssociations.getFirst();
        var pinnedSelection =
            new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                gd.target().canonicalVersionId(),
                selectedCommit.commitId(),
                publicationRequest.derivedWorkflowIdentity(),
                association.digest());
        String controlPlaneRequestId = "genuine-start-session-" + UUID.randomUUID();
        UUID ownerAttemptId = UUID.randomUUID();
        long ownerFence = 19L;
        var startSessionTuple =
            startSessionTuple(
                controlPlaneRequestId,
                gd.target().canonicalTenantId(),
                templateId,
                "Genuine selected descriptor storage proof");
        startSessionProjections.put(
            controlPlaneRequestId,
            new StartSessionProjectionBinding(startSessionTuple, ownerAttemptId, ownerFence));
        try (var projectionClient =
            new StartSessionRedeemedOperationProjectionClient(
                endpoints, pki.client("game-design-service"), channels, NAMESPACE)) {
          projectionClient.init();
          var associationReader =
              new StartSessionTemplateAssociationReadService(
                  gd.dsl(), gd.transactions(), NAMESPACE, projectionClient, releaseService);
          var descriptorProducer =
              new StartSessionLaunchDescriptorProducer(
                  gd.dsl(),
                  gd.transactions(),
                  NAMESPACE,
                  associationReader,
                  releaseService,
                  new ObjectMapper());
          var firstDescriptorBinding =
              withGameSessionPeer(
                  () ->
                      descriptorProducer.resolve(
                          startSessionReadRequest(
                              startSessionTuple,
                              ownerAttemptId,
                              ownerFence,
                              pinnedSelection,
                              UUID.randomUUID())));
          assertThat(firstDescriptorBinding.descriptor().versionId())
              .isEqualTo(gd.target().gameDesignVersionRowId());
          assertThat(firstDescriptorBinding.descriptor().launchDescriptorId()).isNotBlank();
          assertThat(firstDescriptorBinding.associationRead().association().associationDigest())
              .isEqualTo(association.digest());
          var expectedAuthoredWorldBinding =
              firstDescriptorBinding.descriptor().authoredWorldBinding();

          var versionRepository = new VersionRepository(gd.dsl());
          var launchDescriptorRepository = new LaunchDescriptorRepository(gd.dsl());
          TemplateRemapSetService remapSetService =
              new TemplateRemapSetServiceImpl(
                  new VersionTemplateRemapSetRepository(gd.dsl()), versionRepository);
          var launchDescriptorService =
              new LaunchDescriptorServiceImpl(
                  new GameTemplateRepository(gd.dsl()),
                  launchDescriptorRepository,
                  versionRepository,
                  releaseService,
                  remapSetService,
                  sourceRepository,
                  new ObjectMapper());
          ReflectionTestUtils.setField(launchDescriptorService, "workloadNamespace", NAMESPACE);
          CompleteLaunchBindingService completeLaunchBindingService =
              new CompleteLaunchBindingServiceImpl(
                  launchDescriptorService, sourceRepository, releaseService, versionRepository);
          ReflectionTestUtils.setField(
              completeLaunchBindingService, "workloadNamespace", NAMESPACE);
          AtomicInteger completeBindingOwnerReadCalls = new AtomicInteger();
          CompleteLaunchBindingService transportCompleteLaunchBindingService =
              readCompleteLaunchBindingInOwnerSnapshot(
                  gd, completeLaunchBindingService, completeBindingOwnerReadCalls);
          register(
              gdHandlers, completeLaunchBindingGrpcService(transportCompleteLaunchBindingService));
          UUID completeBindingReadRequestId = UUID.randomUUID();
          var persistedLaunchDescriptor =
              launchDescriptorRepository
                  .findBoundByRequest(
                      NAMESPACE, gd.target().canonicalTenantId(), controlPlaneRequestId)
                  .orElseThrow();
          List<String> completeBindingOwnerRowsBefore =
              completeBindingOwnerRowVersions(
                  gd.dsl(),
                  persistedLaunchDescriptor.getId(),
                  release.id(),
                  gd.target().gameDesignVersionRowId(),
                  worldSource.operationId(),
                  gd.target().canonicalTenantId(),
                  controlPlaneRequestId);
          var completeBinding =
              readCompleteLaunchBindingInOwnerSnapshot(
                  gd,
                  completeLaunchBindingService,
                  completeBindingReadRequestId,
                  gd.target().canonicalTenantId(),
                  worldSource.worldSlug(),
                  controlPlaneRequestId,
                  expectedAuthoredWorldBinding.requestDigest(),
                  expectedAuthoredWorldBinding.resultDigest());
          assertThat(completeBinding.descriptor()).isEqualTo(expectedAuthoredWorldBinding);
          completeBinding.releaseAttestation().requireValid(completeBinding.descriptor());
          assertThat(completeBinding.releaseAttestation().schemaVersion()).isEqualTo(3);
          assertThat(completeBinding.releaseAttestation().descriptorResultDigest())
              .isEqualTo(expectedAuthoredWorldBinding.resultDigest());
          assertThat(completeBinding.releaseAttestation().worldStartLocationEvidence())
              .isEqualTo(release.worldPublishedStartLocationEvidence());
          var completeWorldSelector =
              completeBinding.releaseAttestation().worldStartLocationEvidence();
          assertThat(completeWorldSelector.request().canonicalTenantId())
              .isEqualTo(gd.target().canonicalTenantId());
          assertThat(completeWorldSelector.request().canonicalVersionId())
              .isEqualTo(gd.target().canonicalVersionId());
          assertThat(completeWorldSelector.request().publicationRequestId())
              .isEqualTo(intent.publishRequestId());
          assertThat(completeWorldSelector.request().publicationFence())
              .isEqualTo(actualFreeze.acknowledgement().publicationFence());
          assertThat(completeWorldSelector.request().appliedCommitId())
              .isEqualTo(selectedCommit.commitId().toString());
          assertThat(completeWorldSelector.request().contentDigest())
              .isEqualTo(retainedWorldDigest.contentDigest());
          assertThat(completeWorldSelector.request().digestSchemaVersion())
              .isEqualTo(retainedWorldDigest.digestSchemaVersion());
          assertThat(completeWorldSelector.request().worldAffectedTuples()).isNotEmpty();
          var completeWorldParticipant =
              completeBinding.releaseAttestation().participantDigests().getFirst();
          assertThat(completeWorldParticipant.participantKey()).isEqualTo("WORLD_MANAGEMENT");
          assertThat(completeWorldParticipant.appliedCommitId())
              .isEqualTo(selectedCommit.commitId().toString());
          assertThat(completeWorldParticipant.contentDigest())
              .isEqualTo(retainedWorldDigest.contentDigest());
          assertThat(completeWorldParticipant.digestSchemaVersion())
              .isEqualTo(retainedWorldDigest.digestSchemaVersion());
          var completeGameLogicParticipant =
              completeBinding.releaseAttestation().participantDigests().stream()
                  .filter(participant -> "GAME_LOGIC".equals(participant.participantKey()))
                  .findFirst()
                  .orElseThrow();
          assertThat(completeGameLogicParticipant.appliedCommitId())
              .isEqualTo(selectedCommit.commitId().toString());
          assertThat(completeGameLogicParticipant.contentDigest())
              .isEqualTo(actualGameLogicDigest.contentDigest());
          assertThat(completeGameLogicParticipant.digestSchemaVersion())
              .isEqualTo(actualGameLogicDigest.digestSchemaVersion());
          assertThat(completeGameLogicParticipant.abilitySchemaDigest())
              .isEqualTo(actualGameLogicDigest.abilitySchemaDigest());

          var completeBindingRpcRequest =
              GetLaunchDescriptorRequest.newBuilder()
                  .setRequestId(completeBindingReadRequestId.toString())
                  .setCanonicalTenantId(gd.target().canonicalTenantId().toString())
                  .setWorldSlug(worldSource.worldSlug())
                  .setControlPlaneRequestId(controlPlaneRequestId)
                  .setExpectedRequestDigest(expectedAuthoredWorldBinding.requestDigest())
                  .setExpectedResultDigest(expectedAuthoredWorldBinding.resultDigest())
                  .build();
          try (var worldToGameDesignCompleteBindingClient =
              new AuthoredWorldLaunchDescriptorClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE)) {
            worldToGameDesignCompleteBindingClient.init();
            CompleteLaunchBindingEvidence transportedCompleteBinding =
                worldToGameDesignCompleteBindingClient.getComplete(completeBindingRpcRequest);
            assertThat(transportedCompleteBinding.descriptor())
                .isEqualTo(completeBinding.descriptor());
            assertThat(transportedCompleteBinding.releaseAttestation())
                .isEqualTo(completeBinding.releaseAttestation());
            assertThat(
                    worldToGameDesignCompleteBindingClient.getComplete(completeBindingRpcRequest))
                .isEqualTo(transportedCompleteBinding);
          }

          try (var unauthorizedAccountToGameDesignClient =
              new AuthoredWorldLaunchDescriptorClient(
                  endpoints, pki.client("account-service"), channels, NAMESPACE)) {
            unauthorizedAccountToGameDesignClient.init();
            int ownerReadsBeforeDeniedPeer = completeBindingOwnerReadCalls.get();
            assertThatThrownBy(
                    () ->
                        unauthorizedAccountToGameDesignClient.getComplete(
                            GetLaunchDescriptorRequest.getDefaultInstance()))
                .isInstanceOf(IllegalStateException.class);
            assertThat(completeBindingOwnerReadCalls.get()).isEqualTo(ownerReadsBeforeDeniedPeer);
          }

          try (var worldToGameDesignCompleteBindingClient =
              new AuthoredWorldLaunchDescriptorClient(
                  endpoints, pki.client("world-management-service"), channels, NAMESPACE)) {
            worldToGameDesignCompleteBindingClient.init();
            assertThatThrownBy(
                    () ->
                        worldToGameDesignCompleteBindingClient.getComplete(
                            completeBindingRpcRequest.toBuilder()
                                .setExpectedRequestDigest(
                                    changedDigest(expectedAuthoredWorldBinding.requestDigest()))
                                .build()))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                    () ->
                        worldToGameDesignCompleteBindingClient.getComplete(
                            completeBindingRpcRequest.toBuilder()
                                .setExpectedResultDigest(
                                    changedDigest(expectedAuthoredWorldBinding.resultDigest()))
                                .build()))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                    () ->
                        worldToGameDesignCompleteBindingClient.getComplete(
                            completeBindingRpcRequest.toBuilder()
                                .setCanonicalTenantId(UUID.randomUUID().toString())
                                .build()))
                .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(
                    () ->
                        worldToGameDesignCompleteBindingClient.getComplete(
                            completeBindingRpcRequest.toBuilder()
                                .setWorldSlug("genuine-selected-export-world-substitute")
                                .build()))
                .isInstanceOf(IllegalStateException.class);
          }

          var completeBindingRetry =
              readCompleteLaunchBindingInOwnerSnapshot(
                  gd,
                  completeLaunchBindingService,
                  completeBindingReadRequestId,
                  gd.target().canonicalTenantId(),
                  worldSource.worldSlug(),
                  controlPlaneRequestId,
                  expectedAuthoredWorldBinding.requestDigest(),
                  expectedAuthoredWorldBinding.resultDigest());
          assertThat(completeBindingRetry).isEqualTo(completeBinding);

          assertThatThrownBy(
                  () ->
                      readCompleteLaunchBindingInOwnerSnapshot(
                          gd,
                          completeLaunchBindingService,
                          completeBindingReadRequestId,
                          gd.target().canonicalTenantId(),
                          worldSource.worldSlug(),
                          controlPlaneRequestId,
                          changedDigest(expectedAuthoredWorldBinding.requestDigest()),
                          expectedAuthoredWorldBinding.resultDigest()))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      readCompleteLaunchBindingInOwnerSnapshot(
                          gd,
                          completeLaunchBindingService,
                          completeBindingReadRequestId,
                          gd.target().canonicalTenantId(),
                          worldSource.worldSlug(),
                          controlPlaneRequestId,
                          expectedAuthoredWorldBinding.requestDigest(),
                          changedDigest(expectedAuthoredWorldBinding.resultDigest())))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      readCompleteLaunchBindingInOwnerSnapshot(
                          gd,
                          completeLaunchBindingService,
                          completeBindingReadRequestId,
                          UUID.randomUUID(),
                          worldSource.worldSlug(),
                          controlPlaneRequestId,
                          expectedAuthoredWorldBinding.requestDigest(),
                          expectedAuthoredWorldBinding.resultDigest()))
              .isInstanceOf(IllegalArgumentException.class);
          assertThatThrownBy(
                  () ->
                      readCompleteLaunchBindingInOwnerSnapshot(
                          gd,
                          completeLaunchBindingService,
                          completeBindingReadRequestId,
                          gd.target().canonicalTenantId(),
                          "genuine-selected-export-world-substitute",
                          controlPlaneRequestId,
                          expectedAuthoredWorldBinding.requestDigest(),
                          expectedAuthoredWorldBinding.resultDigest()))
              .isInstanceOf(IllegalArgumentException.class);
          assertThat(
                  completeBindingOwnerRowVersions(
                      gd.dsl(),
                      persistedLaunchDescriptor.getId(),
                      release.id(),
                      gd.target().gameDesignVersionRowId(),
                      worldSource.operationId(),
                      gd.target().canonicalTenantId(),
                      controlPlaneRequestId))
              .containsExactlyElementsOf(completeBindingOwnerRowsBefore);

          var replay =
              withGameSessionPeer(
                  () ->
                      descriptorProducer.resolve(
                          startSessionReadRequest(
                              startSessionTuple,
                              ownerAttemptId,
                              ownerFence,
                              pinnedSelection,
                              UUID.randomUUID())));
          assertThat(replay.descriptor()).isEqualTo(firstDescriptorBinding.descriptor());
          assertThat(replay.associationRead().request().readRequestId())
              .isNotEqualTo(firstDescriptorBinding.associationRead().request().readRequestId());

          var changedTuple =
              startSessionTuple(
                  controlPlaneRequestId,
                  gd.target().canonicalTenantId(),
                  templateId,
                  "Changed original StartSession tuple");
          // The fixed Account projection double returns the original operation; these altered
          // tuple/attempt/fence requests are rejected before Game Design opens its SQL snapshot.
          assertProjectionUnavailable(
              "original operation tuple",
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  changedTuple,
                                  ownerAttemptId,
                                  ownerFence,
                                  pinnedSelection,
                                  UUID.randomUUID()))));

          assertProjectionUnavailable(
              "owner attempt",
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  startSessionTuple,
                                  UUID.randomUUID(),
                                  ownerFence,
                                  pinnedSelection,
                                  UUID.randomUUID()))));
          assertProjectionUnavailable(
              "owner fence",
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  startSessionTuple,
                                  ownerAttemptId,
                                  ownerFence + 1L,
                                  pinnedSelection,
                                  UUID.randomUUID()))));
          var changedSelection =
              new StartSessionTemplateAssociationReadEvidence.ExactReplay(
                  gd.target().canonicalVersionId(),
                  selectedCommit.commitId(),
                  publicationRequest.derivedWorkflowIdentity(),
                  "sha256:" + "0".repeat(64));
          assertFailedPrecondition(
              () ->
                  withGameSessionPeer(
                      () ->
                          descriptorProducer.resolve(
                              startSessionReadRequest(
                                  startSessionTuple,
                                  ownerAttemptId,
                                  ownerFence,
                                  changedSelection,
                                  UUID.randomUUID()))));
        }
        assertThat(gd.dsl().fetchCount(DSL.table("launch_descriptor"))).isOne();
        assertThat(
                gd.dsl()
                    .fetchCount(DSL.table("game_design_start_session_launch_descriptor_binding")))
            .isOne();
        assertThat(gd.dsl().fetchCount(DSL.table("published_release_bundle"))).isOne();
        assertThat(candidateService.readSelectedCandidate(publicationRequest))
            .isEqualTo(first.candidateBinding());
      }
    } finally {
      stopAll(
          automationServer, entityServer, gameLogicServer, accountServer, worldServer, gdServer);
    }
  }

  /**
   * Entity and Automation participant digests remain stipulated test inputs. Their source receipts
   * and Account settlements do not replace those publication participants. World freeze, inventory,
   * selector and Game Design and Game Logic source reads come from their actual owner compositions.
   * This does not prove complete four-owner publication or activation.
   */
  private static List<PublishParticipantDigestDto> selectedSelectorParticipantDigests(
      DraftCommitBinding selectedCommit,
      long versionRowId,
      PublishParticipantDigestDto worldManagementDigest,
      DesignControlPlaneDigestDto gameDesignDigest,
      PublishParticipantDigestDto gameLogicDigest) {
    return AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
        .map(
            participant -> {
              if ("WORLD_MANAGEMENT".equals(participant)) return worldManagementDigest;
              if ("GAME_LOGIC".equals(participant)) return gameLogicDigest;
              if ("GAME_DESIGN_CONTROL_PLANE".equals(participant)) {
                return new PublishParticipantDigestDto(
                    participant,
                    gameDesignDigest.scopeValue(),
                    null,
                    gameDesignDigest.appliedCommitId(),
                    gameDesignDigest.contentDigest(),
                    gameDesignDigest.digestSchemaVersion(),
                    null,
                    null,
                    null);
              }
              return new PublishParticipantDigestDto(
                  participant,
                  Long.toString(versionRowId),
                  null,
                  selectedCommit.commitId().toString(),
                  "c".repeat(64),
                  AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                      participant, AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION),
                  null,
                  null,
                  null);
            })
        .toList();
  }

  /** Uses the real owner persistence service inside the fixture's explicit READ_COMMITTED tx. */
  private static PublishAttemptService transactionBoundPublishAttempts(
      Store store, PublishAttemptServiceImpl delegate) {
    return new PublishAttemptService() {
      @Override
      public <T> T executeScriptPatchTransaction(Supplier<T> operation) {
        return delegate.executeScriptPatchTransaction(() -> store.transaction(operation));
      }

      @Override
      public <T> T executeFullVersionTransaction(Supplier<T> operation) {
        return delegate.executeFullVersionTransaction(() -> store.transaction(operation));
      }

      @Override
      public void createFullVersionAttempt(
          net.firedevops.firemud.gamedesign.dto.VersionDto version,
          String workflowId,
          String requestDigest) {
        delegate.createFullVersionAttempt(version, workflowId, requestDigest);
      }

      @Override
      public void createScriptPatchAttempt(
          net.firedevops.firemud.gamedesign.dto.VersionDto version,
          String workflowId,
          Long baseVersionId,
          String requestDigest) {
        delegate.createScriptPatchAttempt(version, workflowId, baseVersionId, requestDigest);
      }

      @Override
      public void recordScriptPatchParticipantDigests(
          String workflowId, List<PublishParticipantDigestDto> digests) {
        delegate.recordScriptPatchParticipantDigests(workflowId, digests);
      }

      @Override
      public void markScriptPatchSucceeded(String workflowId) {
        delegate.markScriptPatchSucceeded(workflowId);
      }

      @Override
      public void markScriptPatchFailed(String workflowId, String code, String message) {
        delegate.markScriptPatchFailed(workflowId, code, message);
      }

      @Override
      public java.util.Optional<net.firedevops.firemud.gamedesign.entity.PublishAttempt>
          findByPublishWorkflowId(String workflowId) {
        return delegate.findByPublishWorkflowId(workflowId);
      }

      @Override
      public void recordFullVersionParticipantDigests(
          String workflowId, List<PublishParticipantDigestDto> digests) {
        delegate.recordFullVersionParticipantDigests(workflowId, digests);
      }

      @Override
      public void markFullVersionSucceeded(String workflowId) {
        delegate.markFullVersionSucceeded(workflowId);
      }

      @Override
      public void markFullVersionFailed(String workflowId, String code, String message) {
        delegate.markFullVersionFailed(workflowId, code, message);
      }
    };
  }

  /** Test-only Account projection responder that replays a fixed original tuple and fence. */
  private static BindableService accountProjectionDouble(
      Map<String, StartSessionProjectionBinding> projections) {
    return new StartSessionOperatorAuthorizationServiceGrpc
        .StartSessionOperatorAuthorizationServiceImplBase() {
      @Override
      public void readRedeemedOperationProjection(
          ReadRedeemedOperationProjectionRequest request,
          StreamObserver<ReadRedeemedOperationProjectionResponse> responseObserver) {
        try {
          var requestedPreTuple =
              StartSessionPreAuthorizationReservationTuple.fromCanonicalJson(
                  request
                      .getCanonicalPreAuthorizationTupleBytes()
                      .toString(StandardCharsets.UTF_8));
          var original =
              Objects.requireNonNull(
                  projections.get(requestedPreTuple.controlPlaneRequestId()),
                  "No stipulated Account projection was registered for this logical operation");
          var tuple = original.tuple();
          var preTuple = tuple.preAuthorizationTuple();
          var bundle =
              StartSessionAuthorityEvidenceBundle.decode(tuple.authorityEvidenceBundleBytes());
          var reference = tuple.bundleReference();
          Instant redeemedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
          var response =
              ReadRedeemedOperationProjectionResponse.newBuilder()
                  .setControlPlaneRequestId(preTuple.controlPlaneRequestId())
                  .setCanonicalPreAuthorizationTupleBytes(
                      ByteString.copyFrom(preTuple.canonicalJson(), StandardCharsets.UTF_8))
                  .setMutationDigest(preTuple.mutationDigest())
                  .setAuthorizationReferenceFingerprint(tuple.authorizationReferenceFingerprint())
                  .setReservationOwnerId(tuple.reservationOwnerId().toString())
                  .setReservationClaimFence(tuple.reservationClaimFence())
                  .setAuthenticatedRedeemerWorkloadIdentity(
                      "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service")
                  .setOwnerAttemptId(original.ownerAttemptId().toString())
                  .setOwnerFence(original.ownerFence())
                  .setReferenceExpiresAt(timestamp(Instant.parse(bundle.authorizationExpiresAt())))
                  .setRedeemedAt(timestamp(redeemedAt))
                  .setIssuanceOperationId(bundle.issuanceOperationId().toString())
                  .setIssuanceFence(Long.parseLong(bundle.issuanceFence()))
                  .setBundleReference(
                      AuthorityEvidenceBundleReference.newBuilder()
                          .setBundleVersion(reference.bundleVersion())
                          .setSourceVersion(reference.sourceVersion())
                          .setSourceFence(reference.sourceFence())
                          .setLinearization(reference.linearization()))
                  .setAuthorityEvidenceBundle(
                      ByteString.copyFrom(tuple.authorityEvidenceBundleBytes()))
                  .build();
          responseObserver.onNext(response);
          responseObserver.onCompleted();
        } catch (RuntimeException invalid) {
          responseObserver.onError(invalid);
        }
      }
    };
  }

  private static StartSessionPostAuthorizationExecutionTuple startSessionTuple(
      String controlPlaneRequestId, UUID canonicalTenantId, long templateId, String auditReason) {
    var action =
        new StartSessionOperatorAction(
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_ID,
            StartSessionOperatorAction.ACTION_FAMILY_SCHEMA_VERSION,
            new StartSessionOperatorAction.Scope(canonicalTenantId, NAMESPACE),
            new StartSessionOperatorAction.Target(templateId, UUID.randomUUID()),
            StartSessionOperatorAction.ExpectedVersion.ABSENT,
            new StartSessionOperatorAction.Mutation(StartSessionOperatorAction.ClientIp.absent()),
            auditReason);
    var preTuple =
        StartSessionPreAuthorizationReservationTuple.createHuman(
            controlPlaneRequestId, UUID.randomUUID(), action);
    var reference =
        new StartSessionAuthorityEvidenceBundle.BundleReference(
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION, "17", "23", "18446744073709551615");
    var now = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    var authority =
        Map.of(
            "bundleVersion",
            StartSessionAuthorityEvidenceBundle.BUNDLE_VERSION,
            "authorityScope",
            Map.of(
                "scope",
                Map.of("tenantId", canonicalTenantId.toString(), "targetNamespace", NAMESPACE),
                "actionFamily",
                preTuple.actionFamily(),
                "applicableAccountId",
                preTuple.actor().accountId().toString(),
                "applicableTenantId",
                canonicalTenantId.toString()),
            "accountProjectionEvidence",
            Map.of(
                "sourceType",
                "ACCOUNT",
                "sourceEvidenceId",
                "sha256:" + "a".repeat(64),
                "sourceEvidenceVersion",
                "17",
                "projectionStatus",
                "CURRENT",
                "evaluatedAt",
                now.toString(),
                "expiresAt",
                now.plusSeconds(300).toString()),
            "issuanceOperationIdentity",
            Map.of(
                "issuanceOperationId",
                UUID.randomUUID().toString(),
                "controlPlaneRequestId",
                preTuple.controlPlaneRequestId(),
                "actionFamilyRequestIdentity",
                Map.of(
                    "requestIdentityKind",
                    "controlPlaneRequestId",
                    "requestId",
                    preTuple.controlPlaneRequestId()),
                "mutationDigest",
                preTuple.mutationDigest()),
            "issuanceKind",
            "human_operator",
            "authorityTuple",
            Map.of(
                "issuerAuthGeneration",
                1L,
                "accountAuthorityGeneration",
                2L,
                "tenantAuthorityGeneration",
                Map.of(canonicalTenantId.toString(), 3L),
                "membershipAuthorityGeneration",
                Map.of(canonicalTenantId.toString(), 4L),
                "privateRealmGrantVersions",
                List.of()),
            "membershipVersion",
            Map.of(canonicalTenantId.toString(), 5L),
            "issuanceFence",
            "23",
            "issuanceEvidence",
            Map.of(
                "evidenceType",
                StartSessionAuthorityEvidenceBundle.HUMAN_EVIDENCE_TYPE,
                "actorAccountId",
                preTuple.actor().accountId().toString(),
                "controlUiTokenJti",
                UUID.randomUUID().toString(),
                "role",
                "tenantAdmin",
                "accountGeneration",
                "2",
                "tenantGeneration",
                "3"));
    try {
      byte[] authorityBytes =
          Rfc8785CanonicalJson.canonicalizeUtf8(new ObjectMapper().writeValueAsString(authority));
      return StartSessionPostAuthorizationExecutionTuple.createHuman(
          preTuple,
          "spiffe://firemud/ns/" + NAMESPACE + "/sa/logging-admin-service",
          "arfp/v1/test-key/" + "b".repeat(64),
          UUID.randomUUID(),
          19L,
          authorityBytes,
          reference);
    } catch (IOException invalid) {
      throw new IllegalStateException("Could not construct stipulated Account tuple", invalid);
    }
  }

  private static StartSessionTemplateAssociationReadEvidence.Request startSessionReadRequest(
      StartSessionPostAuthorizationExecutionTuple tuple,
      UUID attemptId,
      long fence,
      StartSessionTemplateAssociationReadEvidence.Selection selection,
      UUID observationId) {
    return new StartSessionTemplateAssociationReadEvidence.Request(
        StartSessionTemplateAssociationReadEvidence.SCHEMA_VERSION,
        NAMESPACE,
        observationId,
        tuple.canonicalBytes(),
        attemptId,
        fence,
        selection);
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static <T> T withGameSessionPeer(Supplier<T> operation) {
    var peer =
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/" + NAMESPACE + "/sa/game-session-service",
            NAMESPACE,
            "game-session-service");
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return operation.get();
    } finally {
      context.detach(previous);
    }
  }

  private static void assertFailedPrecondition(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure ->
                assertThat(((io.grpc.StatusRuntimeException) failure).getStatus().getCode())
                    .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION));
  }

  private static void assertProjectionUnavailable(String rejectedEvidence, Runnable operation) {
    assertThatThrownBy(operation::run)
        .as(
            "Account projection rejects changed %s before the Game Design SQL snapshot",
            rejectedEvidence)
        .isInstanceOf(io.grpc.StatusRuntimeException.class)
        .satisfies(
            failure -> {
              var status = ((io.grpc.StatusRuntimeException) failure).getStatus();
              assertThat(status.getCode()).isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
              assertThat(status.getDescription())
                  .isEqualTo("Exact redeemed StartSession projection unavailable");
            });
  }

  private Store gameDesignStore() {
    var store = serviceStore(GAME_DESIGN_POSTGRES, "game-design");
    String tenantKey = UUID.randomUUID().toString();
    UUID creationRequestId = UUID.randomUUID();
    var creation =
        Objects.requireNonNull(
            store.transaction(
                () ->
                    new GameTenantCreationRepository(store.dsl(), new GameRepository(store.dsl()))
                        .createCandidate(
                            NAMESPACE,
                            creationRequestId,
                            tenantKey,
                            "Genuine selected export Game",
                            "Persisted Game Design source and publication identity")));
    assertThat(
            new GameTenantCreationRepository(store.dsl(), new GameRepository(store.dsl()))
                .read(creationRequestId, NAMESPACE))
        .contains(creation);
    assertThat(
            new TemplateReferenceRepository(store.dsl())
                .readPhase(creation.canonicalTenantId())
                .orElseThrow()
                .phase())
        .isEqualTo(TemplateReferenceRepository.Phase.BACKFILLING);
    var version = new Version();
    version.setTenantId(tenantKey);
    version.setVersionNumber(1);
    version.setVersionState(VersionLifecycleState.DRAFT);
    version.setVersionStateEpoch(1L);
    var savedVersion =
        Objects.requireNonNull(
            store.transaction(() -> new VersionRepository(store.dsl()).save(version)));
    var target =
        new TargetProof(
            savedVersion.getCanonicalTenantId(),
            savedVersion.getCanonicalVersionId(),
            savedVersion.getId(),
            savedVersion.getTenantId(),
            savedVersion.getIdentitySourceGameRowId(),
            savedVersion.getIdentitySourceGameTenantKey(),
            savedVersion.getIdentitySourceProvenanceKind());
    assertThat(new GameDesignSourceRepository(store.dsl()).readGenesis(target)).isPresent();
    return store.withIdentity(target, savedVersion);
  }

  private static SelectedOwnerIntakeSourceReadScope selectedOwnerSourceScope(
      Owner owner, DraftCommitBinding selected) {
    return new SelectedOwnerIntakeSourceReadScope(
        owner,
        NAMESPACE,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        selected);
  }

  private static Map<String, Integer> selectedOwnerSourceRowCounts(DSLContext dsl) {
    return Map.of(
        "command",
        dsl.fetchCount(DSL.table("game_design_command_source_snapshot")),
        "realm-policy",
        dsl.fetchCount(DSL.table("game_design_realm_policy_snapshot")),
        "asset",
        dsl.fetchCount(DSL.table("game_design_asset_source_snapshot")),
        "gameplay-rule",
        dsl.fetchCount(DSL.table("game_design_gameplay_rule_snapshot")),
        "branding",
        dsl.fetchCount(DSL.table("game_design_branding_source_snapshot")),
        "template-config",
        dsl.fetchCount(DSL.table("game_design_template_config_source_snapshot")));
  }

  private static Map<String, Integer> automationSourceRowCounts(DSLContext dsl) {
    return Map.of(
        "scripts", dsl.fetchCount(DSL.table("scripts")),
        "script_event_bindings", dsl.fetchCount(DSL.table("script_event_bindings")),
        "script_patch_base_bindings", dsl.fetchCount(DSL.table("script_patch_base_bindings")),
        "automation_empty_selected_source_association",
            dsl.fetchCount(DSL.table("automation_empty_selected_source_association")),
        "automation_empty_selected_source_receipt",
            dsl.fetchCount(DSL.table("automation_empty_selected_source_receipt")),
        "automation_empty_source_numeric_key_reservation",
            dsl.fetchCount(DSL.table("automation_empty_source_numeric_key_reservation")));
  }

  private static Map<String, Integer> entitySourceRowCounts(DSLContext dsl) {
    var counts = new TreeMap<String, Integer>();
    for (String table :
        List.of(
            "actor_active_conditions",
            "actor_resource_states",
            "body_layout_slot_definitions",
            "character_equipment",
            "character_friend",
            "characters",
            "container_instances",
            "crafting_ingredients",
            "crafting_recipes",
            "entity_mutation_effects",
            "equipment_slot_definitions",
            "inventory",
            "item_instances",
            "item_stacks",
            "item_transfer_audits",
            "item_visible_ref_counters",
            "items",
            "npcs",
            "room_ground_inventory",
            "entity_empty_source_numeric_key_reservation",
            "entity_empty_selected_source_association",
            "entity_empty_selected_source_family_state",
            "entity_empty_selected_source_receipt")) {
      counts.put(table, dsl.fetchCount(DSL.table(table)));
    }
    for (String table : ENTITY_EMPTY_ONLY_PROVIDER_TABLES.values()) {
      counts.put(table, dsl.fetchCount(DSL.table(table)));
    }
    return Map.copyOf(counts);
  }

  private static void assertPersistedEntityReceipt(
      DSLContext dsl,
      EntityEmptySelectedSourceIntakeReceipt receipt,
      net.firedevops.firemud.common.account.sourceintake.SelectedOwnerIntakeAuthorizationBinding
          authorization,
      WorldSelectedDraftPublicationFreezeEvidence freeze) {
    assertThat(receipt.localTenantKey()).isPositive();
    assertThat(receipt.localVersionKey()).isPositive().isNotEqualTo(receipt.localTenantKey());

    var association =
        dsl.fetchOne(
            "SELECT genesis_id, operation_id, fence_id, intake_request_id, canonical_tenant_id, "
                + "canonical_version_id, selected_commit_id, source_revision_id, "
                + "source_revision_order, local_tenant_key, local_version_key, request_digest, "
                + "schema_digest, authorization_binding_digest, selected_source_digest, "
                + "world_read_request_id, world_read_request_digest, world_closure_digest, "
                + "receipt_digest FROM entity_empty_selected_source_association "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            authorization.targetNamespace(),
            authorization.intakeRequestId());
    assertThat(association).isNotNull();
    assertThat(association.get("operation_id", UUID.class)).isEqualTo(authorization.operationId());
    assertThat(association.get("fence_id", UUID.class)).isEqualTo(authorization.fenceId());
    assertThat(association.get("canonical_tenant_id", UUID.class))
        .isEqualTo(authorization.tenantId());
    assertThat(association.get("canonical_version_id", UUID.class))
        .isEqualTo(authorization.versionId());
    assertThat(association.get("selected_commit_id", UUID.class))
        .isEqualTo(authorization.selected().commitId());
    assertThat(association.get("source_revision_id", UUID.class))
        .isEqualTo(receipt.sourceRevisionId());
    assertThat(association.get("source_revision_order", String.class))
        .isEqualTo(receipt.sourceRevisionOrder());
    assertThat(association.get("local_tenant_key", Long.class)).isEqualTo(receipt.localTenantKey());
    assertThat(association.get("local_version_key", Long.class))
        .isEqualTo(receipt.localVersionKey());
    assertThat(association.get("request_digest", String.class)).isEqualTo(receipt.requestDigest());
    assertThat(association.get("schema_digest", String.class)).isEqualTo(receipt.schemaDigest());
    assertThat(association.get("authorization_binding_digest", String.class))
        .isEqualTo(authorization.digest());
    assertThat(association.get("selected_source_digest", String.class))
        .isEqualTo(receipt.selectedSourceDigest());
    assertThat(association.get("world_read_request_id", UUID.class))
        .isEqualTo(receipt.worldReadRequestId());
    assertThat(association.get("world_read_request_digest", String.class))
        .isEqualTo(receipt.worldReadRequestDigest());
    assertThat(association.get("world_closure_digest", String.class))
        .isEqualTo(receipt.worldClosureDigest());
    assertThat(association.get("receipt_digest", String.class)).isEqualTo(receipt.receiptDigest());

    var reservedKeys =
        dsl.fetch(
            "SELECT key_kind, numeric_key, claim_kind FROM entity_empty_source_numeric_key_reservation "
                + "WHERE numeric_key IN (?, ?) ORDER BY key_kind",
            receipt.localTenantKey(),
            receipt.localVersionKey());
    assertThat(reservedKeys).hasSize(2);
    assertThat(reservedKeys.get(0).get("key_kind", String.class)).isEqualTo("TENANT");
    assertThat(reservedKeys.get(0).get("numeric_key", Long.class))
        .isEqualTo(receipt.localTenantKey());
    assertThat(reservedKeys.get(0).get("claim_kind", String.class))
        .isEqualTo("CANONICAL_EMPTY_SOURCE");
    assertThat(reservedKeys.get(1).get("key_kind", String.class)).isEqualTo("VERSION");
    assertThat(reservedKeys.get(1).get("numeric_key", Long.class))
        .isEqualTo(receipt.localVersionKey());
    assertThat(reservedKeys.get(1).get("claim_kind", String.class))
        .isEqualTo("CANONICAL_EMPTY_SOURCE");

    var persistedFamilyStates =
        dsl.fetch(
            "SELECT family_name, owner_state, evidence_kind, row_count, unqualified_row_count, "
                + "retained_row_count, selected_scope_row_count, reference_count "
                + "FROM entity_empty_selected_source_family_state "
                + "WHERE target_namespace = ? AND intake_request_id = ? ORDER BY family_name",
            authorization.targetNamespace(),
            authorization.intakeRequestId());
    var expectedFamilyNames =
        receipt.familyStates().stream()
            .map(EntityEmptySelectedSourceIntakeReceipt.FamilyCensus::family)
            .sorted()
            .toList();
    assertThat(persistedFamilyStates).hasSize(23);
    assertThat(persistedFamilyStates.map(row -> row.get("family_name", String.class)))
        .containsExactlyElementsOf(expectedFamilyNames);
    for (var persisted : persistedFamilyStates) {
      var family =
          receipt.familyStates().stream()
              .filter(
                  candidate ->
                      candidate.family().equals(persisted.get("family_name", String.class)))
              .findFirst()
              .orElseThrow();
      assertThat(persisted.get("owner_state", String.class)).isEqualTo(family.state().name());
      assertThat(persisted.get("evidence_kind", String.class))
          .isEqualTo(family.evidenceKind().name());
      assertThat(persisted.get("row_count", Long.class)).isZero();
      assertThat(persisted.get("unqualified_row_count", Long.class)).isZero();
      assertThat(persisted.get("retained_row_count", Long.class)).isZero();
      assertThat(persisted.get("selected_scope_row_count", Long.class)).isZero();
      assertThat(persisted.get("reference_count", Long.class)).isZero();
    }

    for (var provider : ENTITY_EMPTY_ONLY_PROVIDER_TABLES.entrySet()) {
      var providerRow =
          dsl.fetchOne(
              "SELECT provider_schema_version, owner_state, row_count, reference_count, "
                  + "authorization_binding_digest, selected_source_digest, "
                  + "source_revision_binding_digest, world_closure_digest, provider_state_digest "
                  + "FROM "
                  + provider.getValue()
                  + " WHERE target_namespace = ? AND intake_request_id = ?",
              authorization.targetNamespace(),
              authorization.intakeRequestId());
      assertThat(providerRow).as(provider.getKey()).isNotNull();
      assertThat(providerRow.get("provider_schema_version", Short.class)).isEqualTo((short) 1);
      assertThat(providerRow.get("owner_state", String.class)).isEqualTo("EMPTY");
      assertThat(providerRow.get("row_count", Long.class)).isZero();
      assertThat(providerRow.get("reference_count", Long.class)).isZero();
      assertThat(providerRow.get("authorization_binding_digest", String.class))
          .isEqualTo(authorization.digest());
      assertThat(providerRow.get("selected_source_digest", String.class))
          .isEqualTo(receipt.selectedSourceDigest());
      assertThat(providerRow.get("source_revision_binding_digest", String.class))
          .isEqualTo(receipt.selectedSourceRevisionBindingDigest());
      assertThat(providerRow.get("world_closure_digest", String.class))
          .isEqualTo(receipt.worldClosureDigest());
      assertThat(providerRow.get("provider_state_digest", String.class))
          .isEqualTo(receipt.providerStateDigest(provider.getKey()));
    }

    var persistedReceipt =
        dsl.fetchOne(
            "SELECT request_digest, schema_digest, receipt_digest, receipt_bytes "
                + "FROM entity_empty_selected_source_receipt "
                + "WHERE target_namespace = ? AND intake_request_id = ?",
            authorization.targetNamespace(),
            authorization.intakeRequestId());
    assertThat(persistedReceipt).isNotNull();
    assertThat(persistedReceipt.get("request_digest", String.class))
        .isEqualTo(receipt.requestDigest());
    assertThat(persistedReceipt.get("schema_digest", String.class))
        .isEqualTo(receipt.schemaDigest());
    assertThat(persistedReceipt.get("receipt_digest", String.class))
        .isEqualTo(receipt.receiptDigest());
    assertThat(persistedReceipt.get("receipt_bytes", byte[].class))
        .containsExactly(receipt.canonicalBytes());

    assertThat(receipt.inputs().worldInventoryReadEvidence().request().freezeEvidence())
        .isEqualTo(freeze);
  }

  private static List<String> selectedOwnerReservationSourceRows(DSLContext dsl, UUID operationId) {
    return dsl.fetch(
            "SELECT source_key, source_evidence "
                + "FROM account_selected_owner_intake_source_read_sources "
                + "WHERE operation_id = ? ORDER BY source_key",
            operationId)
        .map(
            row ->
                row.get("source_key", String.class)
                    + ":"
                    + HexFormat.of().formatHex(row.get("source_evidence", byte[].class)));
  }

  private static Store serviceStore(PostgreSQLContainer<?> postgres, String service) {
    var schema = service.replace('-', '_') + "_" + UUID.randomUUID().toString().replace("-", "");
    var data =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    data.setSchema(schema);
    Flyway.configure()
        .dataSource(data)
        .schemas(schema)
        .defaultSchema(schema)
        .table("flyway_schema_history_" + service.replace('-', '_'))
        .placeholders(Map.of("serviceSchema", schema))
        .locations(migrationLocation(service))
        .load()
        .migrate();
    var transactions = new DataSourceTransactionManager(data);
    var configuration = new DefaultConfiguration();
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(data)));
    configuration.set(new SpringTransactionProvider(transactions));
    return new Store(DSL.using(configuration), transactions, null, null);
  }

  private static String migrationLocation(String service) {
    var directoryName = service + "-service";
    for (Path current = Path.of("").toAbsolutePath();
        current != null;
        current = current.getParent()) {
      Path migrations =
          current
              .resolve("services")
              .resolve(directoryName)
              .resolve("src/main/resources/db/migration");
      if (Files.isDirectory(migrations)) return "filesystem:" + migrations;
    }
    throw new IllegalStateException("Service migration directory is unavailable: " + service);
  }

  private static DraftCommitBinding binding(
      TargetProof target, long assetId, String fileName, String worldSlug, String worldDisplayName)
      throws Exception {
    UUID commit = UUID.randomUUID();
    UUID region = UUID.randomUUID();
    UUID zone = UUID.randomUUID();
    UUID room = UUID.randomUUID();
    var familyCounts = new ArrayList<WorldFreshGraphFamilyCount>();
    for (var family :
        List.of(
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE,
            WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING)) {
      familyCounts.add(
          WorldFreshGraphFamilyCount.newBuilder()
              .setFamily(family)
              .setCount(
                  family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION
                          || family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE
                          || family == WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM
                      ? 1
                      : 0)
              .build());
    }
    var declaration =
        WorldFreshGraphDeclaration.newBuilder()
            .setTenantId(target.canonicalTenantId().toString())
            .setVersionId(target.canonicalVersionId().toString())
            .addAllFamilyCounts(familyCounts)
            .setInboundSourceClosure(authoredEmptyInboundSourceClosure())
            .setStartLocation(
                net.firedevops.firemud.worldmanagement.v1.RoomTemplateRef.newBuilder()
                    .setTenantId(target.canonicalTenantId().toString())
                    .setVersionId(target.canonicalVersionId().toString())
                    .setRoomTemplateId(room.toString()))
            .build();
    var mutations =
        List.of(
            mutation(
                    commit, room, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ROOM)
                .setRoom(
                    RoomDesignMutation.newBuilder()
                        .setName("Genuine selected room")
                        .setZoneId(zone.toString())
                        .setDescription("Selected export source room"))
                .build(),
            mutation(
                    commit, zone, region, WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_ZONE)
                .setZone(
                    ZoneDesignMutation.newBuilder()
                        .setName("Genuine selected zone")
                        .setRegionId(region.toString()))
                .build(),
            mutation(
                    commit,
                    region,
                    region,
                    WorldDesignAggregateType.WORLD_DESIGN_AGGREGATE_TYPE_REGION)
                .setRegion(
                    RegionDesignMutation.newBuilder()
                        .setName("Genuine selected region")
                        .setWeather("clear")
                        .setShardId(1))
                .setFreshGraphDeclaration(declaration)
                .build());

    var revisions = new ArrayList<RevisionPayload>();
    var units = new ArrayList<AffectedUnit>();
    UUID gameplayRuleRevisionId = UUID.randomUUID();
    revisions.add(
        new RevisionPayload(
            "0",
            gameplayRuleRevisionId,
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.upsertPayload(
                new GameplayRuleManifest.AdmissionTag("genuine-selected-export"))));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            GameplayRuleSource.SCOPE,
            target.canonicalVersionId().toString(),
            GameplayRuleSource.SCOPE,
            GameplayRuleSource.SCOPE_ID,
            "0"));
    revisions.add(
        new RevisionPayload(
            "1",
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            AssetSource.upsertPayload(
                Long.toString(assetId), fileName, AssetSource.Requiredness.REQUIRED)));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            AssetSource.SCOPE,
            target.canonicalVersionId().toString(),
            AssetSource.SCOPE,
            AssetSource.SCOPE_ID,
            "0"));
    UUID realmPolicyRevisionId = UUID.randomUUID();
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            realmPolicyRevisionId,
            Owner.GAME_DESIGN_CONTROL_PLANE,
            realmPolicyRevisionPayload(worldSlug, worldDisplayName, realmPolicyRevisionId)));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            RealmPolicySource.SCOPE,
            target.canonicalVersionId().toString(),
            RealmPolicySource.SCOPE,
            "effective",
            "0"));
    var templateConfig =
        new TemplateConfigSource.Config(
            "{\"schemaVersion\":1,\"baseVersionId\":\""
                + target.canonicalVersionId()
                + "\",\"world\":{\"regions\":[],\"rooms\":[]},"
                + "\"entity\":{\"items\":[],\"npcs\":[]},\"gameLogic\":{\"inputs\":[{"
                + "\"family\":\"ADMISSION_TAGS\",\"key\":\"genuine-selected-export\","
                + "\"revisionId\":\""
                + gameplayRuleRevisionId
                + "\"}]},\"automation\":{\"scripts\":[],"
                + "\"scriptPatch\":{\"presence\":\"ABSENT\"}},\"supportedSettings\":[]}");
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.createPayload(
                "Genuine selected export template", templateConfig)));
    units.add(
        new AffectedUnit(
            Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.SCOPE,
            target.canonicalVersionId().toString(),
            TemplateConfigSource.SCOPE,
            TemplateConfigSource.SCOPE_ID,
            "0"));
    // These are authored inventory declarations only, not Entity/Automation owner receipts.
    var automationInventory = automationSourceInventory();
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.ownerInventoryPayload(
                Owner.AUTOMATION_SCRIPTING, automationInventory)));
    var entityInventory = entitySourceInventory();
    revisions.add(
        new RevisionPayload(
            Integer.toString(revisions.size()),
            UUID.randomUUID(),
            Owner.GAME_DESIGN_CONTROL_PLANE,
            TemplateConfigSource.ownerInventoryPayload(Owner.ENTITY_MANAGEMENT, entityInventory)));
    for (var worldMutation : mutations) {
      revisions.add(
          new RevisionPayload(
              Integer.toString(revisions.size()),
              UUID.fromString(worldMutation.getLogicalRevisionId()),
              Owner.WORLD_MANAGEMENT,
              JsonFormat.printer().omittingInsignificantWhitespace().print(worldMutation)));
      String family =
          worldMutation.getAggregateType().name().replace("WORLD_DESIGN_AGGREGATE_TYPE_", "");
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              worldMutation.getAggregateId(),
              "AGGREGATE",
              worldMutation.getAggregateId(),
              "0"));
      units.add(
          new AffectedUnit(
              Owner.WORLD_MANAGEMENT,
              family,
              worldMutation.getAggregateId(),
              "REGION_SUBTREE",
              worldMutation.getScopeId(),
              "0"));
    }
    return DraftCommitBinding.create(
        target, UUID.randomUUID(), commit, "base-commit-0", revisions, units);
  }

  private static String realmPolicyRevisionPayload(
      String worldSlug, String worldDisplayName, UUID revisionId) throws Exception {
    var mapper = new ObjectMapper();
    var policyInput =
        Map.of(
            "schemaVersion",
            1,
            "worldSlug",
            worldSlug,
            "worldDisplayName",
            worldDisplayName,
            "realmSlug",
            "genuine-selected-export",
            "realmDisplayName",
            "Genuine Selected Export",
            "visible",
            true,
            "publicProduction",
            true,
            "stateScope",
            RealmEntryPolicy.StateScope.SHARED.name(),
            "entryPolicy",
            RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY.name());
    var policy = RealmEntryPolicy.parse(mapper.writeValueAsString(policyInput), mapper);
    var revision =
        Map.of(
            "revisionKind", RealmEntryPolicy.REVISION_KIND,
            "logicalRevisionId", revisionId.toString(),
            "policy", mapper.readTree(policy.canonicalJson()));
    return new String(
        Rfc8785CanonicalJson.canonicalizeUtf8(mapper.writeValueAsString(revision)),
        StandardCharsets.UTF_8);
  }

  private static AutomationAuthoredSourceInventoryDeclaration automationSourceInventory() {
    return AutomationAuthoredSourceInventoryDeclaration.parse(
        "{\"schema\":\"automation-authored-source-inventory/v1\","
            + "\"families\":{\"SCRIPT_DEFINITIONS\":[],\"EVENT_BINDINGS\":[],"
            + "\"SCRIPT_PATCH_SOURCES\":[]}}");
  }

  private static EntityAuthoredSourceInventoryDeclaration entitySourceInventory() {
    return EntityAuthoredSourceInventoryDeclaration.parse(
        "{\"schema\":\"entity-authored-source-inventory/v1\","
            + "\"equipmentApplicability\":\"NOT_APPLICABLE\",\"families\":{"
            + "\"ACTOR_BODY_LAYOUT_ASSIGNMENTS\":[],\"ARCHETYPE_ASSIGNMENTS\":[],"
            + "\"ARCHETYPE_CONSTRAINTS\":[],\"ARCHETYPE_ROOTS\":[],"
            + "\"BALANCE_CURVE_ATTACHMENTS\":[],\"BALANCE_CURVE_ROOTS\":[],"
            + "\"BODY_LAYOUT_MEMBERSHIPS\":[],\"BODY_LAYOUT_ROOTS\":[],"
            + "\"CRAFTING_INGREDIENT_BINDINGS\":[],\"CRAFTING_RECIPE_RESULT_BINDINGS\":[],"
            + "\"CRAFTING_RECIPE_ROOTS\":[],\"EQUIPMENT_ATTACHMENT_RULES\":[],"
            + "\"EQUIPMENT_CAPABILITIES\":[],\"EQUIPMENT_COMPATIBILITY_RULES\":[],"
            + "\"EQUIPMENT_OCCUPANCY_RULES\":[],\"EQUIPMENT_SLOT_GROUPS\":[],"
            + "\"EQUIPMENT_SLOT_ROOTS\":[],\"INBOUND_LOOT_BINDINGS\":[],"
            + "\"ITEM_TEMPLATE_ROOTS\":[],\"LOOT_ITEM_MAPPINGS\":[],"
            + "\"LOOT_TABLE_ROOTS\":[],\"NPC_TEMPLATE_ROOTS\":[],"
            + "\"OTHER_ACTOR_TEMPLATE_ROOTS\":[]}}");
  }

  private static WorldInboundSourceClosureDeclaration authoredEmptyInboundSourceClosure() {
    return WorldInboundSourceClosureDeclaration.newBuilder()
        .setSchemaVersion(1)
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ROOT))
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_LOOT_REFERENCE_ATTACHMENT))
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_SELECTION))
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_BEHAVIOR_BINDING))
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_HOOK))
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_SCRIPT_REFERENCE))
        .addFamilyCounts(
            inboundSourceFamilyCount(
                WorldInboundSourceFamily.WORLD_INBOUND_SOURCE_FAMILY_AUTOMATION_TARGET_BINDING))
        .build();
  }

  private static WorldInboundSourceFamilyCount inboundSourceFamilyCount(
      WorldInboundSourceFamily family) {
    return WorldInboundSourceFamilyCount.newBuilder().setFamily(family).setCount(0).build();
  }

  private static WorldDesignMutationRevision worldMutation(RevisionPayload revision) {
    try {
      var mutation = WorldDesignMutationRevision.newBuilder();
      JsonFormat.parser().merge(revision.payload(), mutation);
      return mutation.build();
    } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
      throw new AssertionError(
          "Selected World source revision is not valid protobuf JSON", exception);
    }
  }

  private static WorldDesignMutationRevision.Builder mutation(
      UUID commit, UUID aggregate, UUID region, WorldDesignAggregateType type) {
    return WorldDesignMutationRevision.newBuilder()
        .setCommitId(commit.toString())
        .setLogicalRevisionId(UUID.randomUUID().toString())
        .setAggregateId(aggregate.toString())
        .setAggregateType(type)
        .setOperation(WorldDesignMutationOperation.WORLD_DESIGN_MUTATION_OPERATION_UPSERT)
        .setScopeType(WorldDesignScopeType.WORLD_DESIGN_SCOPE_TYPE_REGION_SUBTREE)
        .setScopeId(region.toString());
  }

  private static Server startServer(TestPki pki, String service, MutableHandlerRegistry handlers)
      throws Exception {
    var identity = pki.server(service);
    return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
        .fallbackHandlerRegistry(handlers)
        .maxInboundMessageSize(20 * 1024 * 1024)
        .maxInboundMetadataSize(AccountOriginalDraftOrderGrpcCodec.MAX_METADATA_BYTES)
        .sslContext(
            GrpcSslContexts.forServer(identity.certificate().toFile(), identity.key().toFile())
                .trustManager(pki.ca().toFile())
                .clientAuth(ClientAuth.REQUIRE)
                .build())
        .build()
        .start();
  }

  private static void register(MutableHandlerRegistry handlers, BindableService... services) {
    for (var service : services)
      handlers.addService(ServerInterceptors.intercept(service, new GrpcPeerIdentityInterceptor()));
  }

  private static String loopback(Server server) {
    return "127.0.0.1:" + server.getPort();
  }

  private static void stopAll(Server... servers) throws InterruptedException {
    for (var server : servers) if (server != null) server.shutdownNow();
    var terminated = true;
    for (var server : servers)
      if (server != null) terminated &= server.awaitTermination(5, TimeUnit.SECONDS);
    if (!terminated) throw new IllegalStateException("Test owner server did not stop");
  }

  private static byte[] alteredManifest(byte[] original) {
    String json = new String(original, StandardCharsets.UTF_8);
    String marker = "\"selectedInventoryDigest\":\"";
    int valueStart = json.indexOf(marker);
    if (valueStart < 0) throw new IllegalStateException("Selected digest field is missing");
    valueStart += marker.length();
    int valueEnd = json.indexOf('"', valueStart);
    if (valueEnd < 0) throw new IllegalStateException("Selected digest value is malformed");
    String previous = json.substring(valueStart, valueEnd);
    String changed = previous.charAt(0) == '0' ? "1".repeat(64) : "0".repeat(64);
    return (json.substring(0, valueStart) + changed + json.substring(valueEnd))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String changedDigest(String digest) {
    if (digest == null || !digest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected a sha256-prefixed digest");
    }
    char replacement = digest.charAt(digest.length() - 1) == '0' ? '1' : '0';
    return digest.substring(0, digest.length() - 1) + replacement;
  }

  private static CompleteLaunchBindingDto readCompleteLaunchBindingInOwnerSnapshot(
      Store owner,
      CompleteLaunchBindingService service,
      UUID readRequestId,
      UUID canonicalTenantId,
      String worldSlug,
      String controlPlaneRequestId,
      String expectedRequestDigest,
      String expectedResultDigest) {
    TransactionTemplate ownerSnapshot = new TransactionTemplate(owner.transactions());
    ownerSnapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ownerSnapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    ownerSnapshot.setReadOnly(true);
    return Objects.requireNonNull(
        ownerSnapshot.execute(
            ignored ->
                service.getCompleteLaunchBinding(
                    readRequestId,
                    canonicalTenantId,
                    worldSlug,
                    controlPlaneRequestId,
                    expectedRequestDigest,
                    expectedResultDigest)));
  }

  /**
   * Applies the owner's declared transaction boundary to the directly composed handler service; the
   * fixture does not use Spring AOP on the gRPC worker thread.
   */
  private static CompleteLaunchBindingService readCompleteLaunchBindingInOwnerSnapshot(
      Store owner, CompleteLaunchBindingService service, AtomicInteger readCalls) {
    return (readRequestId,
        canonicalTenantId,
        worldSlug,
        controlPlaneRequestId,
        expectedRequestDigest,
        expectedResultDigest) -> {
      readCalls.incrementAndGet();
      return readCompleteLaunchBindingInOwnerSnapshot(
          owner,
          service,
          readRequestId,
          canonicalTenantId,
          worldSlug,
          controlPlaneRequestId,
          expectedRequestDigest,
          expectedResultDigest);
    };
  }

  private static GameDesignGrpcService completeLaunchBindingGrpcService(
      CompleteLaunchBindingService completeLaunchBindingService) {
    GameDesignGrpcService grpcService =
        new GameDesignGrpcService(
            mock(PingService.class),
            mock(RevisionService.class),
            mock(VersionService.class),
            mock(LaunchDescriptorService.class),
            completeLaunchBindingService,
            mock(TemplateRemapSetService.class),
            mock(VersionAssetArtifactService.class),
            mock(SettingsAuthorityService.class),
            mock(GameAuthoredHelpTopicService.class),
            mock(TemporalVersionPublishWorkflowMetadataResolver.class),
            new SimpleMeterRegistry());
    ReflectionTestUtils.setField(grpcService, "workloadNamespace", NAMESPACE);
    return grpcService;
  }

  private static List<String> completeBindingOwnerRowVersions(
      DSLContext dsl,
      long launchDescriptorRowId,
      long releaseBundleId,
      long versionRowId,
      UUID sourceOperationId,
      UUID canonicalTenantId,
      String controlPlaneRequestId) {
    return List.of(
        dsl.fetchSingle(
                "select xmin::text from launch_descriptor where id = ?", launchDescriptorRowId)
            .get(0, String.class),
        dsl.fetchSingle(
                "select xmin::text from published_release_bundle where id = ?", releaseBundleId)
            .get(0, String.class),
        dsl.fetchSingle("select xmin::text from version where id = ?", versionRowId)
            .get(0, String.class),
        dsl.fetchSingle(
                "select xmin::text from game_design_authored_world_source_operations where operation_id = ?",
                sourceOperationId)
            .get(0, String.class),
        dsl.fetchSingle(
                "select xmin::text from game_design_start_session_launch_descriptor_binding "
                    + "where target_namespace = ? and canonical_tenant_id = ? "
                    + "and control_plane_request_id = ?",
                NAMESPACE,
                canonicalTenantId,
                controlPlaneRequestId)
            .get(0, String.class));
  }

  private record Store(
      DSLContext dsl,
      DataSourceTransactionManager transactions,
      TargetProof target,
      Version version) {
    <T> T transaction(Supplier<T> operation) {
      var transaction = new TransactionTemplate(transactions);
      transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      return transaction.execute(ignored -> operation.get());
    }

    Store withIdentity(TargetProof target, Version version) {
      return new Store(dsl, transactions, target, version);
    }
  }

  private record TestIdentity(Path certificate, Path key) {
    CommonGrpcClientProperties properties(Path ca) {
      var result = new CommonGrpcClientProperties();
      result.setPlaintext(false);
      result.setCertChain(certificate.toString());
      result.setPrivateKey(key.toString());
      result.setCaCert(ca.toString());
      return result;
    }
  }

  private record StartSessionProjectionBinding(
      StartSessionPostAuthorizationExecutionTuple tuple, UUID ownerAttemptId, long ownerFence) {}

  /** Ephemeral same-namespace mTLS identities for the native owner-service proof fixture. */
  private static final class TestPki {
    private final Path ca;
    private final Map<String, TestIdentity> servers = new ConcurrentHashMap<>();
    private final Map<String, TestIdentity> clients = new ConcurrentHashMap<>();

    TestPki(Path root) throws Exception {
      Files.createDirectories(root);
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var caKeys = generator.generateKeyPair();
      var caName = new org.bouncycastle.asn1.x500.X500Name("CN=Test-only selected export proof CA");
      var now = java.time.Instant.now();
      var caBuilder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caName,
              java.math.BigInteger.ONE,
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              caName,
              caKeys.getPublic());
      caBuilder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(true));
      caBuilder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          true,
          new org.bouncycastle.asn1.x509.KeyUsage(org.bouncycastle.asn1.x509.KeyUsage.keyCertSign));
      var caCertificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  caBuilder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      ca = pem(root.resolve("ca.pem"), caCertificate);
      for (String service :
          List.of(
              "account-service",
              "game-design-service",
              "world-management-service",
              "game-logic-service",
              "automation-scripting-service",
              "entity-management-service")) {
        servers.put(service, issue(root, service, true, caKeys, caCertificate));
        clients.put(service, issue(root, service, false, caKeys, caCertificate));
      }
    }

    Path ca() {
      return ca;
    }

    TestIdentity server(String service) {
      return Objects.requireNonNull(servers.get(service), "unknown test server identity");
    }

    CommonGrpcClientProperties client(String service) {
      return Objects.requireNonNull(clients.get(service), "unknown test client identity")
          .properties(ca);
    }

    private static TestIdentity issue(
        Path root,
        String service,
        boolean server,
        java.security.KeyPair caKeys,
        java.security.cert.X509Certificate caCertificate)
        throws Exception {
      var generator = java.security.KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var keys = generator.generateKeyPair();
      var now = java.time.Instant.now();
      var builder =
          new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
              caCertificate,
              new java.math.BigInteger(120, new java.security.SecureRandom()),
              java.util.Date.from(now.minusSeconds(60)),
              java.util.Date.from(now.plusSeconds(3600)),
              new org.bouncycastle.asn1.x500.X500Name("CN=" + service),
              keys.getPublic());
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.basicConstraints,
          true,
          new org.bouncycastle.asn1.x509.BasicConstraints(false));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.keyUsage,
          false,
          new org.bouncycastle.asn1.x509.KeyUsage(
              org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                  | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
          false,
          new org.bouncycastle.asn1.x509.ExtendedKeyUsage(
              server
                  ? org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth
                  : org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth));
      builder.addExtension(
          org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
          false,
          new org.bouncycastle.asn1.x509.GeneralNames(
              new org.bouncycastle.asn1.x509.GeneralName[] {
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier,
                    "spiffe://firemud/ns/" + NAMESPACE + "/sa/" + service),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.dNSName, "localhost"),
                new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.iPAddress, "127.0.0.1")
              }));
      var certificate =
          new org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
              .getCertificate(
                  builder.build(
                      new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
                          .build(caKeys.getPrivate())));
      String prefix = service + (server ? "-server" : "-client");
      return new TestIdentity(
          pem(root.resolve(prefix + ".crt"), certificate),
          pem(root.resolve(prefix + ".key"), keys.getPrivate()));
    }

    private static Path pem(Path path, Object value) throws Exception {
      try (var writer =
          new org.bouncycastle.openssl.jcajce.JcaPEMWriter(Files.newBufferedWriter(path))) {
        writer.writeObject(value);
      }
      return path;
    }
  }

  /**
   * Real S3-compatible object I/O; each outbound conditional PUT re-reads the committed candidate
   * through the real GD reader immediately before transmission.
   */
  private static final class MinioConditionalObjectStore implements AutoCloseable {
    private final VersionAssetExportCandidateService candidates;
    private final PublicationDigestRequestBinding request;
    private final AtomicInteger puts = new AtomicInteger();
    private final AtomicInteger gets = new AtomicInteger();
    private final URI endpoint;
    private final S3Client client;
    private byte[] inventoryBytes;
    private String operationDigest;
    private String selectedCommitDigest;
    private String inventoryDigest;

    MinioConditionalObjectStore(
        VersionAssetExportCandidateService candidates, PublicationDigestRequestBinding request) {
      this.candidates = candidates;
      this.request = request;
      endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
      client =
          S3Client.builder()
              .endpointOverride(endpoint)
              .credentialsProvider(
                  StaticCredentialsProvider.create(
                      AwsBasicCredentials.create(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)))
              .region(MINIO_REGION)
              .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
              .overrideConfiguration(
                  configuration ->
                      configuration.addExecutionInterceptor(
                          new CandidateReadbackExecutionInterceptor()))
              .build();
      try {
        client.createBucket(CreateBucketRequest.builder().bucket(MINIO_BUCKET).build());
      } catch (RuntimeException failure) {
        client.close();
        throw failure;
      }
    }

    void requireCandidateBeforeWrites(SelectedDraftAssetInventory inventory) {
      inventoryBytes = inventory.canonicalBytes();
      operationDigest = sha256(inventory.operation().canonicalBytes());
      selectedCommitDigest = inventory.selectedCommit().digest();
      inventoryDigest = inventory.digest();
      if (!objects().isEmpty()) {
        throw new IllegalStateException("Test object store is already populated");
      }
    }

    String endpoint() {
      return endpoint.toString();
    }

    String bucket() {
      return MINIO_BUCKET;
    }

    S3Client client() {
      return client;
    }

    List<S3Object> objects() {
      return client
          .listObjectsV2(ListObjectsV2Request.builder().bucket(MINIO_BUCKET).build())
          .contents();
    }

    StoredObject objectAt(String key) {
      var observed =
          client.getObjectAsBytes(GetObjectRequest.builder().bucket(MINIO_BUCKET).key(key).build());
      return new StoredObject(observed.asByteArray(), observed.response().contentType());
    }

    int putCalls() {
      return puts.get();
    }

    int getCalls() {
      return gets.get();
    }

    @Override
    public void close() {
      client.close();
    }

    private void requireExactCandidateBeforePut() {
      var retained = candidates.readSelectedCandidate(request);
      if (inventoryBytes == null
          || !request.requestDigest().equals(retained.requestDigest())
          || !Arrays.equals(request.canonicalPreimage(), retained.requestPreimage())
          || !operationDigest.equals(retained.operationDigest())
          || !selectedCommitDigest.equals(retained.selectedCommitDigest())
          || !SelectedDraftAssetInventory.SCHEMA.equals(retained.inventorySchema())
          || !inventoryDigest.equals(retained.inventoryDigest())
          || !Arrays.equals(inventoryBytes, retained.inventoryBytes())
          || !("sha256:" + sha256(retained.inventoryBytes())).equals(retained.inventoryDigest())
          || !("sha256:" + sha256(retained.manifestBytes()))
              .equals(retained.manifest().manifestHash())) {
        throw new AssertionError(
            "Exact durable candidate, inventory and manifest were not readable before object PUT");
      }
    }

    private final class CandidateReadbackExecutionInterceptor implements ExecutionInterceptor {
      @Override
      public void beforeTransmission(
          software.amazon.awssdk.core.interceptor.Context.BeforeTransmission context,
          ExecutionAttributes executionAttributes) {
        if (context.request() instanceof PutObjectRequest put) {
          if (!MINIO_BUCKET.equals(put.bucket()) || !"*".equals(put.ifNoneMatch())) {
            throw new AssertionError("Selected object PUT was not conditional to the test bucket");
          }
          puts.incrementAndGet();
          requireExactCandidateBeforePut();
        } else if (context.request() instanceof GetObjectRequest get
            && MINIO_BUCKET.equals(get.bucket())) {
          gets.incrementAndGet();
        }
      }
    }
  }

  private record StoredObject(byte[] bytes, String contentType) {
    StoredObject {
      bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
