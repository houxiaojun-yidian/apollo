package com.ctrip.framework.apollo.openapi.v1.controller;


import com.ctrip.framework.apollo.common.dto.ClusterDTO;
import com.ctrip.framework.apollo.common.dto.ReleaseDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.http.MultiResponseEntity;
import com.ctrip.framework.apollo.common.http.RichResponseEntity;
import com.ctrip.framework.apollo.common.utils.RequestPrecondition;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.utils.StringUtils;
import com.ctrip.framework.apollo.openapi.dto.OpenReleaseDTO;
import com.ctrip.framework.apollo.openapi.util.OpenApiBeanUtils;
import com.ctrip.framework.apollo.portal.component.PortalSettings;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceReleaseModel;
import com.ctrip.framework.apollo.portal.listener.ConfigPublishEvent;
import com.ctrip.framework.apollo.portal.service.ClusterService;
import com.ctrip.framework.apollo.portal.service.ReleaseService;
import com.ctrip.framework.apollo.portal.spi.UserService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Objects;

import static com.ctrip.framework.apollo.common.utils.RequestPrecondition.checkModel;

@RestController("openapiReleaseController")
@RequestMapping("/openapi/v1/envs/{env}")
public class ReleaseController {

  @Autowired
  private ReleaseService releaseService;
  @Autowired
  private UserService userService;
  @Autowired
  private PortalSettings portalSettings;
  @Autowired
  private ClusterService clusterService;

  @PreAuthorize(value = "@consumerPermissionValidator.hasReleaseNamespacePermission(#request, #appId, #namespaceName, #env)")
  @RequestMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases", method = RequestMethod.POST)
  public OpenReleaseDTO createRelease(@PathVariable String appId, @PathVariable String env,
                                      @PathVariable String clusterName,
                                      @PathVariable String namespaceName,
                                      @RequestBody NamespaceReleaseModel model,
                                      HttpServletRequest request) {

    checkModel(model != null);
    RequestPrecondition.checkArguments(!StringUtils.isContainEmpty(model.getReleasedBy(), model
            .getReleaseTitle()),
        "Params(releaseTitle and releasedBy) can not be empty");

    if (userService.findByUserId(model.getReleasedBy()) == null) {
      throw new BadRequestException("user(releaseBy) not exists");
    }

    model.setAppId(appId);
    model.setEnv(Env.fromString(env).toString());
    model.setClusterName(clusterName);
    model.setNamespaceName(namespaceName);

    return OpenApiBeanUtils.transformFromReleaseDTO(releaseService.publish(model));
  }

  /**
   * 全环境发布
   * 因为我们会有这种场景：对于一个namespace的改动，要同步到当前appid下的全部env、全部cluster下的各个同名namespace。然后再把这些namespace逐个都发布掉。
   * 由于批量同步功能是已有的，但是全环境发布功能并没有，所以这里支持了一下
   */
  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName)")
  @RequestMapping(value = "/apps/{appId}/namespaces/{namespaceName}/multiReleases", method = RequestMethod.POST)
  public MultiResponseEntity<OpenReleaseDTO> createMultiRelease(@PathVariable String appId,
                                                            @PathVariable String namespaceName, @RequestBody NamespaceReleaseModel model) {
    checkModel(model != null);
    RequestPrecondition.checkArguments(!StringUtils.isContainEmpty(model.getReleasedBy(), model
                    .getReleaseTitle()),
            "Params(releaseTitle and releasedBy) can not be empty");

    if (userService.findByUserId(model.getReleasedBy()) == null) {
      throw new BadRequestException("user(releaseBy) not exists");
    }

    MultiResponseEntity<OpenReleaseDTO> response = MultiResponseEntity.ok();
    List<Env> envs = portalSettings.getActiveEnvs();
    if (CollectionUtils.isEmpty(envs)) {
      return null;
    }
    for (Env env : envs) {
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

        OpenReleaseDTO openReleaseDTO = OpenApiBeanUtils.transformFromReleaseDTO(releaseService.publish(model));

        response.addResponseEntity(RichResponseEntity.ok(openReleaseDTO));
      }
    }
    return response;
  }

  @RequestMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases/latest", method = RequestMethod.GET)
  public OpenReleaseDTO loadLatestActiveRelease(@PathVariable String appId, @PathVariable String env,
                                                @PathVariable String clusterName, @PathVariable
                                                    String namespaceName) {
    ReleaseDTO releaseDTO = releaseService.loadLatestRelease(appId, Env.fromString
        (env), clusterName, namespaceName);
    if (releaseDTO == null) {
      return null;
    }

    return OpenApiBeanUtils.transformFromReleaseDTO(releaseDTO);
  }

}
