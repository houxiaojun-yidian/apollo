package com.ctrip.framework.apollo.openapi.v1.controller;


import com.ctrip.framework.apollo.common.dto.ClusterDTO;
import com.ctrip.framework.apollo.common.dto.ReleaseDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.http.MultiResponseEntity;
import com.ctrip.framework.apollo.common.http.RichResponseEntity;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.ctrip.framework.apollo.common.utils.RequestPrecondition;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.utils.StringUtils;
import com.ctrip.framework.apollo.openapi.dto.NamespaceGrayDelReleaseDTO;
import com.ctrip.framework.apollo.openapi.dto.NamespaceReleaseDTO;
import com.ctrip.framework.apollo.openapi.dto.OpenReleaseDTO;
import com.ctrip.framework.apollo.openapi.util.OpenApiBeanUtils;
import com.ctrip.framework.apollo.portal.component.PortalSettings;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceReleaseModel;
import com.ctrip.framework.apollo.portal.service.ClusterService;
import com.ctrip.framework.apollo.portal.entity.model.NamespaceGrayDelReleaseModel;
import com.ctrip.framework.apollo.portal.service.NamespaceBranchService;
import com.ctrip.framework.apollo.portal.service.ReleaseService;
import com.ctrip.framework.apollo.portal.spi.UserService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

import java.util.List;

import static com.ctrip.framework.apollo.common.utils.RequestPrecondition.checkModel;

@RestController("openapiReleaseController")
@RequestMapping("/openapi/v1/envs/{env}")
public class ReleaseController {

  private final ReleaseService releaseService;
  private final UserService userService;
  private final NamespaceBranchService namespaceBranchService;
  private final PortalSettings portalSettings;
  private final ClusterService clusterService;

  public ReleaseController(
      final ReleaseService releaseService,
      final UserService userService,
      final NamespaceBranchService namespaceBranchService,
      final PortalSettings portalSettings,
      final ClusterService clusterService) {
    this.releaseService = releaseService;
    this.userService = userService;
    this.namespaceBranchService = namespaceBranchService;
    this.portalSettings = portalSettings;
    this.clusterService = clusterService;
  }

  @PreAuthorize(value = "@consumerPermissionValidator.hasReleaseNamespacePermission(#request, #appId, #namespaceName, #env)")
  @PostMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases")
  public OpenReleaseDTO createRelease(@PathVariable String appId, @PathVariable String env,
                                      @PathVariable String clusterName,
                                      @PathVariable String namespaceName,
                                      @RequestBody NamespaceReleaseDTO model,
                                      HttpServletRequest request) {
    RequestPrecondition.checkArguments(!StringUtils.isContainEmpty(model.getReleasedBy(), model
            .getReleaseTitle()),
        "Params(releaseTitle and releasedBy) can not be empty");

    if (userService.findByUserId(model.getReleasedBy()) == null) {
      throw new BadRequestException("user(releaseBy) not exists");
    }

    NamespaceReleaseModel releaseModel = BeanUtils.transform(NamespaceReleaseModel.class, model);

    releaseModel.setAppId(appId);
    releaseModel.setEnv(Env.fromString(env).toString());
    releaseModel.setClusterName(clusterName);
    releaseModel.setNamespaceName(namespaceName);

    return OpenApiBeanUtils.transformFromReleaseDTO(releaseService.publish(releaseModel));
  }

  /**
   * 全环境发布
   * 因为我们会有这种场景：对于一个namespace的改动，要同步到当前appid下的全部env、全部cluster下的各个同名namespace。然后再把这些namespace逐个都发布掉。
   * 由于批量同步功能是已有的，但是全环境发布功能并没有，所以这里支持了一下
   */
  @PreAuthorize(value = "@permissionValidator.hasReleaseNamespacePermission(#appId, #namespaceName)")
  @PostMapping(value = "/apps/{appId}/namespaces/{namespaceName}/multiReleases")
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

  @GetMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases/latest")
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

