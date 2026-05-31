-- Collab example schema: a shared task board. Multiple clients write tasks to a board and each
-- subscriber sees the board's task list update live. REPLICA IDENTITY FULL makes logical decoding
-- emit the row's column values on every change, so the generated invalidation rule can read
-- board_id off a task change and wake exactly the right board's subscribers.

CREATE TABLE IF NOT EXISTS boards (
  id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL
);

CREATE TABLE IF NOT EXISTS tasks (
  id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  board_id uuid NOT NULL REFERENCES boards(id),
  title    text NOT NULL,
  done     boolean NOT NULL DEFAULT false,
  assignee text
);

ALTER TABLE tasks REPLICA IDENTITY FULL;

-- A demo board with a stable id so the README's curl/websocket examples work out of the box.
INSERT INTO boards (id, name) VALUES ('00000000-0000-0000-0000-000000000001', 'Demo Board')
  ON CONFLICT (id) DO NOTHING;
