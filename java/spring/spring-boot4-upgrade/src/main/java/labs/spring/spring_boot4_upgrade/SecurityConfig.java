package labs.spring.spring_boot4_upgrade;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** 第 3 站：和全景一样用 HTTP Basic 认出用户 1024。lambda 写法在 Security 6 和 7 都能编译。 */
@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        return http.csrf(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .httpBasic(b -> {})
                .build();
    }

    @Bean
    InMemoryUserDetailsManager users() {
        return new InMemoryUserDetailsManager(User.withUsername("1024").password("{noop}pw").roles("BUYER").build());
    }
}
