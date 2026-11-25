package jp.bandsaw.pc;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** この PC の IPv4 アドレス（PLC の交信先に登録する値） */
final class Net {
    private Net() {}

    static String ipList() {
        List<String> l = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) l.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return l.isEmpty() ? "ネットワーク未接続" : String.join(" / ", l);
    }
}
