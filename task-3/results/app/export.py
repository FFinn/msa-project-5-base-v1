from __future__ import annotations

import csv
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

import psycopg2


def required_env(name: str) -> str:
    value = os.getenv(name)
    if not value:
        raise RuntimeError(f"Environment variable {name} is required")
    return value


def main() -> None:
    output_dir = Path(os.getenv("OUTPUT_DIR", "/output"))
    output_dir.mkdir(parents=True, exist_ok=True)

    business_tz = ZoneInfo(os.getenv("BUSINESS_TIMEZONE", "Europe/Moscow"))
    run_date = os.getenv("RUN_DATE") or datetime.now(business_tz).date().isoformat()
    final_path = output_dir / f"shipments-{run_date}.csv"
    temp_path = output_dir / f".{final_path.name}.tmp"

    connection = psycopg2.connect(
        host=required_env("DB_HOST"),
        port=int(os.getenv("DB_PORT", "5432")),
        dbname=required_env("DB_NAME"),
        user=required_env("DB_USER"),
        password=required_env("DB_PASSWORD"),
        connect_timeout=10,
        application_name="shipments-exporter",
    )
    connection.set_session(readonly=True, autocommit=False)

    rows_exported = 0
    started_at = datetime.now(timezone.utc)

    try:
        # Named cursor keeps the result on the server and streams it in batches.
        with connection.cursor(name="shipments_export_cursor") as cursor:
            cursor.itersize = 5000
            cursor.execute(
                """
                SELECT id, client_id, driver_id, vehicle_id, status, created_at
                FROM shipments
                ORDER BY id
                """
            )

            with temp_path.open("w", encoding="utf-8", newline="") as file:
                writer = csv.writer(file)
                writer.writerow(
                    ["id", "client_id", "driver_id", "vehicle_id", "status", "created_at"]
                )
                for row in cursor:
                    writer.writerow(row)
                    rows_exported += 1

        os.replace(temp_path, final_path)
        duration_ms = int((datetime.now(timezone.utc) - started_at).total_seconds() * 1000)
        print(
            json.dumps(
                {
                    "event": "shipment_export_completed",
                    "status": "success",
                    "run_date": run_date,
                    "rows_exported": rows_exported,
                    "file": str(final_path),
                    "duration_ms": duration_ms,
                },
                ensure_ascii=False,
            )
        )
    except Exception as exc:
        if temp_path.exists():
            temp_path.unlink()
        print(
            json.dumps(
                {
                    "event": "shipment_export_failed",
                    "status": "failed",
                    "run_date": run_date,
                    "error_type": type(exc).__name__,
                    "message": str(exc),
                },
                ensure_ascii=False,
            ),
            file=sys.stderr,
        )
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
