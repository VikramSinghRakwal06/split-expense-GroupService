package com.splitexpense.group.exception;

/**
 * A resource named by the request does not exist. Mapped to 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
