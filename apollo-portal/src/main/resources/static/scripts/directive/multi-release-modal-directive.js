directive_module.directive('multireleasemodal', multiReleaseModalDirective);

function multiReleaseModalDirective(toastr, AppUtil, EventManager, ReleaseService, NamespaceBranchService) {
    return {
        restrict: 'E',
        templateUrl: '../../views/component/multi-release-modal.html',
        transclude: true,
        replace: true,
        scope: {
            appId: '=',
            env: '=',
            cluster: '='
        },
        link: function (scope) {

            scope.switchReleaseChangeViewType = switchReleaseChangeViewType;
            scope.multiPublish = multiPublish;

            scope.releaseBtnDisabled = false;
            scope.releaseChangeViewType = 'change';
            scope.releaseComment = '';
            scope.isEmergencyPublish = false;
            
            EventManager.subscribe(EventManager.EventType.PUBLISH_NAMESPACE,
                                   function (context) {

                                       var namespace = context.namespace;
                                       scope.toReleaseNamespace = context.namespace;
                                       scope.isEmergencyPublish = !!context.isEmergencyPublish;

                                       var date = new Date().Format("yyyyMMddhhmmss");
                                       namespace.releaseTitle = date + "-release";

                                       AppUtil.showModal('#multiReleaseModal');
                                   });

            function multiPublish() {
                scope.releaseBtnDisabled = true;
                ReleaseService.multiPublish(scope.appId,
                    scope.toReleaseNamespace.baseInfo.namespaceName,
                    scope.toReleaseNamespace.releaseTitle,
                    scope.releaseComment,
                    scope.isEmergencyPublish).then(
                    function (result) {
                        AppUtil.hideModal('#multiReleaseModal');
                        toastr.success("发布成功");

                        scope.releaseBtnDisabled = false;

                        EventManager.emit(EventManager.EventType.REFRESH_NAMESPACE,
                            {
                                namespace: scope.toReleaseNamespace
                            })

                    }, function (result) {
                        scope.releaseBtnDisabled = false;
                        toastr.error(AppUtil.errorMsg(result), "发布失败");

                    }
                );

            }

            function switchReleaseChangeViewType(type) {
                scope.releaseChangeViewType = type;
            }
        }
    }
}


