# Internal Structure of DMS-UI, Api-Gateway Services and Cloud-Commons Library

## Table of Contents

1. `DMS-UI` service
   1. Versions
   2. Description
   3. Frontend responsibilities
   4. Backend responsibilities
   5. User flow with code examples
2. `Api-Gateway` service
   1. Versions
   2. Description
   3. Responsibilities
   4. User flow with code examples
3. `Cloud-Commons` library
   1. Version
   2. Description
   3. How the logic is structured
   4. How the library is connected and used in other services
4. Q&A
   1. Why logging out in `dms-ui` also logs out `api-gateway`, and vice versa

![](communication_diagram.png)

## 1. DMS-UI service

Repository: `https://github.com/octopusden/octopus-dms-ui`

### 1.1 Versions

- JDK 21
- Kotlin 1.9.22
- Gradle 8.6
- Spring Boot 3.2.2
- Spring Cloud 2023.0.1

### 1.2 Description

`dms-ui` is a Spring Boot (WebFlux) application that acts as a BFF. It is responsible for OAuth2 authentication through SSO Keycloak, serving frontend static assets, and proxying requests to `dms-service`.

`dms-ui` has neither JPA nor JDBC. It does not work with a database at all, and communicates only with `dms-service` over HTTP.

### 1.3 Frontend responsibilities

- It does not manage tokens directly; it only renders the UI and sends requests.
- On startup, it calls `/auth/me`. It receives a user object with roles and computed permissions to understand what should and should not be displayed.

### 1.4 Backend responsibilities

- Starts OAuth2 authentication through Keycloak (SSO).
- Reverse-proxies requests to `dms-service` through `spring-cloud-gateway`. Routes `/auth/**` and `/rest/api/**` to `dms-service`. The `TokenRelay` filter forwards the access token from `OAuth2AuthorizedClient`.
- Serves frontend static assets (`WebConfig.class`).
- Exposes metrics via `/actuator/*`. Health checks are also available (`spring-boot-starter-actuator` + `micrometer-registry-prometheus`).
- Allows access to `/static/**`, `/bundle.js`, `/main.css`, `/favicon.ico`, `/logout`, and `/actuator/**`, and denies access to `/`, `/index.html`, `/auth/**`, and `/rest/api/**` unless the user is authenticated. CSRF is disabled (`SecurityConfig.class`).

`SecurityConfig.class`

```kotlin
.authorizeExchange { exchanges ->
    exchanges
        // allow unauthenticated access
        .pathMatchers(
            "/static/**",
            "/bundle.js",
            "/main.css",
            "/favicon.ico",
            "/logout",
            "/actuator/**",
        ).permitAll()
        // require authentication for these resources
        .pathMatchers(
            "/",
            "/index.html",
            "/auth/**",
            "/rest/api/**",
        ).authenticated()
        // require authentication for any other requests
        .anyExchange().authenticated()
}
// Tell Security that this application is an OAuth2 client with Keycloak auth
.oauth2Login(Customizer.withDefaults())
// Handle /logout
.logout { logout ->
    logout
        .logoutSuccessHandler { webFilterExchange, _ ->
            val response = webFilterExchange.exchange.response
            response.statusCode = HttpStatus.FOUND
            // redirect to logoutUrl and remove the SSO session
            response.headers.location = URI.create(logoutUrl)
            Mono.empty()
        }
}
// Disable Cross-Origin Resource Sharing (CORS)
.csrf { it.disable() }
```

### 1.5 User flow with code examples

The user opens `dms-ui` and immediately enters the WebFlux `SecurityWebFilterChain`, specifically `SecurityConfig.securityFilterChain()`. When trying to access a protected resource, the user is redirected to SSO Keycloak and must enter credentials.

Examples of Keycloak configuration taken from `service-config`, which are passed into `dms-ui`:

`application.yaml`

```yaml
auth-server:
 url: https://<sso URL>
  realm: f1
```

This config contains predefined auth server variables that point to SSO and the `f1` realm, an isolated area where users, clients, roles, and so on are configured. These values are later reused in other configs and in the `cloud-commons` library.

Then:

`dms-ui.yaml`

```yaml
spring:
  security:
    oauth2:
      client:
        provider:
          # description of the Keycloak authorization server
          keycloak:
            # endpoint for obtaining and refreshing tokens
            token-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/token
            # where we redirect when accessing a protected resource
            authorization-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/auth
            # endpoint for retrieving user info by access token
            userinfo-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/userinfo
            # which field is treated as the username
            user-name-attribute: preferred_username
            # used to verify JWT signature and validity
            jwk-set-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/certs
```

