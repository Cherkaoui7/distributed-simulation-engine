
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class GameClient extends JPanel {
    static final String HOST = "127.0.0.1";
    static final int PORT = 8080;
    static final int WORLD_W = 1000;
    static final int WORLD_H = 650;
    static final int HEADER_H = 40;
    static final double SMOOTHING = 0.25;

    static class Entity {
        final int id;
        final double x, y;

        Entity(int id, double x, double y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    private volatile Entity[] entities = new Entity[0];
    private final Map<Integer, Point2D.Double> rendered = new HashMap<>();
    private volatile String status = "Connecting...";
    private BufferedWriter writer;
    private int selectedId = 1;

    private static final Pattern ENTITY = Pattern.compile(
        "\\{\"id\":(\\d+),\"x\":([-\\d.]+),\"y\":([-\\d.]+)\\}"
    );

    public GameClient() {
        setPreferredSize(new Dimension(1000, 700));
        setBackground(new Color(18, 24, 38));
        setFocusable(true);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                double dx = 0, dy = 0;

                switch (e.getKeyCode()) {
                    case KeyEvent.VK_W:
                    case KeyEvent.VK_UP:
                        dy = -5;
                        break;
                    case KeyEvent.VK_S:
                    case KeyEvent.VK_DOWN:
                        dy = 5;
                        break;
                    case KeyEvent.VK_A:
                    case KeyEvent.VK_LEFT:
                        dx = -5;
                        break;
                    case KeyEvent.VK_D:
                    case KeyEvent.VK_RIGHT:
                        dx = 5;
                        break;
                    default:
                        return;
                }

                sendMove(dx, dy);
            }
        });

        new javax.swing.Timer(16, event -> repaint()).start();
        startNetwork();
    }

    private void startNetwork() {
        Thread t = new Thread(() -> {
            try (Socket socket = new Socket(HOST, PORT);
                 BufferedReader in = new BufferedReader(
                     new InputStreamReader(socket.getInputStream()))) {

                writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream()));

                status = "Connected | Entities: 0 | WASD / Arrows";
                SwingUtilities.invokeLater(this::requestFocusInWindow);

                String line;
                while ((line = in.readLine()) != null) {
                    Matcher m = ENTITY.matcher(line);
                    ArrayList<Entity> next = new ArrayList<>();

                    while (m.find()) {
                        try {
                            int id = Integer.parseInt(m.group(1));
                            double x = Double.parseDouble(m.group(2));
                            double y = Double.parseDouble(m.group(3));
                            next.add(new Entity(id, x, y));
                        } catch (NumberFormatException ignored) {
                            // Ignore malformed entity data.
                        }
                    }

                    entities = next.toArray(new Entity[0]);
                    status = "Connected | Entities: " + entities.length
                        + " | WASD / Arrows";
                }

                status = "Disconnected from backend";
            } catch (IOException ex) {
                status = "Connection error: " + ex.getMessage();
            }
        }, "network-reader");

        t.setDaemon(true);
        t.start();
    }

    private void sendMove(double dx, double dy) {
        BufferedWriter out = writer;
        if (out == null) return;

        String message = String.format(Locale.US,
            "{\"type\":\"move\",\"id\":%d,\"dx\":%.1f,\"dy\":%.1f}",
            selectedId, dx, dy);

        try {
            synchronized (out) {
                out.write(message);
                out.newLine();
                out.flush();
            }
        } catch (IOException ex) {
            status = "Move failed: " + ex.getMessage();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D d = (Graphics2D) g.create();

        int w = getWidth();
        int h = getHeight();
        int worldH = h - HEADER_H;

        d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);

        d.setColor(new Color(45, 57, 75));
        for (int i = 0; i <= 10; i++) {
            int x = i * w / 10;
            int y = HEADER_H + i * worldH / 10;
            d.drawLine(x, HEADER_H, x, h);
            d.drawLine(0, y, w, y);
        }

        Entity[] snapshot = entities;
        Set<Integer> present = new HashSet<>();

        for (Entity e : snapshot) {
            present.add(e.id);

            Point2D.Double p = rendered.computeIfAbsent(
                e.id, id -> new Point2D.Double(e.x, e.y));

            p.x += (e.x - p.x) * SMOOTHING;
            p.y += (e.y - p.y) * SMOOTHING;

            double ex = Math.max(0, Math.min(WORLD_W, p.x));
            double ey = Math.max(0, Math.min(WORLD_H, p.y));

            int x = (int) (ex * w / WORLD_W);
            int y = HEADER_H + (int) (ey * worldH / WORLD_H);

            if (e.id == selectedId) {
                d.setColor(Color.YELLOW);
                d.fillOval(x - 12, y - 12, 24, 24);
                d.setColor(Color.CYAN);
                d.fillOval(x - 8, y - 8, 16, 16);
            } else {
                d.setColor(new Color(0, 220, 140));
                d.fillOval(x - 4, y - 4, 8, 8);
            }
        }

        rendered.keySet().retainAll(present);

        d.setColor(Color.WHITE);
        d.fillRect(0, 0, w, HEADER_H);
        d.setColor(Color.BLACK);
        d.drawString(status, 20, 25);
        d.dispose();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Distributed Simulation Client");
            GameClient c = new GameClient();

            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setContentPane(c);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            c.requestFocusInWindow();
        });
    }
}
