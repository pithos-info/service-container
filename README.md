# service-container

A protocol-agnostic base layer for building Pithos services that serve both **gRPC** and **HTTP/REST** on the same port. Built on Quarkus + Vert.x. All payloads are typed as protobuf `Message` objects; JSON transcoding over HTTP is handled automatically.

---

## Why a shared container?

Quarkus runs `quarkus-grpc` and `quarkus-rest` on the same Vert.x event loop and port. The transport is demuxed by `Content-Type`:

| Content-Type | Protocol |
|---|---|
| `application/grpc` | gRPC (HTTP/2) |
| `application/json` | REST/HTTP |
| `application/x-protobuf` | REST/HTTP (binary protobuf) |

This module provides the shared handler contracts and request-context plumbing so app modules (e.g. `rbac`) only implement business logic — not protocol wiring.

---

## Module structure

```
service-container/
├── pom.xml          parent — Quarkus BOM + shared dependency versions
└── core/
    └── pom.xml      library jar — base interfaces and HTTP plumbing
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

Abstract base class that maps inbound HTTP headers to a `RequestContext` proto and delegates to `handle()`. App handlers extend this.

```java
public abstract class BaseServiceHandler<Req extends Message, Resp extends Message>
        implements ServiceHandler<Req, Resp> {

    // HTTP entry point — called by the Quarkus REST resource
    public final Uni<Resp> handleHttp(Req request, MultiMap httpHeaders) { ... }

    // Implement business logic here
    @Override
    public abstract Uni<Resp> handle(Req request, RequestContext context);
}
```

For **gRPC**, build `RequestContext` from `io.grpc.Metadata` in an interceptor and call `handle(request, context)` directly.

### `ProtobufJsonProvider`

Jakarta RS `MessageBodyReader` / `MessageBodyWriter` registered automatically via `@Provider`. Handles:

- **Write** `application/json` — delegates to `ProtoBufSerde.serialize()` (Google `JsonFormat`)
- **Write** `application/x-protobuf` — raw binary `writeTo()`
- **Read** `application/json` — delegates to `ProtoBufSerde` with a reflectively obtained builder

---

## HTTP header → `RequestContext` mapping

`BaseServiceHandler.handleHttp()` extracts the following headers and populates the `RequestContext` proto (defined in `runtime-model/core/RequestContext.proto`):

| HTTP Header | `RequestContext` field |
|---|---|
| `X-Request-Id` / `X-Correlation-Id` | `requestId` |
| `X-Enterprise-Id` | `enterpriseId`, `authContext.enterpriseId` |
| `Authorization` | `authContext.userAuthToken` |
| `X-User-Id` | `authContext.userId` |
| `Host` | `host` |
| `X-Forwarded-For` / `X-Real-IP` | `source` |
| `User-Agent` | `userAgent` |
| `Cache-Control` | `cacheControl` |
| `Accept-Language` | `locale` |
| `X-Log-Level` | `logLevel` (mapped to `LogLevelType` enum) |
| all other headers | `attributes` map |

---

## Implementing a handler (example: RBAC create-role)

```java
// 1. Implement the verb interface
@ApplicationScoped
public class CreateRoleHandler
        extends BaseServiceHandler<CreateRoleRequest, RoleResponse>
        implements PostHandler<CreateRoleRequest, RoleResponse> {

    @Override
    public Uni<RoleResponse> handle(CreateRoleRequest request, RequestContext context) {
        // context.getRequestId(), context.getAuthContext().getUserAuthToken(), etc.
        return Uni.createFrom().item(
            RoleResponse.newBuilder()
                .setRoleId(UUID.randomUUID().toString())
                .build()
        );
    }
}

// 2. Wire into a Quarkus REST resource
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
| `info.pithos.serde:info-pithos-serde` | JSON ↔ protobuf via `ProtoBufSerde` |
| `io.smallrye.reactive:mutiny` | Async `Uni<T>` return type |
