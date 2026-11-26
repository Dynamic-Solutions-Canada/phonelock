import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal desktop "parent" for testing, using only the JDK.
 *   java scripts/dev/ParentCli.java send '{"t":"status","full":true}'
 *   java scripts/dev/ParentCli.java read 10m        # decrypt recent messages
 *   java scripts/dev/ParentCli.java newsecret       # print a fresh 32-byte secret
 * Family chat (docs/chat.md):
 *   java scripts/dev/ParentCli.java chat 'Dinner at six'   # a parent's chat message (new cid each time)
 *   java scripts/dev/ParentCli.java chatsync 0             # ask for the history after this seq
 *   java scripts/dev/ParentCli.java chatlimit 5 30         # slow mode 5 min, timeout 30 min (0 0 = off)
 *   java scripts/dev/ParentCli.java chats 1h               # decrypt recent chat, chatin, chatread, chatlog
 * Reads .secrets/devices/$DEVICE/pairing.properties (server, topic, secret).
 */
public class ParentCli {
    static final byte[] AAD = "phonelock/1".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] a) throws Exception {
        if (a[0].equals("newsecret")) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            System.out.println(Base64.getUrlEncoder().withoutPadding().encodeToString(k));
            return;
        }
        var p = new java.util.Properties();
        // DEVICE=<slug> picks .secrets/devices/<slug>/pairing.properties
        String dev = System.getenv().getOrDefault("DEVICE", "kid");
        p.load(Files.newBufferedReader(Path.of(".secrets/devices/" + dev + "/pairing.properties")));
        String server = p.getProperty("server"), topic = p.getProperty("topic");
        byte[] key = Base64.getUrlDecoder().decode(p.getProperty("secret"));
        HttpClient http = HttpClient.newHttpClient();
        if (a[0].equals("chat")) {
            String json = "{\"t\":\"chat\",\"cid\":\"" + randomId() + "\",\"text\":" + quote(a[1]) + ",\"at\":"
                    + System.currentTimeMillis() + "}";
            send(http, server, topic, key, json);
        } else if (a[0].equals("chatsync")) {
            long after = Long.parseLong(a.length > 1 ? a[1] : "0");
            send(http, server, topic, key, "{\"t\":\"chatsync\",\"after\":" + after + "}");
        } else if (a[0].equals("chatlimit")) {
            long minutes = a.length > 2 ? Long.parseLong(a[2]) : 0;
            long until = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
            send(
                    http,
                    server,
                    topic,
                    key,
                    "{\"t\":\"chatlimit\",\"every\":" + Integer.parseInt(a[1]) + ",\"until\":" + until + "}");
        } else if (a[0].equals("send")) {
            send(http, server, topic, key, a[1]);
        } else if (a[0].equals("read")) {
            read(http, server, topic, key, a.length > 1 ? a[1] : "10m", false);
        } else if (a[0].equals("chats")) {
            read(http, server, topic, key, a.length > 1 ? a[1] : "10m", true);
        }
    }

    /** Seals {@code json} (the common fields are added here) and posts it to the topic. */
    static void send(HttpClient http, String server, String topic, byte[] key, String json) throws Exception {
        json = json.replaceFirst(
                "\\{",
                "{\"v\":1,\"id\":\"" + randomId() + "\",\"ts\":" + System.currentTimeMillis() + ",\"from\":\"PC\",");
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(AAD);
        byte[] ct = c.doFinal(json.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[12 + ct.length];
        System.arraycopy(iv, 0, all, 0, 12);
        System.arraycopy(ct, 0, all, 12, ct.length);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(all);
        var r = http.send(
                HttpRequest.newBuilder(URI.create(server + "/" + topic))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        System.out.println("sent " + r.statusCode() + ": " + json);
    }

    /**
     * Prints the topic's messages since {@code since} (ntfy's form: 10m, 1h, a message id), decrypted. With
     * {@code chatOnly}, only the family chat's messages ({@code chat}, {@code chatin}, {@code chatread},
     * {@code chatlog}, including a {@code chatlog} sent as {@code big}).
     */
    static void read(HttpClient http, String server, String topic, byte[] key, String since, boolean chatOnly)
            throws Exception {
        var r = http.send(
                HttpRequest.newBuilder(URI.create(server + "/" + topic + "/json?poll=1&since=" + since))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        for (String line : r.body().split("\n")) {
            int i = line.indexOf("\"message\":\"");
            if (i < 0) continue;
            String msg = line.substring(i + 11, line.indexOf('"', i + 11));
            try {
                byte[] all = Base64.getUrlDecoder().decode(msg);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, all, 0, 12));
                c.updateAAD(AAD);
                String plain = new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
                if (!chatOnly || isChat(plain)) System.out.println(plain);
                // A message too big for ntfy travels as a "big" wrapper with the real one attached
                // (raw iv[12] + AES-GCM, AAD phonelock/blob/<wrapper id>): print that one too.
                int u = line.indexOf("\"url\":\"");
                if (plain.contains("\"t\":\"big\"") && u >= 0) {
                    String url = line.substring(u + 7, line.indexOf('"', u + 7));
                    String id = plain.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
                    byte[] blob = http.send(
                                    HttpRequest.newBuilder(URI.create(url)).build(),
                                    HttpResponse.BodyHandlers.ofByteArray())
                            .body();
                    Cipher b = Cipher.getInstance("AES/GCM/NoPadding");
                    b.init(
                            Cipher.DECRYPT_MODE,
                            new SecretKeySpec(key, "AES"),
                            new GCMParameterSpec(128, blob, 0, 12));
                    b.updateAAD(("phonelock/blob/" + id).getBytes(StandardCharsets.US_ASCII));
                    String inner = new String(b.doFinal(blob, 12, blob.length - 12), StandardCharsets.UTF_8);
                    if (!chatOnly || isChat(inner)) System.out.println(inner);
                }
            } catch (Exception e) {
                if (!chatOnly) System.out.println("(not ours) " + msg);
            }
        }
    }

    /** Whether a decrypted message is one of the family chat's. */
    static boolean isChat(String plain) {
        return plain.matches("(?s).*\"t\":\"chat(in|read|log)?\".*");
    }

    static String randomId() {
        byte[] id = new byte[9];
        new SecureRandom().nextBytes(id);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id);
    }

    /** {@code s} as a JSON string literal. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
