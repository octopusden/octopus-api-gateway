package org.octopusden.cloud.apigateway

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
open class ApiGateway

fun main(args: Array<String>) {
    SpringApplication.run(ApiGateway::class.java, *args)
}
