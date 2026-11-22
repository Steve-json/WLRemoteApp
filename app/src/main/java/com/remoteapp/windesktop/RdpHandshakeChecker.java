package com.remoteapp.windesktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 标准 Microsoft Windows 远程桌面协议 (MS-RDPBCGR, 默认 3389 端口) X.224 握手检测器：
 * 发送 19 字节标准 TPKT + X.224 Connection Request PDU，验证目标 Windows 计算机的自带远程桌面服务是否在线响应。
 */
public class RdpHandshakeChecker {

    // 标准 RDP X.224 Connection Request PDU (请求 PROTOCOL_RDP | PROTOCOL_SSL | PROTOCOL_HYBRID/NLA)
    private static final byte[] X224_CONNECTION_REQUEST = new byte[]{
            0x03, 0x00, 0x00, 0x13, // TPKT Header (Version=3, Reserved=0, Length=19)
            0x0E,                   // X.224 Length Indicator (14 bytes)
            (byte) 0xE0,            // X.224 Connection Request (CR = 0xE0)
            0x00, 0x00,             // DST-REF
            0x00, 0x00,             // SRC-REF
            0x00,                   // Class 0
            0x01,                   // TYPE_RDP_NEG_REQ
            0x00,                   // flags
            0x08, 0x00,             // length = 8 bytes
            0x03, 0x00, 0x00, 0x00  // requestedProtocols = PROTOCOL_SSL (1) | PROTOCOL_HYBRID/NLA (2)
    };

    public static void verifyRdpEndpoint(String ip, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(X224_CONNECTION_REQUEST);
            out.flush();

            byte[] resp = new byte[19];
            int read = in.read(resp);
            if (read < 6) {
                throw new IOException("远程端口未返回有效的 Windows RDP 握手响应");
            }
            // TPKT 首字节必须为 0x03，X.224 Connection Confirm 标志字节为 0xD0
            if (resp[0] != 0x03 || (resp[5] & 0xF0) != 0xD0) {
                throw new IOException("目标端口响应非标准 Windows RDP 协议 (TPKT=" + resp[0] + ")");
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
