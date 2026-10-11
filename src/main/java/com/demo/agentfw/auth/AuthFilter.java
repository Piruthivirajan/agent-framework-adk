package com.demo.agentfw.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Keeps the whole application behind the demo password. Without a signed-in session an API call gets
 * a 401 with a JSON body and a page request is redirected to the login page. Only the login page, the
 * login call and the health check are open (the health check is what a load balancer or Docker probes).
 *
 * <p>This is a gate for a demo, not an identity system: one shared password, one role.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    static final String SESSION_FLAG = "agentfw.authenticated";

    private static final Set<String> OPEN_PATHS = Set.of("/login.html", "/api/login", "/api/me", "/favicon.ico");

    private final AuthProperties props;

    public AuthFilter(AuthProperties props) {
        this.props = props;
        if (!props.enabled()) {
            org.slf4j.LoggerFactory.getLogger(AuthFilter.class).warn(
                    "agentfw.auth.password (AGENTFW_DEMO_PASSWORD) is not set: the application is open to anyone "
                            + "who can reach it. Set it before exposing the application.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return OPEN_PATHS.contains(path) || path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_FLAG))) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/")) {
            byte[] body = "{\"code\":\"LOGIN_REQUIRED\",\"message\":\"Please sign in to use the application.\"}"
                    .getBytes(StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getOutputStream().write(body);
        } else {
            response.sendRedirect("/login.html");
        }
    }
}
