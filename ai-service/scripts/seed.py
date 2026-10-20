"""Local demo data (FR-083, §6.7). Deterministic (seed 42), idempotent, local only.

    docker compose --profile tools run --rm seed             # once; re-running is a no-op
    docker compose --profile tools run --rm seed --reset     # wipe and recreate (APP_ENV=local only)
    ... seed --small                                          # ~60 tasks, used by CI/E2E

Connects as the schema owner (DB_OWNER_USER). Prints demo login emails, never passwords. The data is
synthetic: model metrics trained on it are a demonstration, not evidence of real-world accuracy.
"""

from __future__ import annotations

import argparse
import os
import random
import sys
from datetime import UTC, datetime, timedelta

import bcrypt
import psycopg

RNG = random.Random(42)
NOW = datetime.now(UTC).replace(minute=0, second=0, microsecond=0)
LABELS = [("bug", "rose"), ("feature", "violet"), ("backend", "blue"), ("frontend", "teal"), ("docs", "green"), ("infra", "amber")]
PEOPLE = [
    # email local part, name, role, estimation bias (actual ≈ estimate × bias)
    ("admin", "Meera Iyer", "ADMIN", 1.0),
    ("priya", "Priya Sharma", "MANAGER", 1.1),
    ("kabir", "Kabir Mehta", "MANAGER", 1.2),
    ("arjun", "Arjun Rao", "MEMBER", 1.4),
    ("asha", "Asha Nair", "MEMBER", 1.25),
    ("ravi", "Ravi Kumar", "MEMBER", 0.9),
    ("sana", "Sana Khan", "MEMBER", 1.1),
    ("dev", "Dev Patel", "MEMBER", 1.5),
    ("neha", "Neha Gupta", "MEMBER", 1.0),
]
PROJECTS = [
    ("PULSE", "Pulse web app", "The customer-facing dashboard.", "priya", ["arjun", "asha", "ravi", "sana"]),
    ("ORBIT", "Orbit integrations", "Partner APIs and webhooks.", "kabir", ["dev", "neha", "ravi"]),
    ("NOVA", "Nova mobile launch", "Responsive mobile experience and launch tasks.", "priya", ["asha", "sana", "neha", "dev"]),
]
TEMPLATES = {
    "bug": [
        ("Fix {thing} crash on {where}", "HIGH"),
        ("Fix login redirect after {where} refresh", "HIGH"),
        ("Error when exporting {thing}", "HIGH"),
        ("Security: sanitise {thing} input", "URGENT"),
        ("Outage: {thing} service timeouts", "URGENT"),
    ],
    "feature": [
        ("Add {thing} filter to {where}", "MEDIUM"),
        ("Export {thing} as CSV", "MEDIUM"),
        ("Build {thing} settings page", "MEDIUM"),
        ("Support bulk {thing} actions", "LOW"),
    ],
    "backend": [("Optimise {thing} query", "MEDIUM"), ("Add pagination to {thing} endpoint", "MEDIUM"), ("Migrate {thing} table", "HIGH")],
    "frontend": [
        ("Polish {thing} empty state", "LOW"),
        ("Responsive layout for {where}", "MEDIUM"),
        ("Keyboard shortcuts for {thing}", "LOW"),
    ],
    "docs": [("Document {thing} API", "LOW"), ("Write onboarding guide for {where}", "LOW")],
    "infra": [("Upgrade {thing} dependencies", "MEDIUM"), ("Add CI check for {thing}", "MEDIUM"), ("Backup script for {thing}", "LOW")],
}
THINGS = [
    "invoice",
    "report",
    "webhook",
    "dashboard",
    "user",
    "notification",
    "search",
    "billing",
    "audit log",
    "profile",
    "chart",
    "token",
]
WHERES = ["the board", "mobile", "settings", "the API", "checkout", "the admin panel", "onboarding", "the project page"]
COMMENTS = [
    "Blocked until the API review is done — reviewer on leave.",
    "Scope grew after the design review; estimate was too low.",
    "Pairing with {other} on this tomorrow.",
    "Merged behind a feature flag.",
    "Waiting on credentials from the partner team.",
    "Reproduced locally; root cause is a missing index.",
    "QA found an edge case with empty input.",
    "Moved the deadline after the planning meeting.",
]


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None:
        sys.exit(f"{name} is required")
    return value


def connect() -> psycopg.Connection:
    url = env("AI_DATABASE_URL", "postgresql://localhost:5432/apismartpulse")
    return psycopg.connect(url, user=env("DB_OWNER_USER", "asp_owner"), password=env("DB_OWNER_PASSWORD"), autocommit=False)


