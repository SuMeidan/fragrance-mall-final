package io.scent.account;

import io.scent.common.ApiResult;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }

    @Bean
    FilterRegistrationBean<TokenFilter> tokenRegistration(TokenFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Run only inside Spring Security, after context initialization.
        return registration;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, TokenFilter filter, ObjectMapper json,
            @Value("${springdoc.api-docs.enabled:false}") boolean docs) throws Exception {
        http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).logout(l -> l.disable())
            .authorizeHttpRequests(a -> {
                a.requestMatchers(HttpMethod.GET, "/api/v1/catalog/**", "/api/v1/capabilities", "/media/**", "/actuator/health").permitAll();
                a.requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN");
                a.requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/admin-login", "/api/v1/auth/register").permitAll();
                a.requestMatchers(HttpMethod.POST, "/api/v1/payments/alipay/notify").permitAll();
                a.requestMatchers(HttpMethod.POST, "/api/v1/advisor/chat", "/api/v1/advisor/chat/stream").permitAll();
                a.requestMatchers(HttpMethod.DELETE, "/api/v1/advisor/conversations/*").permitAll();
                a.requestMatchers(io.scent.advisor.ProductMcpController.PATH).permitAll(); // Dedicated Bearer check in the MCP controller.
                if (docs) { a.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll(); }
                a.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
                a.requestMatchers("/api/v1/addresses/**", "/api/v1/addresses").hasRole("BUYER");
                a.requestMatchers("/api/v1/cart/**", "/api/v1/cart", "/api/v1/orders/**", "/api/v1/orders").hasRole("BUYER");
                a.requestMatchers("/api/v1/after-sales/**").hasRole("BUYER");
                a.requestMatchers("/api/v1/auth/me", "/api/v1/auth/logout").authenticated();
                a.anyRequest().denyAll();
            })
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                json.writeValue(res.getOutputStream(), ApiResult.error(401, "AUTH_REQUIRED", "请先登录"));
            }).accessDeniedHandler((req, res, ex) -> {
                res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                json.writeValue(res.getOutputStream(), ApiResult.error(403, "FORBIDDEN", "没有操作权限"));
            }))
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource cors(@Value("${scent.security.cors-origins}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::strip).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Trace-Id"));
        config.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
