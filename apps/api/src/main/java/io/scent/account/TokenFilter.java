package io.scent.account;

import io.scent.common.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public class TokenFilter extends OncePerRequestFilter {
    private final AuthMapper mapper;
    private final ObjectMapper json;
    public TokenFilter(AuthMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // This exact machine endpoint checks its own dedicated credential, never a buyer/admin session.
        return io.scent.advisor.ProductMcpController.PATH.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            if (!header.matches("Bearer [A-Za-z0-9_-]{43}")) { reject(response); return; }
            Actor actor = mapper.authenticate(AuthService.hash(header.substring(7)));
            if (actor == null) { reject(response); return; }
            var auth = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + actor.role())));
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), ApiResult.error(401, "AUTH_INVALID", "登录已失效，请重新登录"));
    }
}
