package com.demo.agentfw.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sign in and out. The password comparison is constant time (on SHA-256 digests, so the length does not
 * leak either), a wrong password costs the client a counted failure, and after too many failures from
 * one address the login is refused for a while: a shared demo password must not be guessable by script.
 * The password is never logged or echoed.
 */
@RestController
@RequestMapping("/api")
class AuthController {

    record LoginRequest(String password) {
    }

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final int MAX_TRACKED_CLIENTS = 1000;

    private final AuthProperties props;
    private final Map<String, Deque<Long>> failures = new LinkedHashMap<>();

    AuthController(AuthProperties props) {
        this.props = props;
    }

    @PostMapping(path = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        if (!props.enabled()) {
            return ResponseEntity.ok(Map.of("authenticated", true, "authRequired", false));
        }
        String client = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        long waitSeconds = lockedFor(client, now);
        if (waitSeconds > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("code", "TOO_MANY_ATTEMPTS",
                    "message", "Too many wrong passwords. Try again in " + (waitSeconds / 60 + 1) + " minute(s)."));
        }
        if (!matches(body == null ? null : body.password())) {
            recordFailure(client, now);
            log.warn("failed login from {}", client);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code", "INVALID_PASSWORD",
                    "message", "That password is not correct."));
        }
        clear(client);
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();                              // a fresh session id on sign-in (no fixation)
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(AuthFilter.SESSION_FLAG, Boolean.TRUE);
        session.setMaxInactiveInterval(props.sessionHours() * 3600);
        return ResponseEntity.ok(Map.of("authenticated", true, "authRequired", true));
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Map.of("authenticated", false, "authRequired", props.enabled());
    }

    /** Lets the pages know whether to show a sign-in; open to everyone. */
    @GetMapping("/me")
    Map<String, Object> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean in = !props.enabled()
                || (session != null && Boolean.TRUE.equals(session.getAttribute(AuthFilter.SESSION_FLAG)));
        return Map.of("authenticated", in, "authRequired", props.enabled());
    }

    private boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] a = sha.digest(candidate.getBytes(StandardCharsets.UTF_8));
            byte[] b = sha.digest(props.password().getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(a, b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private synchronized long lockedFor(String client, long now) {
        Deque<Long> times = failures.get(client);
        if (times == null) {
            return 0;
        }
        long window = props.lockSeconds() * 1000L;
        while (!times.isEmpty() && now - times.peekFirst() > window) {
            times.pollFirst();
        }
        if (times.size() >= props.maxFailures()) {
            return Math.max(1, (window - (now - times.peekFirst())) / 1000);
        }
        return 0;
    }

    private synchronized void recordFailure(String client, long now) {
        if (failures.size() >= MAX_TRACKED_CLIENTS && !failures.containsKey(client)) {
            failures.remove(failures.keySet().iterator().next());
        }
        failures.computeIfAbsent(client, c -> new ArrayDeque<>()).addLast(now);
    }

    private synchronized void clear(String client) {
        failures.remove(client);
    }
}