This config describes the Keycloak server itself.

`dms-ui.yaml`

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            # required scope for OIDC (id_token, userinfo)
            scope: openid
            # bind to the provider config above
            provider: keycloak
            # configure OAuth2 flow
            authorization-grant-type: authorization_code
            # where to return after authentication
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

This one describes the client that will work with Keycloak.

After logging in to Keycloak, a session is created and the corresponding cookies appear in the browser.

Then the user is redirected back to `dms-ui` with an authorization code (`authorization-grant-type`). That code is sent to Keycloak's token endpoint (`token-uri`) to obtain tokens. The access token and refresh token are stored in `OAuth2AuthorizedClientRepository`, while `Authentication` is stored in the `SecurityContext`.

The token itself has a fixed lifetime. After it expires, either the endpoint for issuing a new token is called again, or the user is redirected to log in again if the session is no longer valid.

All further proxied requests to `dms-service` are sent with a bearer token thanks to `TokenRelay`:

`dms-ui.yaml`

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - TokenRelay
```

Production gateway config:

`dms-ui-cloud-prod.yaml`

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: dms-service
          uri: http://dms-production.f1.svc.cluster.local:8080
          predicates:
            - Path=/auth/**,/rest/api/**
```

QA gateway config:

`dms-ui-cloud-qa.yaml`

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: dms-service
          uri: http://dms-test.f1.svc.cluster.local:8080
          predicates:
            - Path=/auth/**,/rest/api/**
```

In other words:

`dms-ui -> dms-service + token`

If the path points to static content that is available without authentication, security lets it through and the request reaches `WebConfig` / `ResourceHandler`. `WebConfig` serves what is needed from `classpath:/static`.

`WebConfig.class`

```kotlin
@Configuration
open class WebConfig : WebFluxConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler(
            "/static/**",
            "/bundle.js",
            "/main.css",
            "/favicon.ico",
            "/index.html",
        ).addResourceLocations("classpath:/static/")
    }
}
```

On logout, the Keycloak session is removed and the user is redirected to the prod/qa base URL used in `securityFilterChain`:

`dms-ui.yaml`

```yaml
auth-server:
  logout-url: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/logout?redirect_uri=${dms-ui.hostname}
```

Prod redirect:

`dms-ui-cloud-prod.yaml`

```yaml
dms-ui.hostname: <host_name>
```

QA redirect:

`dms-ui-cloud-qa.yaml`

```yaml
dms-ui.hostname: <host_name>
```

## 2. Api-Gateway service

Repository: `https://github.com/octopusden/octopus-api-gateway`

### 2.1 Versions

- JDK 21
- Kotlin 1.9.22
- Gradle 8.6
- Spring Boot 3.2.2
- Spring Cloud 2023.0.1
- Cloud Commons 2.0.14

### 2.2 Description

`api-gateway` is a gateway for other microservices such as `components-registry-service`, `dms-service`, `dms-getver`, `employee-service`, `release-management-service`, and `vcs-facade`, built on top of Spring Cloud Gateway.

### 2.3 Responsibilities

- Request routing
- Isolating access to other services
- Authentication and authorization management
- Security processing (OAuth2 with Keycloak)
- Converting Basic Auth into JWT tokens

The service provides a single entry point, load balancing, filters such as `TokenRelay`, and monitoring via Actuator. It does not store data; it only proxies traffic and adds a security layer.

Overall architecture: Spring Reactive (`WebFlux`) with filters for security and routing. It also includes the `octopus-cloud-commons` library described below.

The service contains a main `index.html` page. Default GET requests are redirected there, and from that page users can conveniently navigate to any proxied service from the list above.

`GatewayController.class`

```kotlin
@Controller
class GatewayController(gatewayProperties: GatewayProperties) {
    // Requests gateway routes defined in api-gateway-cloud-<test/prod>.yaml
    // to render navigation buttons
    private val routeIds = gatewayProperties.routes.filter { route ->
        route.predicates.any { predicate ->
            predicate.name == "Path" && predicate.args.values.any { it == "/${route.id}/**" }
        }
    }.map { it.id }.sorted()

    // Return a view with model data and buttons for different services from routeIds
    @GetMapping
    fun index(model: Model, @AuthenticationPrincipal oauth2User: OAuth2User): String {
        model.addAttribute("userName", oauth2User.name)
        model.addAttribute("routeIds", routeIds)
        return "index"
    }
}
```

### 2.4 User flow with code examples

If a user opens gateway site, they must authenticate in Keycloak, as indicated by the security configuration:

`SecurityConfig.class`

```kotlin
http.authorizeExchange { exchanges: AuthorizeExchangeSpec ->
    // authentication is required for "/"
    exchanges.pathMatchers("/").authenticated()
    // everything else is allowed
    exchanges.anyExchange().permitAll()
}
```

After authentication, a token is obtained and stored in the `Authentication` of the `Api-Gateway` `SecurityContext` (different from `dms-ui`), and the user is redirected to the requested page.

Even the configuration is similar to the one described for `dms-ui`:

`api-gateway.yaml`

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - TokenRelay
  security:
    oauth2:
      client:
        provider:
          keycloak:
            token-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/token
            authorization-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/auth
            userinfo-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/userinfo
            user-name-attribute: preferred_username
            jwk-set-uri: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/certs
        registration:
          keycloak:
            scope: openid
            provider: keycloak
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
```

Request routing is also similar to `dms-ui`, except that it additionally exposes services that `dms-ui` cannot reach due to the architecture. Prod and QA configs are merged below for clarity:

`api-gateway-cloud-prod/qa.yaml`

```yaml
spring:
  cloud:
    gateway:
      default-filters:
        - TokenRelay
      routes:
        - id: components-registry-service
          uri: http://components-registry-service-<production/test>.f1.svc.cluster.local:8080
          predicates:
            - Path=/components-registry-service/**
          # Remove "/components-registry-service/" from the request for proper service handling
          filters:
            - StripPrefix=1

        - id: dms-getver
          uri: http://dms-getver-<production/test>.f1.svc.cluster.local:8080
          predicates:
            - Path=/dms-getver/**
          filters:
            - StripPrefix=1

        - id: dms-service
          uri: http://dms-<production/test>.f1.svc.cluster.local:8080
          predicates:
            - Path=/dms-service/**
          filters:
            - StripPrefix=1

        # Kept for backward compatibility from the time when dms-service
        # contained dms-ui and was accessed through api-gateway
        - id: dms-ui-redirect
          uri: no://op
          predicates:
            - Path=/dms-ui/**
          filters:
            - RedirectTo=302, <host_name>

        - id: employee-service
          uri: http://employee-service-<production/test>.f1.svc.cluster.local:8080
          predicates:
            - Path=/employee-service/**
          filters:
            - StripPrefix=1

        - id: vcs-facade
          uri: http://vcs-facade-<production/test>.f1.svc.cluster.local:8080
          predicates:
            - Path=/vcs-facade/**
          filters:
            - StripPrefix=1

        - id: release-management-service
          uri: http://release-management-service-<production/test>.f1.svc.cluster.local:8080
          predicates:
            - Path=/release-management-service/**
          filters:
            - StripPrefix=1
```

On logout, the Keycloak session is removed, `Authentication` is removed from the `SecurityContext`, and the user is redirected to the prod or QA URL:

`SecurityConfig.class`

```kotlin
@Configuration
@EnableWebFluxSecurity
@Import(AuthServerClient::class) // Enable integration with cloud-commons
open class SecurityConfig(
    // Different profile properties define where logout redirects to
    @Value("\${auth-server.logout-url}")
    private val logoutUrl: String
) {
    @Bean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        /* ... */
        .logout { logout ->
            logout.logoutSuccessHandler { exchange, _ ->
                exchange.exchange.response.apply {
                    statusCode = HttpStatus.FOUND
                    // Add an action on successful logout
                    headers.add(HttpHeaders.LOCATION, logoutUrl)
                    // Clear cookie
                    cookies.remove("JSESSIONID")
                }
                exchange.exchange.session.flatMap { it.invalidate() }
            }
        }
        /* ... */
    }
}
```

`api-gateway.yaml`

```yaml
auth-server:
  # Difference: api-gateway.hostname is set without https and is defined
  # in application-level profiles, not api-gateway-level profiles
  logout-url: ${auth-server.url}/realms/${auth-server.realm}/protocol/openid-connect/logout?redirect_uri=https://${api-gateway.hostname}
