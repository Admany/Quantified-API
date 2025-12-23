package org.admany.quantified.api.model;

import org.admany.quantified.core.common.network.PacketSerializer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class QuantifiedPacketTest {

    @Test
    void dataSyncPayloadAndType() {
        String payload = "hello world";
        QuantifiedPacket qp = QuantifiedPacket.dataSync("text", payload);
        PacketSerializer.Packet p = qp.toPacket("mod", "chan");
        assertThat(p).isInstanceOf(PacketSerializer.DataSyncPacket.class);

        PacketSerializer.DataSyncPacket dp = (PacketSerializer.DataSyncPacket) p;
        assertThat(dp.getDataType()).isEqualTo("text");
        assertThat(new String(dp.getData())).isEqualTo(payload);
    }
}
