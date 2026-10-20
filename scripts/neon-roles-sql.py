"""Prints the one-time SQL that prepares a Neon branch for ApiSmartPulse (run as the branch owner).

Creates the three least-privilege roles with the passwords from .env.neon, enables pgvector and
hands the public schema to asp_owner (Flyway then applies V1..Vn as asp_owner). Neon accepts only plaintext role passwords (it rejects SCRAM
verifiers), so the output is a secret: write it to the gitignored file below, paste it into the Neon
SQL Editor for the branch, then delete it.

    python scripts/neon-roles-sql.py > .env.neon-roles.sql
"""

import re
import sys


def main() -> None:
    path = sys.argv[1] if len(sys.argv) > 1 else ".env.neon"
    env = dict(
        line.strip().split("=", 1)
        for line in open(path, encoding="utf-8")
        if "=" in line and not line.lstrip().startswith("#")
    )
    statements = []
    for role, key in (("asp_owner", "DB_OWNER_PASSWORD"), ("asp_app", "DB_APP_PASSWORD"), ("asp_ai", "DB_AI_PASSWORD")):
        password = env[key]
        if not re.fullmatch(r"[A-Za-z0-9]{20,}", password):
            sys.exit(f"{key} must be 20+ letters/digits (regenerate .env.neon)")
        statements.append(f"CREATE ROLE {role} LOGIN PASSWORD '{password}';")
    statements += [
        "CREATE EXTENSION IF NOT EXISTS vector;",
        "GRANT CONNECT ON DATABASE neondb TO asp_owner, asp_app, asp_ai;",
        "GRANT asp_owner TO CURRENT_USER;",  # needed to transfer schema ownership
        "ALTER SCHEMA public OWNER TO asp_owner;",
        "REVOKE CREATE ON SCHEMA public FROM PUBLIC;",
        "GRANT USAGE ON SCHEMA public TO asp_app, asp_ai;",
    ]
    print("\n".join(statements))


if __name__ == "__main__":
    main()
