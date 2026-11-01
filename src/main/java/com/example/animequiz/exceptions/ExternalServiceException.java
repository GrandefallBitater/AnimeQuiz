package com.example.animequiz.exceptions;

public class ExternalServiceException extends RuntimeException {

    public ExternalServiceException(String message) {
        super(message);
    }

    public  ExternalServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
