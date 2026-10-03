# Coordinate and direction

Goal: represent a position as an immutable `Coordinate(int x, int y)` record
and implement `Coordinate move(Direction direction)`. `Direction` has
`UP`, `RIGHT`, `DOWN`, and `LEFT`.

Use screen coordinates: x increases to the right and y increases downward.
A movement changes one axis by one step and leaves the other axis unchanged.
For example, moving right from (2, 3) produces (3, 3); the original value
remains (2, 3).

Read the visible Arrange–Act–Assert examples, complete the learner scaffold,
and run `fruit-and-faults check`. Check all four directions and preservation
of the original coordinate. Earlier lesson checks still apply.

Review your diff and use `feat: add coordinate movement` for the local commit.
