
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;

public class Backend {
    static final int PORT=8080;
    static final Pattern ENTITY=Pattern.compile(
        "\\{\"id\":(\\d+),\"x\":([-\\d.]+),\"y\":([-\\d.]+)\\}");
    static final Pattern MOVE=Pattern.compile(
        "\"type\"\\s*:\\s*\"move\".*?\"id\"\\s*:\\s*(\\d+).*?\"dx\"\\s*:\\s*(-?[\\d.]+).*?\"dy\"\\s*:\\s*(-?[\\d.]+)");
    static final Pattern MIGRATE=Pattern.compile(
        "\"type\":\"migrate\",\"id\":(\\d+),\"x\":([-\\d.]+),\"y\":([-\\d.]+),\"vx\":([-\\d.]+),\"vy\":([-\\d.]+)");
    static final Pattern ACK=Pattern.compile(
        "\"type\":\"ack\",\"id\":(\\d+)");
    static final Map<Integer,String> entities=new TreeMap<>();
    static final Map<Integer,Socket> owners=new HashMap<>();
    static final Map<Integer,Socket> pendingSource=new HashMap<>();
    static final Object lock=new Object();
    static Socket a,b,client;
    static BufferedWriter clientOut;

    public static void main(String[] args) throws Exception {
        ServerSocket server=new ServerSocket(PORT);
        System.out.println("Backend listening on "+PORT);
        client=server.accept();
        clientOut=new BufferedWriter(new OutputStreamWriter(client.getOutputStream()));
        a=new Socket("127.0.0.1",5000);
        b=new Socket("127.0.0.1",5001);
        Thread ta=new Thread(()->readShard(a,"A"));
        Thread tb=new Thread(()->readShard(b,"B"));
        ta.start(); tb.start();
        readClient();
    }
    static void readShard(Socket source,String name) {
        try(BufferedReader in=new BufferedReader(new InputStreamReader(source.getInputStream()))) {
            String line;
            while((line=in.readLine())!=null) {
                Matcher m=MIGRATE.matcher(line);
                if(m.find()) {
                    int id=Integer.parseInt(m.group(1));
                    Socket target=source==a?b:a;
                    synchronized(lock) { pendingSource.put(id,source); }
                    send(target,line);
                    System.out.println("HANDOFF id="+id+" "+name+" -> "+(target==a?"A":"B"));
                    continue;
                }
                Matcher ack=ACK.matcher(line);
                if(ack.find()) {
                    int id=Integer.parseInt(ack.group(1));
                    Socket original;
                    synchronized(lock) {
                        original=pendingSource.remove(id);
                        if(original!=null)owners.put(id,source);
                    }
                    if(original!=null) {
                        send(original,"{\"type\":\"ack\",\"id\":"+id+"}");
                        System.out.println("CONFIRMED id="+id);
                        publish();
                    }
                    continue;
                }
                Matcher e=ENTITY.matcher(line);
                synchronized(lock) {
                    while(e.find()) {
                        int id=Integer.parseInt(e.group(1));
                        entities.put(id,e.group());
                        owners.put(id,source);
                    }
                }
                if(line.contains("\"entities\""))publish();
            }
        } catch(Exception ex) {
            System.err.println("Shard "+name+": "+ex.getMessage());
        }
    }
    static void publish() {
        synchronized(lock) {
            if(clientOut==null)return;
            try {
                clientOut.write("{\"entities\":[");
                boolean first=true;
                for(String e:entities.values()) {
                    if(!first)clientOut.write(",");
                    clientOut.write(e); first=false;
                }
                clientOut.write("]}\n"); clientOut.flush();
            } catch(IOException ex) {
                System.err.println("Publish: "+ex.getMessage());
            }
        }
    }
    static void readClient() {
        try(BufferedReader in=new BufferedReader(new InputStreamReader(client.getInputStream()))) {
            String line;
            while((line=in.readLine())!=null) {
                Matcher m=MOVE.matcher(line);
                if(!m.find())continue;
                int id=Integer.parseInt(m.group(1));
                Socket target;
                synchronized(lock) { target=owners.get(id); }
                if(target!=null)send(target,line);
            }
        } catch(IOException ex) {
            System.err.println("Client: "+ex.getMessage());
        }
    }
    static void send(Socket socket,String line) {
        try {
            synchronized(socket) {
                BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
                out.write(line); out.newLine(); out.flush();
            }
        } catch(IOException ex) {
            System.err.println("Send: "+ex.getMessage());
        }
    }
}
