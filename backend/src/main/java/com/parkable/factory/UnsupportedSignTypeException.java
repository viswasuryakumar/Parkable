package com.parkable.factory;

/**
 * A rule type the factory cannot map. Thrown only for data that already passed
 * schema validation — reaching this means the schema and the factory have
 * drifted apart, which is a bug, not bad input.
 */
public class UnsupportedSignTypeException extends RuntimeException {
    public UnsupportedSignTypeException(String signType) {
        super("Unsupported sign type: " + signType);
    }
}