```

Prod redirect:

`application-cloud-prod.yaml`

```yaml
api-gateway:
  hostname: <host_name>
```

QA redirect:

`application-cloud-qa.yaml`

```yaml
api-gateway:
  hostname: <host_name>
```

If the request to `api-gateway` uses a Basic Auth token (`user:passwd`), for example:

`curl example`

```bash
curl -u user:passwd https://<host_name>/dms-service/rest/api/3/components/monit-unit/minor-versions
```

then a filter from `BasicAuthFilter.class` is used. It converts that token into a long-lived offline bearer token, replaces the header, and then forwards it through `TokenRelay`.

`BasicAuthFilter.class`

```kotlin
@Component
class BasicAuthFilter(private val authServerClient: AuthServerClient) : GlobalFilter { }
```

It is injected into the shared Cloud Gateway filter chain. A simplified flow looks like this:

![](spring-cloud-gateway-diagram.png)

`BasicAuthFilter.class`

```kotlin
override fun filter(
    exchange: ServerWebExchange,
    chain: GatewayFilterChain
): Mono<Void> {
    // Hook into the Cloud Gateway request chain
    exchange.request
        // We need the Authorization header because we will replace it
        .headers["Authorization"]
        ?.let { authHeader ->
            log.debug("Request has Authorization header")
            if (authHeader.size == 1) {
                authHeader.firstOrNull()
                    ?.let { header ->
                        // Only process Basic auth. If it is Bearer, leave it untouched.
                        if (header.startsWith("Basic")) {
                            log.debug("Authorization header has 'Basic' prefix, processing authentication")
                            val basicAuth = header.replace("Basic ", "")
                            translateBasicAuthToBearerAccessToken(basicAuth)
                                ?.let { accessToken ->
                                    log.info("Basic Auth to JWT translation success")
                                    exchange.request
                                        .mutate()
                                        // Modify and replace the header
                                        .header("Authorization", "Bearer $accessToken")
                                        .build()
                                } ?: kotlin.run {
                                    log.debug("There is no JWT authentication, skipping...")
                                }
                        }
                    }
            }
        }

    // Pass control to the remaining filters to preserve gateway flow integrity
    return chain.filter(exchange)
    // There is no response processing
}
```

`translateBasicAuthToBearerAccessToken` contains the token conversion logic:

`BasicAuthFilter.class`

```kotlin
private fun translateBasicAuthToBearerAccessToken(basicAuth: String): String? {
    // Decode the token and get user:passwd
    val authString = Base64.getDecoder()
        .decode(basicAuth)
        .decodeToString()

    // Get Pair(username, password)
    val (username, password) = authString
        .split(":")

    // Generate offline JWT
    val offlineJwt = authTokens[authString]
        ?.let { existedOfflineJwt ->
            val currentTime = Instant.now()
                .plusSeconds(60)
            // If the token is not expired, use it
            if (existedOfflineJwt.accessTokenExpDate > currentTime) {
                log.debug("Access token for '$username' is actual, using it")
                existedOfflineJwt
            } else {
                log.debug("Access token for '$username' is expired")
                // If it is expired, use the refresh token
                if (existedOfflineJwt.refreshTokenExpDate > currentTime) {
                    log.debug("Refresh token for '$username' is actual, refreshing access token")
                    refreshToken(existedOfflineJwt)
                } else {
                    // Otherwise return nothing
                    log.debug("Refresh token for '$username' is expired")
                    null
                }
            }
        } ?: kotlin.run {
            log.debug("Generating new offline token for '$username'")
            generateToken(username, password)
        }

    // Save or overwrite tokens in the map
    return offlineJwt?.let { offlineJwtValue ->
        authTokens[authString] = offlineJwtValue
        offlineJwtValue.accessToken
    }
}