def half(x: float) -> float:
    return max(0.5, round(x * 2) / 2)


def seed(conn: psycopg.Connection, tasks_per_project: int) -> None:
    password = env("SEED_DEMO_PASSWORD", "")
    if len(password) < 10 or "<" in password:
        sys.exit("SEED_DEMO_PASSWORD must be set to at least 10 characters (not the placeholder)")
    admin_email = env("SEED_ADMIN_EMAIL", "admin@demo.local").strip().lower()
    hashed = bcrypt.hashpw(password.encode(), bcrypt.gensalt(12)).decode()

    users: dict[str, int] = {}
    bias: dict[int, float] = {}
    for local, name, role, b in PEOPLE:
        email = admin_email if local == "admin" else f"{local}@demo.local"
        uid = conn.execute(
            "INSERT INTO users (email, name, password_hash, role, last_login_at) VALUES (%s, %s, %s, %s, NULL) RETURNING id",
            (email, name, hashed, role),
        ).fetchone()[0]
        users[local] = uid
        bias[uid] = b

    for key, pname, desc, owner, members in PROJECTS:
        start = (NOW - timedelta(days=365)).date()
        pid = conn.execute(
            "INSERT INTO projects (key, name, description, owner_id, start_date, target_date, created_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s) RETURNING id",
            (key, pname, desc, users[owner], start, (NOW + timedelta(days=60)).date(), NOW - timedelta(days=365)),
        ).fetchone()[0]
        team = [owner, *members]
        for m in team:
            capacity = 20 if m == "asha" and key == "PULSE" else RNG.choice([30, 40, 40])
            conn.execute("INSERT INTO project_members (project_id, user_id, capacity_hours) VALUES (%s,%s,%s)", (pid, users[m], capacity))
        label_ids = {}
        for lname, color in LABELS:
            label_ids[lname] = conn.execute(
                "INSERT INTO labels (project_id, name, color) VALUES (%s,%s,%s) RETURNING id", (pid, lname, color)
            ).fetchone()[0]
        seed_tasks(conn, pid, key, [users[m] for m in team], bias, label_ids, tasks_per_project, users)


