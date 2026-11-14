package local.watchvoice;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;

/** Network operation lifetime; private-file reads retain their existing semantics. */
final class HttpIo implements AutoCloseable {
    private final HttpURLConnection connection;
    private final long deadline;
    private final ScheduledExecutorService timer;
    private final ScheduledFuture<?> expiry;
    private final ExecutorService io;
    private volatile Future<?> pending;
    private volatile boolean cancelled, timedOut, closed;
    HttpIo(HttpURLConnection connection, long deadline) {
        this.connection = connection; this.deadline = deadline;
        long remaining = Math.max(0, deadline - System.nanoTime());
        int milliseconds = (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
        connection.setConnectTimeout(connection.getConnectTimeout() == 0 ? milliseconds : Math.min(milliseconds, connection.getConnectTimeout()));
        connection.setReadTimeout(connection.getReadTimeout() == 0 ? milliseconds : Math.min(milliseconds, connection.getReadTimeout()));
        timer = Executors.newSingleThreadScheduledExecutor(action -> { Thread thread = new Thread(action, "WatchVoice-http-deadline"); thread.setDaemon(true); return thread; });
        io = Executors.newSingleThreadExecutor(action -> { Thread thread = new Thread(action, "WatchVoice-http-io"); thread.setDaemon(true); return thread; });
        expiry = timer.schedule(() -> { timedOut = true; Future<?> task = pending; if (task != null) task.cancel(true); connection.disconnect(); }, remaining, TimeUnit.NANOSECONDS);
    }
    void check() throws IOException {
        if (cancelled || closed || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        if (timedOut || System.nanoTime() >= deadline) throw new IOException("operation_timeout");
    }
    synchronized void cancel() {
        cancelled = true; if (pending != null) pending.cancel(true); abort();
    }
    private void abort() { try { timer.execute(connection::disconnect); } catch (RejectedExecutionException ignored) { } }
    // Only transport work runs here. Parsing, credential writes and callbacks remain on the caller.
    private <T> T call(Callable<T> action) throws IOException {
        Future<T> task;
        synchronized (this) { check(); task = io.submit(action); pending = task; }
        try {
            T value = task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            check(); return value;
        } catch (TimeoutException e) {
            timedOut = true; task.cancel(true); abort(); throw new IOException("operation_timeout");
        } catch (CancellationException e) {
            check(); throw new IOException("cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); cancel(); throw new IOException("cancelled");
        } catch (ExecutionException e) {
            check(); if (e.getCause() instanceof IOException cause) throw cause;
            throw new IOException("network_io");
        } finally { synchronized (this) { if (pending == task) pending = null; } }
    }
    int responseCode() throws IOException { return call(connection::getResponseCode); }
    void send(byte[] body) throws IOException { call(() -> { try (OutputStream output = connection.getOutputStream()) { output.write(body); } return null; }); }
    byte[] readErrorLimited(int limit) throws IOException {return call(() -> readStream(connection.getErrorStream(),limit));}
    byte[] readLimited(int limit) throws IOException {
        return call(() -> readBody(limit));
    }
    private byte[] readBody(int limit) throws IOException {
        check();
        return readStream(connection.getInputStream(),limit);
    }
    private byte[] readStream(InputStream stream,int limit) throws IOException {
        check();if(stream==null)return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while (true) {
                check(); count = input.read(buffer); check();
                if (count == -1) break;
                if (out.size() + count > limit) throw new IOException("response_size");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (IOException e) { check(); throw e; }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; expiry.cancel(false); if (pending != null) pending.cancel(true);
        io.shutdownNow(); abort(); timer.shutdown();
    }
}
