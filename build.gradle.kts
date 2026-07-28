plugins {
    kotlin("jvm")
    id("org.octopusden.octopus-release-management")
    id("org.springframework.boot")
    id("com.bmuschko.docker-spring-boot-application")
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    signing
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("org.octopusden.octopus-quality")
}

octopusQuality {
    // Repo has no coverage tool configured yet — disable coverage verification.
    coverage {
        enabled.set(false)
    }
    // Enforce Kotlin static analysis (detekt + ktlint); current debt is absorbed by
    // detekt-baseline.xml / ktlint-baseline.xml so the gate stays green while enforcing.
    kotlin {
        failOnViolation.set(true)
    }
}

group = "org.octopusden.cloud.api-gateway"

java {
    withJavadocJar()
    withSourcesJar()
}

tasks.withType<GenerateModuleMetadata> {
    // The value 'enforced-platform' is provided in the validation
    // error message
    suppressedValidationErrors.add("enforced-platform")
}

repositories {
    mavenCentral()
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("MAVEN_USERNAME"))
            password.set(System.getenv("MAVEN_PASSWORD"))
        }
    }
}

// Not published to Maven Central: the gateway is a deployable, its deliverable is the docker
// image on ghcr, and nothing consumes it as a Maven dependency. `publish-to-nexus: false` in
// .github/workflows/release.yml keeps the release pipeline away from Sonatype; declaring no
// publication here is the second half of that — without it a manual `./gradlew publishToSonatype`
// with valid OSSRH credentials could still upload the ~59 MB fat jar.

springBoot {
    buildInfo()
}

val dockerRegistry = System.getenv().getOrDefault("DOCKER_REGISTRY", project.properties["docker.registry"]) as? String
val octopusGithubDockerRegistry = System.getenv().getOrDefault(
    "OCTOPUS_GITHUB_DOCKER_REGISTRY",
    project.properties["octopus.github.docker.registry"],
) as? String

docker {
    springBootApplication {
        baseImage.set("$dockerRegistry/eclipse-temurin:21-jdk")
        ports.set(listOf(8765))
        images.set(setOf("$octopusGithubDockerRegistry/octopusden/${project.name}:${project.version}"))
    }
}

tasks.getByName("dockerBuildImage").doFirst {
    validateDockerRegistryParams()
}

dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${project.property("spring-cloud.version")}"))
    implementation(enforcedPlatform("org.springframework.boot:spring-boot-dependencies:${project.properties["spring-boot.version"]}"))

    implementation(kotlin("stdlib"))

    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.cloud:spring-cloud-starter-bootstrap")

    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("io.micrometer:micrometer-registry-prometheus")

    implementation("org.springframework.cloud:spring-cloud-starter-config")

    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity6")

    implementation("org.octopusden.octopus-cloud-commons:octopus-security-common:${project.properties["cloud-commons.version"]}") {
        exclude("org.springframework.security")
        exclude("org.springframework.boot")
        exclude("org.jetbrains.kotlin")
        exclude("org.springframework.cloud")
    }
}

fun validateDockerRegistryParams() {
    if (dockerRegistry.isNullOrBlank() || octopusGithubDockerRegistry.isNullOrBlank()) {
        throw IllegalArgumentException(
            "Start gradle build with" +
                (if (dockerRegistry.isNullOrBlank()) " -Pdocker.registry=..." else "") +
                (if (octopusGithubDockerRegistry.isNullOrBlank()) " -Poctopus.github.docker.registry=..." else "") +
                " or set env variable(s):" +
                (if (dockerRegistry.isNullOrBlank()) " DOCKER_REGISTRY" else "") +
                (if (octopusGithubDockerRegistry.isNullOrBlank()) " OCTOPUS_GITHUB_DOCKER_REGISTRY" else ""),
        )
    }
}
