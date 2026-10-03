package org.fruitandfaults.cli;

/**
 * The exit status and separate output channels of a command.
 *
 * @param exitCode process exit status
 * @param stdout human-oriented output
 * @param stderr diagnostic output
 */
public record CommandResult(ExitCode exitCode, String stdout, String stderr) {}
