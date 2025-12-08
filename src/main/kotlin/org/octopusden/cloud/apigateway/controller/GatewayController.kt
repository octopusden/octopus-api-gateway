package org.octopusden.cloud.apigateway.controller

import org.springframework.cloud.gateway.config.GatewayProperties
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class GatewayController(gatewayProperties: GatewayProperties) {
    private val routeIds = gatewayProperties.routes.filter { route ->
        route.predicates.any { predicate ->
            predicate.name == "Path" && predicate.args.values.any { it == "/${route.id}/**" }
        }
    }.map { it.id }

    @GetMapping
    fun index(model: Model, @AuthenticationPrincipal oauth2User: OAuth2User): String {
        model.addAttribute("userName", oauth2User.name)
        model.addAttribute("routeIds", routeIds)
        return "index"
    }
}
