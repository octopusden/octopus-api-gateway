rootProject.name = "api-gateway"

pluginManagement {
    plugins {
        kotlin("jvm") version (extra["kotlin.version"] as String)
        id("org.octopusden.octopus-release-management") version (extra["release-management.version"] as String)
        id("org.springframework.boot") version (extra["spring-boot.version"] as String)
        id("io.github.gradle-nexus.publish-plugin") version ("1.1.0") apply (false)
        id("com.bmuschko.docker-spring-boot-application") version (extra["bmuschko-docker-plugin.version"] as String)
        id("io.gitlab.arturbosch.detekt") version (extra["detekt.version"] as String)
        id("org.jlleitschuh.gradle.ktlint") version (extra["ktlint-gradle.version"] as String)
        id("org.octopusden.octopus-quality") version "2.4.1"
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
