# service-container

A protocol-agnostic base layer for building Pithos services that serve both **gRPC** and **HTTP/REST** on the same port. Built on Quarkus + Vert.x. All payloads are typed as protobuf `Message` objects; JSON transcoding over HTTP is handled automatically.

Every service that depends on `service-container-core` gets the following **out of the box**:

- OAuth token validation on every authenticated request
- `POST /auth/login` REST endpoint + `AuthService.Login` gRPC RPC
- Structured `ServiceException` / `ErrorCode` error handling mapped to HTTP status codes and gRPC `Status` codes

---

## Why a shared container?

Quarkus runs `quarkus-grpc` and `quarkus-rest` on the same Vert.x event loop and port. The transport is demuxed by `Content-Type`:

| Content-Type | Protocol |
|---|---|
| `application/grpc` | gRPC (HTTP/2) |
| `application/json` | REST/HTTP |
| `application/x-protobuf` | REST/HTTP (binary protobuf) |

This module provides the shared handler contracts, auth wiring, and request-context plumbing so app modules only implement business logic — not protocol wiring.

---

## Module structure

```
service-container/
├── pom.xml          parent — Quarkus BOM + shared dependency versions
└── core/
    ├── pom.xml      library jar
    └── src/main/java/info/pithos/service/container/core/
        ├── ServiceHandler.java
        ├── BaseServiceHandler.java
        ├── LoginHandler.java
        ├── GetHandler.java / PostHandler.java / PutHandler.java / PatchHandler.java
        ├── ProtobufJsonProvider.java
        ├── rest/
        │   └── AuthRestResource.java      POST /auth/login
        └── grpc/
            ├── AuthGrpcService.java       AuthService.Login RPC
            └── GrpcRequestContextBuilder.java
```

### Maven coordinates

| Artifact | GroupId | ArtifactId |
|---|---|---|
| Parent | `info.pithos.service.container` | `info-pithos-service-container` |
| Core | `info.pithos.service.container.core` | `info-pithos-service-container-core` |

---

## Core abstractions

### `ServiceHandler<Req, Resp>`

The root contract for all handlers. Both HTTP and gRPC paths converge here.

```java
public interface ServiceHandler<Req extends Message, Resp extends Message> {
    Uni<Resp> handle(Req request, RequestContext context);
}
```

### Verb-specific interfaces

Marker interfaces that extend `ServiceHandler`. The routing layer uses the type to bind the correct HTTP method.

| Interface | HTTP verb | Request source |
|---|---|---|
| `GetHandler<Req, Resp>` | `GET` | path / query params |
| `PostHandler<Req, Resp>` | `POST` | request body |
| `PutHandler<Req, Resp>` | `PUT` | request body (full replace) |
| `PatchHandler<Req, Resp>` | `PATCH` | request body (partial update) |

### `BaseServiceHandler<Req, Resp>`

Abstract base that requires an `OAuthClient` at construction time. Before delegating to `handle()` it:

1. Extracts the `Authorization: Bearer <token>` header (if present)
2. Calls `OAuthClient.introspectToken()` and rejects inactive tokens with `UNAUTHORIZED`
3. Populates `AuthContext.userId` from the validated token `subject` (not the untrusted `X-User-Id` header)
4. Normalises any exception thrown by `handle()` into a `ServiceException` with the appropriate `ErrorCode`

```java
public abstract class BaseServiceHandler<Req extends Message, Resp extends Message>
        implements ServiceHandler<Req, Resp> {

    protected BaseServiceHandler(OAuthClient oAuthClient) { ... }

    // HTTP entry point — called by the Quarkus REST resource
    public final Uni<Resp> handleHttp(Req request, MultiMap httpHeaders) { ... }

    // Implement business logic here
    @Override
    public abstract Uni<Resp> handle(Req request, RequestContext context);

    // Available to subclasses that need to issue tokens (e.g. LoginHandler)
    protected final OAuthClient oAuthClient() { ... }
}
```

For **gRPC**, build `RequestContext` from `io.grpc.Metadata` via `GrpcRequestContextBuilder` and call `handle(request, context)` directly.

### `ProtobufJsonProvider`

Jakarta RS `MessageBodyReader` / `MessageBodyWriter` registered automatically via `@Provider`. Handles:

- **Write** `application/json` — delegates to `ProtoBufSerde.serialize()` (Google `JsonFormat`)
- **Write** `application/x-protobuf` — raw binary `writeTo()`
- **Read** `application/json` — delegates to `ProtoBufSerde` with a reflectively obtained builder

---

## Error handling

All exceptions that escape `handle()` are normalised by `BaseServiceHandler` into a `ServiceException` before propagating to the transport layer.

### `ErrorCode`

Enum in `info.pithos.runtime.core.context` covering the full set of meaningful HTTP status codes:

