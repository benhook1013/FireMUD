package net.firedevops.firemud.gamedesign.service.impl;

record PublishWorkflowRequest(
    String tenantId, String notes, String publishRequestId, String publishWorkflowId) {}
