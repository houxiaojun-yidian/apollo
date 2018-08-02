package com.ctrip.framework.apollo.portal.controller;

import com.ctrip.framework.apollo.common.dto.ClusterDTO;
import com.ctrip.framework.apollo.common.dto.ReleaseDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.http.MultiResponseEntity;
import com.ctrip.framework.apollo.common.http.RichResponseEntity;
import com.ctrip.framework.apollo.common.utils.RequestPrecondition;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.portal.component.PortalSettings;
import com.ctrip.framework.apollo.portal.component.config.PortalConfig;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceReleaseModel;
import com.ctrip.framework.apollo.portal.entity.vo.EnvClusterInfo;
import com.ctrip.framework.apollo.portal.entity.vo.ReleaseCompareResult;
import com.ctrip.framework.apollo.portal.entity.bo.ReleaseBO;
import com.ctrip.framework.apollo.portal.listener.ConfigPublishEvent;
import com.ctrip.framework.apollo.portal.service.ClusterService;
import com.ctrip.framework.apollo.portal.service.ReleaseService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

import static com.ctrip.framework.apollo.common.utils.RequestPrecondition.checkModel;

@RestController
public class ReleaseController {

  @Autowired
  private ReleaseService releaseService;
  @Autowired
  private ApplicationEventPublisher publisher;
  @Autowired
  private PortalConfig portalConfig;
  @Autowired
  private PortalSettings portalSettings;
  @Autowired
  private ClusterService clusterService;

  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName, #env)")
  @RequestMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/releases", method = RequestMethod.POST)
  public ReleaseDTO createRelease(@PathVariable String appId,
                                  @PathVariable String env, @PathVariable String clusterName,
                                  @PathVariable String namespaceName, @RequestBody NamespaceReleaseModel model) {

    checkModel(Objects.nonNull(model));
    model.setAppId(appId);
    model.setEnv(env);
    model.setClusterName(clusterName);
    model.setNamespaceName(namespaceName);

    if (model.isEmergencyPublish() && !portalConfig.isEmergencyPublishAllowed(Env.valueOf(env))) {
      throw new BadRequestException(String.format("Env: %s is not supported emergency publish now", env));
    }

    ReleaseDTO createdRelease = releaseService.publish(model);

    ConfigPublishEvent event = ConfigPublishEvent.instance();
    event.withAppId(appId)
        .withCluster(clusterName)
        .withNamespace(namespaceName)
        .withReleaseId(createdRelease.getId())
        .setNormalPublishEvent(true)
        .setEnv(Env.valueOf(env));

    publisher.publishEvent(event);

    return createdRelease;
  }

