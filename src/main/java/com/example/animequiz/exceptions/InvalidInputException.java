package com.example.animequiz.exceptions;

public class InvalidInputException extends IllegalArgumentException {

    public InvalidInputException(String message) {
        super(message);
    }
    public InvalidInputException(String message, Throwable cause) {
        super(message, cause);
    }
}