def seed_tasks(conn, pid, key, team, bias, label_ids, n, users):  # type: ignore[no-untyped-def]
    task_ids: list[int] = []
    open_count = max(8, n // 6)
    for i in range(n):
        number = i + 1
        kind = RNG.choice(list(TEMPLATES))
        template, priority = RNG.choice(TEMPLATES[kind])
        title = template.format(thing=RNG.choice(THINGS), where=RNG.choice(WHERES))
        description = f"{title}. Context: requested in the {RNG.choice(['weekly', 'monthly', 'quarterly'])} review."
        is_open = i >= n - open_count
        if is_open:
            created = NOW - timedelta(days=RNG.randint(1, 20), hours=RNG.randint(0, 20))
        else:
            created = NOW - timedelta(days=365) + timedelta(days=(330 * i) // n, hours=RNG.randint(0, 20))
        assignee = RNG.choice(team)
        if is_open and key == "PULSE" and i % 2 == 0:
            assignee = users["asha"]  # makes Asha overloaded for the workload demo
        estimate = RNG.choice([1, 2, 3, 4, 6, 8, 12, 16, 24]) if RNG.random() > 0.1 else None
        if is_open and i % 7 == 0:
            estimate = RNG.choice([16, 20, 24])  # large tasks for "Split this task"
        due = (created + timedelta(days=RNG.randint(3, 21))).date()
        status, started, completed, actual = "TODO", None, None, None
        if not is_open:
            status = "DONE"
            started = created + timedelta(hours=RNG.randint(2, 48))
            base = estimate if estimate is not None else RNG.choice([2, 4, 6])
            actual = half(base * bias[assignee] * RNG.uniform(0.8, 1.25))
            late = RNG.random() < 0.25
            due_dt = datetime.combine(due, datetime.min.time(), UTC)
            completed = due_dt + timedelta(days=RNG.randint(1, 6)) if late else min(due_dt - timedelta(hours=RNG.randint(1, 48)), NOW)
            completed = max(completed, started + timedelta(hours=1))
        elif RNG.random() < 0.4:
            status, started = "IN_PROGRESS", created + timedelta(hours=RNG.randint(1, 30))
        if is_open:
            due = (NOW + timedelta(days=RNG.randint(-3, 21))).date()
        updated = completed or started or created
        tid = conn.execute(
            "INSERT INTO tasks (project_id, number, title, description, status, priority, due_date, assignee_id, creator_id,"
            " estimate_hours, actual_hours, started_at, completed_at, created_at, updated_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING id",
            (
                pid,
                number,
                title,
                description,
                status,
                priority,
                due,
                assignee,
                team[0],
                estimate,
                actual,
                started,
                completed,
                created,
                updated,
            ),
        ).fetchone()[0]
        task_ids.append(tid)
        events = [(tid, pid, "CREATED", None, "TODO", team[0], created)]
        if started:
            events.append((tid, pid, "STATUS_CHANGED", "TODO", "IN_PROGRESS", assignee, started))
        if completed:
            events.append((tid, pid, "STATUS_CHANGED", "IN_PROGRESS", "DONE", assignee, completed))
        conn.cursor().executemany(
            "INSERT INTO task_events (task_id, project_id, event_type, from_value, to_value, actor_id, occurred_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s)",
            events,
        )
        chosen = {kind} | ({RNG.choice(["backend", "frontend"])} if RNG.random() < 0.5 else set())
        for lname in chosen:
            conn.execute("INSERT INTO task_labels (task_id, label_id) VALUES (%s,%s)", (tid, label_ids[lname]))
        if RNG.random() < 0.4:
            for pos in range(RNG.randint(2, 5)):
                conn.execute(
                    "INSERT INTO subtasks (task_id, title, done, position) VALUES (%s,%s,%s,%s)",
                    (
                        tid,
                        f"Step {pos + 1}: {RNG.choice(['investigate', 'implement', 'review', 'test', 'release'])}",
                        status == "DONE" or RNG.random() < 0.3,
                        pos,
                    ),
                )
        if RNG.random() < 0.3:
            for _ in range(RNG.randint(1, 3)):
                at = min(created + timedelta(hours=RNG.randint(1, 72)), NOW)
                body = RNG.choice(COMMENTS).format(other=RNG.choice(["Ravi", "Sana", "Dev"]))
                conn.execute(
                    "INSERT INTO comments (task_id, author_id, body, created_at, updated_at) VALUES (%s,%s,%s,%s,%s)",
                    (tid, RNG.choice(team), body, at, at),
                )
        if len(task_ids) > 5 and RNG.random() < 0.1:
            blocker = RNG.choice(task_ids[:-1])  # always an older task → never a cycle
            conn.execute(
                "INSERT INTO task_dependencies (task_id, blocker_task_id, created_by) VALUES (%s,%s,%s) ON CONFLICT DO NOTHING",
                (tid, blocker, team[0]),
            )
        if status != "DONE":
            conn.execute("INSERT INTO task_predictions (task_id, status) VALUES (%s, 'PENDING')", (tid,))
    conn.execute("UPDATE projects SET next_task_number = %s WHERE id = %s", (n + 1, pid))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--reset", action="store_true", help="truncate application tables first (APP_ENV=local only)")
    parser.add_argument("--small", action="store_true", help="about 60 tasks instead of 600 (CI/E2E)")
    args = parser.parse_args()
    if env("AUTH_PROVIDER", "local") != "local":
        sys.exit("Demo data needs AUTH_PROVIDER=local: seeded users have local passwords, not Neon Auth accounts")
    admin_email = env("SEED_ADMIN_EMAIL", "admin@demo.local").strip().lower()
    with connect() as conn:
        if args.reset:
            if env("APP_ENV", "") != "local":
                sys.exit("--reset is only allowed when APP_ENV=local")
            conn.execute(
                "TRUNCATE users, refresh_tokens, projects, project_members, tasks, subtasks, labels, task_labels, comments,"
                " task_dependencies, task_events, task_predictions, api_keys, task_embeddings, ml_model_versions, ai_sync_queue"
                " RESTART IDENTITY CASCADE"
            )
        elif conn.execute("SELECT 1 FROM users WHERE lower(email) = %s", (admin_email,)).fetchone():
            print("Demo data already present — nothing to do (use --reset to recreate).")
            return
        seed(conn, 20 if args.small else 200)
        conn.commit()
    print("Seeded 3 projects (PULSE, ORBIT, NOVA). Demo logins (password = SEED_DEMO_PASSWORD from your .env):")
    for local, name, role, _ in PEOPLE:
        print(f"  {role:<8} {admin_email if local == 'admin' else local + '@demo.local':<24} {name}")
    print("Next: Admin > Models > Train models, then Re-index embeddings.")


if __name__ == "__main__":
    main()
