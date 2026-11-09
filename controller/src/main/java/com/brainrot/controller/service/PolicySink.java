package com.brainrot.controller.service;

import com.brainrot.controller.ipc.EnforcerClient;
import com.brainrot.controller.policy.Policy;

import java.io.IOException;

/** Where policies go. A failure means the enforcer did not take the policy, for any reason. */
@FunctionalInterface
public interface PolicySink {
    void deliver(Policy policy) throws IOException;

    static PolicySink toEnforcer(EnforcerClient client) {
        return policy -> {
            switch (client.sendPolicy(policy)) {
                case EnforcerClient.Reply.Accepted accepted -> { }
                case EnforcerClient.Reply.Rejected rejected ->
                        throw new IOException("the enforcer rejected the policy: " + rejected.message());
            }
        };
    }
}
