# Example: Collab, a live task board

A complete, runnable app on the Monolith libraries: multiple clients write tasks to a board and
every subscriber sees that board's list update **live**. It exercises the whole stack:

- **`monolith-api` + `monolith-codegen`**: [`BoardTasks`](src/main/java/com/standardapplied/monolith/example/collab/BoardTasks.java)
  is a `@PgQuery` record; the processor generates its reader, its `run(...)`, and its invalidation rule.
- **`monolith-runtime`**: `PgPool` + the binary `PQexecParams` write path (no JDBC); writes return a `Result<UUID>`.
- **`monolith-reactive`**: a WAL-tailing `Invalidator` feeds a `ReactiveHub`; a task write resolves to its board and wakes exactly that board's subscribers.
- **`monolith-helidon`**: the optional `LiveQueryWsListener` pushes the fresh result over a WebSocket.

The app **registers its own routes** ([`CollabRoutes`](src/main/java/com/standardapplied/monolith/example/collab/CollabRoutes.java))
and drops the adapter's listener onto its own WebSocket endpoint
([`CollabApp`](src/main/java/com/standardapplied/monolith/example/collab/CollabApp.java)). The
libraries impose no framework or routing of their own.

## Run it

Requires a local PostgreSQL with `wal_level = logical` and a `collab` database:

```bash
createdb collab
mvn -pl examples/collab -am compile exec:exec    # forks a JVM with native access enabled; binds :8080
```

It connects to `host=localhost dbname=collab` by default (see `CollabConfig.defaults()`). To run
from a built jar instead, passing an explicit port:

```bash
java --enable-native-access=ALL-UNNAMED -cp <classpath> \
     com.standardapplied.monolith.example.collab.CollabApp 8099
```

The schema (a `boards` and a `tasks` table, plus a stable demo board) is applied on startup.

## Try it

```bash
BOARD=00000000-0000-0000-0000-000000000001

# add a task (binary insert, no JDBC) → 201 + the new id
curl -X POST "http://localhost:8080/boards/$BOARD/tasks?title=Write+the+README&assignee=uday"

# a blank title is rejected by NewTask's canonical constructor → 400
curl -X POST "http://localhost:8080/boards/$BOARD/tasks?title="

# mark it done → 204
curl -X PUT "http://localhost:8080/tasks/<id>/done?value=true"
```

Subscribe to the live view over WebSocket at `ws://localhost:8080/live-query`, send the text frame
`BoardTasks:00000000-0000-0000-0000-000000000001`, and you get the current result immediately and a
fresh frame every time anyone writes to that board. Each frame is
`[int32 rowCount] ([int32 byteLength] [row bytes])*` in Monolith's binary layout.
