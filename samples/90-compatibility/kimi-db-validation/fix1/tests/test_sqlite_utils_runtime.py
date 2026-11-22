from __future__ import annotations

import sqlite3

import pytest
from sqlite_utils import Database


@pytest.fixture
def db(tmp_path):
    database = Database(tmp_path / "sqlite-utils-runtime.db")
    try:
        yield database
    finally:
        database.conn.close()


def create_items(db):
    table = db["items"]
    table.create({"id": int, "name": str, "score": float}, pk="id")
    return table


def lists(rows):
    return [list(row) for row in rows]


@pytest.mark.case_id("DB02-SU-SCHEMA-001")
def test_schema_insert_and_query(db, case):
    fixed = case["fixed_input"]
    type_map = {"int": int, "str": str, "float": float}
    schema = {name: type_map[type_name] for name, type_name in fixed["schema"].items()}
    table = db["items"].create(schema, pk="id")
    table.insert_all(fixed["rows"])
    pragma_rows = db.execute("PRAGMA table_info(items)").fetchall()
    observed_schema = [[row[1], row[2], row[5]] for row in pragma_rows]
    observed_rows = db.execute(
        "SELECT id, name, score FROM items ORDER BY id"
    ).fetchall()
    case["observed"] = {"schema": observed_schema, "rows": lists(observed_rows)}
    assert case["observed"] == case["expected"]


@pytest.mark.case_id("DB02-SU-PK-002")
def test_duplicate_pk_error_and_recovery(db, case):
    fixed = case["fixed_input"]
    table = create_items(db)
    table.insert(fixed["original"])
    error = None
    try:
        table.insert(fixed["duplicate"])
    except sqlite3.IntegrityError as exc:
        error = exc
    assert error is not None
    table.insert(fixed["recovered"])
    rows = db.execute("SELECT id, name, score FROM items ORDER BY id").fetchall()
    case["observed"] = {
        "error_type": type(error).__name__,
        "error_mentions_pk": "items.id" in str(error),
        "after_recovery": lists(rows),
    }
    case["diagnostics"] = {"exception_message": str(error), "exception_repr": repr(error)}
    assert case["observed"] == case["expected"]


@pytest.mark.case_id("DB02-SU-TYPES-003")
def test_type_affinity_conversion_boundary(db, case):
    table = create_items(db)
    table.insert_all(case["fixed_input"]["rows"])
    rows = db.execute(
        """
        SELECT
            id, typeof(id),
            name, typeof(name),
            score, typeof(score)
        FROM items
        ORDER BY id
        """
    ).fetchall()
    case["observed"] = {"rows": lists(rows)}
    assert case["observed"] == case["expected"]


@pytest.mark.case_id("DB02-SU-SEQUENCE-004")
def test_consecutive_operations_and_reopen_consistency(tmp_path, case):
    fixed = case["fixed_input"]
    database_path = tmp_path / "sqlite-utils-sequence.db"
    db = Database(database_path)
    table = create_items(db)
    table.insert_all(fixed["initial_rows"])
    table.upsert(fixed["upsert"])
    table.update(fixed["update_id"], fixed["update_values"])
    table.delete(fixed["delete_id"])
    table.insert(fixed["final_insert"])
    db.conn.close()

    reopened = Database(database_path)
    try:
        final_rows = reopened.execute(
            "SELECT id, name, score FROM items ORDER BY id"
        ).fetchall()
        row_count = reopened.execute("SELECT count(*) FROM items").fetchone()[0]
    finally:
        reopened.conn.close()
    case["observed"] = {
        "database_file_exists": database_path.is_file(),
        "row_count": row_count,
        "final_rows": lists(final_rows),
    }
    assert case["observed"] == case["expected"]
