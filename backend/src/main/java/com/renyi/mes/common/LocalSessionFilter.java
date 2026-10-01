package com.renyi.mes.common;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class LocalSessionFilter extends OncePerRequestFilter {
    private final LocalSessionCredentials credentials;
    LocalSessionFilter(LocalSessionCredentials credentials) { this.credentials = credentials; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var session = request.getSession(false);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LocalSessionCredentials.SessionActor) {
            SecurityContextHolder.clearContext();
        }
        if (SecurityContextHolder.getContext().getAuthentication() == null && session != null
                && session.getAttribute(LocalSessionCredentials.SESSION_ACTOR) instanceof LocalSessionCredentials.SessionActor actor) {
            if (credentials.valid(actor)) {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(actor, null, List.of(new SimpleGrantedAuthority("MES_SESSION"))));
                if (credentials.mustChange(actor.employeeCode()) && !request.getRequestURI().startsWith("/api/auth/")) {
                    response.setStatus(403);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"请先修改密码\"}}");
                    return;
                }
            } else { session.invalidate(); }
        }
        chain.doFilter(request, response);
    }
}