| Range | Codes |
|---|---|
| 2xx | `OK`, `ACCEPTED` |
| 3xx | `MOVED_PERMANENTLY`, `FOUND`, `NOT_MODIFIED` |
| 4xx | `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `REQUEST_TIMEOUT`, `CONFLICT`, `GONE`, `PRECONDITION_FAILED`, `PAYLOAD_TOO_LARGE`, `URI_TOO_LONG`, `UNSUPPORTED_MEDIA_TYPE`, `EXPECTATION_FAILED`, `UPGRADE_REQUIRED`, `TOO_MANY_REQUESTS` |
| 5xx | `INTERNAL_SERVER_ERROR`, `NOT_IMPLEMENTED`, `BAD_GATEWAY`, `SERVICE_UNAVAILABLE` |

Each constant exposes `httpStatus()` (int) and `reason()` (String).

### `ServiceException`

```java
// throw from any handler
throw new ServiceException(ErrorCode.NOT_FOUND, "role not found");
throw new ServiceException(ErrorCode.CONFLICT, "resource already exists");
throw new ServiceException(ErrorCode.FORBIDDEN, "insufficient permissions");
```

### Automatic exception mapping

`BaseServiceHandler` maps unchecked exceptions that don't extend `ServiceException`:

| Thrown | Mapped to |
|---|---|
| `ServiceException` | passed through unchanged |
| `IllegalArgumentException` | `BAD_REQUEST (400)` |
| `SecurityException` | `UNAUTHORIZED (401)` |
| anything else | `INTERNAL_SERVER_ERROR (500)` |

### gRPC status mapping (`AuthGrpcService`)

`ServiceException` error codes are mapped to gRPC `Status` in the gRPC service layer:

| `ErrorCode` | gRPC `Status` |
|---|---|
| `BAD_REQUEST` | `INVALID_ARGUMENT` |
| `UNAUTHORIZED` | `UNAUTHENTICATED` |
| `FORBIDDEN` | `PERMISSION_DENIED` |
| `NOT_FOUND` | `NOT_FOUND` |
| `CONFLICT` | `ALREADY_EXISTS` |
| `TOO_MANY_REQUESTS` | `RESOURCE_EXHAUSTED` |
| `SERVICE_UNAVAILABLE` | `UNAVAILABLE` |
| `NOT_IMPLEMENTED` | `UNIMPLEMENTED` |
| all others | `INTERNAL` |

---

## OOB auth endpoints

### REST — `POST /auth/login`

Handled by `AuthRestResource` → `LoginHandler`. Accepts `LoginRequest` JSON, delegates to `OAuthClient.login()`, returns `LoginResponse` JSON.

```
POST /auth/login
Content-Type: application/json

{ "username": "alice", "password": "secret", "scopes": ["openid"] }

→ 200 OK
{ "accessToken": "...", "refreshToken": "...", "expiresIn": 3600, "tokenType": "Bearer", "scope": "openid" }
```

### gRPC — `AuthService.Login`

Handled by `AuthGrpcService` → `LoginHandler`. Service definition from `auth-model`:

```proto
service AuthService {
  rpc Login(LoginRequest) returns (LoginResponse);
}
```

`AuthGrpcService` reads gRPC `Metadata` via `AuthGrpcService.METADATA_KEY`, which must be populated by a server-side interceptor before each call. `GrpcRequestContextBuilder` maps metadata keys to `RequestContext` fields using the same key names as the HTTP headers.

---

## HTTP header → `RequestContext` mapping

`BaseServiceHandler.handleHttp()` maps the following headers. When a valid Bearer token is present, `authContext.userId` is populated from the introspected token `subject` rather than the header.

| HTTP Header | `RequestContext` field |
|---|---|
| `X-Request-Id` / `X-Correlation-Id` | `requestId` (UUID generated if absent) |
| `X-Enterprise-Id` | `enterpriseId`, `authContext.enterpriseId` |
| `Authorization` | `authContext.userAuthToken`; token subject → `authContext.userId` |
| `X-User-Id` | `authContext.userId` (only when no Bearer token is present) |
| `Host` | `host` |
| `X-Forwarded-For` / `X-Real-IP` | `source` |
| `User-Agent` | `userAgent` |
| `Cache-Control` | `cacheControl` |
| `Accept-Language` | `locale` |
| `X-Log-Level` | `logLevel` (mapped to `LogLevelType` enum) |
| all other headers | `attributes` map |

---

## Implementing a handler

```java
@ApplicationScoped
public class CreateRoleHandler
        extends BaseServiceHandler<CreateRoleRequest, RoleResponse>
        implements PostHandler<CreateRoleRequest, RoleResponse> {

    @Inject
    public CreateRoleHandler(OAuthClient oAuthClient) {
        super(oAuthClient);
    }

    @Override
    public Uni<RoleResponse> handle(CreateRoleRequest request, RequestContext context) {
        if (request.getName().isBlank()) {
            throw new ServiceException(ErrorCode.BAD_REQUEST, "role name is required");
        }
        return Uni.createFrom().item(
            RoleResponse.newBuilder()
                .setRoleId(UUID.randomUUID().toString())
                .build()
        );
    }
}

// REST resource
@Path("/roles")
@ApplicationScoped
public class RoleResource {

    @Inject CreateRoleHandler handler;
    @Inject HttpServerRequest httpRequest;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<RoleResponse> create(CreateRoleRequest request) {
        return handler.handleHttp(request, httpRequest.headers());
    }
}
```

---

## Key dependencies

| Dependency | Purpose |
|---|---|
| `io.quarkus.platform:quarkus-bom` | Quarkus version management |
| `io.quarkus:quarkus-grpc` | gRPC server (add in app module) |
| `io.quarkus:quarkus-rest` | REST server (add in app module) |
| `io.vertx:vertx-core` | `MultiMap` for HTTP header reading |
| `com.google.protobuf:protobuf-java` | Protobuf runtime |
| `info.pithos.runtime.core:pithos-runtime-core-model` | `RequestContext` proto |
| `info.pithos.runtime.core.context:pithos-runtime-core-context` | `ErrorCode`, `ServiceException` |
| `info.pithos.auth:info-pithos-auth-api` | `OAuthClient` interface |
| `info.pithos.auth:info-pithos-auth-model` | `LoginRequest`, `LoginResponse`, `AuthServiceGrpc` |
| `info.pithos.serde:info-pithos-serde` | JSON ↔ protobuf via `ProtoBufSerde` |
| `io.smallrye.reactive:mutiny` | Async `Uni<T>` return type |
| `io.grpc:grpc-stub` | gRPC `StreamObserver`, `BindableService` |
| `io.grpc:grpc-protobuf` | gRPC protobuf descriptor support |
