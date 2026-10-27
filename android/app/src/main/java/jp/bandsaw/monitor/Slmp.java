package jp.bandsaw.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 三菱 SLMP（MCプロトコル 3Eフレーム・バイナリ）でワードデバイスに一括書込みする。
 * Q シリーズ（内蔵Ethernet）／ iQ-R ／ iQ-F で使える Q 互換の形式（サブコマンド 0000）。
 */
public final class Slmp implements AutoCloseable {
    public static final String[] DEVICES = {"D", "W", "R", "ZR"};
    private static final int[] CODES = {0xA8, 0xB4, 0xAF, 0xB0};

    private final String host;
    private final int port;
    private final boolean udp;
    private Socket tcp;
    private DatagramSocket dgram;
    private final byte[] rx = new byte[512];

    public Slmp(String host, int port, boolean udp) {
        this.host = host; this.port = port; this.udp = udp;
    }

    public static int deviceCode(String dev) {
        for (int i = 0; i < DEVICES.length; i++) if (DEVICES[i].equalsIgnoreCase(dev)) return CODES[i];
        throw new IllegalArgumentException("未対応のデバイス: " + dev);
    }

    /** ワード一括書込み（コマンド 1401, サブコマンド 0000）の電文を作る */
    public static byte[] buildWriteWords(int devCode, int head, short[] data) {
        int n = data.length;
        int reqLen = 2 + 2 + 2 + 3 + 1 + 2 + 2 * n;   // 監視タイマ〜データ末尾
        byte[] f = new byte[9 + reqLen];
        int i = 0;
        f[i++] = 0x50; f[i++] = 0x00;                 // サブヘッダ
        f[i++] = 0x00;                                // ネットワーク番号
        f[i++] = (byte) 0xFF;                         // PC番号
        f[i++] = (byte) 0xFF; f[i++] = 0x03;          // 要求先ユニットI/O番号 03FF
        f[i++] = 0x00;                                // 要求先ユニット局番号
        f[i++] = (byte) reqLen; f[i++] = (byte) (reqLen >> 8);
        f[i++] = 0x10; f[i++] = 0x00;                 // 監視タイマ 16 × 250ms = 4秒
        f[i++] = 0x01; f[i++] = 0x14;                 // コマンド 1401（一括書込み）
        f[i++] = 0x00; f[i++] = 0x00;                 // サブコマンド 0000（ワード単位）
        f[i++] = (byte) head; f[i++] = (byte) (head >> 8); f[i++] = (byte) (head >> 16);
        f[i++] = (byte) devCode;
        f[i++] = (byte) n; f[i++] = (byte) (n >> 8);
        for (short v : data) { f[i++] = (byte) v; f[i++] = (byte) (v >> 8); }
        return f;
    }

    private void open() throws IOException {
        if (udp) {
            if (dgram == null) {
                dgram = new DatagramSocket();
                dgram.setSoTimeout(2000);
            }
        } else if (tcp == null || tcp.isClosed()) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 2000);
            s.setSoTimeout(2000);
            s.setTcpNoDelay(true);
            tcp = s;
        }
    }

    /** 書込み。失敗したら IOException（終了コードつき） */
    public synchronized void writeWords(String dev, int head, short[] data) throws IOException {
        byte[] req = buildWriteWords(deviceCode(dev), head, data);
        try {
            open();
            int len;
            if (udp) {
                dgram.send(new DatagramPacket(req, req.length, InetAddress.getByName(host), port));
                DatagramPacket p = new DatagramPacket(rx, rx.length);
                dgram.receive(p);
                len = p.getLength();
            } else {
                OutputStream o = tcp.getOutputStream();
                o.write(req);
                o.flush();
                len = readTcpResponse(tcp.getInputStream());
            }
            checkResponse(rx, len);
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private int readTcpResponse(InputStream in) throws IOException {
        int got = 0;
        while (got < 9) { int r = in.read(rx, got, 9 - got); if (r < 0) throw new IOException("PLCが接続を閉じました"); got += r; }
        int dl = (rx[7] & 0xFF) | ((rx[8] & 0xFF) << 8);
        if (9 + dl > rx.length) throw new IOException("応答が長すぎます");
        while (got < 9 + dl) { int r = in.read(rx, got, 9 + dl - got); if (r < 0) throw new IOException("PLCが接続を閉じました"); got += r; }
        return got;
    }

    static void checkResponse(byte[] b, int len) throws IOException {
        if (len < 11 || (b[0] & 0xFF) != 0xD0 || b[1] != 0x00) throw new IOException("SLMPの応答ではありません");
        int end = (b[9] & 0xFF) | ((b[10] & 0xFF) << 8);
        if (end != 0) throw new IOException(String.format("PLCがエラーを返しました（終了コード %04X）", end));
    }

    @Override
    public synchronized void close() {
        try { if (tcp != null) tcp.close(); } catch (IOException ignored) { }
        tcp = null;
        if (dgram != null) dgram.close();
        dgram = null;
    }
}
