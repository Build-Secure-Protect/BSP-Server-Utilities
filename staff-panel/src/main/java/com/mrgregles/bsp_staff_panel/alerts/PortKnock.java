package com.mrgregles.bsp_staff_panel.alerts;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * Whether a game server's port takes a connection (SU-167, the brief): a plain TCP connect, closed again at once, never a
 * Minecraft status ping (a Forge server's mod list makes that reply too large to read reliably, Velocity Additions' bug B1,
 * and a frozen server can still answer pings from its network thread). Open but silent means frozen; closed means crashed
 * or stopped; no answer means the server or its machine is unreachable.
 */
public final class PortKnock {
    public static final int TIMEOUT_MS = 3_000;

    private PortKnock() {
    }

    public static ServerWatch.Port knock(String address) {
        return knock(address, TIMEOUT_MS);
    }

    public static ServerWatch.Port knock(String address, int timeoutMs) {
        InetSocketAddress at = parse(address);
        if (at == null) {
            return ServerWatch.Port.UNKNOWN;
        }
        try (Socket s = new Socket()) {
            s.connect(at, timeoutMs);
            return ServerWatch.Port.OPEN;
        } catch (ConnectException refused) {
            return ServerWatch.Port.CLOSED;
        } catch (SocketTimeoutException | NoRouteToHostException none) {
            return ServerWatch.Port.NO_ANSWER;
        } catch (IOException | RuntimeException other) {
            return ServerWatch.Port.NO_ANSWER;
        }
    }

    /** {@code host:port} or {@code [v6]:port}, resolved; null when it is not one, or says nothing ({@code 0.0.0.0}, empty). */
    static InetSocketAddress parse(String address) {
        String[] hp = split(address);
        return hp == null ? null : new InetSocketAddress(hp[0], Integer.parseInt(hp[1]));
    }

    /** Whether this text is an address the panel can check. */
    public static boolean usable(String address) {
        return split(address) != null;
    }

    private static String[] split(String address) {
        if (address == null) {
            return null;
        }
        String t = address.trim();
        int colon = t.lastIndexOf(':');
        if (colon <= 0 || colon == t.length() - 1) {
            return null;
        }
        String host = t.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank() || host.equals("0.0.0.0") || host.equals("::") || host.contains(" ")) {
            return null;
        }
        try {
            int p = Integer.parseInt(t.substring(colon + 1));
            return p >= 1 && p <= 65535 ? new String[]{host, String.valueOf(p)} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
