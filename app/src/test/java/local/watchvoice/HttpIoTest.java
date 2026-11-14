package local.watchvoice;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.Assert.*;

public class HttpIoTest {
    // Catches a continuous body retaining the credential lock beyond the absolute deadline.
    @Test public void continuousBodyDeadlineReleasesLockForSecondClient() throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(2);
        ReentrantLock authLock = new ReentrantLock(); CountDownLatch firstOwnsLock = new CountDownLatch(1), secondFinished = new CountDownLatch(1);
        try (Server server = new Server(); HttpIo first = new HttpIo(server.connection("/continuous"), after(250))) {
            Future<String> firstResult = clients.submit(() -> {
                authLock.lock(); firstOwnsLock.countDown();
                try { first.readLimited(4096); return "unexpected_success"; }
                catch (IOException e) { return e.getMessage(); }
                finally { authLock.unlock(); }
            });
            assertTrue(firstOwnsLock.await(1, TimeUnit.SECONDS));
            Future<byte[]> secondResult = clients.submit(() -> {
                authLock.lock();
                try (HttpIo second = new HttpIo(server.connection("/fast"), after(2000))) { return second.readLimited(4096); }
                finally { authLock.unlock(); secondFinished.countDown(); }
            });
            assertTrue("slow body must release the lock before its 1.5 second stream ends", secondFinished.await(900, TimeUnit.MILLISECONDS));
            assertEquals("operation_timeout", firstResult.get(1, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{'d', 'a', 't', 'a'}, secondResult.get(1, TimeUnit.SECONDS));
        } finally { clients.shutdownNow(); }
    }
    // Catches a body read blocked after headers outliving the operation deadline.
    @Test public void deadlineInterruptsBlockedBodyRead() throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor(); CountDownLatch finished = new CountDownLatch(1);
        try (Server server = new Server(); HttpIo request = new HttpIo(server.connection("/blocked"), after(250))) {
            Future<String> result = client.submit(() -> {
                try { request.readLimited(4096); return "unexpected_success"; }
                catch (IOException e) { return e.getMessage(); }
                finally { finished.countDown(); }
            });
            assertTrue(server.headersSent.await(1, TimeUnit.SECONDS));
            assertTrue("deadline must abort an in-flight read", finished.await(900, TimeUnit.MILLISECONDS));
            assertEquals("operation_timeout", result.get(1, TimeUnit.SECONDS));
        } finally { client.shutdownNow(); }
    }
    // Catches a late fragment resetting inactivity time while the absolute watchdog fails to abort.
    @Test public void deadlineInterruptsAfterLateBodyFragment() throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor(); CountDownLatch finished = new CountDownLatch(1);
        try (Server server = new Server(); HttpIo request = new HttpIo(server.connection("/lateblocked"), after(700))) {
            Future<String> result = client.submit(() -> {
                try { request.readLimited(4096); return "unexpected_success"; }
                catch (IOException e) { return e.getMessage(); }
                finally { finished.countDown(); }
            });
            assertTrue(server.headersSent.await(1, TimeUnit.SECONDS));
            assertTrue("absolute deadline must abort a later read before a new inactivity timeout", finished.await(950, TimeUnit.MILLISECONDS));
            assertEquals("operation_timeout", result.get(1, TimeUnit.SECONDS));
        } finally { client.shutdownNow(); }
    }
    // Catches explicit Stop leaving an in-flight response alive until its socket timeout.
    @Test public void cancellationInterruptsBlockedBodyRead() throws Exception {
        ExecutorService client = Executors.newSingleThreadExecutor(); CountDownLatch finished = new CountDownLatch(1);
        try (Server server = new Server(); HttpIo request = new HttpIo(server.connection("/blocked"), after(5000))) {
            Future<String> result = client.submit(() -> {
                try { request.readLimited(4096); return "unexpected_success"; }
                catch (IOException e) { return e.getMessage(); }
                finally { finished.countDown(); }
            });
            assertTrue(server.headersSent.await(1, TimeUnit.SECONDS)); long cancellationStarted = System.nanoTime(); request.cancel();
            assertTrue("Stop must abort an in-flight read", finished.await(900, TimeUnit.MILLISECONDS));
            assertTrue("cancellation must not block waiting for the remote body", System.nanoTime() - cancellationStarted < TimeUnit.MILLISECONDS.toNanos(900));
            assertEquals("cancelled", result.get(1, TimeUnit.SECONDS));
        } finally { client.shutdownNow(); }
    }
    // Catches dropping the byte ceiling while adding deadline handling.
    @Test public void successfulBodyPreservesByteLimit() throws Exception {
        try (Server server = new Server(); HttpIo request = new HttpIo(server.connection("/fast"), after(2000))) {
            IOException error = assertThrows(IOException.class, () -> request.readLimited(3));
            assertEquals("response_size", error.getMessage());
        }
    }
    @Test public void boundedErrorBodyUsesDeadlineOwnerAndPreserves429() throws Exception {
        try(Server server=new Server();HttpIo request=new HttpIo(server.connection("/limited"),after(2000))){
            assertEquals(429,request.responseCode());assertEquals("{\"detail\":\"Too many requests\"}",new String(request.readErrorLimited(1024),StandardCharsets.UTF_8));
        }
        try(Server server=new Server();HttpIo request=new HttpIo(server.connection("/limited"),after(2000))){
            assertEquals(429,request.responseCode());assertEquals("response_size",assertThrows(IOException.class,()->request.readErrorLimited(3)).getMessage());
        }
    }
    private static long after(long milliseconds) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds); }

    /** Real local server: continuous small writes never reach the client's inactivity timeout. */
    private static final class Server implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        final ExecutorService workers = Executors.newCachedThreadPool();
        final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
        final CountDownLatch headersSent = new CountDownLatch(1);
        Server() throws IOException {
            workers.submit(() -> {
                try { while (!listener.isClosed()) { Socket socket = listener.accept(); sockets.add(socket); workers.submit(() -> serve(socket)); } }
                catch (IOException ignored) { }
            });
        }
        HttpURLConnection connection(String path) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + listener.getLocalPort() + path).openConnection();
            connection.setConnectTimeout(2000); connection.setReadTimeout(5000); return connection;
        }
        void serve(Socket socket) {
            try (Socket closeable = socket) {
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String requestLine = input.readLine(), line;
                while ((line = input.readLine()) != null && !line.isEmpty()) { }
                OutputStream output = socket.getOutputStream();
                if(requestLine.contains("/limited")){byte[] body="{\"detail\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8);output.write(("HTTP/1.1 429 Too Many Requests\r\nContent-Length: "+body.length+"\r\nRetry-After: 30\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();return;}
                boolean continuous = requestLine.contains("/continuous"), blocked = requestLine.contains("/blocked"), lateBlocked = requestLine.contains("/lateblocked");
                output.write(("HTTP/1.1 200 OK\r\nContent-Length: " + (continuous ? 240 : lateBlocked ? 8 : 4) + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); output.flush(); headersSent.countDown();
                if (lateBlocked) { Thread.sleep(450); output.write(new byte[]{'d', 'a', 't', 'a'}); output.flush(); Thread.sleep(1500); }
                if (blocked) Thread.sleep(1500);
                for (int i = 0; i < (continuous ? 60 : 1); i++) { output.write(new byte[]{'d', 'a', 't', 'a'}); output.flush(); if (continuous) Thread.sleep(25); }
            } catch (IOException ignored) { }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void close() throws IOException { listener.close(); for (Socket socket : sockets) socket.close(); workers.shutdownNow(); }
    }
}
