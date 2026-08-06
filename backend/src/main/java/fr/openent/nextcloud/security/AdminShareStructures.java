package fr.openent.nextcloud.security;

import fr.openent.nextcloud.core.enums.WorkflowActions;
import fr.openent.nextcloud.helper.WorkflowHelper;
import fr.wseduc.webutils.http.Binding;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import org.entcore.common.http.filter.ResourcesProvider;
import org.entcore.common.user.UserInfos;

public class AdminShareStructures implements ResourcesProvider {
    @Override
    public void authorize(HttpServerRequest httpServerRequest, Binding binding, UserInfos userInfos, Handler<Boolean> handler) {
        handler.handle(WorkflowHelper.hasRight(userInfos, WorkflowActions.ADMIN_SHARE_STRUCTURES.getAction()));
    }
}
