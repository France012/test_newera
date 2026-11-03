package it.enterprise.claims.bff.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security baseline of the BFF.
 *
 * <p><strong>Phase 1 placeholder.</strong> Every request is permitted and the application is
 * stateless; real authentication/authorisation (OIDC resource server, JWT scopes per claim
 * operation) is deliberately deferred to ADR-0005. The chain exists now - instead of leaving Spring
 * Security with its defaults - so that:
 *
 * <ul>
 *   <li>no random generated password is printed at startup and no basic-auth prompt is enforced;
 *   <li>CORS preflight is handled by the single {@code CorsConfigurationSource} bean;
 *   <li>enabling OIDC later is a change of one method body, not a new framework wiring.
 * </ul>
 *
 * <p>TODO(TK-DE445B, ADR-0005): replace {@code permitAll()} with {@code oauth2ResourceServer(jwt ->
 * ...)} and scope-based authorisation rules.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /**
   * @param http security builder provided by Spring Security
   * @return the stateless, CORS-aware filter chain of the BFF
   * @throws Exception if the filter chain cannot be built
   */
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
    return http.build();
  }
}