companion object {
    // Map associating user credentials with the token entity
    private val authTokens = ConcurrentHashMap<String, OfflineJwt>()
}
```

The token generation and refresh methods simply call methods from the `cloud-commons` library, passing user/password and refresh token respectively.

All of this makes the system more flexible. An offline token lives independently of the SSO session.

## 3. Cloud-Commons library

Repository: `https://github.com/octopusden/octopus-cloud-commons`

### 3.1 Version

- JDK 21
- Kotlin 1.9.22
- Gradle 8.9
- Spring Boot 3.2.2
- Spring Cloud 2023.0.1

### 3.2 Description

`cloud-commons` is a library that contains tools for integration with Keycloak, JWT token handling, extracting user info such as roles and groups, checking permissions, and providing a base security configuration for Spring Boot.

It is needed to avoid duplicating security logic in every individual service. It is added as a dependency. There is no business logic in it, only security utilities.

### 3.3 How the logic is structured

The bean we start with is used by other services that connect this library: `CloudCommonWebSecurityConfig`.

`CloudCommonWebSecurityConfig.class`

```kotlin
// Enable the Security Filter Chain: every HTTP request now passes through security filters
@EnableWebSecurity
// Enable different pre/post checks used in services importing the library
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
// SecurityProperties is described below
@EnableConfigurationProperties(SecurityProperties::class)
abstract class CloudCommonWebSecurityConfig(
    // AuthServerClient is described below
    private val authServerClient: AuthServerClient,
    protected val securityProperties: SecurityProperties
)
```

