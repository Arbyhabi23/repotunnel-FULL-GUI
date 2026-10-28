package hev.htproxy;

// Harus persis sama dengan yang didaftarkan engine (src/hev-jni.c):
// nama method, tipe parameter, dan tipe kembalian.
public class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
