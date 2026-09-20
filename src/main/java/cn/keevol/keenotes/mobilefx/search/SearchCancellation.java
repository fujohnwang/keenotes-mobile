package cn.keevol.keenotes.mobilefx.search;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Cancels this query's HTTP call without interrupting shared Lucene IO. */
public final class SearchCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> action = new AtomicReference<>();
    public void cancel() {
        cancelled.set(true);
        Runnable callback = action.getAndSet(null);
        if (callback != null) callback.run();
    }
    public void check() { if (cancelled.get()) throw new CancellationException(); }
    void attach(Runnable callback) {
        action.set(callback);
        if (cancelled.get()) { Runnable pending = action.getAndSet(null); if (pending != null) pending.run(); }
        check();
    }
    void detach() { action.set(null); }
}
