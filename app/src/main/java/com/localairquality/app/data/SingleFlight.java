package com.localairquality.app.data;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Share one in-flight operation without letting a completed failure prevent retries. */
public final class SingleFlight<T> {
    private final Executor executor;
    private CompletableFuture<T> active;
    public SingleFlight(Executor executor) { this.executor = executor; }
    public synchronized CompletableFuture<T> run(Supplier<T> operation) {
        if (active == null || active.isDone()) active = CompletableFuture.supplyAsync(operation, executor);
        return active;
    }
}
