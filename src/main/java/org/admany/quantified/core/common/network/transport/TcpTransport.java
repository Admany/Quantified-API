package org.admany.quantified.core.common.network.transport;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TcpTransport implements DataTransport, Runnable {
    private static final Logger LOGGER = Logger.getLogger(TcpTransport.class.getName());
    private static final int MAX_PACKET_SIZE = 8 * 1024 * 1024;

    private final Socket socket;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Object sendLock = new Object();

    private volatile Consumer<byte[]> receiver;
    private Thread readerThread;

    private TcpTransport(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.socket.setSoTimeout(1000);
        startReader();
    }

    public static TcpTransport connect(String host, int port) throws IOException {
        Socket socket = new Socket(host, port);
        return new TcpTransport(socket);
    }

    static TcpTransport fromAccepted(Socket socket) throws IOException {
        return new TcpTransport(socket);
    }

    private void startReader() {
        readerThread = new Thread(this, "quantified-tcp-reader-" + socket.getPort());
        readerThread.setDaemon(true);
        readerThread.start();
    }

    @Override
    public void run() {
        try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
            while (open.get()) {
                int len;
                try {
                    len = in.readInt();
                } catch (SocketTimeoutException e) {
                    continue;
                } catch (EOFException eof) {
                    break;
                }
                if (len <= 0 || len > MAX_PACKET_SIZE) {
                    LOGGER.warning("Dropping invalid frame length: " + len);
                    break;
                }
                byte[] data = new byte[len];
                in.readFully(data);
                Consumer<byte[]> handler = receiver;
                if (handler != null) {
                    handler.accept(data);
                }
            }
        } catch (IOException e) {
            if (open.get()) {
                LOGGER.log(Level.WARNING, "TCP transport read error", e);
            }
        } finally {
            close();
        }
    }

    @Override
    public void send(byte[] data) throws Exception {
        if (data == null) return;
        if (!open.get()) throw new IOException("Transport closed");
        synchronized (sendLock) {
            try {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeInt(data.length);
                out.write(data);
                out.flush();
            } catch (IOException e) {
                close();
                throw e;
            }
        }
    }

    @Override
    public void setReceiveHandler(Consumer<byte[]> handler) {
        this.receiver = handler;
    }

    @Override
    public boolean isOpen() {
        return open.get() && !socket.isClosed();
    }

    @Override
    public void close() {
        if (!open.getAndSet(false)) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
