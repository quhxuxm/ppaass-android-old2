package com.ppaass.agent.service.handler;

import android.util.Log;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ppaass.agent.cryptography.CryptographyUtil;
import com.ppaass.agent.protocol.message.AgentMessagePayload;
import com.ppaass.agent.protocol.message.Message;
import com.ppaass.agent.protocol.message.PayloadEncryptionType;
import com.ppaass.agent.protocol.message.ProxyMessagePayload;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.IOException;

public class PpaassMessageUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final PpaassMessageUtil INSTANCE = new PpaassMessageUtil();

    private PpaassMessageUtil() {
    }

    public Message parseMessageBytes(ByteBuf messageBytes) {
        byte[] messageBytesArray = new byte[messageBytes.readableBytes()];
        messageBytes.readBytes(messageBytesArray);
        Message result = null;
        try {
            result = OBJECT_MAPPER.readValue(messageBytesArray, Message.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to parse bytes to message.", e);
            throw new RuntimeException(e);
        }
        if (PayloadEncryptionType.Plain == result.getPayloadEncryption().getType()) {
            return result;
        }
        if (PayloadEncryptionType.Aes == result.getPayloadEncryption().getType()) {
            var payloadEncryptionToken =
                    CryptographyUtil.INSTANCE.rsaDecrypt(result.getPayloadEncryption().getToken());
            result.getPayloadEncryption().setToken(payloadEncryptionToken);
            var decryptedPayloadBytes =
                    CryptographyUtil.INSTANCE.aesDecrypt(payloadEncryptionToken, result.getPayload());
            Log.d(PpaassMessageUtil.class.getName(),
                    "Message payload bytes:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(decryptedPayloadBytes)) +
                            "\n");
            result.setPayload(decryptedPayloadBytes);
            return result;
        }
        throw new IllegalStateException();
    }

    public ProxyMessagePayload parseProxyMessagePayloadBytes(byte[] payloadBytes) {
        ProxyMessagePayload result = null;
        try {
            result = OBJECT_MAPPER.readValue(payloadBytes, ProxyMessagePayload.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to parse bytes to proxy message payload.", e);
            throw new RuntimeException(e);
        }
        return result;
    }

    public byte[] generateAgentMessagePayloadBytes(AgentMessagePayload agentMessagePayload) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(agentMessagePayload);
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write agent message payload as bytes.", e);
            throw new RuntimeException(e);
        }
    }

    public byte[] generateMessageBytes(Message message) {
        byte[] originalPayloadRsaEncryptionToken = message.getPayloadEncryption().getToken();
        byte[] rsaEncryptedPayloadEncryptionToken =
                CryptographyUtil.INSTANCE.rsaEncrypt(originalPayloadRsaEncryptionToken);
        message.getPayloadEncryption().setToken(rsaEncryptedPayloadEncryptionToken);
        if (message.getPayload() == null) {
            try {
                return OBJECT_MAPPER.writeValueAsBytes(message);
            } catch (JsonProcessingException e) {
                Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
                throw new RuntimeException(e);
            }
        }
        if (PayloadEncryptionType.Aes == message.getPayloadEncryption().getType()) {
            byte[] encryptedPayload = CryptographyUtil.INSTANCE.aesEncrypt(originalPayloadRsaEncryptionToken,
                    message.getPayload());
            message.setPayload(encryptedPayload);
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
