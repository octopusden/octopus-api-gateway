package org.octopusden.cloud.apigateway.filter

import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.cloud.commons.security.client.dto.OfflineJwt
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Component
class BasicAuthFilter(private val authServerClient: AuthServerClient) : GlobalFilter {

    override fun filter(
        exchange: ServerWebExchange,
        chain: GatewayFilterChain
    ): Mono<Void> {
        exchange.request
            .headers["Authorization"]
            ?.let { authHeader ->
                log.debug("Request has Authorization header")
                if (authHeader.size == 1) {
                    authHeader.firstOrNull()
                        ?.let { header ->
                            if (header.startsWith("Basic")) {
                                log.debug("Authorization header has 'Basic' prefix, processing authentication")
                                val basicAuth = header.replace("Basic ", "")
                                translateBasicAuthToBearerAccessToken(basicAuth)
                                    ?.let { accessToken ->
                                        log.info("Basic Auth to JWT translation success")
                                        exchange.request
                                            .mutate()
                                            .header("Authorization", "Bearer $accessToken")
                                            .build()
                                    }
                                    ?: kotlin.run {
                                        log.debug("There is no JWT authentication, skipping...")
                                    }
                            }
                        }
                }
            }
        return chain.filter(exchange)
    }

    private fun translateBasicAuthToBearerAccessToken(basicAuth: String): String? {
        val authString = Base64.getDecoder()
            .decode(basicAuth)
            .decodeToString()

        val (username, password) = authString
            .split(":")

        val offlineJwt = authTokens[authString]
            ?.let { existedOfflineJwt ->
                val currentTime = Instant.now()
                    .plusSeconds(60)

                if (existedOfflineJwt.accessTokenExpDate > currentTime) {
                    log.debug("Access token for '$username' is actual, using it")
                    existedOfflineJwt
                } else {
                    log.debug("Access token for '$username' is expired")
                    if (existedOfflineJwt.refreshTokenExpDate > currentTime) {
                        log.debug("Refresh token for '$username' is actual, refreshing access token")
                        refreshToken(existedOfflineJwt)
                    } else {
                        log.debug("Refresh token for '$username' is expired")
                        null
                    }
                }
            }
            ?: kotlin.run {
                log.debug("Generating new offline token for '$username'")
                generateToken(username, password)
            }

        return offlineJwt?.let { offlineJwtValue ->
            authTokens[authString] = offlineJwtValue
            offlineJwtValue.accessToken
        }
    }

    private fun generateToken(username: String, password: String) =
        try {
            authServerClient.generateOfflineJwt(username, password)
        } catch (e: Exception) {
            log.error("Can't authenticate '$username': ${e.message}")
            log.debug("Stacktrace:", e)
            null
        }

    private fun refreshToken(existedOfflineJwt: OfflineJwt) =
        try {
            authServerClient.refreshOfflineJwt(existedOfflineJwt.refreshToken)
        } catch (e: Exception) {
            log.error("Can't refresh token: ${e.message}")
            log.debug("Stacktrace:", e)
            null
        }

    companion object {
        private val log = LoggerFactory.getLogger(BasicAuthFilter::class.java)
        private val authTokens = ConcurrentHashMap<String, OfflineJwt>()
    }
}
