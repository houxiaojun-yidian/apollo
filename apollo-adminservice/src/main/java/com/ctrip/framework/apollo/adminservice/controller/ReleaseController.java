package com.ctrip.framework.apollo.adminservice.controller;


import com.ctrip.framework.apollo.biz.entity.Cluster;
import com.ctrip.framework.apollo.biz.service.ClusterService;
import com.ctrip.framework.apollo.common.dto.ClusterDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.common.http.MultiResponseEntity;
import com.ctrip.framework.apollo.common.http.RichResponseEntity;
import com.ctrip.framework.apollo.common.utils.RequestPrecondition;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.utils.StringUtils;
import com.google.common.base.Splitter;

import com.ctrip.framework.apollo.biz.entity.Namespace;
import com.ctrip.framework.apollo.biz.entity.Release;
import com.ctrip.framework.apollo.biz.message.MessageSender;
import com.ctrip.framework.apollo.biz.message.Topics;
import com.ctrip.framework.apollo.biz.service.NamespaceBranchService;
import com.ctrip.framework.apollo.biz.service.NamespaceService;
import com.ctrip.framework.apollo.biz.service.ReleaseService;
import com.ctrip.framework.apollo.biz.utils.ReleaseMessageKeyGenerator;
import com.ctrip.framework.apollo.common.constants.NamespaceBranchStatus;
import com.ctrip.framework.apollo.common.dto.ItemChangeSets;
import com.ctrip.framework.apollo.common.dto.ReleaseDTO;
import com.ctrip.framework.apollo.common.exception.NotFoundException;
import com.ctrip.framework.apollo.common.utils.BeanUtils;
import com.google.common.base.Splitter;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ctrip.framework.apollo.common.utils.RequestPrecondition.checkModel;

@RestController
public class ReleaseController {

  private static final Splitter RELEASES_SPLITTER = Splitter.on(",").omitEmptyStrings()
      .trimResults();


  private final ReleaseService releaseService;
  private final NamespaceService namespaceService;
  private final MessageSender messageSender;
  private final NamespaceBranchService namespaceBranchService;
  private final ClusterService clusterService;

  public ReleaseController(
      final ReleaseService releaseService,
      final NamespaceService namespaceService,
      final MessageSender messageSender,
      final NamespaceBranchService namespaceBranchService,
      final ClusterService clusterService) {
    this.releaseService = releaseService;
    this.namespaceService = namespaceService;
    this.messageSender = messageSender;
    this.namespaceBranchService = namespaceBranchService;
    this.clusterService = clusterService;
  }


  @GetMapping("/releases/{releaseId}")
  public ReleaseDTO get(@PathVariable("releaseId") long releaseId) {
    Release release = releaseService.findOne(releaseId);
    if (release == null) {
      throw new NotFoundException(String.format("release not found for %s", releaseId));
    }
    return BeanUtils.transform(ReleaseDTO.class, release);
  }

  @GetMapping("/releases")
  public List<ReleaseDTO> findReleaseByIds(@RequestParam("releaseIds") String releaseIds) {
    Set<Long> releaseIdSet = RELEASES_SPLITTER.splitToList(releaseIds).stream().map(Long::parseLong)
        .collect(Collectors.toSet());

    List<Release> releases = releaseService.findByReleaseIds(releaseIdSet);

    return BeanUtils.batchTransform(ReleaseDTO.class, releases);
  }

  @GetMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases/all")
  public List<ReleaseDTO> findAllReleases(@PathVariable("appId") String appId,
                                          @PathVariable("clusterName") String clusterName,
                                          @PathVariable("namespaceName") String namespaceName,
                                          Pageable page) {
    List<Release> releases = releaseService.findAllReleases(appId, clusterName, namespaceName, page);
    return BeanUtils.batchTransform(ReleaseDTO.class, releases);
  }


  @GetMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases/active")
  public List<ReleaseDTO> findActiveReleases(@PathVariable("appId") String appId,
                                             @PathVariable("clusterName") String clusterName,
                                             @PathVariable("namespaceName") String namespaceName,
                                             Pageable page) {
    List<Release> releases = releaseService.findActiveReleases(appId, clusterName, namespaceName, page);
    return BeanUtils.batchTransform(ReleaseDTO.class, releases);
  }

  @GetMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases/latest")
  public ReleaseDTO getLatest(@PathVariable("appId") String appId,
                              @PathVariable("clusterName") String clusterName,
                              @PathVariable("namespaceName") String namespaceName) {
    Release release = releaseService.findLatestActiveRelease(appId, clusterName, namespaceName);
    return BeanUtils.transform(ReleaseDTO.class, release);
  }

