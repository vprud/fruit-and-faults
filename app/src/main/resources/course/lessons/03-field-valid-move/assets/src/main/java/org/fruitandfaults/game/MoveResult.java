package org.fruitandfaults.game;

/** The resulting coordinate and explicit movement outcome. */
public record MoveResult(Coordinate coordinate, MoveStatus status) {}
