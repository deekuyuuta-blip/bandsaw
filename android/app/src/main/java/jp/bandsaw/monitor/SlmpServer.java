package jp.bandsaw.monitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;

/**
 * SLMP サーバー（相手機器側）。PLC のシンプルCPU通信（交信先: SLMP対応機器 / QnA互換3Eフレーム）
 * からの読出し要求に答える。TCP と UDP の同じポート番号で待ち受ける。
 *
 * 対応コマンド（バイナリ, 3E/4E フレーム）
 *   0401 一括読出し  サブコマンド 0000/0002（ワード）, 0001/0003（ビット: 常に0を返す）
 *   1401 一括書込み  サブコマンド 0000/0002（ワード）
 *   0619 折返しテスト
 *   0101 形名読出し
 *   それ以外は終了コード C059（コマンド未対応）
 * ワードデバイス（D, W, R, ZR）はすべて同じメモリ（0〜MEM-1 番地）として扱う。
 */
public final class SlmpServer {
    public static final int MEM = 1024;

    public interface Listener {
        void onRequest(String peer, int cmd, int sub, boolean ok);
    }

    private final int port;
    private final short[] mem = new short[MEM];
    private final Listener listener;
    private volatile boolean stop;
    private DatagramSocket udp;
    private ServerSocket tcp;
    private final List<Socket> clients = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    public SlmpServer(int port, Listener l) {
        this.port = port;
        this.listener = l;
    }

    /** アプリ側から状態を書き込む */
    public void setWords(int head, short[] w) {
        synchronized (mem) {
            for (int i = 0; i < w.length; i++) if (head + i >= 0 && head + i < MEM) mem[head + i] = w[i];
        }
    }

    public short getWord(int a) {
        synchronized (mem) { return (a >= 0 && a < MEM) ? mem[a] : 0; }
    }

    public void start() throws IOException {
        stop = false;
        udp = new DatagramSocket(null);
        udp.setReuseAddress(true);
        udp.bind(new InetSocketAddress(port));
        tcp = new ServerSocket();
        tcp.setReuseAddress(true);
        tcp.bind(new InetSocketAddress(port));
        Thread tu = new Thread(this::udpLoop, "slmp-udp");
        Thread tt = new Thread(this::tcpLoop, "slmp-tcp");
        threads.add(tu); threads.add(tt);
        tu.setDaemon(true); tt.setDaemon(true);
        tu.start(); tt.start();
    }

    public void close() {
        stop = true;
        if (udp != null) udp.close();
        try { if (tcp != null) tcp.close(); } catch (IOException ignored) { }
        synchronized (clients) {
            for (Socket s : clients) try { s.close(); } catch (IOException ignored) { }
            clients.clear();
        }
    }