Besides additional helper functions such as printing roles and their permissions after bean creation, it contains the central `securityFilterChain`:

`CloudCommonWebSecurityConfig.class`

```kotlin
@Bean
open fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
    http
        .authorizeHttpRequests { auth ->
            auth
                // Allow these resources for services importing the library
                .requestMatchers(
                    "/",
                    "/actuator/**",
                    "/v2/api-docs",
                    "/v3/api-docs",
                    "/v3/api-docs/swagger-config",
                    "/swagger-resources/**",
                    "/swagger-ui/**"
                )
                .permitAll()
                // Deny everything else unless authenticated
                .anyRequest().authenticated()
        }
        // Tell Spring Security this is a resource server:
        // we do not log users in here, we validate tokens
        .oauth2ResourceServer { oauth2 ->
            oauth2.jwt { jwt ->
                // Override the Authentication converter
                jwt.jwtAuthenticationConverter(
                    JwtAuthenticationConverter().apply {
                        // Replace GrantedAuthorities mapping with our implementation
                        setJwtGrantedAuthoritiesConverter(
                            UserInfoGrantedAuthoritiesConverter(authServerClient)
                        )
                    }
                )
            }
        }
        // Disable Cross-Site Request Forgery protection
        .cors { it.disable() }
    return http.build()
}
```

This way we define the allowed and protected resources, override the authentication converter, and disable CORS.

This is exactly the class inherited by services where we want to add request authentication.

Before looking at the converter itself, it is worth saying a few words about users, roles, groups, and permissions.

The user entity must contain some informational base. In our case, that is the `username` field. However, that alone is not enough to implement security logic, so fields such as roles and groups are added.

Groups represent organizational membership, while roles represent a set of user permissions that quickly show what a user can and cannot access.

`User.class`

```kotlin
data class User(
    // username
    val username: String,
    // roles
    val roles: Collection<Role>,
    // groups
    val groups: Collection<String>
)
```

`Role.class`

```kotlin
data class Role(
    // role name
    val name: String,
    // permissions
    val permissions: Set<String>
)
```

`ROLE_` is a Spring Security convention. `GROUP_` is our own custom convention.

Now about the converter:

`UserInfoGrantedAuthoritiesConverter.java`

```kotlin
// The class implements Spring Core Converter and overrides convert()
override fun convert(jwt: Jwt): Collection<GrantedAuthority> {
    // Get user info from Keycloak
    val userInfo = authServerClient.getUserInfo(jwt.tokenValue)
    // Map roles and groups to SimpleGrantedAuthority
    val userRoles = userInfo.roles
        // Role gets ROLE_ prefix
        .map { role -> SimpleGrantedAuthority("${SecurityService.ROLE_PREFIX}$role") }
    val userGroups = userInfo.groups
        // Group gets GROUP_ prefix
        .map { group -> SimpleGrantedAuthority("${SecurityService.GROUP_PREFIX}$group") }
    return (userRoles + userGroups).toCollection(ArrayList())
}
```

As mentioned above, this is used by `JwtAuthenticationConverter`.

This method transforms a raw user entity into what will be stored in `Authentication`. If we need the reverse transformation, `SecurityService` helps with that. The example above already showed that it stores the constants `ROLE_PREFIX` and `GROUP_PREFIX`, so now we need to look at how the current user is extracted from `SecurityContext`.

`SecurityService.java`

