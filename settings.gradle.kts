rootProject.name = "api-gateway"

pluginManagement {
    plugins {
        kotlin("jvm") version (extra["kotlin.version"] as String)
        id("org.octopusden.octopus-release-management") version (extra["release-management.version"] as String)
        id("org.springframework.boot") version (extra["spring-boot.version"] as String)
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
