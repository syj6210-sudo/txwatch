package com.txwatch.service;

public final class DomainExceptions {

    private DomainExceptions() {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }

    public static class SeparationOfDutiesException extends RuntimeException {
        public SeparationOfDutiesException(String message) {
            super(message);
        }
    }
}
