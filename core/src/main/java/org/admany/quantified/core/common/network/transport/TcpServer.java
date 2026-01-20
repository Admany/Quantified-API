package org.admany.quantified.core.common.network.transport;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;


public final class TcpServer implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(TcpServer.class.getName());

    private final ServerSocket serverSocket;
    private final ExecutorService acceptor;
    private final Consumer<TcpTransport> onClient;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public TcpServer(int port, Consumer<TcpTransport> onClient) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.onClient = onClient;
        this.acceptor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "quantified-tcp-acceptor-" + port);
            t.setDaemon(true);
            return t;
        });
        this.acceptor.submit(this::acceptLoop);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                TcpTransport transport = TcpTransport.fromAccepted(socket);
                if (onClient != null) {
                    onClient.accept(transport);
                }
            } catch (SocketException se) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "TCP server socket error", se);
                }
                break;
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "TCP server accept error", e);
                }
            }
        }
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        acceptor.shutdownNow();
    }
}