```kotlin
fun getCurrentUser(): User {
    // Get current security context
    return SecurityContextHolder.getContext()
        ?.authentication
        ?.let { authentication ->
            // Parse username from JWT, see Keycloak config: user-name-attribute
            val username = (authentication.credentials as? Jwt)?.claims?.get("preferred_username")
                as? String ?: ""

            // Get authorities
            val authorities = authentication.authorities ?: emptySet()

            // Extract roles (starting with ROLE_) and map them to permissions
            val roles = authorities.filter { it.authority.startsWith(ROLE_PREFIX) }
                .map { it.authority }
                .mapNotNull { name -> securityProperties.roles[name]?.let { name to it } }
                .map { (name, permissions) -> Role(name, permissions) }
                .toSet()

            // Extract groups by removing the prefix
            val groups = authorities.filter { it.authority.startsWith(GROUP_PREFIX) }
                .map { it.authority.replace("^$GROUP_PREFIX".toRegex(), "") }
                .toSet()

            User(username, roles, groups)
        // If context is empty, treat the user as anonymous with empty roles and groups
        } ?: User("anonymous", emptySet(), emptySet())
}
```

In practice, this method is used in services that import the library to implement `/auth/me`, which was already mentioned in the chapter about `api-gateway`.

There is no need to analyze the imported `SecurityProperties` in depth. It is simply a map loaded from YAML configuration:

`application.yaml`

```yaml
octopus-security:
  roles:
    ROLE_F1_ADMIN:
      - ACCESS_CONFIGURATION
      - ACCESS_META
      - ACCESS_NOTES
      - ACCESS_DOCUMENTATION
      - ACCESS_DISTRIBUTION
      - PUBLISH_ARTIFACT
      - DELETE_DATA
      - ACCESS_EMPLOYEE
      - ACCESS_CUSTOMER
    ROLE_EMPLOYEE_SERVICE_USER:
      - ACCESS_EMPLOYEE
      - ACCESS_CUSTOMER
    ROLE_DMS_USER:
      - ACCESS_META
      - ACCESS_NOTES
      - ACCESS_DOCUMENTATION
      - ACCESS_DISTRIBUTION
    ROLE_DMS_PUBLISHER:
      - ACCESS_CONFIGURATION
      - ACCESS_META
      - PUBLISH_ARTIFACT
    ROLE_DMS_MAINTAINER:
      - DELETE_DATA
```

As you can see, there are several roles and permissions bound to them. The only difference is that in the QA config this map changes:

`application-cloud-qa.yaml`

```yaml
octopus-security:
  roles:
    ROLE_EMPLOYEE_SERVICE_USER_DEV:
      - ACCESS_EMPLOYEE
      - ACCESS_CUSTOMER
    ROLE_DMS_USER_DEV:
      - ACCESS_CONFIGURATION
      - ACCESS_META
      - ACCESS_NOTES
      - ACCESS_DOCUMENTATION
      - ACCESS_DISTRIBUTION
    ROLE_DMS_PUBLISHER_DEV:
      - ACCESS_CONFIGURATION
      - ACCESS_META
      - PUBLISH_ARTIFACT
```

In the converter example, we also encountered the `AuthServerClient` class. This bean:

- Sends requests to retrieve user information.
- Handles generation and refresh of offline JWT tokens.

Overall, it is extremely important and effectively represents the core logic of the library.

This is what `api-gateway` used in its `GlobalFilter` when it needed to generate or refresh an offline JWT.

It contains a client that sends requests to Keycloak:

`AuthServerClient`

```kotlin
private val restTemplate = RestTemplate(SimpleClientHttpRequestFactory())
```

When the service starts, `openIdConfiguration` is constructed. This is an entity that stores URL paths used to communicate with Keycloak, retrieve user information, and work with tokens.

`AuthServerClient`

```kotlin
private val openIdConfiguration: OpenIdConfiguration

init {
    try {
        // During AuthServerClient initialization, load OpenIdConfiguration
        // built from properties, including userInfoEndpoint and tokenEndpoint
        openIdConfiguration = authServerProperties.openIdConfigurationUrl
            ?.let { openIdConfigurationUrl ->
                restTemplate.getForEntity(
                    openIdConfigurationUrl,
                    OpenIdConfiguration::class.java
                ).body ?: /* ... */
            } /* ... */
    }
}
```

And `OpenIdConfiguration` itself:

`OpenIdConfiguration.class`

