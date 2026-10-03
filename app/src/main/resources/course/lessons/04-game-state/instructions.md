# Game state

Goal: implement `GameState(Coordinate player, int successfulMoves)` with
`GameState move(Direction direction, Board board)`.

Use the movement result from the preceding lesson. A successful movement
changes the player coordinate and increments successfulMoves by one.
A blocked movement preserves both the player coordinate and successfulMoves.
Return the resulting immutable state and preserve the original value.

For a 3 by 2 board, a player at (1, 1) with count 4 moves right to (2, 1)
with count 5. A further right command stays at (2, 1) with count 5.

Read the cumulative visible tests. Distinguish a movement calculation from
a transition of the complete game state, then run `fruit-and-faults check`.
All earlier lesson checks remain part of completion.

Review the diff and use `feat: track successful game moves` for the local
commit. Be ready to explain the successful-move count in the reflection.
