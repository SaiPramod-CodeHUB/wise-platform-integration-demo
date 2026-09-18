package com.sai.wise.exception;

/**
 * The FX rate we quoted is no longer valid.
 *
 * <p>Deliberately its own exception type: the correct response is not to retry
 * the funding call, it is to go back and get a new quote, then tell the
 * customer the rate changed. Silently executing at a stale rate would be worse
 * than failing.
 */
public class QuoteExpiredException extends RuntimeException {
    public QuoteExpiredException(String message) { super(message); }
}
