package com.example.migration.security;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Roles: READER (view), OPERATOR (create, load, advance), REVIEWER (decide mappings), APPROVER (switch).
 * The acting name always comes from the login, never from the request body.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      .csrf(csrf -> csrf.disable()) // stateless API with Basic auth, no cookies to forge
      .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .httpBasic(Customizer.withDefaults())
      .authorizeHttpRequests(a -> a
        .requestMatchers(GET, "/actuator/health", "/actuator/health/**").permitAll()
        .requestMatchers(POST, "/api/runs").hasRole("OPERATOR")
        .requestMatchers(POST, "/api/runs/*/load").hasRole("OPERATOR")
        .requestMatchers(POST, "/api/runs/*/mappings/suggest").hasRole("OPERATOR")
        .requestMatchers(PUT, "/api/runs/*/mappings/*").hasRole("REVIEWER")
        // SWITCHED is further restricted to APPROVER inside RunController.
        .requestMatchers(POST, "/api/runs/*/transitions").hasAnyRole("OPERATOR", "APPROVER")
        .requestMatchers(GET, "/api/**", "/actuator/**").authenticated()
        .anyRequest().denyAll());
    return http.build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /** Format: name:password:ROLE|ROLE;name2:password2:ROLE. There are deliberately no default users. */
  @Bean
  UserDetailsService users(@Value("${migration.users:}") String spec, PasswordEncoder encoder) {
    if (spec == null || spec.isBlank()) {
      throw new IllegalStateException("MIGRATION_USERS is not set; refusing to start without any users");
    }
    List<UserDetails> users = new ArrayList<>();
    for (String entry : spec.split(";")) {
      String[] parts = entry.trim().split(":");
      if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
        throw new IllegalStateException("Each MIGRATION_USERS entry must look like name:password:ROLE|ROLE");
      }
      users.add(User.withUsername(parts[0].trim()).password(encoder.encode(parts[1]))
        .roles(parts[2].trim().split("\\|")).build());
    }
    return new InMemoryUserDetailsManager(users);
  }
}
