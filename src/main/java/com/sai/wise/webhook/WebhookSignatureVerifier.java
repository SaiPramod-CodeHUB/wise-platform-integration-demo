package com.sai.wise.webhook;

import com.sai.wise.config.WiseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Verifies that a webhook delivery actually came from Wise.
 *
 * <p>Your webhook endpoint is a public URL. Anyone can POST to it. Without
 * signature verification, "this transfer completed" is a claim made by a
 * stranger on the internet — and acting on it means someone can tell your
 * system a payment succeeded when it did not.
 *
 * <p>Wise signs the raw request body with its private key and sends the
 * signature in a header. We verify with the corresponding public key.
 * Critically, we verify against the <b>raw bytes</b> we received: re-serialising
 * parsed JSON changes whitespace and key order and the signature stops
 * matching.
 */
@Component
public class WebhookSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureVerifier.class);
    private static final String ALGORITHM = "SHA256withRSA";

    private final WiseProperties props;

    public WebhookSignatureVerifier(WiseProperties props) {
        this.props = props;
    }

    public boolean isValid(String rawBody, String signatureHeader) {

        if (!props.getWebhook().isVerifySignature()) {
            log.warn("[WEBHOOK] signature verification DISABLED — local development only");
            return true;
        }

        String publicKeyPem = props.getWebhook().getPublicKey();
        if (publicKeyPem == null || publicKeyPem.isBlank()) {
            log.error("[WEBHOOK] no public key configured; refusing to trust delivery");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("[WEBHOOK] delivery arrived with no signature header; rejected");
            return false;
        }

        try {
            PublicKey publicKey = parsePublicKey(publicKeyPem);

            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            boolean valid = verifier.verify(Base64.getDecoder().decode(signatureHeader));
            if (!valid) {
                log.warn("[WEBHOOK] signature did not verify; delivery rejected");
            }
            return valid;

        } catch (Exception e) {
            log.error("[WEBHOOK] signature verification error; delivery rejected", e);
            return false;
        }
    }

    private PublicKey parsePublicKey(String pem) throws Exception {
        String cleaned = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
    }
}
