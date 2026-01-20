package org.admany.quantified.core.common.network.transport;

import java.io.Closeable;

public interface DataTransport extends Closeable {

    void send(byte[] data) throws Exception;

    void setReceiveHandler(java.util.function.Consumer<byte[]> handler);

    boolean isOpen();

    @Override
    void close();
}
