package net.firedevops.firemud.gamedesign.client;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractReloadingBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.gamedesign.dto.AppliedWorldDesignMutationDto;
import net.firedevops.firemud.gamedesign.dto.PublishParticipantDigestDto;
import net.firedevops.firemud.gamedesign.dto.WorldDesignMutationRevisionDto;
import net.firedevops.firemud.worldmanagement.v1.ApplyWorldDesignMutationRequest;
import net.firedevops.firemud.worldmanagement.v1.GenerationRuleDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.worldmanagement.v1.RegionDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.RoomExitDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldEntitySpawnBindingDesignMutation;
import net.firedevops.firemud.worldmanagement.v1.WorldManagementServiceGrpc;
import net.firedevops.firemud.worldmanagement.v1.ZoneDesignMutation;
import org.springframework.stereotype.Component;

@Component
public class WorldManagementClient
    extends AbstractReloadingBlockingGrpcClient<
        WorldManagementServiceGrpc.WorldManagementServiceBlockingStub> {
  public WorldManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer, WorldManagementClient.class);
  }

  @PostConstruct
  void init() throws SSLException, IOException {
    initReloadingClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getWorldManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "world-management-service:6565";
  }

  @Override
  protected WorldManagementServiceGrpc.WorldManagementServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        WorldManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public PublishParticipantDigestDto getDraftDesignDigestForVersion(
      String tenantId, long versionId) {
    var response =
        stub()
            .getDraftDesignDigest(
                GetDraftDesignDigestRequest.newBuilder()
                    .setTenantId(tenantId)
                    .setVersionId(String.valueOf(versionId))
                    .build());
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      return new PublishParticipantDigestDto(
          "WORLD_MANAGEMENT",
          String.valueOf(versionId),
          null,
          null,
          null,
          response.getError().getCode(),
          response.getError().getMessage());
    }
    return new PublishParticipantDigestDto(
        "WORLD_MANAGEMENT",
        response.getScopeValue(),
        response.getAppliedCommitId(),
        response.getContentDigest(),
        response.getDigestSchemaVersion(),
        null,
        null);
  }

  public AppliedWorldDesignMutationDto applyWorldDesignMutation(
      String tenantId, long versionId, WorldDesignMutationRevisionDto mutation) {
    var response =
        stub().applyWorldDesignMutation(payloadBuilder(tenantId, versionId, mutation).build());
    if (response.hasError() && !response.getError().getCode().isBlank()) {
      throw new IllegalArgumentException(
          response.getError().getCode() + ": " + response.getError().getMessage());
    }
    return new AppliedWorldDesignMutationDto(
        response.getResult().name(),
        response.getAggregateId(),
        response.getDraftRevisionEpoch(),
        response.getDraftScopeRevisionEpoch() == 0L ? null : response.getDraftScopeRevisionEpoch());
  }

  private ApplyWorldDesignMutationRequest.Builder payloadBuilder(
      String tenantId, long versionId, WorldDesignMutationRevisionDto mutation) {
    ApplyWorldDesignMutationRequest.Builder builder =
        ApplyWorldDesignMutationRequest.newBuilder()
            .setTenantId(tenantId)
            .setVersionId(String.valueOf(versionId))
            .setCommitId(mutation.commitId())
            .setRevisionId(mutation.logicalRevisionId())
            .setOperationValue(enumValue(mutation.operation()))
            .setAggregateTypeValue(enumValue(mutation.aggregateType()))
            .setAggregateId(mutation.aggregateId())
            .setExpectedDraftRevisionEpoch(defaultLong(mutation.expectedDraftRevisionEpoch()))
            .setScopeType(defaultString(mutation.scopeType()))
            .setScopeId(defaultString(mutation.scopeId()))
            .setExpectedDraftScopeRevisionEpoch(
                defaultLong(mutation.expectedDraftScopeRevisionEpoch()));
    if (mutation.region() != null) {
      return builder.setRegion(
          RegionDesignMutation.newBuilder()
              .setName(defaultString(mutation.region().name()))
              .setWeather(defaultString(mutation.region().weather()))
              .setShardId(defaultInt(mutation.region().shardId()))
              .setGenerationSeed(defaultLong(mutation.region().generationSeed()))
              .setGeneratorType(defaultString(mutation.region().generatorType()))
              .setGeneratorParams(defaultString(mutation.region().generatorParams()))
              .setSpacingMultiplier(defaultDouble(mutation.region().spacingMultiplier()))
              .build());
    }
    if (mutation.zone() != null) {
      return builder.setZone(
          ZoneDesignMutation.newBuilder()
              .setName(defaultString(mutation.zone().name()))
              .setRegionId(defaultString(mutation.zone().regionId()))
              .build());
    }
    if (mutation.room() != null) {
      return builder.setRoom(
          RoomDesignMutation.newBuilder()
              .setName(defaultString(mutation.room().name()))
              .setDescription(defaultString(mutation.room().description()))
              .setZoneId(defaultString(mutation.room().zoneId()))
              .setNameLocalizedVariantsJson(
                  defaultString(mutation.room().nameLocalizedVariantsJson()))
              .setDescriptionLocalizedVariantsJson(
                  defaultString(mutation.room().descriptionLocalizedVariantsJson()))
              .build());
    }
    if (mutation.roomExit() != null) {
      return builder.setRoomExit(
          RoomExitDesignMutation.newBuilder()
              .setFromRoomId(defaultString(mutation.roomExit().fromRoomId()))
              .setToRoomId(defaultString(mutation.roomExit().toRoomId()))
              .setDirection(defaultString(mutation.roomExit().direction()))
              .setCost(defaultInt(mutation.roomExit().cost()))
              .build());
    }
    if (mutation.generationRule() != null) {
      return builder.setGenerationRule(
          GenerationRuleDesignMutation.newBuilder()
              .setName(defaultString(mutation.generationRule().name()))
              .setValue(defaultString(mutation.generationRule().value()))
              .build());
    }
    if (mutation.worldEntitySpawnBinding() != null) {
      return builder.setWorldEntitySpawnBinding(
          WorldEntitySpawnBindingDesignMutation.newBuilder()
              .setRoomId(defaultString(mutation.worldEntitySpawnBinding().roomId()))
              .setEntityTemplateTypeValue(
                  enumValue(mutation.worldEntitySpawnBinding().entityTemplateType()))
              .setEntityTemplateId(
                  defaultString(mutation.worldEntitySpawnBinding().entityTemplateId()))
              .setSpawnCount(defaultInt(mutation.worldEntitySpawnBinding().spawnCount()))
              .setRespawnDelaySeconds(
                  defaultInt(mutation.worldEntitySpawnBinding().respawnDelaySeconds()))
              .build());
    }
    return builder;
  }

  private int enumValue(String enumName) {
    if (enumName == null || enumName.isBlank()) {
      return 0;
    }
    return switch (enumName) {
      case "WORLD_DESIGN_MUTATION_OPERATION_UPSERT" -> 1;
      case "WORLD_DESIGN_MUTATION_OPERATION_DELETE" -> 2;
      case "WORLD_DESIGN_AGGREGATE_TYPE_REGION" -> 1;
      case "WORLD_DESIGN_AGGREGATE_TYPE_ZONE" -> 2;
      case "WORLD_DESIGN_AGGREGATE_TYPE_ROOM" -> 3;
      case "WORLD_DESIGN_AGGREGATE_TYPE_ROOM_EXIT" -> 4;
      case "WORLD_DESIGN_AGGREGATE_TYPE_GENERATION_RULE" -> 5;
      case "WORLD_DESIGN_AGGREGATE_TYPE_WORLD_ENTITY_SPAWN_BINDING" -> 6;
      case "ENTITY_TEMPLATE_REFERENCE_TYPE_ITEM" -> 1;
      case "ENTITY_TEMPLATE_REFERENCE_TYPE_NPC" -> 2;
      default -> 0;
    };
  }

  private String defaultString(String value) {
    return value == null ? "" : value;
  }

  private long defaultLong(Long value) {
    return value == null ? 0L : value;
  }

  private int defaultInt(Integer value) {
    return value == null ? 0 : value;
  }

  private double defaultDouble(Double value) {
    return value == null ? 0.0d : value;
  }
}
