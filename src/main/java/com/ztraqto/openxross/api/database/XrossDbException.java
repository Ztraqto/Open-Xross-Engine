package com.ztraqto.openxross.api.database;

public class XrossDbException extends RuntimeException {
    public XrossDbException(String message) {
        super(message);
    }

    public XrossDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
