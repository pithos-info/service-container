# gRPC + REST API Implementation

When asked to implement a new API entity using this framework, follow these instructions
exactly. Do not write any code until all inputs are collected.

---

## Step 1 — Collect inputs

Ask the developer for the following. Collect all answers before proceeding.

```
1. App source root
   The base directory for generated Java files.
   Example: /path/to/my-app/src/main/java

2. App base package
   The root Java package for the app.
   Example: com.example.myapp

3. Proto file path
   Absolute path to the .proto file where messages and the service will be added.

4. Proto Java package
   The java_package option in that proto file.
   Example: com.example.myapp.service

5. Data model type
   The fully-qualified class (or prefix) for the internal proto data objects
   used by the service layer — distinct from the API proto messages.
   Example: MyApp.Permission  (from the data model proto)
   Leave blank if the service layer uses the same proto types as the API.

6. Data model package
   Java package of the data model type above (so it can be imported).
   Leave blank if same as proto Java package.

7. Entity name
   PascalCase singular. This becomes the class name prefix for all generated files.
   Example: Permission

8. Entity shape
   crud         — owns its own rows; standard operations are Create / Get / Update / Delete / List
   association  — join between two existing entities; standard operations are
                  Add / Remove / ListBy{ParentEntity}

9. Entity fields
   For crud: the fields on the entity message and on Create / Update request messages.
   For association: the two foreign-key fields (e.g. groupId + userId).

10. Service interface
    The existing service interface the handlers will call.
    Example: PermissionService  (in package {app base package}.service or similar)
    Provide the fully-qualified name if it differs from the app base package.

11. Custom operations
    Any operations beyond the standard set for the shape, one per line.
    Format: MethodName : InputType -> OutputType : "description"
    Example:
      HasPermission : CheckPermissionRequest -> Bool : "check if user has a permission"
      GetUserPermissions : Empty -> PermissionList : "all permissions for the current user"
    Leave blank if none.

12. REST routes
    HTTP method + path for every operation, including custom ones.
    Example:
      GET    /permissions
      POST   /permissions
      GET    /permissions/:id
      PUT    /permissions/:id
      DELETE /permissions/:id
      GET    /permissions/me
    Path parameters must match the proto field names they map to.

13. Guice module
    Fully-qualified class name and file path of the Guice module where bindings are added.
    Example: com.example.myapp.AppModule
             /path/to/my-app/src/main/java/com/example/myapp/AppModule.java

14. gRPC server
    Fully-qualified class name and file path of the gRPC server class.
    Example: com.example.myapp.server.AppGrpcServer
             /path/to/my-app/src/main/java/com/example/myapp/server/AppGrpcServer.java

15. REST router
    Fully-qualified class name and file path of the REST router class.
    Example: com.example.myapp.rest.AppRestRouter
             /path/to/my-app/src/main/java/com/example/myapp/rest/AppRestRouter.java
```

Confirm the inputs back to the developer as a summary before writing any files.

---

## Step 2 — Proto: messages + service

Edit the proto file from input 3. Add a section at the bottom.

**CRUD shape — messages:**
```proto
// ─── {Entity} ─────────────────────────────────────────────────────────────────

message Create{Entity}Request {
  // one field per create input
}

message Update{Entity}Request {
  string id = 1;
  // updatable fields
}

message {Entity} {
  string id            = 1;
  string enterpriseId  = 2;
  // entity fields
  int64  utcCreatedAt  = N;
  int64  utcModifiedAt = N;
}

message {Entity}List {
  repeated {Entity} {entities} = 1;
}
```

**CRUD shape — service:**
```proto
service {Entity}Service {
  rpc Create(Create{Entity}Request) returns ({Entity});
  rpc Get(GetByIdRequest)           returns ({Entity});
  rpc Update(Update{Entity}Request) returns ({Entity});
  rpc Delete(DeleteByIdRequest)     returns (Empty);
  rpc List(Empty)                   returns ({Entity}List);
  // one rpc per custom operation
}
```

**Association shape — messages:**
```proto
// ─── {EntityA}{EntityB} ───────────────────────────────────────────────────────

message Add{EntityA}{EntityB}Request {
  string {entityAId} = 1;
  string {entityBId} = 2;
}

message Remove{EntityA}{EntityB}Request {
  string {entityAId} = 1;
  string {entityBId} = 2;
}

message {EntityA}{EntityB} {
  string {entityAId} = 1;
  string {entityBId} = 2;
  int64  utcCreatedAt = 3;
}

message {EntityA}{EntityB}List {
  repeated {EntityA}{EntityB} {items} = 1;
}
```