  @Transactional
  @PostMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/releases")
  public ReleaseDTO publish(@PathVariable("appId") String appId,
                            @PathVariable("clusterName") String clusterName,
                            @PathVariable("namespaceName") String namespaceName,
                            @RequestParam("name") String releaseName,
                            @RequestParam(name = "comment", required = false) String releaseComment,
                            @RequestParam("operator") String operator,
                            @RequestParam(name = "isEmergencyPublish", defaultValue = "false") boolean isEmergencyPublish) {
    Namespace namespace = namespaceService.findOne(appId, clusterName, namespaceName);
    if (namespace == null) {
      throw new NotFoundException(String.format("Could not find namespace for %s %s %s", appId,
                                                clusterName, namespaceName));
    }
    Release release = releaseService.publish(namespace, releaseName, releaseComment, operator, isEmergencyPublish);

    //send release message
    Namespace parentNamespace = namespaceService.findParentNamespace(namespace);
    String messageCluster;
    if (parentNamespace != null) {
      messageCluster = parentNamespace.getClusterName();
    } else {
      messageCluster = clusterName;
    }
    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, messageCluster, namespaceName),
                              Topics.APOLLO_RELEASE_TOPIC);
    return BeanUtils.transform(ReleaseDTO.class, release);
  }

  /**
   * 全环境发布
   * 因为我们会有这种场景：对于一个namespace的改动，要同步到当前appid下的全部env、全部cluster下的各个同名namespace。然后再把这些namespace逐个都发布掉。
   * 由于批量同步功能是已有的，但是全环境发布功能并没有，所以这里支持了一下
   */
  @Transactional
  @PostMapping("/apps/{appId}/namespaces/{namespaceName}/multiReleases")
  public MultiResponseEntity<ReleaseDTO> multiPublish(@PathVariable("appId") String appId,
                                                      @PathVariable("namespaceName") String namespaceName,
                                                      @RequestParam("name") String releaseName,
                                                      @RequestParam(name = "comment", required = false) String releaseComment,
                                                      @RequestParam("operator") String operator,
                                                      @RequestParam(name = "isEmergencyPublish", defaultValue = "false") boolean isEmergencyPublish) {

    MultiResponseEntity<ReleaseDTO> response = MultiResponseEntity.ok();

    List<Cluster> clusterList = clusterService.findClusters(appId);
    if (CollectionUtils.isEmpty(clusterList)) {
      return null;
    }
    for (Cluster cluster : clusterList) {
      String clusterName = cluster.getName();
      ReleaseDTO openReleaseDTO = publish(appId, clusterName, namespaceName, releaseName, releaseComment, operator, isEmergencyPublish);

      response.addResponseEntity(RichResponseEntity.ok(openReleaseDTO));
    }
    return response;
  }


  /**
   * merge branch items to master and publish master
   *
   * @return published result
   */
  @Transactional
  @PostMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/updateAndPublish")
  public ReleaseDTO updateAndPublish(@PathVariable("appId") String appId,
                                     @PathVariable("clusterName") String clusterName,
                                     @PathVariable("namespaceName") String namespaceName,
                                     @RequestParam("releaseName") String releaseName,
                                     @RequestParam("branchName") String branchName,
                                     @RequestParam(value = "deleteBranch", defaultValue = "true") boolean deleteBranch,
                                     @RequestParam(name = "releaseComment", required = false) String releaseComment,
                                     @RequestParam(name = "isEmergencyPublish", defaultValue = "false") boolean isEmergencyPublish,
                                     @RequestBody ItemChangeSets changeSets) {
    Namespace namespace = namespaceService.findOne(appId, clusterName, namespaceName);
    if (namespace == null) {
      throw new NotFoundException(String.format("Could not find namespace for %s %s %s", appId,
                                                clusterName, namespaceName));
    }

    Release release = releaseService.mergeBranchChangeSetsAndRelease(namespace, branchName, releaseName,
                                                                     releaseComment, isEmergencyPublish, changeSets);

    if (deleteBranch) {
      namespaceBranchService.deleteBranch(appId, clusterName, namespaceName, branchName,
                                          NamespaceBranchStatus.MERGED, changeSets.getDataChangeLastModifiedBy());
    }

    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, clusterName, namespaceName),
                              Topics.APOLLO_RELEASE_TOPIC);

    return BeanUtils.transform(ReleaseDTO.class, release);

  }

  @Transactional
  @PutMapping("/releases/{releaseId}/rollback")
  public void rollback(@PathVariable("releaseId") long releaseId,
                       @RequestParam("operator") String operator) {

    Release release = releaseService.rollback(releaseId, operator);

    String appId = release.getAppId();
    String clusterName = release.getClusterName();
    String namespaceName = release.getNamespaceName();
    //send release message
    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, clusterName, namespaceName),
                              Topics.APOLLO_RELEASE_TOPIC);
  }

  @Transactional
  @PostMapping("/apps/{appId}/clusters/{clusterName}/namespaces/{namespaceName}/gray-del-releases")
  public ReleaseDTO publish(@PathVariable("appId") String appId,
                            @PathVariable("clusterName") String clusterName,
                            @PathVariable("namespaceName") String namespaceName,
                            @RequestParam("operator") String operator,
                            @RequestParam("releaseName") String releaseName,
                            @RequestParam(name = "comment", required = false) String releaseComment,
                            @RequestParam(name = "isEmergencyPublish", defaultValue = "false") boolean isEmergencyPublish,
                            @RequestParam(name = "grayDelKeys") Set<String> grayDelKeys){
    Namespace namespace = namespaceService.findOne(appId, clusterName, namespaceName);
    if (namespace == null) {
      throw new NotFoundException(String.format("Could not find namespace for %s %s %s", appId,
              clusterName, namespaceName));
    }

    Release release = releaseService.grayDeletionPublish(namespace, releaseName, releaseComment, operator, isEmergencyPublish, grayDelKeys);

    //send release message
    Namespace parentNamespace = namespaceService.findParentNamespace(namespace);
    String messageCluster;
    if (parentNamespace != null) {
      messageCluster = parentNamespace.getClusterName();
    } else {
      messageCluster = clusterName;
    }
    messageSender.sendMessage(ReleaseMessageKeyGenerator.generate(appId, messageCluster, namespaceName),
            Topics.APOLLO_RELEASE_TOPIC);
    return BeanUtils.transform(ReleaseDTO.class, release);
  }

}
