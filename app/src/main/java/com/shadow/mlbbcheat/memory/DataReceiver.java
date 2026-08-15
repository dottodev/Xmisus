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

    public void start() throws IOException {
        running = true;
        server = new ServerSocket(PORT, 4, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "data-receiver");
        t.setDaemon(true);
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = server.accept();
                 InputStream in = socket.getInputStream()) {
                byte[] buf = new byte[FRAME_SIZE];
                int read;
                while (running && (read = in.read(buf)) != -1) {
                    if (read < FRAME_SIZE) continue;
                    handleFrame(buf);
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void handleFrame(byte[] frame) {
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
    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    public static byte[] encodeFrame(PlayerData p) {
        if (p.id < 0 || p.id > 255) return null;
        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = 0x01;
        frame[1] = (byte) (p.isEnemy ? 1 : 0);
        ByteBuffer b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(2, p.x);
        b.putFloat(6, p.y);
        b.putFloat(10, p.hp);
        return frame;
    }
}
