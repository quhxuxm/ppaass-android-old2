package com.ppaass.agent.rust.util;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class UUIDUtil {
    public static final UUIDUtil INSTANCE = new UUIDUtil();

    private UUIDUtil() {
    }

    public String generateUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public byte[] generateUuidInBytes() {
        return this.generateUuid().getBytes(StandardCharsets.UTF_8);
    }
}
