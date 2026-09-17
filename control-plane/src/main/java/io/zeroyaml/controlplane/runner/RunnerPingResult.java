package io.zeroyaml.controlplane.runner;

/**
 * The values returned by a successful Runner Ping RPC.
 */
public record RunnerPingResult(String message, String runnerVersion) {
}