```kotlin
data class OpenIdConfiguration(
    @JsonProperty("userinfo_endpoint") val userInfoEndpoint: String,
    @JsonProperty("token_endpoint") val tokenEndpoint: String
)
```

Now the `AuthServerClient` methods.

Method for retrieving user information:

`AuthServerClient`

```kotlin
fun getUserInfo(token: String): UserInfo {
    // Get headers
    val headers = HttpHeaders()
    // Add Authorization
    headers.add("Authorization", "Bearer $token")
    // Build request and validate response
    return validateResponse(
        restTemplate.exchange(
            openIdConfiguration.userInfoEndpoint,
            HttpMethod.GET,
            HttpEntity<String>(headers),
            UserInfo::class.java
        )
    )
}
```

Methods for working with tokens: generation and refresh:

`AuthServerClient`

```kotlin
// Generate offline JWT
fun generateOfflineJwt(username: String, password: String): OfflineJwt {
    log.trace("Generate Access Token for user: '$username'")
    // Pass username and password
    return getOfflineJwt({
        with(it) {
            add("username", username)
            add("password", password)
        }
    // PASSWORD_GRANT_TYPE = password, OFFLINE_ACCESS_SCOPE = offline_access
    }, PASSWORD_GRANT_TYPE, OFFLINE_ACCESS_SCOPE)
}

// Refresh offline JWT
fun refreshOfflineJwt(refreshToken: String): OfflineJwt {
    log.trace("Refresh Token: $refreshToken")
    // pass refresh token to refresh offline JWT
    return getOfflineJwt({
        with(it) {
            add("refresh_token", refreshToken)
        }
    // REFRESH_TOKEN_GRANT_TYPE = "refresh_token"
    }, REFRESH_TOKEN_GRANT_TYPE)
}

// Shared method used by both operations above
private fun getOfflineJwt(
    extendParams: (LinkedMultiValueMap<String, String>) -> Unit = {},
    grantType: String,
    scope: String? = null
): OfflineJwt {
    // Build params
    val params = LinkedMultiValueMap<String, String>().apply {
        add("client_id", authClientProperties.clientId)
        add("client_secret", authClientProperties.clientSecret)
        add("grant_type", grantType)
        scope?.let { scopeValue ->
            add("scope", scopeValue)
        }
    }
    // Add extra params
    extendParams.invoke(params)

    // Build HttpHeaders
    val headers = with(HttpHeaders()) {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        this
    }

    val formEntity = HttpEntity(params, headers)

    // POST request with the request body
    val responseEntity = restTemplate.exchange(
        openIdConfiguration.tokenEndpoint,
        HttpMethod.POST,
        formEntity,
        OfflineJwt::class.java
    )

    return validateResponse(responseEntity)
}
```

That is essentially all. The only thing left is to briefly look at the data classes used by `AuthServerClient`.

`AuthClientProperties`, from which the data for token generation and refresh requests to Keycloak is taken:

`AuthClientProperties.class`

```kotlin
@ConfigurationProperties("spring.security.oauth2.client.registration.keycloak")
data class AuthClientProperties(
    var clientId: String? = null,
    var clientSecret: String? = null
)
```

Configuration providing these values:

`application-dev.yaml`

```yaml
auth-server:
  client-id: # set via EnvFile
  client-secret: # set via EnvFile
```

`AuthServerProperties`, which also reads the SSO URL and realm and helps construct request URLs that are then stored in `OpenIdConfiguration`:

`AuthServerProperties.class`

```kotlin
@ConfigurationProperties("auth-server")
data class AuthServerProperties(
    var url: String? = null,
    var realm: String? = null
) {
    val openIdConfigurationUrl: String?
        get() = issuerUrl?.let { urlValue ->
            "$urlValue/.well-known/openid-configuration"
        }

    val issuerUrl: String?
        get() = url?.let { urlValue ->
            realm?.let { realmValue ->
                "$urlValue/realms/$realmValue"
            }
        }
}
```

When `AuthServerClient` receives tokens from Keycloak, they look like this:

`OfflineJwt.class`

```kotlin
data class OfflineJwt(
    // Needed to access protected resources
    @JsonProperty("access_token") val accessToken: String,
    // Needed to refresh the access token
    @JsonProperty("refresh_token") val refreshToken: String
)
```

When `AuthServerClient` retrieves user information from Keycloak, it looks like this:

