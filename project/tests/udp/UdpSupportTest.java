package udp;

import org.junit.jupiter.api.Test;
import types.UdpConfig;
import types.UdpUtil;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UdpSupportTest {

    @Test
    void assignsDistinctCommandPortsForDifferentDrones() {
        // Multi-drone UDP launcher rule under test:
        // each drone process needs its own command port so commands do not collide.
        assertEquals(5003, UdpConfig.commandPortForDrone(1));
        assertEquals(5004, UdpConfig.commandPortForDrone(2));
        assertEquals(5005, UdpConfig.commandPortForDrone(3));
    }

    @Test
    void sendsAndReceivesUdpPayloadWithoutCorruption() throws Exception {
        // UDP utility rule under test:
        // subsystem payloads should survive a local loopback send/receive unchanged.
        try (DatagramSocket receiver = new DatagramSocket(0)) {
            receiver.setSoTimeout(2000);
            int port = receiver.getLocalPort();
            String payload = "STATUS|1|EN_ROUTE|9|12.500|590.000|100.000|50.000|en route";

            UdpUtil.send("localhost", port, payload);

            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            receiver.receive(packet);
            String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

            assertEquals(payload, received);
        } catch (SocketTimeoutException e) {
            throw new AssertionError("Timed out waiting for loopback UDP payload.", e);
        }
    }
}
