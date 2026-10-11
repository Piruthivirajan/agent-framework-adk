package com.demo.agentfw.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The demo login: one shared password from the environment ({@code AGENTFW_DEMO_PASSWORD}).
 *
 * @param password     empty means the application is open (local development); set it for any
 *                     reachable deployment
 * @param maxFailures  wrong passwords allowed per client address within {@code lockSeconds}
 * @param lockSeconds  length of the window, and of the lock after too many failures
 * @param sessionHours how long a signed-in browser session stays valid
 */
@ConfigurationProperties("agentfw.auth")
public record AuthProperties(
        @DefaultValue("") String password,
        @DefaultValue("5") int maxFailures,
        @DefaultValue("300") int lockSeconds,
        @DefaultValue("8") int sessionHours) {

    public boolean enabled() {
        return password != null && !password.isEmpty();
    }

    @Override
    public String toString() {   // never print the password
        return "AuthProperties[enabled=" + enabled() + ", maxFailures=" + maxFailures + ", lockSeconds="
                + lockSeconds + ", sessionHours=" + sessionHours + "]";
    }
}
