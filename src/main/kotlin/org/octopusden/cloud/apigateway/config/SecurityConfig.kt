package org.octopusden.cloud.apigateway.config

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler

@Configuration
@EnableWebFluxSecurity
@Import(AuthServerClient::class)
open class SecurityConfig(
    private val clientRegistrationRepository: ReactiveClientRegistrationRepository,
) {
    @Bean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http
            .authorizeExchange { exchanges: AuthorizeExchangeSpec ->
                exchanges
                    .pathMatchers(
                        "/logout/connect/back-channel/**"
                    ).permitAll()
                    .pathMatchers(
                        "/"
                    ).authenticated()
                    .anyExchange().permitAll()
            }
            .oauth2Login(Customizer.withDefaults())
            .logout { logout ->
                logout.logoutSuccessHandler(oidcLogoutSuccessHandler())
            }
            .oidcLogout { oidcLogout ->
                oidcLogout.backChannel {}
            }
            .csrf { it.disable() }
        return http.build()
    }

    private fun oidcLogoutSuccessHandler(): ServerLogoutSuccessHandler {
        val handler = OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository)
        handler.setPostLogoutRedirectUri("{baseUrl}")
        return handler
    }
}
