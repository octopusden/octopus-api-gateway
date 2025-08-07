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
import reactor.core.publisher.Mono

@Configuration
@EnableWebFluxSecurity
@Import(AuthServerClient::class)
open class SecurityConfig(@Value("\${auth-server.logout-url}") private val logoutUrl: String) {
    @Bean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http.authorizeExchange { exchanges: AuthorizeExchangeSpec ->
            exchanges.pathMatchers("/dms-ui/actuator/**").permitAll()
            exchanges.pathMatchers("/","/dms-ui/**").authenticated()
            exchanges.anyExchange().permitAll()
        }
            .oauth2Login(Customizer.withDefaults())
            .logout()
            .logoutSuccessHandler { exchange, _ ->
                exchange.exchange.response.statusCode = HttpStatus.FOUND
                exchange.exchange.response.headers.add(HttpHeaders.LOCATION, logoutUrl)
                Mono.empty()
            }
            .and().csrf().disable()
        return http.build()
    }
}
