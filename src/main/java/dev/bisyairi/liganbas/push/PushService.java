package dev.bisyairi.liganbas.push;

import dev.bisyairi.liganbas.AppProperties;
import jakarta.annotation.PostConstruct;
import tools.jackson.databind.json.JsonMapper;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.spec.ECNamedCurveGenParameterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

/** Sends Web Push messages signed with this server's VAPID key (generated on first start). */
@Service
public class PushService {
    public enum Result { OK, GONE, ERROR }

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final AppProperties props;
    private final JsonMapper json;
    private String publicKey;
    private nl.martijndwars.webpush.PushService client;

    public PushService(AppProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    @PostConstruct
    void init() throws Exception {
        Path file = Path.of(props.dataDir(), "vapid.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (var in = Files.newInputStream(file)) {
                p.load(in);
            }
        } else {
            Files.createDirectories(file.getParent());
            KeyPairGenerator g = KeyPairGenerator.getInstance("ECDH", BouncyCastleProvider.PROVIDER_NAME);
            g.initialize(new ECNamedCurveGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            byte[] pub = ((ECPublicKey) kp.getPublic()).getQ().getEncoded(false);  // uncompressed point
            byte[] priv = fixedLength(((ECPrivateKey) kp.getPrivate()).getD(), 32);
            p.setProperty("public", b64(pub));
            p.setProperty("private", b64(priv));
            try (var out = Files.newOutputStream(file)) {
                p.store(out, "VAPID keys - keep private, never regenerate (it would invalidate every subscription)");
            }
            try {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX file system
            }
            log.info("Generated a new VAPID key pair at {}", file.toAbsolutePath());
        }
        this.publicKey = p.getProperty("public");
        this.client = new nl.martijndwars.webpush.PushService(publicKey, p.getProperty("private"), props.vapidSubject());
    }

    public String publicKey() {
        return publicKey;
    }

    public Result send(String endpoint, String p256dh, String auth, String title, String body, String tag) {
        try {
            byte[] payload = json.writeValueAsBytes(Map.of("title", title, "body", body, "tag", tag));
            Notification n = new Notification(new Subscription(endpoint, new Subscription.Keys(p256dh, auth)), new String(payload, StandardCharsets.UTF_8));
            HttpResponse resp = client.send(n);
            int status = resp.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                return Result.OK;
            }
            if (status == 404 || status == 410) {
                return Result.GONE;  // subscription expired or user revoked permission
            }
            log.warn("Push service answered HTTP {}", status);
            return Result.ERROR;
        } catch (IOException e) {
            log.warn("Push failed: {}", e.toString());
            return Result.ERROR;
        } catch (Exception e) {
            log.warn("Push failed unexpectedly", e);
            return Result.ERROR;
        }
    }

    private static byte[] fixedLength(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        if (raw.length == len) {
            return raw;
        }
        if (raw.length == len + 1 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        byte[] out = new byte[len];
        System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        return out;
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
