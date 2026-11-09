import json
import os
from pathlib import Path
import subprocess
import sys

from click.testing import CliRunner

from swarm_cli01.click_app import click_app


def _observation(result, output_name):
    output = Path(output_name)
    return {
        "exit_code": result.exit_code,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "output_exists": output.exists(),
        "output_text": output.read_text(encoding="utf-8") if output.exists() else None,
        "exception": repr(result.exception) if result.exception else None,
    }


def test_clk_001_cli_overrides_env_and_writes_file(record_property):
    expected = {
        "exit_code": 0,
        "stdout": "wrote=result.txt;count=3;upper=true\n",
        "stderr": "",
        "output_text": "ENV-BOT\nENV-BOT\nENV-BOT\n",
    }
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            click_app,
            ["--count", "3", "--upper", "result.txt"],
            env={"SWARM_NAME": "env-bot", "SWARM_COUNT": "2"},
            catch_exceptions=False,
        )
        observed = _observation(result, "result.txt")
        record_property("observed", json.dumps(observed, ensure_ascii=False))
        assert result.exit_code == expected["exit_code"]
        assert result.stdout == expected["stdout"]
        assert result.stderr == expected["stderr"]
        assert observed["output_text"] == expected["output_text"]


def test_clk_002_explicit_name_and_default_count(record_property):
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            click_app,
            ["--name", "default-bot", "default.txt"],
            env={"SWARM_NAME": None, "SWARM_COUNT": None},
            catch_exceptions=False,
        )
        observed = _observation(result, "default.txt")
        record_property("observed", json.dumps(observed, ensure_ascii=False))
        assert result.exit_code == 0
        assert result.stdout == "wrote=default.txt;count=1;upper=false\n"
        assert result.stderr == ""
        assert observed["output_text"] == "default-bot\n"


def test_clk_003_missing_required_option_is_rejected(record_property):
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(click_app, ["missing.txt"], env={"SWARM_NAME": None, "SWARM_COUNT": None})
        observed = _observation(result, "missing.txt")
        record_property("observed", json.dumps(observed, ensure_ascii=False))
        assert result.exit_code == 2
        assert result.stdout == ""
        assert "Missing option '--name'." in result.stderr
        assert not observed["output_exists"]


def test_clk_004_unknown_option_is_rejected(record_property):
    runner = CliRunner()
    with runner.isolated_filesystem():
        result = runner.invoke(
            click_app,
            ["--name", "n", "--bogus", "x", "bad.txt"],
            env={"SWARM_NAME": None, "SWARM_COUNT": None},
        )
        observed = _observation(result, "bad.txt")
        record_property("observed", json.dumps(observed, ensure_ascii=False))
        assert result.exit_code == 2
        assert result.stdout == ""
        assert "No such option '--bogus'." in result.stderr
        assert not observed["output_exists"]


def test_clk_005_file_error_then_next_invocation_succeeds(record_property):
    runner = CliRunner()
    with runner.isolated_filesystem():
        failed = runner.invoke(
            click_app,
            ["--name", "recovery", "missing/out.txt"],
            env={"SWARM_NAME": None, "SWARM_COUNT": None},
        )
        failed_observed = _observation(failed, "missing/out.txt")
        assert failed.exit_code == 1
        assert failed.stdout == ""
        assert "Error: cannot write out.txt:" in failed.stderr
        assert not failed_observed["output_exists"]

        succeeded = runner.invoke(
            click_app,
            ["--name", "recovery", "out.txt"],
            env={"SWARM_NAME": None, "SWARM_COUNT": None},
            catch_exceptions=False,
        )
        success_observed = _observation(succeeded, "out.txt")
        record_property(
            "observed",
            json.dumps(
                {"failed": failed_observed, "recovered": success_observed},
                ensure_ascii=False,
            ),
        )
        assert succeeded.exit_code == 0
        assert succeeded.stdout == "wrote=out.txt;count=1;upper=false\n"
        assert succeeded.stderr == ""
        assert success_observed["output_text"] == "recovery\n"


def test_clk_006_real_subprocess_invocation(record_property, tmp_path):
    root = Path(__file__).resolve().parents[2]
    env = os.environ.copy()
    env.pop("SWARM_NAME", None)
    env.pop("SWARM_COUNT", None)
    env["PYTHONPATH"] = str(root / "src")
    command = [
        sys.executable,
        "-m",
        "swarm_cli01.click_app",
        "--name",
        "process",
        "--count",
        "2",
        "process.txt",
    ]
    completed = subprocess.run(
        command,
        cwd=tmp_path,
        env=env,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=20,
        check=False,
    )
    observed = {
        "command": command,
        "exit_code": completed.returncode,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
        "output_text": (tmp_path / "process.txt").read_text(encoding="utf-8")
        if (tmp_path / "process.txt").exists()
        else None,
    }
    record_property("observed", json.dumps(observed, ensure_ascii=False))
    assert completed.returncode == 0
    assert completed.stdout == "wrote=process.txt;count=2;upper=false\n"
    assert completed.stderr == ""
    assert observed["output_text"] == "process\nprocess\n"
