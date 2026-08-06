package fr.openent.nextcloud.core.enums;

import fr.openent.nextcloud.core.constants.WorkflowRight;

public enum WorkflowActions {
    ACCESS(WorkflowRight.VIEW),
    ADMIN_DESKTOP(WorkflowRight.ADMIN_DESKTOP),
    ADMIN_SHARE_STRUCTURES(WorkflowRight.ADMIN_SHARE_STRUCTURES);

    private final String actionName;

    WorkflowActions(String actionName) {
        this.actionName = actionName;
    }

    public String getAction() {
        return this.actionName;
    }
}
