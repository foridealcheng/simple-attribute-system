package com.attribution.decoder;

/**
 * 解码器异常
 * 
 * 当解码失败时抛出
 * 
 * @author SimpleAttributeSystem
 * @version 1.0.0
 */
public class DecoderException extends Exception {

    public DecoderException(String message) {
        super(message);
    }

    public DecoderException(String message, Throwable cause) {
        super(message, cause);
    }

    public DecoderException(Throwable cause) {
        super(cause);
    }
}
