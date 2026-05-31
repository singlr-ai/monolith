/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.monolith.example.collab;

import java.util.Objects;

/**
 * Immutable configuration for the collab server: where to bind, how to reach Postgres, the
 * connection-pool size, and the logical-replication slot name the reactive feed owns. Built through
 * {@link #newBuilder()} with {@code with*} setters; the canonical constructor validates every field
 * so an invalid server can never be constructed.
 *
 * @param host the bind host
 * @param port the bind port; {@code 0} asks the OS for an ephemeral port
 * @param conninfo the libpq connection string (e.g. {@code "host=localhost dbname=collab"})
 * @param poolSize the number of pooled connections; positive
 * @param slot the logical-replication slot name for the reactive feed; non-blank
 */
public record CollabConfig(String host, int port, String conninfo, int poolSize, String slot) {

  private static final CollabConfig DEFAULTS =
      new CollabConfig("127.0.0.1", 8080, defaultConninfo(), 8, "collab_app");

  public CollabConfig {
    Objects.requireNonNull(host, "host must not be null");
    if (host.isBlank()) {
      throw new IllegalArgumentException("host must not be blank");
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port must be in [0, 65535], got " + port);
    }
    Objects.requireNonNull(conninfo, "conninfo must not be null");
    if (conninfo.isBlank()) {
      throw new IllegalArgumentException("conninfo must not be blank");
    }
    if (poolSize <= 0) {
      throw new IllegalArgumentException("poolSize must be positive, got " + poolSize);
    }
    Objects.requireNonNull(slot, "slot must not be null");
    if (slot.isBlank()) {
      throw new IllegalArgumentException("slot must not be blank");
    }
  }

  /** Returns the default configuration: loopback :8080, an 8-connection pool, slot {@code collab_app}. */
  public static CollabConfig defaults() {
    return DEFAULTS;
  }

  /** Starts a builder seeded with {@link #defaults()}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  private static String defaultConninfo() {
    return "host=localhost dbname=collab user=" + System.getProperty("user.name");
  }

  /** Mutable builder for {@link CollabConfig}; each {@code with*} setter overrides one field. */
  public static final class Builder {

    private String host = DEFAULTS.host;
    private int port = DEFAULTS.port;
    private String conninfo = DEFAULTS.conninfo;
    private int poolSize = DEFAULTS.poolSize;
    private String slot = DEFAULTS.slot;

    private Builder() {}

    public Builder withHost(String host) {
      this.host = host;
      return this;
    }

    public Builder withPort(int port) {
      this.port = port;
      return this;
    }

    public Builder withConninfo(String conninfo) {
      this.conninfo = conninfo;
      return this;
    }

    public Builder withPoolSize(int poolSize) {
      this.poolSize = poolSize;
      return this;
    }

    public Builder withSlot(String slot) {
      this.slot = slot;
      return this;
    }

    public CollabConfig build() {
      return new CollabConfig(host, port, conninfo, poolSize, slot);
    }
  }
}
