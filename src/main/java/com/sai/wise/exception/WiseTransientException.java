package com.sai.wise.exception;

/**
 * A failure that is worth retrying: a timeout, a 5xx, a connection reset.
 *
 * <p>The distinction matters enormously in payments. Retrying a transient
 * failure is correct. Retrying a permanent one (a validation error, a rejected
 * recipient) just burns rate limit and delays telling the partner the truth.
 */
public class WiseTransientException extends RuntimeException {
    public WiseTransientException(String message) { super(message); }
    public WiseTransientException(String message, Throwable cause) { super(message, cause); }
}
