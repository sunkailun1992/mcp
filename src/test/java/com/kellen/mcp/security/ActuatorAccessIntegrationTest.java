package com.kellen.mcp.security;

import org.springframework.boot.actuate.autoconfigure.context.ShutdownEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.jackson.JacksonEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.env.EnvironmentEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.actuate.web.ServletManagementContextAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.actuate.endpoint.web.WebMvcHealthEndpointExtensionAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import com.kellen.security.config.ActuatorSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 消费者以真实 MVC/Actuator 端点验证公共安全链；测试不装配 Nacos、数据库或 MQ。 */
@SpringBootTest(classes = ActuatorAccessIntegrationTest.Config.class, properties = {
        "spring.config.name=actuator-consumer-isolated",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.config.import-check.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "management.endpoints.web.exposure.include=health,env",
        "security.actuator.token=synthetic-consumer-actuator-token"
})
class ActuatorAccessIntegrationTest {
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void configureHttp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void healthRemainsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void managementDiscoveryAndEnvironmentRejectAnonymousAndWrongToken() throws Exception {
        for (String path : new String[]{"/actuator", "/actuator/env"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("actuatorReq", "synthetic-wrong-token"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void configuredTokenAllowsManagementRequest() throws Exception {
        mvc.perform(get("/actuator").header("actuatorReq", "synthetic-consumer-actuator-token"))
                .andExpect(status().isOk());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @Import(ActuatorSecurityConfig.class)
    @ImportAutoConfiguration({
        PropertyPlaceholderAutoConfiguration.class,
        ConfigurationPropertiesAutoConfiguration.class,
        ApplicationAvailabilityAutoConfiguration.class,
        JacksonAutoConfiguration.class,
        HttpMessageConvertersAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class,
        EndpointAutoConfiguration.class,
        JacksonEndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class,
        ManagementContextAutoConfiguration.class,
        ServletManagementContextAutoConfiguration.class,
        EnvironmentEndpointAutoConfiguration.class,
        ShutdownEndpointAutoConfiguration.class,
        HealthContributorAutoConfiguration.class,
        HealthContributorRegistryAutoConfiguration.class,
        HealthEndpointAutoConfiguration.class,
        AvailabilityProbesAutoConfiguration.class,
        WebMvcHealthEndpointExtensionAutoConfiguration.class
})
    static class Config {
        /** 业务链放行时，Actuator 仍由更高顺序的公共过滤链保护。 */
        @Bean
        SecurityFilterChain applicationChain(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
        }
    }
}
