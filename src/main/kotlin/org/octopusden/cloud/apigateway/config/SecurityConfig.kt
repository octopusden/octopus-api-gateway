package org.octopusden.cloud.apigateway.config

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.net.URLEncoder


@Configuration
@EnableWebFluxSecurity
@Import(AuthServerClient::class)
open class SecurityConfig(@Value("\${auth-server.logout-url}") private val logoutUrl: String) {
    @Bean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http.authorizeExchange { exchanges: AuthorizeExchangeSpec ->
            exchanges.pathMatchers("/dms-ui/actuator/**").permitAll()
            exchanges.pathMatchers("/logout**", "/dms-ui/**").authenticated()
            exchanges.anyExchange().permitAll()
        }
            .oauth2Login(Customizer.withDefaults())
            .logout()
            .logoutSuccessHandler { exchange, _ ->
                exchange.exchange.response.statusCode = HttpStatus.FOUND
                exchange.exchange.response.headers.add(HttpHeaders.LOCATION, logoutUrl)
                Mono.empty()
            }
            .and()
            .addFilterBefore(LogoutFilter(), SecurityWebFiltersOrder.LOGOUT_PAGE_GENERATING)
            .csrf().disable()

        return http.build()
    }

    private class LogoutFilter : WebFilter {
        private val matcher = ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/logout")

        override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> = matcher.matches(exchange)
            .filter { matchResult -> matchResult.isMatch }
            .switchIfEmpty(chain.filter(exchange).then(Mono.empty()))
            .flatMap { _ ->
                val response = exchange.response
                response.statusCode = HttpStatus.FOUND
                val redirectUrl = exchange.request
                    .queryParams[REDIRECT_URL_PARAM_NAME]
                    ?.joinToString(",")
                    ?.let { "?$REDIRECT_URL_PARAM_NAME=${URLEncoder.encode(it, Charsets.UTF_8)}" } ?: ""
                response.headers.add(HttpHeaders.LOCATION, "/$LOGOUT_CUSTOM_ENDPOINT$redirectUrl")
                Mono.empty()
            }
    }

    companion object {
        const val REDIRECT_URL_PARAM_NAME = "redirect_url"
        const val LOGOUT_CUSTOM_ENDPOINT = "logout-form"
    }
}
