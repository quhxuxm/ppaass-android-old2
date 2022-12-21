package com.ppaass.agent.service.handler;

import android.util.Log;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.cryptography.CryptographyUtil;
import com.ppaass.agent.protocol.message.PpaassMessageAgentPayload;
import com.ppaass.agent.protocol.message.PpaassMessage;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryptionType;
import com.ppaass.agent.protocol.message.PpaassMessageProxyPayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.IOException;

public class PpaassMessageUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final PpaassMessageUtil INSTANCE = new PpaassMessageUtil();

    private PpaassMessageUtil() {
    }

    public PpaassMessage parseMessageBytes(ByteBuf messageBytes) {
        byte[] messageBytesArray = new byte[messageBytes.readableBytes()];
        messageBytes.readBytes(messageBytesArray);
        PpaassMessage result = null;
        try {
            result = OBJECT_MAPPER.readValue(messageBytesArray, PpaassMessage.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to parse bytes to message.", e);
            throw new RuntimeException(e);
        }
        if (PpaassMessagePayloadEncryptionType.Plain == result.getPayloadEncryption().getType()) {
            return result;
        }
        if (PpaassMessagePayloadEncryptionType.Aes == result.getPayloadEncryption().getType()) {
            var payloadEncryptionToken =
                    CryptographyUtil.INSTANCE.rsaDecrypt(result.getPayloadEncryption().getToken());
            result.getPayloadEncryption().setToken(payloadEncryptionToken);
            var decryptedPayloadBytes =
                    CryptographyUtil.INSTANCE.aesDecrypt(payloadEncryptionToken, result.getPayloadBytes());
            Log.v(PpaassMessageUtil.class.getName(),
                    "Message payload bytes:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(decryptedPayloadBytes)) +
                            "\n");
            result.setPayloadBytes(decryptedPayloadBytes);
            return result;
        }
        throw new IllegalStateException();
    }

    public PpaassMessageProxyPayload parseProxyMessagePayloadBytes(byte[] payloadBytes) {
        PpaassMessageProxyPayload result = null;
        try {
            result = OBJECT_MAPPER.readValue(payloadBytes, PpaassMessageProxyPayload.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to parse bytes to proxy message payload.", e);
            throw new RuntimeException(e);
        }
        return result;
    }

    public byte[] generateAgentMessagePayloadBytes(PpaassMessageAgentPayload agentMessagePayload) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(agentMessagePayload);
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write agent message payload as bytes.", e);
            throw new RuntimeException(e);
        }
    }

    public byte[] generateMessageBytes(PpaassMessage message) {
        byte[] originalPayloadRsaEncryptionToken = message.getPayloadEncryption().getToken();
        byte[] rsaEncryptedPayloadEncryptionToken =
                CryptographyUtil.INSTANCE.rsaEncrypt(originalPayloadRsaEncryptionToken);
        message.getPayloadEncryption().setToken(rsaEncryptedPayloadEncryptionToken);
        if (message.getPayloadBytes() == null) {
            try {
                return OBJECT_MAPPER.writeValueAsBytes(message);
            } catch (JsonProcessingException e) {
                Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
                throw new RuntimeException(e);
            }
        }
        if (PpaassMessagePayloadEncryptionType.Aes == message.getPayloadEncryption().getType()) {
            byte[] encryptedPayload = CryptographyUtil.INSTANCE.aesEncrypt(originalPayloadRsaEncryptionToken,
                    message.getPayloadBytes());
            message.setPayloadBytes(encryptedPayload);
            try {
                return OBJECT_MAPPER.writeValueAsBytes(message);
            } catch (JsonProcessingException e) {
                Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
                throw new RuntimeException(e);
            }
        }
        //Plain
        try {
            return OBJECT_MAPPER.writeValueAsBytes(message);
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
    }
}
