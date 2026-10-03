# Board and valid movement

Goal: decide whether an adjacent coordinate is inside a rectangular board.
`Board(int width, int height)` exposes `contains(Coordinate coordinate)`.
Valid x values run from 0 through width minus 1; valid y values run from 0
through height minus 1.

`Movement.move(Coordinate current, Direction direction, Board board)`
returns a `MoveResult(Coordinate coordinate, MoveStatus status)`.
An allowed move returns the candidate position with `MOVED`.
A blocked move returns the original position with `BLOCKED`.

For a 3 by 2 board, moving right from (1, 1) reaches (2, 1).
Moving right from (2, 1) remains at (2, 1) and reports `BLOCKED`.

Read the supplied boundary cases and complete the clearly marked analogous
JUnit case in the editable template. Your template must change and compile;
the independent completion check verifies the associated public behavior.
Explain why your chosen boundary is valid or blocked.

Run `fruit-and-faults check`, review the diff, and use
`feat: enforce board boundaries` for the local commit.