**Association shape — service:**
```proto
service {EntityA}{EntityB}Service {
  rpc Add(Add{EntityA}{EntityB}Request)        returns ({EntityA}{EntityB});
  rpc Remove(Remove{EntityA}{EntityB}Request)  returns (Empty);
  rpc ListBy{EntityA}(GetByIdRequest)          returns ({EntityA}{EntityB}List);
  // one rpc per custom operation
}
```

For custom operations that return `Bool` (has / is checks), add:
```proto
message Check{Entity}Request {
  // fields needed to perform the check
}
```

Trigger a build after editing the proto so protoc regenerates Java sources before
writing handler or gRPC service code.

---

## Step 3 — Handlers

Create: `{app source root}/{app base package as path}/handler/{Entity}Handlers.java`

One outer `final` class, private constructor. Each operation is a `public static final`
inner class extending `BaseServiceHandler<Req, Resp>`.

```java
package {app base package}.handler;

import com.google.inject.Inject;
import info.pithos.auth.OAuthClient;
import {service interface package}.{Entity}Service;
import {data model package}.{DataModelPrefix};   // omit if not applicable
import {proto java package}.Create{Entity}Request;
import {proto java package}.Update{Entity}Request;
import {proto java package}.Delete{Entity}Request; // or DeleteByIdRequest
import {proto java package}.GetByIdRequest;
import {proto java package}.Empty;
import {proto java package}.{Entity};
import {proto java package}.{Entity}List;
import info.pithos.runtime.core.context.ErrorCode;
import info.pithos.runtime.core.context.ServiceException;
import info.pithos.runtime.model.protocol.Context.RequestContext;
import info.pithos.serde.ProtoBufMapper;
import info.pithos.service.container.core.BaseServiceHandler;
import io.smallrye.mutiny.Uni;

public final class {Entity}Handlers {

    private {Entity}Handlers() {}

    public static final class Create extends BaseServiceHandler<Create{Entity}Request, {Entity}> {
        private final {Entity}Service service;

        @Inject
        public Create(OAuthClient oAuthClient, {Entity}Service service) {
            super(oAuthClient);
            this.service = service;
        }

        @Override
        public Uni<{Entity}> handle(Create{Entity}Request req, RequestContext rc) {
            {DataModelPrefix}{Entity} data = {DataModelPrefix}{Entity}.newBuilder()
                // map req fields to data fields
                .build();
            return Uni.createFrom().completionStage(() -> service.create(rc, data))
                .map(created -> ProtoBufMapper.map(created, {Entity}.newBuilder()));
        }
    }

    public static final class Get extends BaseServiceHandler<GetByIdRequest, {Entity}> {
        private final {Entity}Service service;

        @Inject
        public Get(OAuthClient oAuthClient, {Entity}Service service) {
            super(oAuthClient);
            this.service = service;
        }

        @Override
        public Uni<{Entity}> handle(GetByIdRequest req, RequestContext rc) {
            return Uni.createFrom().completionStage(() -> service.get(rc, req.getId()))
                .map(opt -> opt.map(d -> ProtoBufMapper.<{Entity}>map(d, {Entity}.newBuilder()))
                    .orElseThrow(() -> new ServiceException(ErrorCode.NOT_FOUND,
                        "{Entity} not found: " + req.getId())));
        }
    }

    public static final class Update extends BaseServiceHandler<Update{Entity}Request, {Entity}> {
        private final {Entity}Service service;

        @Inject
        public Update(OAuthClient oAuthClient, {Entity}Service service) {
            super(oAuthClient);
            this.service = service;
        }

        @Override
        public Uni<{Entity}> handle(Update{Entity}Request req, RequestContext rc) {
            {DataModelPrefix}{Entity} data = {DataModelPrefix}{Entity}.newBuilder()
                .setId(req.getId())
                // map updatable fields
                .build();
            return Uni.createFrom().completionStage(() -> service.update(rc, data))
                .map(updated -> ProtoBufMapper.map(updated, {Entity}.newBuilder()));
        }
    }

    public static final class Delete extends BaseServiceHandler<DeleteByIdRequest, Empty> {
        private final {Entity}Service service;

        @Inject
        public Delete(OAuthClient oAuthClient, {Entity}Service service) {
            super(oAuthClient);
            this.service = service;
        }

        @Override
        public Uni<Empty> handle(DeleteByIdRequest req, RequestContext rc) {
            return Uni.createFrom().completionStage(() -> service.delete(rc, req.getId()))
                .map(v -> Empty.getDefaultInstance());
        }
    }

    public static final class List extends BaseServiceHandler<Empty, {Entity}List> {
        private final {Entity}Service service;

        @Inject
        public List(OAuthClient oAuthClient, {Entity}Service service) {
            super(oAuthClient);
            this.service = service;
        }

        @Override
        public Uni<{Entity}List> handle(Empty req, RequestContext rc) {
            return Uni.createFrom().completionStage(() -> service.list(rc))
                .map(items -> {Entity}List.newBuilder()
                    .addAll{Entities}(items.stream()
                        .map(d -> ProtoBufMapper.<{Entity}>map(d, {Entity}.newBuilder()))
                        .toList())
                    .build());
        }
    }

    // ── Custom operations ──────────────────────────────────────────────────────
    // Add one inner class per custom operation following the same pattern.
    // For Bool-returning checks:
    //
    // public static final class Has{X} extends BaseServiceHandler<Check{X}Request, Bool> {
    //     @Override public Uni<Bool> handle(Check{X}Request req, RequestContext rc) {
    //         return Uni.createFrom().completionStage(() -> service.has{X}(rc, ...))
    //             .map(result -> Bool.newBuilder().setValue(result).build());
    //     }
    // }
}
```

