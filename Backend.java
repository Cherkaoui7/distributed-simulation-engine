import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class Backend {
    static final int PORT = 8080;
    static final Pattern ENTITY = Pattern.compile(
        "\\{\"id\":(\\d+),\"x\":([-\\d.]+),\"y\":([-\\d.]+)(?:,\"type\":(\\d+),\"health\":(\\d+),\"team\":(\\d+))?\\}");
    static final Pattern MOVE = Pattern.compile(
        "\"type\"\\s*:\\s*\"move\".*?\"id\"\\s*:\\s*(\\d+).*?\"dx\"\\s*:\\s*(-?[\\d.]+).*?\"dy\"\\s*:\\s*(-?[\\d.]+)");
    static final Pattern SHOOT = Pattern.compile(
        "\"type\"\\s*:\\s*\"shoot\".*?\"player_id\"\\s*:\\s*(\\d+).*?\"angle\"\\s*:\\s*(-?[\\d.]+)");
    static final Pattern MIGRATE = Pattern.compile(
        "\"type\":\"migrate\",\"id\":(\\d+),\"x\":([-\\d.]+),\"y\":([-\\d.]+),\"vx\":([-\\d.]+),\"vy\":([-\\d.]+),\"entity_type\":(\\d+),\"health\":(\\d+),\"team\":(\\d+)");
    static final Pattern ACK = Pattern.compile("\"type\":\"ack\",\"id\":(\\d+)");
    static final Map<Integer, String> entities = new TreeMap<>();
    static final Map<Integer, Socket> owners = new HashMap<>();
    static final Map<Integer, Socket> pendingSource = new HashMap<>();
    static final Object lock = new Object();
    static Socket a, b, client;
    static BufferedWriter clientOut;

    public static void main(String[] args) throws Exception {
        ServerSocket server = new ServerSocket(PORT);
        System.out.println("Backend listening on " + PORT);
        client = server.accept();
        clientOut = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
        a = new Socket("127.0.0.1", 5000);
        b = new Socket("127.0.0.1", 5001);
        new Thread(() -> readShard(a, "A"), "shard-A-reader").start();
        new Thread(() -> readShard(b, "B"), "shard-B-reader").start();
        readClient();
    }

    static void readShard(Socket source, String name) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(source.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher migration = MIGRATE.matcher(line);
                if (migration.find()) {
                    int id = Integer.parseInt(migration.group(1));
                    Socket target = source == a ? b : a;
                    synchronized (lock) { pendingSource.put(id, source); }
                    send(target, line);
                    System.out.println("HANDOFF id=" + id + " " + name + " -> " + (target == a ? "A" : "B"));
                    continue;
                }

                Matcher ack = ACK.matcher(line);
                if (ack.find()) {
                    int id = Integer.parseInt(ack.group(1));
                    Socket original;
                    synchronized (lock) {
                        original = pendingSource.remove(id);
                        if (original != null) owners.put(id, source);
                    }
                    if (original != null) {
                        send(original, "{\"type\":\"ack\",\"id\":" + id + "}");
                        System.out.println("CONFIRMED id=" + id);
                        publish();
                    }
                    continue;
                }

                Matcher entity = ENTITY.matcher(line);
                Set<Integer> seen = new HashSet<>();
                synchronized (lock) {
                    while (entity.find()) {
                        int id = Integer.parseInt(entity.group(1));
                        seen.add(id);
                        entities.put(id, entity.group());
                        owners.put(id, source);
                    }
                    if (line.contains("\"entities\"")) {
                        Iterator<Map.Entry<Integer, String>> it = entities.entrySet().iterator();
                        while (it.hasNext()) {
                            int id = it.next().getKey();
                            if (owners.get(id) == source && !seen.contains(id) && !pendingSource.containsKey(id)) {
                                it.remove();
                                owners.remove(id);
                            }
                        }
                    }
                }
                if (line.contains("\"entities\"")) publish();
            }
        } catch (Exception ex) {
            System.err.println("Shard " + name + ": " + ex.getMessage());
        }
    }

    static void publish() {
        synchronized (lock) {
            if (clientOut == null) return;
            try {
                clientOut.write("{\"entities\":[");
                boolean first = true;
                for (String entity : entities.values()) {
                    if (!first) clientOut.write(",");
                    clientOut.write(entity);
                    first = false;
                }
                clientOut.write("]}\n");
                clientOut.flush();
            } catch (IOException ex) {
                System.err.println("Publish: " + ex.getMessage());
            }
        }
    }

    static void readClient() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher move = MOVE.matcher(line);
                Matcher shoot = SHOOT.matcher(line);
                int id;
                if (move.find()) {
                    id = Integer.parseInt(move.group(1));
                } else if (shoot.find()) {
                    id = Integer.parseInt(shoot.group(1));
                } else {
                    continue;
                }
                Socket target;
                synchronized (lock) { target = owners.get(id); }
                if (target != null) send(target, line);
            }
        } catch (IOException ex) {
            System.err.println("Client: " + ex.getMessage());
        }
    }

    static void send(Socket socket, String line) {
        try {
            synchronized (socket) {
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                out.write(line);
                out.newLine();
                out.flush();
            }
        } catch (IOException ex) {
            System.err.println("Send: " + ex.getMessage());
        }
    }
}
