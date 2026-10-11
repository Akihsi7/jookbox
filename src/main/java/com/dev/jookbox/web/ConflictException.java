package com.dev.jookbox.web;

/** The request was based on state that has since changed (HTTP 409). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
