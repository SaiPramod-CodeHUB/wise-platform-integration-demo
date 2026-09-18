package com.sai.wise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every tunable lives here, not in code.
 *
 * <p>Deliberate design choice: an integration like this gets deployed into
 * multiple environments (sandbox, partner UAT, production) and the operational
 * numbers — timeouts, retry windows, reconciliation cadence — are the things
 * you end up changing under pressure. Hard-coding them means a code change and
 * a release to fix a production problem. Externalising them means a config
 * change and a rolling restart.
 */
@ConfigurationProperties(prefix = "wise")
public class WiseProperties {

    private String baseUrl = "https://api.wise-sandbox.com";
    private String apiToken = "";
    private String profileId = "";

    private Client client = new Client();
    private Quote quote = new Quote();
    private Webhook webhook = new Webhook();
    private Reconciliation reconciliation = new Reconciliation();

    public static class Client {
        private int connectTimeoutMs = 3000;
        private int responseTimeoutMs = 10000;

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getResponseTimeoutMs() { return responseTimeoutMs; }
        public void setResponseTimeoutMs(int v) { this.responseTimeoutMs = v; }
    }

    public static class Quote {
        /**
         * A Wise quote holds an FX rate for a limited time. If we are this close
         * to expiry we re-quote rather than attempting to fund, because funding
         * against a dead quote fails and the customer sees a confusing error.
         */
        private int minRemainingValiditySeconds = 30;

        public int getMinRemainingValiditySeconds() { return minRemainingValiditySeconds; }
        public void setMinRemainingValiditySeconds(int v) { this.minRemainingValiditySeconds = v; }
    }

    public static class Webhook {
        private String publicKey = "";
        private boolean verifySignature = true;

        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String v) { this.publicKey = v; }
        public boolean isVerifySignature() { return verifySignature; }
        public void setVerifySignature(boolean v) { this.verifySignature = v; }
    }

    public static class Reconciliation {
        private boolean enabled = true;
        private long fixedDelayMs = 300_000L;
        private int staleAfterMinutes = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getFixedDelayMs() { return fixedDelayMs; }
        public void setFixedDelayMs(long v) { this.fixedDelayMs = v; }
        public int getStaleAfterMinutes() { return staleAfterMinutes; }
        public void setStaleAfterMinutes(int v) { this.staleAfterMinutes = v; }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String v) { this.apiToken = v; }
    public String getProfileId() { return profileId; }
    public void setProfileId(String v) { this.profileId = v; }
    public Client getClient() { return client; }
    public void setClient(Client v) { this.client = v; }
    public Quote getQuote() { return quote; }
    public void setQuote(Quote v) { this.quote = v; }
    public Webhook getWebhook() { return webhook; }
    public void setWebhook(Webhook v) { this.webhook = v; }
    public Reconciliation getReconciliation() { return reconciliation; }
    public void setReconciliation(Reconciliation v) { this.reconciliation = v; }
}
