package com.remoteapp.windesktop;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 纯 Java 8 零依赖标准 SSH-2 协议客户端 (RFC 4253 / RFC 4252 / RFC 4254)
 * 仅使用 Android 标准库 (java.security / javax.crypto)，无需任何第三方 JAR，100% 兼容 Android D8。
 * 支持算法：
 * - KEX: ecdh-sha2-nistp256, diffie-hellman-group14-sha256, diffie-hellman-group14-sha1
 * - Cipher: aes128-ctr, aes128-cbc
 * - MAC: hmac-sha2-256, hmac-sha1
 * - Auth: password, keyboard-interactive
 * - Channel: PTY 终端交互会话 (session/pty-req/shell) + SSH 隧道端口转发 (direct-tcpip)
 */
public class PureSsh2Client {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // SSH Message Numbers
    private static final byte SSH_MSG_DISCONNECT = 1;
    private static final byte SSH_MSG_IGNORE = 2;
    private static final byte SSH_MSG_UNIMPLEMENTED = 3;
    private static final byte SSH_MSG_DEBUG = 4;
    private static final byte SSH_MSG_SERVICE_REQUEST = 5;
    private static final byte SSH_MSG_SERVICE_ACCEPT = 6;
    private static final byte SSH_MSG_EXT_INFO = 7;
    private static final byte SSH_MSG_KEXINIT = 20;
    private static final byte SSH_MSG_NEWKEYS = 21;
    private static final byte SSH_MSG_KEXDH_INIT = 30;
    private static final byte SSH_MSG_KEXDH_REPLY = 31;
    private static final byte SSH_MSG_USERAUTH_REQUEST = 50;
    private static final byte SSH_MSG_USERAUTH_FAILURE = 51;
    private static final byte SSH_MSG_USERAUTH_SUCCESS = 52;
    private static final byte SSH_MSG_USERAUTH_BANNER = 53;
    private static final byte SSH_MSG_USERAUTH_INFO_REQUEST = 60;
    private static final byte SSH_MSG_USERAUTH_INFO_RESPONSE = 61;
    private static final byte SSH_MSG_GLOBAL_REQUEST = 80;
    private static final byte SSH_MSG_CHANNEL_OPEN = 90;
    private static final byte SSH_MSG_CHANNEL_OPEN_CONFIRMATION = 91;
    private static final byte SSH_MSG_CHANNEL_OPEN_FAILURE = 92;
    private static final byte SSH_MSG_CHANNEL_WINDOW_ADJUST = 93;
    private static final byte SSH_MSG_CHANNEL_DATA = 94;
    private static final byte SSH_MSG_CHANNEL_EXTENDED_DATA = 95;
    private static final byte SSH_MSG_CHANNEL_EOF = 96;
    private static final byte SSH_MSG_CHANNEL_CLOSE = 97;
    private static final byte SSH_MSG_CHANNEL_REQUEST = 98;
    private static final byte SSH_MSG_CHANNEL_SUCCESS = 99;
    private static final byte SSH_MSG_CHANNEL_FAILURE = 100;

    // Oakley Group 14 (2048-bit MODP Group, RFC 3526)
    private static final BigInteger DH_G = BigInteger.valueOf(2);
    private static final BigInteger DH_P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();

    private int sendSeq = 0;
    private int recvSeq = 0;
    private Cipher sendCipher;
    private Cipher recvCipher;
    private Mac sendMac;
    private Mac recvMac;
    private int macLen = 0;
    private byte[] sessionId;
    private volatile boolean connected = false;

    private final AtomicInteger nextLocalChannel = new AtomicInteger(1);
    private final Map<Integer, SshChannel> channels = new ConcurrentHashMap<Integer, SshChannel>();

    public static class SshChannel {
        final int localId;
        volatile int remoteId = -1;
        volatile boolean openConfirmed = false;
        volatile boolean openFailed = false;
        volatile boolean closed = false;
        final LinkedBlockingQueue<byte[]> dataQueue = new LinkedBlockingQueue<byte[]>();
        final PureSsh2Client client;

        SshChannel(PureSsh2Client client, int localId) {
            this.client = client;
            this.localId = localId;
        }

