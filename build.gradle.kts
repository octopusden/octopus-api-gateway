plugins {
    kotlin("jvm")
    id("org.octopusden.octopus-release-management")
    id("org.springframework.boot")
    id("com.bmuschko.docker-spring-boot-application") version "7.1.0"
    `maven-publish`
    id("io.github.gradle-nexus.publish-plugin")
    signing
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
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(System.getenv("MAVEN_USERNAME"))
            password.set(System.getenv("MAVEN_PASSWORD"))
        }
    }
}

publishing {
    repositories {
        maven {

        }
    }
    publications {
        create<MavenPublication>("bootJar") {
            from(components["java"])
            artifact(tasks.getByName("bootJar"))
            pom {
                name.set(project.name)
                description.set("Octopus module: ${project.name}")
                url.set("https://github.com/octopusden/octopus-api-gateway.git")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/kzaporozhtsev/octopus-employee-service.git")
                    connection.set("scm:git://github.com/octopusden/octopus-employee-service.git")
                }
                developers {
                    developer {
                        id.set("octopus")
                        name.set("octopus")
                    }
                }
            }
        }
    }
}


signing {
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign(publishing.publications["bootJar"])
}


springBoot {
    buildInfo()
}

docker {
    springBootApplication {
        baseImage.set("${rootProject.properties["docker.registry"]}/openjdk:11")
        ports.set(listOf(8765, 8765))
        images.set(setOf("${rootProject.properties["publishing.docker.registry"]}/${project.name}:${project.version}"))
    }
}

dependencies {
    implementation(platform("org.springframework.cloud:spring-cloud-dependencies:${project.property("spring-cloud.version")}"))
    implementation(enforcedPlatform("org.springframework.boot:spring-boot-dependencies:${project.properties["spring-boot.version"]}"))

    implementation(kotlin("stdlib"))

    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.cloud:spring-cloud-starter-bootstrap")

//    implementation("org.springframework.cloud:spring-cloud-starter-netflix-hystrix")
    implementation("org.springframework.boot:spring-boot-starter-webflux")

    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    implementation("io.micrometer:micrometer-registry-prometheus:1.7.2")

    implementation("org.springframework.cloud:spring-cloud-starter-config")

    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")

    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.thymeleaf.extras:thymeleaf-extras-springsecurity5")

    implementation("org.octopusden.octopus-cloud-commons:octopus-security-common:${project.properties["cloud-commons.version"]}") {
        exclude("org.springframework.security")
        exclude("org.springframework.boot")
        exclude("org.jetbrains.kotlin")
        exclude("org.springframework.cloud")
    }
}
