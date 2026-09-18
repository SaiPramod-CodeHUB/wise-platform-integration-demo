package com.sai.wise.exception;

/** A failure that will fail again if retried. Surface it, do not retry it. */
public class WisePermanentException extends RuntimeException {
    private final int statusCode;
    private final String body;

    public WisePermanentException(String message, int statusCode, String body) {
        super(message);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int getStatusCode() { return statusCode; }
    public String getBody() { return body; }
}
