package com.blamenet.collect;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 真实连通性测试。
 * <p>
 * 用于代理服务器可达性等场景：只发起一个普通的 TCP 连接尝试并立即关闭，
 * 不发送任何应用层数据、不做端口扫描，属于只读探测。
 */
public final class NetTest {

    private NetTest() {
    }

    /** TCP 连接测试结果。 */
    public record TcpConnectResult(String host, int port, boolean connected, long durationMillis, String message) {

        public String displayCommand() {
            return "TCP 连接测试 " + host + ":" + port + "（BlameNet 内置只读套接字连接，超时 3000ms）";
        }

        public String rawOutput() {
            return "目标：" + host + ":" + port + "\n结果：" + (connected ? "连接成功（端口开放）" : "连接失败")
                    + "\n耗时：" + durationMillis + " ms\n说明：" + message;
        }
    }

    /** 尝试建立 TCP 连接，默认 3 秒超时。 */
    public static TcpConnectResult tcpConnect(String host, int port, int timeoutMillis) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            long cost = (System.nanoTime() - start) / 1_000_000L;
            return new TcpConnectResult(host, port, true, cost, "三次握手完成，目标端口可达");
        } catch (IOException e) {
            long cost = (System.nanoTime() - start) / 1_000_000L;
            return new TcpConnectResult(host, port, false, cost, e.getClass().getSimpleName() + "：" + e.getMessage());
        }
    }
}
