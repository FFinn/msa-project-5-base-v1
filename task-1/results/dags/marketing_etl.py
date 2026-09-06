from __future__ import annotations

import csv
from datetime import datetime, timedelta
from pathlib import Path

from airflow import DAG
from airflow.exceptions import AirflowException
from airflow.operators.empty import EmptyOperator
from airflow.operators.email import EmailOperator
from airflow.operators.python import BranchPythonOperator, PythonOperator
from airflow.utils.trigger_rule import TriggerRule

DATA_FILE = Path("/opt/airflow/data/orders.csv")


def read_source(**context) -> dict:
    """Read a small demo CSV and return only an aggregate summary via XCom."""
    if context["params"].get("force_failure"):
        raise AirflowException("Controlled failure requested for failure-email demo")

    rows = 0
    total_amount = 0.0
    with DATA_FILE.open(encoding="utf-8", newline="") as file:
        reader = csv.DictReader(file)
        for row in reader:
            rows += 1
            total_amount += float(row["amount"])

    return {"rows": rows, "total_amount": round(total_amount, 2)}


def choose_branch(ti, **_) -> str:
    summary = ti.xcom_pull(task_ids="read_source")
    if summary["total_amount"] >= 1000:
        return "high_value_processing"
    return "regular_processing"


def log_high_value(ti, **_) -> None:
    summary = ti.xcom_pull(task_ids="read_source")
    print(f"High-value batch selected: {summary}")


def log_regular(ti, **_) -> None:
    summary = ti.xcom_pull(task_ids="read_source")
    print(f"Regular batch selected: {summary}")


with DAG(
    dag_id="marketing_batch_poc",
    description="POC: source -> analysis -> branch -> email, with retry policy",
    start_date=datetime(2026, 1, 1),
    schedule=None,
    catchup=False,
    params={"force_failure": False},
    default_args={
        "owner": "data-platform",
        "retries": 2,
        "retry_delay": timedelta(seconds=15),
        "retry_exponential_backoff": True,
        "max_retry_delay": timedelta(minutes=2),
    },
    tags=["sprint-5", "poc", "batch"],
) as dag:
    read = PythonOperator(
        task_id="read_source",
        python_callable=read_source,
    )

    branch = BranchPythonOperator(
        task_id="branch_on_amount",
        python_callable=choose_branch,
    )

    high_value = PythonOperator(
        task_id="high_value_processing",
        python_callable=log_high_value,
    )

    regular = PythonOperator(
        task_id="regular_processing",
        python_callable=log_regular,
    )

    join = EmptyOperator(
        task_id="join",
        trigger_rule=TriggerRule.NONE_FAILED_MIN_ONE_SUCCESS,
    )

    success_email = EmailOperator(
        task_id="success_email",
        to="marketing@example.local",
        subject="[OK] marketing_batch_poc completed",
        html_content=(
            "DAG {{ dag.dag_id }} completed successfully. "
            "Run: {{ run_id }}"
        ),
    )

    failure_email = EmailOperator(
        task_id="failure_email",
        to="data-team@example.local",
        subject="[FAILED] marketing_batch_poc",
        html_content=(
            "DAG {{ dag.dag_id }} has a failed task. "
            "Run: {{ run_id }}"
        ),
        trigger_rule=TriggerRule.ONE_FAILED,
        retries=0,
    )

    read >> branch >> [high_value, regular] >> join
    join >> [success_email, failure_email]
