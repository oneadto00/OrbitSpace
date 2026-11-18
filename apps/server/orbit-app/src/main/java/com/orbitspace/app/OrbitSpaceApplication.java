package com.orbitspace.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@EnableWebSecurity
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class, ManagementWebSecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class
})
public class OrbitSpaceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrbitSpaceApplication.class, args);
    }
}