**Association shape** — replace Create/Get/Update/Delete/List with:
- `Add` — calls `service.add(rc, req.get{EntityAId}(), req.get{EntityBId}())`
- `Remove` — calls `service.remove(rc, ...)`, maps to `Empty.getDefaultInstance()`
- `ListBy{EntityA}` — calls `service.select(rc, FilterCriteria.eq("{entityAId}", req.getId()))`

---

## Step 4 — gRPC service

Create: `{app source root}/{app base package as path}/grpc/{Entity}GrpcService.java`

Every method body is exactly one line. No exceptions.

```java
package {app base package}.grpc;

import com.google.inject.Inject;
import {app base package}.handler.{Entity}Handlers;
import {proto java package}.Create{Entity}Request;
// ... all proto type imports
import info.pithos.service.container.core.grpc.GrpcSupport;
import io.grpc.stub.StreamObserver;

public final class {Entity}GrpcService extends {Entity}ServiceGrpc.{Entity}ServiceImplBase {

    private final {Entity}Handlers.Create create;
    private final {Entity}Handlers.Get    get;
    private final {Entity}Handlers.Update update;
    private final {Entity}Handlers.Delete delete;
    private final {Entity}Handlers.List   list;
    // one field per custom operation handler

    @Inject
    public {Entity}GrpcService(
            {Entity}Handlers.Create create,
            {Entity}Handlers.Get    get,
            {Entity}Handlers.Update update,
            {Entity}Handlers.Delete delete,
            {Entity}Handlers.List   list) {
        this.create = create;
        this.get    = get;
        this.update = update;
        this.delete = delete;
        this.list   = list;
    }

    @Override
    public void create(Create{Entity}Request req, StreamObserver<{Entity}> obs) {
        GrpcSupport.respond(create.handle(req, GrpcSupport.context()), obs);
    }

    @Override
    public void get(GetByIdRequest req, StreamObserver<{Entity}> obs) {
        GrpcSupport.respond(get.handle(req, GrpcSupport.context()), obs);
    }

    @Override
    public void update(Update{Entity}Request req, StreamObserver<{Entity}> obs) {
        GrpcSupport.respond(update.handle(req, GrpcSupport.context()), obs);
    }

    @Override
    public void delete(DeleteByIdRequest req, StreamObserver<Empty> obs) {
        GrpcSupport.respond(delete.handle(req, GrpcSupport.context()), obs);
    }

    @Override
    public void list(Empty req, StreamObserver<{Entity}List> obs) {
        GrpcSupport.respond(list.handle(req, GrpcSupport.context()), obs);
    }

    // one @Override per custom operation, same one-liner pattern
}
```

---

## Step 5 — REST resource

Create: `{app source root}/{app base package as path}/rest/resource/{Entity}Resource.java`

Rules:
- `POST` (create / add) → status **201**
- `GET`, `PUT` → status **200**
- `DELETE` (delete / remove) → `routeNoContent` (204, no body)
- `parseBody` returns null on parse failure and has already written the 400; always guard: `if (req == null) return;`
- Path params injected via `ctx.pathParam("name")` and set on the request proto before dispatch