        public void writeData(byte[] data, int off, int len) throws IOException {
            if (closed) throw new IOException("SSH channel closed");
            int pos = 0;
            while (pos < len) {
                int chunk = Math.min(len - pos, 16384);
                SshBuf buf = new SshBuf();
                buf.writeByte(SSH_MSG_CHANNEL_DATA);
                buf.writeInt(remoteId);
                buf.writeBytes(data, off + pos, chunk);
                client.sendPacket(buf.toByteArray());
                pos += chunk;
            }
        }

        public int readData(byte[] dst, int off, int len) throws IOException {
            if (closed && dataQueue.isEmpty()) return -1;
            try {
                byte[] chunk = dataQueue.poll(300, TimeUnit.MILLISECONDS);
                while (chunk == null) {
                    if (closed || !client.connected) return -1;
                    chunk = dataQueue.poll(300, TimeUnit.MILLISECONDS);
                }
                if (chunk.length == 0) return -1;
                int n = Math.min(len, chunk.length);
                System.arraycopy(chunk, 0, dst, off, n);
                return n;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        public void close() {
            if (!closed) {
                closed = true;
                dataQueue.offer(new byte[0]);
                try {
                    if (remoteId != -1) {
                        SshBuf buf = new SshBuf();
                        buf.writeByte(SSH_MSG_CHANNEL_CLOSE);
                        buf.writeInt(remoteId);
                        client.sendPacket(buf.toByteArray());
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public void connectAndLogin(String host, int port, String user, String password, int timeoutMs) throws Exception {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();

        // 1. Exchange SSH Version Banner
        String clientVersion = "SSH-2.0-WLRemote_1.0";
        out.write((clientVersion + "\r\n").getBytes(UTF8));
        out.flush();

        String serverVersion = readLineBanner();
        if (serverVersion == null || !serverVersion.startsWith("SSH-2.0-")) {
            throw new IOException("非标准 SSH-2 服务响应: " + serverVersion);
        }

        // 2. Build & Send Client KEXINIT
        SshBuf clientKexBuf = new SshBuf();
        clientKexBuf.writeByte(SSH_MSG_KEXINIT);
        byte[] cookie = new byte[16];
        random.nextBytes(cookie);
        clientKexBuf.writeRaw(cookie);
        String kexAlgs = "ecdh-sha2-nistp256,diffie-hellman-group14-sha256,diffie-hellman-group14-sha1";
        String hostKeyAlgs = "ssh-ed25519,ecdsa-sha2-nistp256,rsa-sha2-256,rsa-sha2-512,ssh-rsa";
        String ciphers = "aes128-ctr,aes128-cbc";
        String macs = "hmac-sha2-256,hmac-sha1";
        String comp = "none";
        clientKexBuf.writeString(kexAlgs);
        clientKexBuf.writeString(hostKeyAlgs);
        clientKexBuf.writeString(ciphers);
        clientKexBuf.writeString(ciphers);
        clientKexBuf.writeString(macs);
        clientKexBuf.writeString(macs);
        clientKexBuf.writeString(comp);
        clientKexBuf.writeString(comp);
        clientKexBuf.writeString("");
        clientKexBuf.writeString("");
        clientKexBuf.writeByte((byte) 0);
        clientKexBuf.writeInt(0);
        byte[] I_C = clientKexBuf.toByteArray();
        sendPacket(I_C);

        // 3. Receive Server KEXINIT
        byte[] I_S = readPacket();
        if (I_S.length == 0 || I_S[0] != SSH_MSG_KEXINIT) {
            throw new IOException("期望 SSH_MSG_KEXINIT (20)，实际收到: " + (I_S.length > 0 ? I_S[0] : -1));
        }
        SshReader kexReader = new SshReader(I_S, 17); // skip type(1) + cookie(16)
        String sKex = kexReader.readString();
        String sHostKey = kexReader.readString();
        String sCipherCS = kexReader.readString();
        String sCipherSC = kexReader.readString();
        String sMacCS = kexReader.readString();
        String sMacSC = kexReader.readString();

        String chosenKex = negotiate(kexAlgs, sKex);
        String chosenCipher = negotiate(ciphers, sCipherCS);
        String chosenMac = negotiate(macs, sMacCS);
        if (chosenKex == null || chosenCipher == null || chosenMac == null) {
            throw new IOException("SSH 算法协商失败 (kex=" + sKex + ", cipher=" + sCipherCS + ", mac=" + sMacCS + ")");
        }

        // 4. Perform Key Exchange (ECDH nistp256 or DH Group 14)
        String hashAlg = chosenKex.endsWith("sha1") ? "SHA-1" : "SHA-256";
        MessageDigest md = MessageDigest.getInstance(hashAlg);

        byte[] Q_C;
        byte[] K_S;
        byte[] Q_S;
        byte[] K_mpint;

        if ("ecdh-sha2-nistp256".equals(chosenKex)) {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPair kp = kpg.generateKeyPair();
            java.security.interfaces.ECPublicKey pub = (java.security.interfaces.ECPublicKey) kp.getPublic();
            Q_C = encodeEcPointUncompressed(pub.getW());

            SshBuf initBuf = new SshBuf();
            initBuf.writeByte(SSH_MSG_KEXDH_INIT);
            initBuf.writeBytes(Q_C, 0, Q_C.length);
            sendPacket(initBuf.toByteArray());

            byte[] reply = readPacket();
            if (reply[0] != SSH_MSG_KEXDH_REPLY) {
                throw new IOException("ECDH 握手失败: " + reply[0]);
            }
            SshReader rr = new SshReader(reply, 1);
            K_S = rr.readByteArray();
            Q_S = rr.readByteArray();

            ECPoint serverPt = decodeEcPointUncompressed(Q_S);
            KeyFactory kf = KeyFactory.getInstance("EC");
            java.security.PublicKey serverPub = kf.generatePublic(new ECPublicKeySpec(serverPt, pub.getParams()));
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(kp.getPrivate());
            ka.doPhase(serverPub, true);
            byte[] sharedSecret = ka.generateSecret();
            BigInteger K = new BigInteger(1, sharedSecret);
            K_mpint = toMpint(K);
        } else {
            byte[] xBytes = new byte[32];
            random.nextBytes(xBytes);
            BigInteger x = new BigInteger(1, xBytes);
            BigInteger e = DH_G.modPow(x, DH_P);
            Q_C = e.toByteArray();

            SshBuf initBuf = new SshBuf();
            initBuf.writeByte(SSH_MSG_KEXDH_INIT);
            initBuf.writeBytes(Q_C, 0, Q_C.length);
            sendPacket(initBuf.toByteArray());

            byte[] reply = readPacket();
            if (reply[0] != SSH_MSG_KEXDH_REPLY) {
                throw new IOException("DH 握手失败: " + reply[0]);
            }
            SshReader rr = new SshReader(reply, 1);
            K_S = rr.readByteArray();
            Q_S = rr.readByteArray();
            BigInteger f = new BigInteger(1, Q_S);
            BigInteger K = f.modPow(x, DH_P);
            K_mpint = toMpint(K);
        }

        // Compute exchange hash H = hash(V_C || V_S || I_C || I_S || K_S || Q_C || Q_S || K)
        SshBuf hBuf = new SshBuf();
        hBuf.writeString(clientVersion);
        hBuf.writeString(serverVersion);
        hBuf.writeBytes(I_C, 0, I_C.length);
        hBuf.writeBytes(I_S, 0, I_S.length);
        hBuf.writeBytes(K_S, 0, K_S.length);
        hBuf.writeBytes(Q_C, 0, Q_C.length);
        hBuf.writeBytes(Q_S, 0, Q_S.length);
        hBuf.writeRaw(K_mpint);
        byte[] H = md.digest(hBuf.toByteArray());
        if (sessionId == null) {
            sessionId = H;
        }

        // 5. Send & Receive SSH_MSG_NEWKEYS
        sendPacket(new byte[]{SSH_MSG_NEWKEYS});
        byte[] newKeysResp = readPacket();
        while (newKeysResp.length > 0 && newKeysResp[0] != SSH_MSG_NEWKEYS) {
            newKeysResp = readPacket();
        }

        // 6. Activate Ciphers & MACs
        byte[] ivCS = deriveKey(md, K_mpint, H, (byte) 'A', sessionId, 16);
        byte[] ivSC = deriveKey(md, K_mpint, H, (byte) 'B', sessionId, 16);
        byte[] keyCS = deriveKey(md, K_mpint, H, (byte) 'C', sessionId, 16);
        byte[] keySC = deriveKey(md, K_mpint, H, (byte) 'D', sessionId, 16);
        int mLen = "hmac-sha2-256".equals(chosenMac) ? 32 : 20;
        byte[] macKeyCS = deriveKey(md, K_mpint, H, (byte) 'E', sessionId, mLen);
        byte[] macKeySC = deriveKey(md, K_mpint, H, (byte) 'F', sessionId, mLen);

        String cipherTrans = "aes128-ctr".equals(chosenCipher) ? "AES/CTR/NoPadding" : "AES/CBC/NoPadding";
        sendCipher = Cipher.getInstance(cipherTrans);
        sendCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyCS, "AES"), new IvParameterSpec(ivCS));
        recvCipher = Cipher.getInstance(cipherTrans);
        recvCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keySC, "AES"), new IvParameterSpec(ivSC));

        String jceMac = "hmac-sha2-256".equals(chosenMac) ? "HmacSHA256" : "HmacSHA1";
        sendMac = Mac.getInstance(jceMac);
        sendMac.init(new SecretKeySpec(macKeyCS, jceMac));
        recvMac = Mac.getInstance(jceMac);
        recvMac.init(new SecretKeySpec(macKeySC, jceMac));
        macLen = mLen;

        // 7. Request ssh-userauth service
        SshBuf svcReq = new SshBuf();
        svcReq.writeByte(SSH_MSG_SERVICE_REQUEST);
        svcReq.writeString("ssh-userauth");
        sendPacket(svcReq.toByteArray());

        byte[] svcResp = readUserAuthPacket();
        if (svcResp[0] != SSH_MSG_SERVICE_ACCEPT) {
            throw new IOException("SSH 拒绝 userauth 服务请求: " + svcResp[0]);
        }

        // 8. Authenticate (password first, fallback to keyboard-interactive)
        SshBuf pwReq = new SshBuf();
        pwReq.writeByte(SSH_MSG_USERAUTH_REQUEST);
        pwReq.writeString(user);
        pwReq.writeString("ssh-connection");
        pwReq.writeString("password");
        pwReq.writeByte((byte) 0);
        pwReq.writeString(password);
        sendPacket(pwReq.toByteArray());

        byte[] authResp = readUserAuthPacket();
        if (authResp[0] == SSH_MSG_USERAUTH_FAILURE) {
            // Try keyboard-interactive
            SshBuf kiReq = new SshBuf();
            kiReq.writeByte(SSH_MSG_USERAUTH_REQUEST);
            kiReq.writeString(user);
            kiReq.writeString("ssh-connection");
            kiReq.writeString("keyboard-interactive");
            kiReq.writeString("");
            kiReq.writeString("");
            sendPacket(kiReq.toByteArray());

            while (true) {
                authResp = readUserAuthPacket();
                if (authResp[0] == SSH_MSG_USERAUTH_INFO_REQUEST) {
                    SshReader ir = new SshReader(authResp, 1);
                    ir.readString(); // name
                    ir.readString(); // instruction
                    ir.readString(); // lang
                    int numPrompts = ir.readInt();
                    SshBuf irResp = new SshBuf();
                    irResp.writeByte(SSH_MSG_USERAUTH_INFO_RESPONSE);
                    irResp.writeInt(numPrompts);
                    for (int i = 0; i < numPrompts; i++) {
                        irResp.writeString(password);
                    }
                    sendPacket(irResp.toByteArray());
                } else {
                    break;
                }
            }
        }

        if (authResp[0] != SSH_MSG_USERAUTH_SUCCESS) {
            throw new IOException("Linux SSH 用户名或密码验证失败");
        }

        connected = true;
        socket.setSoTimeout(0);
        startReaderLoop();
    }

    public SshChannel openPtyShell(int cols, int rows) throws Exception {
        int localId = nextLocalChannel.getAndIncrement();
        SshChannel ch = new SshChannel(this, localId);
        channels.put(localId, ch);

        SshBuf open = new SshBuf();
        open.writeByte(SSH_MSG_CHANNEL_OPEN);
        open.writeString("session");
        open.writeInt(localId);
        open.writeInt(0x200000); // 2MB window
        open.writeInt(0x8000);   // 32KB max packet
        sendPacket(open.toByteArray());

        long deadline = System.currentTimeMillis() + 8000;
        while (!ch.openConfirmed && !ch.openFailed && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (!ch.openConfirmed) {
            throw new IOException("无法打开 SSH 会话通道");
        }

        // Request xterm PTY
        SshBuf pty = new SshBuf();
        pty.writeByte(SSH_MSG_CHANNEL_REQUEST);
        pty.writeInt(ch.remoteId);
        pty.writeString("pty-req");
        pty.writeByte((byte) 0);
        pty.writeString("xterm-256color");
        pty.writeInt(cols);
        pty.writeInt(rows);
        pty.writeInt(0);
        pty.writeInt(0);
        pty.writeString("");
        sendPacket(pty.toByteArray());

        // Request shell
        SshBuf sh = new SshBuf();
        sh.writeByte(SSH_MSG_CHANNEL_REQUEST);
        sh.writeInt(ch.remoteId);
        sh.writeString("shell");
        sh.writeByte((byte) 0);
        sendPacket(sh.toByteArray());

        return ch;
    }

    public int startLocalPortForwarding(final String remoteHost, final int remotePort) throws Exception {
        final ServerSocket ss = new ServerSocket(0);
        final int localPort = ss.getLocalPort();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (connected && !ss.isClosed()) {
                        final Socket clientSock = ss.accept();
                        handleForwardedSocket(clientSock, remoteHost, remotePort);
                    }
                } catch (Exception ignored) {
                } finally {
                    try { ss.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
        return localPort;
    }

    private void handleForwardedSocket(final Socket sock, String rHost, int rPort) throws Exception {
        int localId = nextLocalChannel.getAndIncrement();
        final SshChannel ch = new SshChannel(this, localId);
        channels.put(localId, ch);

        SshBuf open = new SshBuf();
        open.writeByte(SSH_MSG_CHANNEL_OPEN);
        open.writeString("direct-tcpip");
        open.writeInt(localId);
        open.writeInt(0x200000);
        open.writeInt(0x8000);
        open.writeString(rHost);
        open.writeInt(rPort);
        open.writeString("127.0.0.1");
        open.writeInt(sock.getPort());
        sendPacket(open.toByteArray());

        long deadline = System.currentTimeMillis() + 6000;
        while (!ch.openConfirmed && !ch.openFailed && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (!ch.openConfirmed) {
            sock.close();
            throw new IOException("SSH 隧道目标端口 " + rPort + " 未开放");
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream sin = sock.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = sin.read(buf)) != -1) {
                        ch.writeData(buf, 0, n);
                    }
                } catch (Exception ignored) {
                } finally {
                    ch.close();
                    try { sock.close(); } catch (Exception ignored) {}
                }
            }
        }).start();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    OutputStream sout = sock.getOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = ch.readData(buf, 0, buf.length)) != -1) {
                        sout.write(buf, 0, n);
                        sout.flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    ch.close();
                    try { sock.close(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    public boolean isConnected() {
        return connected;
    }

    public void disconnect() {
        connected = false;
        for (SshChannel ch : channels.values()) {
            ch.close();
        }
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private void startReaderLoop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (connected) {
                        byte[] pkt = readPacket();
                        if (pkt.length == 0) continue;
                        byte msg = pkt[0];
                        if (msg == SSH_MSG_CHANNEL_OPEN_CONFIRMATION) {
                            SshReader r = new SshReader(pkt, 1);
                            int recip = r.readInt();
                            int sender = r.readInt();
                            SshChannel ch = channels.get(recip);
                            if (ch != null) {
                                ch.remoteId = sender;
                                ch.openConfirmed = true;
                            }
                        } else if (msg == SSH_MSG_CHANNEL_OPEN_FAILURE) {
                            SshReader r = new SshReader(pkt, 1);
                            int recip = r.readInt();
                            SshChannel ch = channels.get(recip);
                            if (ch != null) {
                                ch.openFailed = true;
                            }
                        } else if (msg == SSH_MSG_CHANNEL_DATA || msg == SSH_MSG_CHANNEL_EXTENDED_DATA) {
                            SshReader r = new SshReader(pkt, 1);
                            int recip = r.readInt();
                            if (msg == SSH_MSG_CHANNEL_EXTENDED_DATA) {
                                r.readInt(); // data type code
                            }
                            byte[] data = r.readByteArray();
                            SshChannel ch = channels.get(recip);
                            if (ch != null && data.length > 0) {
                                ch.dataQueue.offer(data);
                                // Send window adjust if chunk is large
                                SshBuf adj = new SshBuf();
                                adj.writeByte(SSH_MSG_CHANNEL_WINDOW_ADJUST);
                                adj.writeInt(ch.remoteId);
                                adj.writeInt(data.length);
                                sendPacket(adj.toByteArray());
                            }
                        } else if (msg == SSH_MSG_CHANNEL_EOF || msg == SSH_MSG_CHANNEL_CLOSE) {
                            SshReader r = new SshReader(pkt, 1);
                            int recip = r.readInt();
                            SshChannel ch = channels.get(recip);
                            if (ch != null) {
                                ch.close();
                            }
                        } else if (msg == SSH_MSG_DISCONNECT) {
                            disconnect();
                            break;
                        }
                    }
                } catch (Exception e) {
                    disconnect();
                }
            }
        }).start();
    }

    private byte[] readUserAuthPacket() throws IOException {
        while (true) {
            byte[] pkt = readPacket();
            if (pkt.length == 0) continue;
            byte b = pkt[0];
            if (b == SSH_MSG_IGNORE || b == SSH_MSG_DEBUG || b == SSH_MSG_EXT_INFO || b == SSH_MSG_USERAUTH_BANNER || b == SSH_MSG_GLOBAL_REQUEST) {
                continue;
            }
            return pkt;
        }
    }

    private String readLineBanner() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            sb.setLength(0);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') sb.append((char) c);
            }
            if (c == -1 && sb.length() == 0) return null;
            String line = sb.toString();
            if (line.startsWith("SSH-")) return line;
        }
    }

    private void sendPacket(byte[] payload) throws IOException {
        synchronized (writeLock) {
            int blockSize = (sendCipher != null) ? 16 : 8;
            int lenWithoutPad = 5 + payload.length;
            int padLen = blockSize - (lenWithoutPad % blockSize);
            if (padLen < 4) padLen += blockSize;

            int packetLen = 1 + payload.length + padLen;
            byte[] plain = new byte[4 + packetLen];
            plain[0] = (byte) (packetLen >>> 24);
            plain[1] = (byte) (packetLen >>> 16);
            plain[2] = (byte) (packetLen >>> 8);
            plain[3] = (byte) packetLen;
            plain[4] = (byte) padLen;
            System.arraycopy(payload, 0, plain, 5, payload.length);
            byte[] pad = new byte[padLen];
            random.nextBytes(pad);
            System.arraycopy(pad, 0, plain, 5 + payload.length, padLen);

            byte[] macBytes = null;
            if (sendMac != null) {
                byte[] seqBuf = new byte[]{
                        (byte) (sendSeq >>> 24),
                        (byte) (sendSeq >>> 16),
                        (byte) (sendSeq >>> 8),
                        (byte) sendSeq
                };
                sendMac.update(seqBuf);
                sendMac.update(plain);
                macBytes = sendMac.doFinal();
            }

            byte[] outBytes = (sendCipher != null) ? sendCipher.update(plain) : plain;
            out.write(outBytes);
            if (macBytes != null) {
                out.write(macBytes, 0, macLen);
            }
            out.flush();
            sendSeq++;
        }
    }

    private byte[] readPacket() throws IOException {
        int blockSize = (recvCipher != null) ? 16 : 8;
        byte[] firstBlockEnc = new byte[blockSize];
        in.readFully(firstBlockEnc);
        byte[] firstBlock = (recvCipher != null) ? recvCipher.update(firstBlockEnc) : firstBlockEnc;

        int packetLen = ((firstBlock[0] & 0xFF) << 24)
                | ((firstBlock[1] & 0xFF) << 16)
                | ((firstBlock[2] & 0xFF) << 8)
                | (firstBlock[3] & 0xFF);
        if (packetLen < 5 || packetLen > 262144) {
            throw new IOException("非法 SSH 数据包长度: " + packetLen);
        }

        int totalLen = 4 + packetLen;
        int remLen = totalLen - blockSize;
        byte[] fullPlain = new byte[totalLen];
        System.arraycopy(firstBlock, 0, fullPlain, 0, blockSize);

        if (remLen > 0) {
            byte[] remEnc = new byte[remLen];
            in.readFully(remEnc);
            byte[] remPlain = (recvCipher != null) ? recvCipher.update(remEnc) : remEnc;
            System.arraycopy(remPlain, 0, fullPlain, blockSize, remLen);
        }

        if (recvMac != null) {
            byte[] macRecv = new byte[macLen];
            in.readFully(macRecv);
            byte[] seqBuf = new byte[]{
                    (byte) (recvSeq >>> 24),
                    (byte) (recvSeq >>> 16),
                    (byte) (recvSeq >>> 8),
                    (byte) recvSeq
            };
            recvMac.update(seqBuf);
            recvMac.update(fullPlain);
            byte[] macCalc = recvMac.doFinal();
            for (int i = 0; i < macLen; i++) {
                if (macRecv[i] != macCalc[i]) {
                    throw new IOException("SSH MAC 校验失败");
                }
            }
        }
        recvSeq++;

        int padLen = fullPlain[4] & 0xFF;
        int payloadLen = packetLen - 1 - padLen;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(fullPlain, 5, payload, 0, payloadLen);
        return payload;
    }

    private static String negotiate(String clientList, String serverList) {
        String[] cTokens = clientList.split(",");
        String[] sTokens = serverList.split(",");
        for (String c : cTokens) {
            for (String s : sTokens) {
                if (c.trim().equals(s.trim())) return c.trim();
            }
        }
        return null;
    }

    private static byte[] toMpint(BigInteger bi) {
        byte[] raw = bi.toByteArray();
        SshBuf b = new SshBuf();
        b.writeBytes(raw, 0, raw.length);
        return b.toByteArray();
    }

    private static byte[] deriveKey(MessageDigest md, byte[] K_mpint, byte[] H, byte letter, byte[] sessionId, int needLen) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        md.reset();
        md.update(K_mpint);
        md.update(H);
        md.update(letter);
        md.update(sessionId);
        byte[] k1 = md.digest();
        bos.write(k1, 0, k1.length);
        while (bos.size() < needLen) {
            md.reset();
            md.update(K_mpint);
            md.update(H);
            byte[] cur = bos.toByteArray();
            md.update(cur);
            byte[] next = md.digest();
            bos.write(next, 0, next.length);
        }
        return Arrays.copyOf(bos.toByteArray(), needLen);
    }

    private static byte[] encodeEcPointUncompressed(ECPoint pt) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        byte[] xb = pt.getAffineX().toByteArray();
        byte[] yb = pt.getAffineY().toByteArray();
        copyFixed32(xb, out, 1);
        copyFixed32(yb, out, 33);
        return out;
    }

    private static ECPoint decodeEcPointUncompressed(byte[] data) throws IOException {
        if (data.length != 65 || data[0] != 0x04) {
            throw new IOException("非法 NIST P-256 公钥点格式");
        }
        byte[] xb = Arrays.copyOfRange(data, 1, 33);
        byte[] yb = Arrays.copyOfRange(data, 33, 65);
        return new ECPoint(new BigInteger(1, xb), new BigInteger(1, yb));
    }

    private static void copyFixed32(byte[] src, byte[] dst, int dstOff) {
        if (src.length == 32) {
            System.arraycopy(src, 0, dst, dstOff, 32);
        } else if (src.length > 32) {
            System.arraycopy(src, src.length - 32, dst, dstOff, 32);
        } else {
            System.arraycopy(src, 0, dst, dstOff + (32 - src.length), src.length);
        }
    }

    private static class SshBuf {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        void writeByte(byte b) {
            bos.write(b & 0xFF);
        }

        void writeInt(int v) {
            bos.write((v >>> 24) & 0xFF);
            bos.write((v >>> 16) & 0xFF);
            bos.write((v >>> 8) & 0xFF);
            bos.write(v & 0xFF);
        }

        void writeRaw(byte[] b) {
            bos.write(b, 0, b.length);
        }

        void writeBytes(byte[] b, int off, int len) {
            writeInt(len);
            bos.write(b, off, len);
        }

        void writeString(String s) {
            byte[] b = s.getBytes(UTF8);
            writeBytes(b, 0, b.length);
        }

        byte[] toByteArray() {
            return bos.toByteArray();
        }
    }

    private static class SshReader {
        private final byte[] buf;
        private int pos;

        SshReader(byte[] buf, int start) {
            this.buf = buf;
            this.pos = start;
        }

        int readInt() {
            int v = ((buf[pos] & 0xFF) << 24)
                    | ((buf[pos + 1] & 0xFF) << 16)
                    | ((buf[pos + 2] & 0xFF) << 8)
                    | (buf[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        byte[] readByteArray() {
            int len = readInt();
            byte[] out = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return out;
        }

        String readString() {
            return new String(readByteArray(), UTF8);
        }
    }
}
