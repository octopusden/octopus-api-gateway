package org.octopusden.cloud.apigateway.config

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
@Import(AuthServerClient::class)
open class SecurityConfig {
    @Bean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http.authorizeExchange { exchanges: AuthorizeExchangeSpec ->
            exchanges.pathMatchers("/").authenticated()
            exchanges.anyExchange().permitAll()
        }
            .oauth2Login(Customizer.withDefaults())
            .oidcLogout { it.backChannel(Customizer.withDefaults()) }
            .csrf { it.disable() }
        return http.build()
    }
}