    @PreAuthorize(value = "@consumerPermissionValidator.hasReleaseNamespacePermission(#request, #appId, #namespaceName, #env)")
    @PostMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/merge")
    public OpenReleaseDTO merge(@PathVariable String appId, @PathVariable String env,
                            @PathVariable String clusterName, @PathVariable String namespaceName,
                            @PathVariable String branchName, @RequestParam(value = "deleteBranch", defaultValue = "true") boolean deleteBranch,
                            @RequestBody NamespaceReleaseDTO model, HttpServletRequest request) {
        RequestPrecondition.checkArguments(!StringUtils.isContainEmpty(model.getReleasedBy(), model
                        .getReleaseTitle()),
                "Params(releaseTitle and releasedBy) can not be empty");

        if (userService.findByUserId(model.getReleasedBy()) == null) {
            throw new BadRequestException("user(releaseBy) not exists");
        }

        ReleaseDTO mergedRelease = namespaceBranchService.merge(appId, Env.valueOf(env.toUpperCase()), clusterName, namespaceName, branchName,
                model.getReleaseTitle(), model.getReleaseComment(),
                model.isEmergencyPublish(), deleteBranch, model.getReleasedBy());

        return OpenApiBeanUtils.transformFromReleaseDTO(mergedRelease);
    }

    @PreAuthorize(value = "@consumerPermissionValidator.hasReleaseNamespacePermission(#request, #appId, #namespaceName, #env)")
    @PostMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/releases")
    public OpenReleaseDTO createGrayRelease(@PathVariable String appId,
                                        @PathVariable String env, @PathVariable String clusterName,
                                        @PathVariable String namespaceName, @PathVariable String branchName,
                                        @RequestBody NamespaceReleaseDTO model,
                                        HttpServletRequest request) {
        RequestPrecondition.checkArguments(!StringUtils.isContainEmpty(model.getReleasedBy(), model
                        .getReleaseTitle()),
                "Params(releaseTitle and releasedBy) can not be empty");

        if (userService.findByUserId(model.getReleasedBy()) == null) {
            throw new BadRequestException("user(releaseBy) not exists");
        }

        NamespaceReleaseModel releaseModel = BeanUtils.transform(NamespaceReleaseModel.class, model);

        releaseModel.setAppId(appId);
        releaseModel.setEnv(Env.fromString(env).toString());
        releaseModel.setClusterName(branchName);
        releaseModel.setNamespaceName(namespaceName);

        return OpenApiBeanUtils.transformFromReleaseDTO(releaseService.publish(releaseModel));
    }

    @PreAuthorize(value = "@consumerPermissionValidator.hasReleaseNamespacePermission(#request, #appId, #namespaceName, #env)")
    @PostMapping(value = "/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/branches/{branchName}/gray-del-releases")
    public OpenReleaseDTO createGrayDelRelease(@PathVariable String appId,
                                               @PathVariable String env, @PathVariable String clusterName,
                                               @PathVariable String namespaceName, @PathVariable String branchName,
                                               @RequestBody NamespaceGrayDelReleaseDTO model,
                                               HttpServletRequest request) {
        RequestPrecondition.checkArguments(!StringUtils.isContainEmpty(model.getReleasedBy(), model
                        .getReleaseTitle()),
                "Params(releaseTitle and releasedBy) can not be empty");
        RequestPrecondition.checkArguments(model.getGrayDelKeys() != null,
                "Params(grayDelKeys) can not be null");

        if (userService.findByUserId(model.getReleasedBy()) == null) {
            throw new BadRequestException("user(releaseBy) not exists");
        }

        NamespaceGrayDelReleaseModel releaseModel = BeanUtils.transform(NamespaceGrayDelReleaseModel.class, model);
        releaseModel.setAppId(appId);
        releaseModel.setEnv(env.toUpperCase());
        releaseModel.setClusterName(branchName);
        releaseModel.setNamespaceName(namespaceName);

        return OpenApiBeanUtils.transformFromReleaseDTO(releaseService.publish(releaseModel, releaseModel.getReleasedBy()));
    }

}