    // ------------------------------------------------------------ 受信ループ
    private void udpLoop() {
        byte[] buf = new byte[2048];
        while (!stop) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                byte[] res = handle(buf, p.getLength(), p.getAddress().getHostAddress() + " UDP");
                if (res != null) udp.send(new DatagramPacket(res, res.length, p.getSocketAddress()));
            } catch (SocketException e) {
                if (stop) return;
            } catch (Exception ignored) { }
        }
    }

    private void tcpLoop() {
        while (!stop) {
            try {
                Socket s = tcp.accept();
                s.setTcpNoDelay(true);
                synchronized (clients) { clients.add(s); }
                Thread t = new Thread(() -> serveTcp(s), "slmp-tcp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (stop) return;
            }
        }
    }

    private void serveTcp(Socket s) {
        String peer = s.getInetAddress().getHostAddress() + " TCP";
        byte[] buf = new byte[4096];
        try (Socket sock = s) {
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            while (!stop) {
                // 先頭: サブヘッダ（3E=2バイト, 4E=6バイト）＋ネットワーク〜要求データ長 7バイト
                if (!readFully(in, buf, 0, 2)) return;
                boolean e4 = (buf[0] & 0xFF) == 0x54 && buf[1] == 0x00;
                int hdr = e4 ? 6 : 2;
                if (e4 && !readFully(in, buf, 2, 4)) return;
                if (!readFully(in, buf, hdr, 7)) return;
                int dl = u16(buf, hdr + 5);
                if (hdr + 7 + dl > buf.length) return;
                if (!readFully(in, buf, hdr + 7, dl)) return;
                byte[] res = handle(buf, hdr + 7 + dl, peer);
                if (res != null) { out.write(res); out.flush(); }
            }
        } catch (IOException ignored) {
        } finally {
            synchronized (clients) { clients.remove(s); }
        }
    }

    private static boolean readFully(InputStream in, byte[] b, int off, int n) throws IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(b, off + got, n - got);
            if (r < 0) return false;
            got += r;
        }
        return true;
    }

    private static int u16(byte[] b, int i) { return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8); }

    // ------------------------------------------------------------ 電文処理
    /** 要求電文を処理して応答電文を返す（不正な電文は null） */
    public byte[] handle(byte[] b, int len, String peer) {
        if (len < 2) return null;
        boolean e4;
        if ((b[0] & 0xFF) == 0x50 && b[1] == 0x00) e4 = false;
        else if ((b[0] & 0xFF) == 0x54 && b[1] == 0x00) e4 = true;
        else return null;
        int h = e4 ? 6 : 2;                       // ネットワーク番号の位置
        if (len < h + 7 + 6) return null;
        int dl = u16(b, h + 5);
        if (len < h + 7 + dl || dl < 6) return null;
        int p = h + 7 + 2;                        // 監視タイマの次（コマンド）
        int cmd = u16(b, p), sub = u16(b, p + 2);
        int d = p + 4;                            // 要求データの先頭
        int end = h + 7 + dl;
        byte[] data = null;
        int err = 0;
        try {
            switch (cmd) {
                case 0x0401: data = read(b, d, end, sub); if (data == null) err = 0xC059; break;
                case 0x1401: err = write(b, d, end, sub) ? 0 : 0xC059; data = new byte[0]; break;
                case 0x0619: {
                    int n = u16(b, d);
                    data = new byte[2 + n];
                    System.arraycopy(b, d, data, 0, 2 + n);
                    break;
                }
                case 0x0101: {
                    data = new byte[18];
                    byte[] name = "BANDSAW MONITOR ".getBytes("US-ASCII");
                    System.arraycopy(name, 0, data, 0, 16);
                    break;
                }
                default: err = 0xC059;
            }
        } catch (Exception ex) {
            err = 0xC061;                         // 要求データ長の異常
        }
        if (listener != null) listener.onRequest(peer, cmd, sub, err == 0);
        return response(b, h, err, err == 0 ? data : errorInfo(b, h, cmd, sub));
    }

    private static int devCount(int sub) { return (sub == 0x0002 || sub == 0x0003) ? 6 : 4; }

    private byte[] read(byte[] b, int d, int end, int sub) {
        boolean ext = sub == 0x0002 || sub == 0x0003;
        boolean bit = sub == 0x0001 || sub == 0x0003;
        if (sub != 0x0000 && sub != 0x0001 && sub != 0x0002 && sub != 0x0003) return null;
        int head = ext ? ((b[d] & 0xFF) | ((b[d + 1] & 0xFF) << 8) | ((b[d + 2] & 0xFF) << 16) | ((b[d + 3] & 0xFF) << 24))
                       : ((b[d] & 0xFF) | ((b[d + 1] & 0xFF) << 8) | ((b[d + 2] & 0xFF) << 16));
        int n = u16(b, d + devCount(sub));
        if (d + devCount(sub) + 2 > end || n <= 0 || n > 7168) return null;
        if (bit) return new byte[(n + 1) / 2];    // ビットは扱わない（すべて0）
        byte[] out = new byte[2 * n];
        synchronized (mem) {
            for (int i = 0; i < n; i++) {
                int a = head + i;
                short v = (a >= 0 && a < MEM) ? mem[a] : 0;
                out[2 * i] = (byte) v;
                out[2 * i + 1] = (byte) (v >> 8);
            }
        }
        return out;
    }

    private boolean write(byte[] b, int d, int end, int sub) {
        if (sub != 0x0000 && sub != 0x0002) return false;
        boolean ext = sub == 0x0002;
        int head = ext ? ((b[d] & 0xFF) | ((b[d + 1] & 0xFF) << 8) | ((b[d + 2] & 0xFF) << 16) | ((b[d + 3] & 0xFF) << 24))
                       : ((b[d] & 0xFF) | ((b[d + 1] & 0xFF) << 8) | ((b[d + 2] & 0xFF) << 16));
        int c = d + devCount(sub);
        int n = u16(b, c);
        if (c + 2 + 2 * n > end) return false;
        synchronized (mem) {
            for (int i = 0; i < n; i++) {
                int a = head + i;
                if (a >= 0 && a < MEM) mem[a] = (short) u16(b, c + 2 + 2 * i);
            }
        }
        return true;
    }

    private static byte[] errorInfo(byte[] b, int h, int cmd, int sub) {
        // ネットワーク番号, PC番号, ユニットI/O番号, 局番号, コマンド, サブコマンド
        return new byte[]{b[h], b[h + 1], b[h + 2], b[h + 3], b[h + 4],
                (byte) cmd, (byte) (cmd >> 8), (byte) sub, (byte) (sub >> 8)};
    }

    private static byte[] response(byte[] b, int h, int end, byte[] data) {
        int dl = 2 + data.length;
        byte[] r = new byte[h + 7 + dl];
        r[0] = (byte) 0xD0;
        r[1] = 0x00;
        if (h == 6) {                              // 4E: 0xD4 00 + シリアル番号 + 00 00
            r[0] = (byte) 0xD4;
            r[2] = b[2]; r[3] = b[3]; r[4] = 0; r[5] = 0;
        }
        System.arraycopy(b, h, r, h, 5);           // ネットワーク〜局番号をそのまま返す
        r[h + 5] = (byte) dl; r[h + 6] = (byte) (dl >> 8);
        r[h + 7] = (byte) end; r[h + 8] = (byte) (end >> 8);
        System.arraycopy(data, 0, r, h + 9, data.length);
        return r;
    }
}
