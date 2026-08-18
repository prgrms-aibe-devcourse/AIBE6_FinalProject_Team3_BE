package com.algogyeyak.admin.entity;

public enum AdminAuditAction {
    UPDATE_ROLE(AdminAuditTargetType.USER),
    UPDATE_STATUS(AdminAuditTargetType.USER),
    CREATE_CHECKLIST_TEMPLATE(AdminAuditTargetType.CHECKLIST_TEMPLATE),
    UPDATE_CHECKLIST_TEMPLATE(AdminAuditTargetType.CHECKLIST_TEMPLATE),
    DELETE_CHECKLIST_TEMPLATE(AdminAuditTargetType.CHECKLIST_TEMPLATE),
    ADD_CHECKLIST_TEMPLATE_IMAGE(AdminAuditTargetType.CHECKLIST_TEMPLATE),
    DELETE_CHECKLIST_TEMPLATE_IMAGE(AdminAuditTargetType.CHECKLIST_TEMPLATE),
    REVIEW_PROPERTY_REPORT(AdminAuditTargetType.PROPERTY_REPORT);

    private final AdminAuditTargetType targetType;

    AdminAuditAction(AdminAuditTargetType targetType) {
        this.targetType = targetType;
    }

    public AdminAuditTargetType targetType() {
        return targetType;
    }
}
