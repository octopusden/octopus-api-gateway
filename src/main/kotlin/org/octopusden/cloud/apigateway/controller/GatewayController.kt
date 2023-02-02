package org.octopusden.cloud.apigateway.controller

import org.octopusden.cloud.apigateway.config.SecurityConfig
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam


@Controller
class GatewayController {
    @GetMapping(SecurityConfig.LOGOUT_CUSTOM_ENDPOINT)
    fun logout(
        model: Model,
        @RegisteredOAuth2AuthorizedClient authorizedClient: OAuth2AuthorizedClient,
        @AuthenticationPrincipal oauth2User: OAuth2User,
        @RequestParam(SecurityConfig.REDIRECT_URL_PARAM_NAME, defaultValue = "/") redirectUrl: String,
    ): String {
        model.addAttribute("userName", oauth2User.name)
        model.addAttribute("clientName", authorizedClient.clientRegistration.clientName)
        model.addAttribute("userAttributes", oauth2User.attributes)
        model.addAttribute("redirectUrl", redirectUrl)
        return "logout"
    }
}
