# Contributing to Monolith

Monolith is early (v0.1) and APIs will change. Issues, discussions, and PRs are welcome.

## Building

Requires **JDK 25+** and **Maven 3.9+**.

```bash
mvn install
```

The reactive and integration tests need a local **PostgreSQL 14+** with `wal_level = logical`
(set `ALTER SYSTEM SET wal_level = 'logical';` then restart). Tests start/connect to a local
instance; there is no H2 or Testcontainers. Monolith tests against real Postgres on purpose.

## Conventions

- **Java**: match the surrounding style (2-space indent, no wildcard imports). The hot FFM path
  must use `MethodHandle.invokeExact(...)` with precise signatures, never `invokeWithArguments`.
- **No third-party runtime dependencies** in `monolith-runtime` (pure JDK + FFM). Adapters
  (e.g. a web framework) live in their own modules and may depend on that framework.
- **Codegen output is byte-stable.** Changes to the processor must not alter the generated wire
  layout for existing types without an intentional, reviewed schema-lock bump.

## License

By contributing you agree your contributions are licensed under the MIT License.