  /**
   * 全环境发布
   * 因为我们会有这种场景：对于一个namespace的改动，要同步到当前appid下的全部env、全部cluster下的各个同名namespace。然后再把这些namespace逐个都发布掉。
   * 由于批量同步功能是已有的，但是全环境发布功能并没有，所以这里支持了一下
   */
  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName)")
  @RequestMapping(value = "/apps/{appId}/namespaces/{namespaceName}/multiReleases", method = RequestMethod.POST)
  public MultiResponseEntity<ReleaseDTO> createMultiRelease(@PathVariable String appId,
                                                          @PathVariable String namespaceName, @RequestBody NamespaceReleaseModel model) {
    checkModel(Objects.nonNull(model));

    MultiResponseEntity<ReleaseDTO> response = MultiResponseEntity.ok();
    List<Env> envs = portalSettings.getActiveEnvs();
    if (CollectionUtils.isEmpty(envs)) {
      return null;
    }
    for (Env env : envs) {
      if (model.isEmergencyPublish() && !portalConfig.isEmergencyPublishAllowed(env)) {
        continue;
      }
      List<ClusterDTO> clusterDTOList = clusterService.findClusters(env, appId);
      if (CollectionUtils.isEmpty(envs)) {
        continue;
      }
      for (ClusterDTO clusterDTO : clusterDTOList) {
        String clusterName = clusterDTO.getName();
        NamespaceReleaseModel newModel = new NamespaceReleaseModel();
        newModel.setAppId(appId);
        newModel.setEnv(env.name());
        newModel.setClusterName(clusterName);
        newModel.setNamespaceName(namespaceName);
        newModel.setEmergencyPublish(model.isEmergencyPublish());
        newModel.setReleaseComment(model.getReleaseComment());
        newModel.setReleasedBy(model.getReleasedBy());
        newModel.setReleaseTitle(model.getReleaseTitle());

        ReleaseDTO createdRelease = this.createRelease(appId, env.name(), clusterName, namespaceName, newModel);
        ConfigPublishEvent event = ConfigPublishEvent.instance();
        event.withAppId(appId)
                .withCluster(clusterName)
                .withNamespace(namespaceName)
                .withReleaseId(createdRelease.getId())
                .setNormalPublishEvent(true)
                .setEnv(env);
        publisher.publishEvent(event);

        response.addResponseEntity(RichResponseEntity.ok(createdRelease));
      }
    }
    return response;
  }

  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName, #env)")
  @RequestMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/releases",
      method = RequestMethod.POST)
  public ReleaseDTO createGrayRelease(@PathVariable String appId,
                                      @PathVariable String env, @PathVariable String clusterName,
                                      @PathVariable String namespaceName, @PathVariable String branchName,
                                      @RequestBody NamespaceReleaseModel model) {

    checkModel(Objects.nonNull(model));
    model.setAppId(appId);
    model.setEnv(env);
    model.setClusterName(branchName);
    model.setNamespaceName(namespaceName);

    if (model.isEmergencyPublish() && !portalConfig.isEmergencyPublishAllowed(Env.valueOf(env))) {
      throw new BadRequestException(String.format("Env: %s is not supported emergency publish now", env));
    }

    ReleaseDTO createdRelease = releaseService.publish(model);

    ConfigPublishEvent event = ConfigPublishEvent.instance();
    event.withAppId(appId)
        .withCluster(clusterName)
        .withNamespace(namespaceName)
        .withReleaseId(createdRelease.getId())
        .setGrayPublishEvent(true)
        .setEnv(Env.valueOf(env));

    publisher.publishEvent(event);

    return createdRelease;
  }


  @RequestMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/releases/all", method = RequestMethod.GET)
  public List<ReleaseBO> findAllReleases(@PathVariable String appId,
                                         @PathVariable String env,
                                         @PathVariable String clusterName,
                                         @PathVariable String namespaceName,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "5") int size) {

    RequestPrecondition.checkNumberPositive(size);
    RequestPrecondition.checkNumberNotNegative(page);

    return releaseService.findAllReleases(appId, Env.valueOf(env), clusterName, namespaceName, page, size);
  }

  @RequestMapping(value = "/apps/{appId}/envs/{env}/clusters/{clusterName}/namespaces/{namespaceName}/releases/active", method = RequestMethod.GET)
  public List<ReleaseDTO> findActiveReleases(@PathVariable String appId,
                                             @PathVariable String env,
                                             @PathVariable String clusterName,
                                             @PathVariable String namespaceName,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "5") int size) {

    RequestPrecondition.checkNumberPositive(size);
    RequestPrecondition.checkNumberNotNegative(page);

    return releaseService.findActiveReleases(appId, Env.valueOf(env), clusterName, namespaceName, page, size);
  }

  @RequestMapping(value = "/envs/{env}/releases/compare", method = RequestMethod.GET)
  public ReleaseCompareResult compareRelease(@PathVariable String env,
                                             @RequestParam long baseReleaseId,
                                             @RequestParam long toCompareReleaseId) {

    return releaseService.compare(Env.valueOf(env), baseReleaseId, toCompareReleaseId);
  }


  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName, #env)")
  @RequestMapping(path = "/envs/{env}/releases/{releaseId}/rollback", method = RequestMethod.PUT)
  public void rollback(@PathVariable String env,
                       @PathVariable long releaseId) {
    releaseService.rollback(Env.valueOf(env), releaseId);
    ReleaseDTO release = releaseService.findReleaseById(Env.valueOf(env), releaseId);
    if (Objects.isNull(release)) {
      return;
    }

    ConfigPublishEvent event = ConfigPublishEvent.instance();
    event.withAppId(release.getAppId())
        .withCluster(release.getClusterName())
        .withNamespace(release.getNamespaceName())
        .withPreviousReleaseId(releaseId)
        .setRollbackEvent(true)
        .setEnv(Env.valueOf(env));

    publisher.publishEvent(event);
  }
}
