package net.melbourne.modules.impl.misc;

import net.melbourne.Managers;
import net.melbourne.events.SubscribeEvent;
import net.melbourne.events.impl.PlayerUpdateEvent;
import net.melbourne.events.impl.PlayerTravelEvent;
import net.melbourne.modules.Category;
import net.melbourne.modules.Feature;
import net.melbourne.modules.FeatureInfo;
import net.melbourne.settings.types.ModeSetting;
import net.melbourne.settings.types.NumberSetting;
import net.melbourne.settings.types.TextSetting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;

@FeatureInfo(name = "Robotics", category = Category.Misc)
public class RoboticsFeature extends Feature {
    public ModeSetting role = new ModeSetting("Role", "Role", "Host", new String[]{"Host", "Server"});
    public TextSetting hostIp = new TextSetting("HostIP", "Host IP", "127.0.0.1", () -> role.getValue().equals("Server"));
    public NumberSetting port = new NumberSetting("Port", "Port", 4444, 1024, 65535);

    private ServerSocket serverSocket;
    private final CopyOnWriteArrayList<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private boolean running;

    private Vec3d poa = null;
    private int lastSlot = -1;
    private boolean lastUsingItem = false;
    private boolean hostJumping = false;

    @Override
    public String getInfo() {
        return role.getValue().equals("Host") ? "Host, " + clients.size() : "Server";
    }

    @Override
    public void onEnable() {
        running = true;
        poa = null;
        hostJumping = false;
        lastSlot = -1;
        lastUsingItem = false;

        if (role.getValue().equals("Host")) {
            new Thread(this::runHost, "Melbourne-Host").start();
        } else {
            new Thread(this::runClient, "Melbourne-Client").start();
        }
    }

    @Override
    public void onDisable() {
        running = false;
        stopMovementKeys();
        try {
            if (serverSocket != null) serverSocket.close();
            clients.forEach(ClientHandler::close);
            clients.clear();
        } catch (Exception ignored) {}
    }

    public void syncToggle(Feature feature, boolean state) {
        if (isEnabled() && role.getValue().equals("Host")) {
            for (ClientHandler client : clients) {
                client.sendModule(feature.getName(), state);
            }
        }
    }

    private void runHost() {
        try {
            serverSocket = new ServerSocket(port.getValue().intValue());
            while (running) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                clients.add(new ClientHandler(socket));
            }
        } catch (IOException ignored) {}
    }

    private void runClient() {
        while (running) {
            try (Socket socket = new Socket(hostIp.getValue(), port.getValue().intValue())) {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                while (running) {
                    byte type = in.readByte();

                    if (type == 0) {
                        in.readUTF();
                        double x = in.readDouble();
                        double y = in.readDouble();
                        double z = in.readDouble();
                        float yaw = in.readFloat();
                        float pitch = in.readFloat();
                        mc.execute(() -> {
                            if (getNull()) return;
                            poa = new Vec3d(x, y, z);
                            mc.player.setYaw(yaw);
                            mc.player.setPitch(pitch);
                        });
                    } else if (type == 1) {
                        String name = in.readUTF();
                        boolean state = in.readBoolean();
                        mc.execute(() -> {
                            Feature f = Managers.FEATURE.getFeatureByName(name);
                            if (f != null) f.setEnabled(state, false);
                        });
                    } else if (type == 2) {
                        int slot = in.readInt();
                        mc.execute(() -> {
                            if (!getNull()) mc.player.getInventory().setSelectedSlot(slot);
                        });
                    } else if (type == 3) {
                        boolean use = in.readBoolean();
                        mc.execute(() -> {
                            if (getNull()) return;
                            mc.options.useKey.setPressed(use);
                            if (use) mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                            else mc.player.stopUsingItem();
                        });
                    } else if (type == 4) {
                        boolean jump = in.readBoolean();
                        mc.execute(() -> {
                            if (getNull()) return;
                            hostJumping = jump;
                            mc.options.jumpKey.setPressed(jump);
                        });
                    }
                }
            } catch (Exception ignored) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored2) {}
            }
        }
    }

    @SubscribeEvent
    public void onUpdate(PlayerUpdateEvent event) {
        if (getNull() || !role.getValue().equals("Host") || clients.isEmpty()) return;

        boolean jump = mc.options.jumpKey.isPressed();
        String name = mc.player.getName().getString();
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        float yaw = mc.player.getYaw();
        float pitch = mc.player.getPitch();
        int slot = mc.player.getInventory().getSelectedSlot();
        boolean use = mc.player.isUsingItem();

        for (ClientHandler c : clients) {
            c.sendUpdate(name, x, y, z, yaw, pitch);
            if (slot != lastSlot) c.sendSlot(slot);
            if (use != lastUsingItem) c.sendUseItem(use);
            c.sendJump(jump);
        }

        lastSlot = slot;
        lastUsingItem = use;
    }

    @SubscribeEvent
    public void onTravel(PlayerTravelEvent event) {
        if (getNull() || !role.getValue().equals("Server") || poa == null) return;

        Vec3d pos = mc.player.getPos();
        Vec3d diff = poa.subtract(pos);

        double distSq = diff.x * diff.x + diff.z * diff.z;
        if (distSq > 256.0) { poa = null; return; }

        double horiz = Math.sqrt(distSq);
        if (horiz < 0.15) {
            event.setMovementInput(new Vec3d(0, event.getMovementInput().y, 0));
            return;
        }

        double max = 1.0;
        double strafe = 0.0;

        double nx = diff.x / horiz;
        double nz = diff.z / horiz;

        float yawRad = (float) Math.toRadians(mc.player.getYaw());
        double fx = -Math.sin(yawRad);
        double fz =  Math.cos(yawRad);
        double rx =  fz;
        double rz = -fx;

        double forward = nx * fx + nz * fz;
        double side = nx * rx + nz * rz;

        forward = clamp(forward, -max, max);
        side = clamp(side, -max, max);

        event.setMovementInput(new Vec3d(side, event.getMovementInput().y, forward));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }


    private void stopMovementKeys() {
        if (mc == null || mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    private class ClientHandler {
        private final Socket s;
        private final DataOutputStream out;

        public ClientHandler(Socket s) throws IOException {
            this.s = s;
            this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        }

        public synchronized void sendUpdate(String n, double x, double y, double z, float yaw, float p) {
            try {
                out.writeByte(0);
                out.writeUTF(n);
                out.writeDouble(x);
                out.writeDouble(y);
                out.writeDouble(z);
                out.writeFloat(yaw);
                out.writeFloat(p);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public synchronized void sendModule(String n, boolean s) {
            try {
                out.writeByte(1);
                out.writeUTF(n);
                out.writeBoolean(s);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public synchronized void sendSlot(int sl) {
            try {
                out.writeByte(2);
                out.writeInt(sl);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public synchronized void sendUseItem(boolean u) {
            try {
                out.writeByte(3);
                out.writeBoolean(u);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public synchronized void sendJump(boolean j) {
            try {
                out.writeByte(4);
                out.writeBoolean(j);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public void close() {
            clients.remove(this);
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
