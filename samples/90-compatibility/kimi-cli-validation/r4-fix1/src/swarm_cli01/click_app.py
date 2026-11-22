from pathlib import Path

import click


@click.command()
@click.option("--name", required=True, envvar="SWARM_NAME", help="Text to write.")
@click.option(
    "--count",
    type=click.IntRange(1, 3),
    default=1,
    envvar="SWARM_COUNT",
    show_default=True,
    help="Number of lines to write.",
)
@click.option("--upper", is_flag=True, help="Uppercase the written text.")
@click.argument("output", type=click.Path(path_type=Path))
def click_app(name: str, count: int, upper: bool, output: Path) -> None:
    """Write NAME to OUTPUT COUNT times."""
    value = name.upper() if upper else name
    try:
        output.write_text("\n".join([value] * count) + "\n", encoding="utf-8")
    except OSError as exc:
        raise click.ClickException(
            f"cannot write {output.name}: {exc.strerror}"
        ) from exc
    click.echo(f"wrote={output.name};count={count};upper={str(upper).lower()}")


if __name__ == "__main__":
    click_app()
