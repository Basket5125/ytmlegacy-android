package com.ytmlegacy.android.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Runs work off the main thread and hands the result back on it. */
public abstract class Task<T> {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3, new ThreadFactory() {
        private int n;

        @Override
        public synchronized Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ui-task-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    private volatile boolean cancelled;

    protected abstract T work() throws Exception;

    protected abstract void done(T result);

    protected void failed(Exception e) { }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public Task<T> start() {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final T result = work();
                    if (cancelled) return;
                    Ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled) done(result);
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.w("Task", "failed: " + e);
                    if (cancelled) return;
                    Ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!cancelled) failed(e);
                        }
                    });
                }
            }
        });
        return this;
    }
}
