package com.github.ma1co.openmemories.tweak;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * Pure-Java RFC 4330 SNTPv4 packet encoding/decoding and injection-safe BusyBox
 * date command formatting (Android API 10 compatible, zero external dependencies).
 */
final class SntpClientPolicy {
    static final int NTP_PORT = 123;
    static final int PACKET_SIZE = 48;
    static final int LAN_TIMEOUT_MS = 1000;
    static final int WAN_TIMEOUT_MS = 3000;
    static final long OFFSET_1900_TO_1970 = 2208988800L;
    static final long MIN_VALID_EPOCH_MS = 1577836800000L; // 2020-01-01T00:00:00Z
    static final String DEFAULT_NTP_HOST = "pool.ntp.org";
    static final String FALLBACK_NTP_HOST = "time.cloudflare.com";

    private SntpClientPolicy() {}

    /**
     * Use a shorter timeout for private/local IPv4 gateways (which answer in <5ms if serving NTP
     * or silently drop UDP/123) so fallback to public NTP pools completes quickly.
     */
    static int timeoutForHostMs(String host) {
        if (host == null) return WAN_TIMEOUT_MS;
        String h = host.trim();
        if (h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.") || h.startsWith("127.")) {
            return LAN_TIMEOUT_MS;
        }
        if (h.startsWith("172.")) {
            int dot = h.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(h.substring(4, dot));
                    if (second >= 16 && second <= 31) return LAN_TIMEOUT_MS;
                } catch (NumberFormatException ignored) {}
            }
        }
        return WAN_TIMEOUT_MS;
    }

    static long queryNtpServer(String host, int port, int timeoutMs) throws IOException {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(host);
            byte[] req = buildRequestPacket();
            socket.send(new DatagramPacket(req, req.length, addr, port));
            byte[] resp = new byte[PACKET_SIZE];
            DatagramPacket respPacket = new DatagramPacket(resp, resp.length);
            socket.receive(respPacket);
            return parseTransmitTimeMs(resp, respPacket.getLength());
        } finally {
            if (socket != null) socket.close();
        }
    }

    /** Build a 48-byte SNTPv4 client mode request packet (LI=0, VN=4, Mode=3 -> 0x23). */
    static byte[] buildRequestPacket() {
        byte[] pkt = new byte[PACKET_SIZE];
        pkt[0] = 0x23;
        return pkt;
    }

    /**
     * Parse the Transmit Timestamp (bytes 40..47) of a 48-byte SNTP response into Unix epoch ms.
     * Returns -1L on malformed packet, KoD/unsynchronized stratum 0, or implausible timestamp.
     */
    static long parseTransmitTimeMs(byte[] packet, int length) {
        if (packet == null || length < PACKET_SIZE || packet.length < PACKET_SIZE) {
            return -1L;
        }
        int mode = packet[0] & 0x07;
        int stratum = packet[1] & 0xFF;
        // Mode 4 = Server, Mode 5 = Broadcast; Stratum 0 = Kiss-o'-Death / unsynchronized.
        if ((mode != 4 && mode != 5) || stratum == 0 || stratum > 15) {
            return -1L;
        }
        long seconds1900 = readUnsigned32(packet, 40);
        long fraction = readUnsigned32(packet, 44);
        if (seconds1900 < OFFSET_1900_TO_1970) {
            return -1L;
        }
        long unixSeconds = seconds1900 - OFFSET_1900_TO_1970;
        long millis = (fraction * 1000L) >>> 32;
        long epochMs = unixSeconds * 1000L + millis;
        return epochMs >= MIN_VALID_EPOCH_MS ? epochMs : -1L;
    }

    /** Write a Unix epoch ms timestamp into an SNTP response packet (used for tests & loopback mocks). */
    static byte[] buildMockServerResponse(long epochMs) {
        byte[] pkt = new byte[PACKET_SIZE];
        pkt[0] = 0x24; // LI=0, VN=4, Mode=4 (server)
        pkt[1] = 2;    // Stratum 2
        long sec1900 = (epochMs / 1000L) + OFFSET_1900_TO_1970;
        long frac = ((epochMs % 1000L) << 32) / 1000L;
        writeUnsigned32(pkt, 40, sec1900);
        writeUnsigned32(pkt, 44, frac);
        return pkt;
    }

    /**
     * Format an injection-safe BusyBox UTC date command:
     *   busybox date -u -s "YYYY-MM-DD HH:MM:SS"
     * Strictly uses integer calendar fields; returns null if epochMs is invalid.
     */
    static String formatBusyBoxUtcDateCommand(long epochMs) {
        if (epochMs < MIN_VALID_EPOCH_MS) return null;
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(epochMs);
        return "busybox date -u -s \"" + formatUtcIso(cal) + "\"";
    }

    static String formatUtcIso(Calendar cal) {
        return pad4(cal.get(Calendar.YEAR)) + "-"
                + pad2(cal.get(Calendar.MONTH) + 1) + "-"
                + pad2(cal.get(Calendar.DAY_OF_MONTH)) + " "
                + pad2(cal.get(Calendar.HOUR_OF_DAY)) + ":"
                + pad2(cal.get(Calendar.MINUTE)) + ":"
                + pad2(cal.get(Calendar.SECOND));
    }

    private static long readUnsigned32(byte[] buf, int off) {
        return ((long) (buf[off] & 0xFF) << 24)
                | ((long) (buf[off + 1] & 0xFF) << 16)
                | ((long) (buf[off + 2] & 0xFF) << 8)
                | ((long) (buf[off + 3] & 0xFF));
    }

    private static void writeUnsigned32(byte[] buf, int off, long val) {
        buf[off] = (byte) ((val >>> 24) & 0xFF);
        buf[off + 1] = (byte) ((val >>> 16) & 0xFF);
        buf[off + 2] = (byte) ((val >>> 8) & 0xFF);
        buf[off + 3] = (byte) (val & 0xFF);
    }

    private static String pad2(int n) { return n < 10 ? ("0" + n) : Integer.toString(n); }
    private static String pad4(int n) {
        if (n < 10) return "000" + n;
        if (n < 100) return "00" + n;
        if (n < 1000) return "0" + n;
        return Integer.toString(n);
    }
}
