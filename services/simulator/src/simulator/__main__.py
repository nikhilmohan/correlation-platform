"""Package entrypoint dispatch — ``python -m simulator [serve | --phase …]`` (spec Task 25).

``serve`` routes to the persistent-service entrypoint (:mod:`simulator.serve`); everything else
routes to the existing one-shot CLI (:mod:`simulator.main`). The existing
``python -m simulator.main --phase {p1,p2,p3}`` invocation is unchanged and continues to work
directly. Both launch modes therefore coexist behind one package entrypoint.
"""

from __future__ import annotations

import sys


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] == "serve":
        from simulator import serve

        return serve.main(argv[1:])
    from simulator import main as one_shot

    return one_shot.main(argv)


if __name__ == "__main__":  # pragma: no cover - process entrypoint
    raise SystemExit(main())
