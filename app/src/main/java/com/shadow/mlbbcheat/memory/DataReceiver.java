package com.shadow.mlbbcheat.memory;

import com.shadow.mlbbcheat.models.PlayerData;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DataReceiver implements AutoCloseable {

    public interface DataListener {
        void onPlayersUpdated(List<PlayerData> players);
    }

    private static final int PORT = 48123;
    private static final int FRAME_SIZE = 17;

    private static final DataReceiver INSTANCE = new DataReceiver();

    public static DataReceiver getInstance() {
        return INSTANCE;
    }

    private final List<PlayerData> players = new CopyOnWriteArrayList<>();
    private volatile float playerLevel = 1f;
    private volatile boolean droneViewEnabled = false;
    private volatile DataListener listener;
    private volatile boolean running = false;
    private ServerSocket server;

    // Rolling-XOR decode state (self-synchronizing, resets per connection)
    private static final byte BOOTSTRAP_KEY = 0x5A;
    private byte xorKey = BOOTSTRAP_KEY;

    public void start() throws IOException {
        running = true;
        server = new ServerSocket(PORT, 4, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "data-receiver");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = server.accept()) {
                currentSocket = socket;
                xorKey = BOOTSTRAP_KEY; // new Lua session
                byte[] buf = new byte[FRAME_SIZE];
                int read;
                try (InputStream in = socket.getInputStream()) {
                    while (running && (read = in.read(buf)) != -1) {
                        if (read < FRAME_SIZE) continue;
                        handleFrame(decodeFrame(buf));
                    }
                }
            } catch (IOException ignored) {
            } finally {
                currentSocket = null;
            }
        }
    }

    private volatile Socket currentSocket;

    /**
     * Send a command frame to the Lua bridge over the already-accepted
     * loopback connection. Command frames (0xE0 marker) are plaintext.
     * Best-effort: never throws.
     */
    public void sendCommand(byte[] frame) {
        Socket s = currentSocket;
        if (s == null || frame == null || frame.length != FRAME_SIZE) return;
        try {
            java.io.OutputStream out = s.getOutputStream();
            out.write(frame);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    /** True while the Lua bridge is connected. */
    public boolean bridgeConnected() {
        return currentSocket != null;
    }

    /**
     * Decode a rolling-XOR frame. Byte 14 (reserved slot) carries the key
     * for the NEXT frame in plaintext; bytes 0-13 and 15-16 are XORed with
     * the current key. TCP ordering makes this self-synchronizing.
     */
    private byte[] decodeFrame(byte[] frame) {
        byte[] out = new byte[FRAME_SIZE];
        for (int i = 0; i < FRAME_SIZE; i++) {
            if (i == 14) {
                out[i] = frame[i];
                continue;
            }
            out[i] = (byte) (frame[i] ^ xorKey);
        }
        xorKey = frame[14];
        return out;
    }

    private void handleFrame(byte[] frame) {
        try {
            byte type = frame[0];
            if (type == 0x01) {
                PlayerData p = PlayerData.fromBytes(frame);
                if (p.id >= 0) {
                    players.add(p);
                    if (listener != null) listener.onPlayersUpdated(players);
                }
            } else if (type == 0x02) {
                ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
                playerLevel = b.getFloat(2);
            } else if (type == 0x03) {
                droneViewEnabled = frame[2] != 0;
            } else if (type == 0x04) {
                players.clear();
            }
        } catch (Throwable t) {
            com.shadow.mlbbcheat.utils.CrashLog.log("handleFrame: " + t);
        }
    }

    public List<PlayerData> getPlayers() {
        return Collections.unmodifiableList(new ArrayList<>(players));
    }

    public float getPlayerLevel() {
        return playerLevel;
    }

    public boolean isDroneViewEnabled() {
        return droneViewEnabled;
    }

    public void setListener(DataListener l) {
        this.listener = l;
    }

    @Override
    public void close() {
        stop();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    public static byte[] encodeFrame(PlayerData p) {
        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = 0x01;
        frame[1] = (byte) p.id;
        frame[2] = (byte) (p.isEnemy ? 1 : 0);
        ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(3, p.x);
        b.putFloat(7, p.y);
        b.putFloat(11, p.hp);
        return frame;
    }
}
