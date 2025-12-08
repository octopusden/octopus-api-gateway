package org.octopusden.cloud.apigateway.config

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
@Import(AuthServerClient::class)
open class SecurityConfig(
    @Value("\${auth-server.logout-url}")
    private val logoutUrl: String
) {
    @Bean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http.authorizeExchange { exchanges: AuthorizeExchangeSpec ->
            exchanges.pathMatchers("/").authenticated()
            exchanges.anyExchange().permitAll()
        }.oauth2Login(
            Customizer.withDefaults()
        ).oidcLogout {
            it.backChannel(Customizer.withDefaults())
        }.logout { logout ->
            logout.logoutSuccessHandler { exchange, _ ->
                exchange.exchange.response.apply {
                    statusCode = HttpStatus.FOUND
                    headers.add(HttpHeaders.LOCATION, logoutUrl)
                    cookies.remove("JSESSIONID")
                }
                exchange.exchange.session.flatMap { it.invalidate() }
            }
        }.csrf { it.disable() }
        return http.build()
    }
}
