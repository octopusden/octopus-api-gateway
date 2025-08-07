package org.octopusden.cloud.apigateway.controller

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class GatewayController {
    @GetMapping
    fun index(
        model: Model,
        @AuthenticationPrincipal oauth2User: OAuth2User,
        @RequestParam("redirect_url", defaultValue = "/dms-ui/") redirectUrl: String,
    ): String {
        model.addAttribute("userName", oauth2User.name)
        model.addAttribute("redirectUrl", redirectUrl)
        return "index"
    }
}
