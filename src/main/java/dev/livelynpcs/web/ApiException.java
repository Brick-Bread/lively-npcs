package dev.livelynpcs.web;

/** An editor API answer other than success: an HTTP status and a message for people. */
final class ApiException extends Exception {

    final int status;

    ApiException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    static ApiException bad(String message) {
        return new ApiException(400, message);
    }

    static ApiException notFound(String message) {
        return new ApiException(404, message);
    }

    static ApiException conflict(String message) {
        return new ApiException(409, message);
    }
}
