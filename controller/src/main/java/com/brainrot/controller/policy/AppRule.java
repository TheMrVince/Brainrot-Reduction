package com.brainrot.controller.policy;

import java.util.List;

/**
 * One blocked app. A process matches if any set criterion matches; the enforcer decides how.
 * See shared/protocol.md.
 */
public record AppRule(String label, String bundleId, String processName, String pathPrefix,
                      List<String> cmdlineContains) {
    public AppRule {
        label = label == null ? "" : label;
        bundleId = bundleId == null ? "" : bundleId;
        processName = processName == null ? "" : processName;
        pathPrefix = pathPrefix == null ? "" : pathPrefix;
        cmdlineContains = cmdlineContains == null ? List.of() : List.copyOf(cmdlineContains);
    }

    public boolean hasCriteria() {
        return !bundleId.isEmpty() || !processName.isEmpty() || !pathPrefix.isEmpty() || !cmdlineContains.isEmpty();
    }
}
