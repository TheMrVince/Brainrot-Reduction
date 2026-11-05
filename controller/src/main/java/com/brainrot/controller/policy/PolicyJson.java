package com.brainrot.controller.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The wire format of a {@link Policy}, as parsed by the enforcer. */
public final class PolicyJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PolicyJson() {}

    /** The {@code set_policy} message, on a single line. */
    public static String toSetPolicyMessage(Policy policy) {
        ObjectNode message = toNode(policy);
        message.put("cmd", "set_policy");
        return message.toString();
    }

    public static ObjectNode toNode(Policy policy) {
        ObjectNode node = JSON.createObjectNode();
        node.put("locked", policy.locked());
        if (policy.unlockedUntil() == null) node.putNull("unlockedUntil");
        else node.put("unlockedUntil", policy.unlockedUntil().getEpochSecond());
        node.put("closeWarningMinutes", policy.closeWarningMinutes());
        node.put("dryRun", policy.dryRun());

        ArrayNode apps = node.putArray("apps");
        for (AppRule rule : policy.apps()) {
            ObjectNode r = apps.addObject();
            if (!rule.label().isEmpty()) r.put("label", rule.label());
            if (!rule.bundleId().isEmpty()) r.put("bundleId", rule.bundleId());
            if (!rule.processName().isEmpty()) r.put("processName", rule.processName());
            if (!rule.pathPrefix().isEmpty()) r.put("pathPrefix", rule.pathPrefix());
            if (!rule.cmdlineContains().isEmpty()) {
                ArrayNode fragments = r.putArray("cmdlineContains");
                rule.cmdlineContains().forEach(fragments::add);
            }
        }
        return node;
    }
}