`UserInfo.class`

```kotlin
data class UserInfo(
    @JsonProperty("roles")
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val roles: Collection<String>,
    @JsonProperty("groups")
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val groups: Collection<String>
)
```

`BasePermissionEvaluator` is the class that implements the basic permission-checking logic for access to resources. It is then implemented in projects that connect `cloud-commons`.

`BasePermissionEvaluator.class`

```kotlin
@Suppress("MemberVisibilityCanBePrivate")
abstract class BasePermissionEvaluator(
    protected val securityService: SecurityService
) : PermissionEvaluator {
    // Check whether the user has the permission from configuration
    open fun hasPermission(permission: String): Boolean {
        val (username, roles) = securityService.getCurrentUser()
        val permissions = roles.flatMap { it.permissions }
        if (log.isTraceEnabled) {
            log.trace("Check '$permission' in '$username' permissions $permissions'")
        }
        return permissions
            .contains(permission)
            .also {
                logGrants(username, permission, "method", it)
            }
    }
    /* ... */
}
```

### 3.4 How the library is connected and used in other services

To connect the library, you must add `octopus-cloud-commons` to `build.gradle`:

`build.gradle`

```groovy
dependencies {
    // add octopus-cloud-commons.version to gradle.properties
    implementation("org.octopusden.octopus-cloud-commons:octopus-security-common:${project.properties["octopus-cloud-commons.version"]}")
}
```

As an example, consider `dms-service`. The project contains a `WebSecurityConfig` class that inherits from `CloudCommonWebSecurityConfig`, imports `AuthServerClient` for working with Keycloak, and reads `SecurityProperties` containing roles and permissions from the service configuration.

`WebSecurityConfig.class`

```kotlin
@Configuration
@Import(AuthServerClient::class)
@EnableConfigurationProperties(SecurityProperties::class)
class WebSecurityConfig(
    authServerClient: AuthServerClient,
    securityProperties: SecurityProperties
) : CloudCommonWebSecurityConfig(
    authServerClient = authServerClient,
    securityProperties = securityProperties,
)
```

The final step, which is optional, is to create a custom `PermissionEvaluator` that inherits from `BasePermissionEvaluator` and implements methods that can then be attached to controllers to check whether the user has access to a specific resource.

Example from the same `dms-service`:

`PermissionEvaluator.class`

```kotlin
@Component
class PermissionEvaluator(
    ...
    securityService: SecurityService
) : BasePermissionEvaluator(securityService) {
    fun hasPermissionByArtifactType(type: ArtifactType?) = type?.let {
        when (type) {
            ArtifactType.NOTES, ArtifactType.REPORT -> hasPermission("ACCESS_NOTES")
            ArtifactType.MANUALS -> hasPermission("ACCESS_DOCUMENTATION")
            ArtifactType.DISTRIBUTION -> hasPermission("ACCESS_DISTRIBUTION")
            ArtifactType.STATIC -> false
        }
    } ?: false
    /* ... */
}
```

And the controller that uses this logic:

`ComponentController.class`

```kotlin
@PreAuthorize(
    ... +
    "@permissionEvaluator.hasPermissionByArtifactType(#type) or " +
    ...
)
fun getComponentVersionArtifacts(...) = /* ... */
```

## 4. Q&A

### 4.1 Why does logging out in `dms-ui` also log out `api-gateway`, and vice versa?

When we log in to `dms-ui` or `api-gateway`, the browser goes through OAuth2 login via Keycloak. Keycloak creates an SSO session and stores it in its own cookies.

When the browser then accesses another client, for example `api-gateway`, it is redirected to Keycloak. Since Keycloak detects an active SSO session, it does not ask the user to log in again and immediately returns the user with an authorization code. As a result, a local `Authentication` is created in the other application as well.

When the user logs out from one client, for example `dms-ui`, the Keycloak end-session endpoint is called. That removes the Keycloak SSO session and clears its cookies.

After that, no client can perform re-authentication or token refresh through Keycloak anymore, even if one of the services still has an outdated `SecurityContext`, because the SSO session has already been removed.

Spring Security does not synchronize logout across applications automatically, so the local `SecurityContext` is cleared only:

- on explicit logout in a given service
- on a repeated OAuth2 login

In other words, logging out in one client does not revoke access tokens that were already issued to other clients. They remain valid for Spring until they expire, but they cannot be refreshed without a new SSO session.