```java
package {app base package}.rest.resource;

import com.google.inject.Inject;
import {app base package}.handler.{Entity}Handlers;
import {proto java package}.Create{Entity}Request;
// ... all proto type imports
import info.pithos.service.container.core.BaseServiceHandler;
import io.vertx.ext.web.Router;

public final class {Entity}Resource {

    private final {Entity}Handlers.Create create;
    private final {Entity}Handlers.Get    get;
    private final {Entity}Handlers.Update update;
    private final {Entity}Handlers.Delete delete;
    private final {Entity}Handlers.List   list;
    // one field per custom operation handler

    @Inject
    public {Entity}Resource(
            {Entity}Handlers.Create create,
            {Entity}Handlers.Get    get,
            {Entity}Handlers.Update update,
            {Entity}Handlers.Delete delete,
            {Entity}Handlers.List   list) {
        this.create = create;
        this.get    = get;
        this.update = update;
        this.delete = delete;
        this.list   = list;
    }

    public void mount(Router r) {
        // mount one route per REST route from input 12
        // derive the handler call and request construction from the route + operation mapping

        // GET collection
        r.get("{rest base path}").handler(ctx ->
            BaseServiceHandler.route(ctx, 200, list, Empty.getDefaultInstance()));

        // POST (create)
        r.post("{rest base path}").handler(ctx -> {
            Create{Entity}Request req = BaseServiceHandler.parseBody(ctx, Create{Entity}Request.newBuilder());
            if (req == null) return;
            BaseServiceHandler.route(ctx, 201, create, req);
        });

        // GET by id
        r.get("{rest base path}/:id").handler(ctx ->
            BaseServiceHandler.route(ctx, 200, get,
                GetByIdRequest.newBuilder().setId(ctx.pathParam("id")).build()));

        // PUT (update) — merge path param into body
        r.put("{rest base path}/:id").handler(ctx -> {
            Update{Entity}Request req = BaseServiceHandler.parseBody(ctx, Update{Entity}Request.newBuilder());
            if (req == null) return;
            BaseServiceHandler.route(ctx, 200, update,
                req.toBuilder().setId(ctx.pathParam("id")).build());
        });

        // DELETE
        r.delete("{rest base path}/:id").handler(ctx ->
            BaseServiceHandler.routeNoContent(ctx, delete,
                DeleteByIdRequest.newBuilder().setId(ctx.pathParam("id")).build()));

        // custom operation routes derived from input 12
    }
}
```

---

## Step 6 — Guice bindings

Edit the Guice module from input 13. Add to `configure()`, grouped by entity.

```java
// ── {Entity} ──────────────────────────────────────────────────────────────────
bind({Entity}Resource.class).in(Singleton.class);

bind({Entity}Handlers.Create.class).in(Singleton.class);
bind({Entity}Handlers.Get.class).in(Singleton.class);
bind({Entity}Handlers.Update.class).in(Singleton.class);
bind({Entity}Handlers.Delete.class).in(Singleton.class);
bind({Entity}Handlers.List.class).in(Singleton.class);
// one bind per custom handler class

bind({Entity}GrpcService.class).in(Singleton.class);
```

Add all required imports.

---

## Step 7 — gRPC server

Edit the gRPC server from input 14. Three changes:

1. Add field: `private final {Entity}GrpcService {entity}GrpcService;`
2. Add constructor parameter and assignment (keep consistent ordering with existing services)
3. Add `.addService({entity}GrpcService)` in the `NettyServerBuilder` chain

---

## Step 8 — REST router

Edit the REST router from input 15. Three changes:

1. Add field: `private final {Entity}Resource {entities};`
2. Add constructor parameter and assignment
3. Add `{entities}.mount(router);` in the `mount()` method

---

## Completion checklist

Verify all eight touch-points before reporting done:

- [ ] Proto messages + service added and build triggered
- [ ] `{Entity}Handlers.java` — one inner class per operation
- [ ] `{Entity}GrpcService.java` — one `@Override` per RPC, all one-liners
- [ ] `{Entity}Resource.java` — one route per REST route from input 12
- [ ] Guice module — resource + all handler inner classes + gRPC service bound
- [ ] gRPC server — field, constructor param, `.addService()` added
- [ ] REST router — field, constructor param, `.mount()` added
- [ ] No RBAC-specific or app-specific assumptions in generated code — all types come from the collected inputs
