package com.github.ma1co.openmemories.tweak;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

final class SntpClientPolicyTests {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        requestPacketHeader();
        parseValidAndInvalidResponses();
        busyBoxDateCommandFormat();
        hostTimeoutSelection();
        loopbackUdpSntpRoundTrip();
        System.out.println("PASS SntpClientPolicyTests: " + assertions + " assertions");
    }

    private static void requestPacketHeader() {
        byte[] req = SntpClientPolicy.buildRequestPacket();
        expect(req.length == 48, "request is 48 bytes");
        expect(req[0] == 0x23, "LI=0 VN=4 Mode=3");
    }

    private static void parseValidAndInvalidResponses() {
        // 2026-11-23T00:00:00.500Z = 1795392000500 ms
        long expected = 1795392000500L;
        byte[] resp = SntpClientPolicy.buildMockServerResponse(expected);
        long parsed = SntpClientPolicy.parseTransmitTimeMs(resp, resp.length);
        expect(Math.abs(parsed - expected) <= 2L, "round-trip epoch within 2ms");

        // Reject stratum 0 (Kiss-o'-Death)
        byte[] kod = SntpClientPolicy.buildMockServerResponse(expected);
        kod[1] = 0;
        expect(SntpClientPolicy.parseTransmitTimeMs(kod, kod.length) == -1L, "rejects stratum 0");

        // Reject client mode packet
        byte[] clientMode = SntpClientPolicy.buildRequestPacket();
        expect(SntpClientPolicy.parseTransmitTimeMs(clientMode, clientMode.length) == -1L, "rejects mode 3");

        // Reject pre-2020 timestamp
        byte[] oldPkt = SntpClientPolicy.buildMockServerResponse(1000000000000L);
        expect(SntpClientPolicy.parseTransmitTimeMs(oldPkt, oldPkt.length) == -1L, "rejects pre-2020 timestamp");

        // Reject truncated packet
        expect(SntpClientPolicy.parseTransmitTimeMs(new byte[12], 12) == -1L, "rejects short packet");
    }

    private static void busyBoxDateCommandFormat() {
        // 2026-01-02 03:04:05 UTC = 1767323045000L
        String cmd = SntpClientPolicy.formatBusyBoxUtcDateCommand(1767323045000L);
        expect("busybox date -u -s \"2026-01-02 03:04:05\"".equals(cmd), "formats exact BusyBox UTC date command");
        expect(SntpClientPolicy.formatBusyBoxUtcDateCommand(1000L) == null, "rejects invalid epoch");
    }

    private static void hostTimeoutSelection() {
        expect(SntpClientPolicy.timeoutForHostMs("10.20.30.1") == SntpClientPolicy.LAN_TIMEOUT_MS, "10.x LAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("192.168.1.1") == SntpClientPolicy.LAN_TIMEOUT_MS, "192.168.x LAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("172.16.0.1") == SntpClientPolicy.LAN_TIMEOUT_MS, "172.16.x LAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("172.31.255.1") == SntpClientPolicy.LAN_TIMEOUT_MS, "172.31.x LAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("127.0.0.1") == SntpClientPolicy.LAN_TIMEOUT_MS, "loopback LAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("172.15.0.1") == SntpClientPolicy.WAN_TIMEOUT_MS, "172.15.x WAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("172.32.0.1") == SntpClientPolicy.WAN_TIMEOUT_MS, "172.32.x WAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs("pool.ntp.org") == SntpClientPolicy.WAN_TIMEOUT_MS, "pool.ntp.org WAN timeout");
        expect(SntpClientPolicy.timeoutForHostMs(null) == SntpClientPolicy.WAN_TIMEOUT_MS, "null defaults to WAN timeout");
    }

    private static void loopbackUdpSntpRoundTrip() throws Exception {
        final long serverEpoch = 1795392000250L;
        final DatagramSocket server = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(3000);
        int port = server.getLocalPort();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] in = new byte[48];
                    DatagramPacket p = new DatagramPacket(in, in.length);
                    server.receive(p);
                    byte[] out = SntpClientPolicy.buildMockServerResponse(serverEpoch);
                    server.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                } catch (Exception ignored) {
                } finally {
                    server.close();
                }
            }
        });
        t.start();
        long got = SntpClientPolicy.queryNtpServer("127.0.0.1", port, 2000);
        t.join(2000);
        expect(Math.abs(got - serverEpoch) <= 2L, "live loopback UDP SNTP query succeeded");
    }

    private static void expect(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
        assertions++;
    }
}
