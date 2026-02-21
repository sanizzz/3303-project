package types;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public class UdpUtil {
    public static void send(String host, int port, String payload) {
        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), port);
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.send(packet);
            }
        } catch (Exception e) {
            System.err.println("[UDP] Send error: " + e.getMessage());
        }
    }

    public static String receive(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
    }
}
