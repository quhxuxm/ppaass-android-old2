package com.ppaass.agent.service.handler;

import android.util.Log;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.cryptography.CryptographyUtil;
import com.ppaass.agent.protocol.message.PpaassMessage;
import com.ppaass.agent.protocol.message.PpaassMessageAgentPayload;
import com.ppaass.agent.protocol.message.PpaassMessageAgentPayloadType;
import com.ppaass.agent.protocol.message.PpaassMessageProxyPayload;
import com.ppaass.agent.protocol.message.address.PpaassNetAddress;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryption;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryptionType;
import com.ppaass.agent.protocol.message.payload.*;
import com.ppaass.agent.util.UUIDUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.io.IOException;

public class PpaassMessageUtil {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final PpaassMessageUtil INSTANCE = new PpaassMessageUtil();

    private PpaassMessageUtil() {
    }

    public PpaassMessage convertBytesToPpaassMessage(ByteBuf messageBytes) {
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
        if (PpaassMessagePayloadEncryptionType.Blowfish == result.getPayloadEncryption().getType()) {
            var payloadEncryptionToken =
                    CryptographyUtil.INSTANCE.rsaDecrypt(result.getPayloadEncryption().getToken());
            result.getPayloadEncryption().setToken(payloadEncryptionToken);
            var decryptedPayloadBytes =
                    CryptographyUtil.INSTANCE.blowfishDecrypt(payloadEncryptionToken, result.getPayloadBytes());
            Log.v(PpaassMessageUtil.class.getName(),
                    "Message payload bytes:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(decryptedPayloadBytes)) +
                            "\n");
            result.setPayloadBytes(decryptedPayloadBytes);
            return result;
        }
        throw new IllegalStateException();
    }

    public PpaassMessageProxyPayload convertBytesToProxyMessagePayload(byte[] payloadBytes) {
        PpaassMessageProxyPayload result = null;
        try {
            result = OBJECT_MAPPER.readValue(payloadBytes, PpaassMessageProxyPayload.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to parse bytes to proxy message payload.", e);
            throw new RuntimeException(e);
        }
        return result;
    }

    private byte[] convertAgentMessagePayloadToBytes(PpaassMessageAgentPayload agentMessagePayload) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(agentMessagePayload);
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write agent message payload as bytes.", e);
            throw new RuntimeException(e);
        }
    }

    public byte[] convertPpaassMessageToBytes(PpaassMessage message) {
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
        if (PpaassMessagePayloadEncryptionType.Blowfish == message.getPayloadEncryption().getType()) {
            byte[] encryptedPayload = CryptographyUtil.INSTANCE.blowfishEncrypt(originalPayloadRsaEncryptionToken,
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

    public PpaassMessage generateTcpLoopInitRequestMessage(PpaassNetAddress srcAddress, PpaassNetAddress destAddress, String userToken, PpaassMessagePayloadEncryption payloadEncryption) {
        var tcpLoopInitRequestPayload = new TcpLoopInitRequestPayload();
        tcpLoopInitRequestPayload.setDestAddress(destAddress);
        tcpLoopInitRequestPayload.setSrcAddress(srcAddress);
        var ppaassMessagePayload = new PpaassMessageAgentPayload();
        ppaassMessagePayload.setPayloadType(PpaassMessageAgentPayloadType.TcpLoopInit);
        try {
            ppaassMessagePayload.setData(OBJECT_MAPPER.writeValueAsBytes(tcpLoopInitRequestPayload));
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
        var message = new PpaassMessage();
        message.setId(UUIDUtil.INSTANCE.generateUuid());
        message.setPayloadBytes(this.convertAgentMessagePayloadToBytes(ppaassMessagePayload));
        message.setPayloadEncryption(payloadEncryption);
        message.setUserToken(userToken);
        return message;
    }

    public TcpLoopInitResponsePayload parseTcpLoopInitResponseMessage(byte[] payloadBytes) {
        try {
            return OBJECT_MAPPER.readValue(payloadBytes, TcpLoopInitResponsePayload.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
    }

    public HeartbeatResponsePayload parseHeartbeatResponseMessage(byte[] payloadBytes) {
        try {
            return OBJECT_MAPPER.readValue(payloadBytes, HeartbeatResponsePayload.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
    }

    public DomainResolveResponsePayload parseDomainNameResolveResponseMessage(byte[] payloadBytes) {
        try {
            return OBJECT_MAPPER.readValue(payloadBytes, DomainResolveResponsePayload.class);
        } catch (IOException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
    }

    public PpaassMessage generateHeartbeatRequestMessage(String userToken, PpaassMessagePayloadEncryption payloadEncryption) {
        var heartbeatRequestPayload = new HeartbeatRequestPayload();
        heartbeatRequestPayload.setTimestamp(System.currentTimeMillis());
        var ppaassMessagePayload = new PpaassMessageAgentPayload();
        ppaassMessagePayload.setPayloadType(PpaassMessageAgentPayloadType.IdleHeartbeat);
        try {
            ppaassMessagePayload.setData(OBJECT_MAPPER.writeValueAsBytes(heartbeatRequestPayload));
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
        var message = new PpaassMessage();
        message.setId(UUIDUtil.INSTANCE.generateUuid());
        message.setPayloadBytes(this.convertAgentMessagePayloadToBytes(ppaassMessagePayload));
        message.setPayloadEncryption(payloadEncryption);
        message.setUserToken(userToken);
        return message;
    }

    public PpaassMessage generateDomainNameResolveRequestMessage(String domainName, int requestId, PpaassNetAddress srcAddress, PpaassNetAddress destAddress, String userToken, PpaassMessagePayloadEncryption payloadEncryption) {
        var domainNameResolveRequestPayload = new DomainResolveRequestPayload();
        domainNameResolveRequestPayload.setDomainName(domainName);
        domainNameResolveRequestPayload.setRequestId(Integer.toString(requestId));
        domainNameResolveRequestPayload.setSrcAddress(srcAddress);
        domainNameResolveRequestPayload.setDestAddress(destAddress);
        var ppaassMessagePayload = new PpaassMessageAgentPayload();
        ppaassMessagePayload.setPayloadType(PpaassMessageAgentPayloadType.DomainNameResolve);
        try {
            ppaassMessagePayload.setData(OBJECT_MAPPER.writeValueAsBytes(domainNameResolveRequestPayload));
        } catch (JsonProcessingException e) {
            Log.e(PpaassMessageUtil.class.getName(), "Fail to write message as bytes.", e);
            throw new RuntimeException(e);
        }
        var message = new PpaassMessage();
        message.setId(UUIDUtil.INSTANCE.generateUuid());
        message.setPayloadBytes(this.convertAgentMessagePayloadToBytes(ppaassMessagePayload));
        message.setPayloadEncryption(payloadEncryption);
        message.setUserToken(userToken);
        return message;
    }
}
