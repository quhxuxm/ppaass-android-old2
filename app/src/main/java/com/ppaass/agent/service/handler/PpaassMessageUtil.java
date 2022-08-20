package com.ppaass.agent.service.handler;

import android.util.Log;
import com.ppaass.agent.cryptography.CryptographyUtil;
import com.ppaass.agent.protocol.message.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public class PpaassMessageUtil {
    public static PpaassMessageUtil INSTANCE = new PpaassMessageUtil();

    private PpaassMessageUtil() {
    }

    public Message parseMessageBytes(ByteBuf messageBytes) {
        Message result = new Message();
        int idLength = messageBytes.readShort() & 0xFFFF;
        byte[] idBytes = new byte[idLength];
        messageBytes.readBytes(idBytes);
        String id = new String(idBytes);
        result.setId(id);
        int refIdLength = messageBytes.readShort() & 0xFFFF;
        byte[] refIdBytes = new byte[refIdLength];
        messageBytes.readBytes(refIdBytes);
        String refId = new String(refIdBytes);
        result.setRefId(refId);
        int connectionIdLength = messageBytes.readShort() & 0xFFFF;
        byte[] connectionIdBytes = new byte[connectionIdLength];
        messageBytes.readBytes(connectionIdBytes);
        String connectionId = new String(connectionIdBytes);
        result.setConnectionId(connectionId);
        int userTokenLength = messageBytes.readShort() & 0xFFFF;
        byte[] userTokenBytes = new byte[userTokenLength];
        messageBytes.readBytes(userTokenBytes);
        String userToken = new String(userTokenBytes);
        result.setUserToken(userToken);
        byte payloadEncryptionTypeValue = messageBytes.readByte();
        PayloadEncryptionType payloadEncryptionType = PayloadEncryptionType.from(payloadEncryptionTypeValue);
        int payloadEncryptionTokenLength = messageBytes.readShort() & 0xFFFF;
        byte[] payloadEncryptionToken = new byte[payloadEncryptionTokenLength];
        messageBytes.readBytes(payloadEncryptionToken);
        long messagePayloadLength = messageBytes.readLong();
        switch (payloadEncryptionType) {
            case Plain: {
                result.setPayloadEncryptionType(PayloadEncryptionType.Plain);
                byte[] payloadBytes = new byte[(int) messagePayloadLength];
                messageBytes.readBytes(payloadBytes);
                result.setPayload(payloadBytes);
                return result;
            }
            case Aes: {
                result.setPayloadEncryptionType(PayloadEncryptionType.Aes);
                //TODO RSA decrypt encryption token
                payloadEncryptionToken = CryptographyUtil.INSTANCE.rsaDecrypt(payloadEncryptionToken);
                result.setPayloadEncryptionToken(payloadEncryptionToken);
                //TODO Decrypt payload with Aes
                byte[] payloadBytes = new byte[(int) messagePayloadLength];
                messageBytes.readBytes(payloadBytes);
                payloadBytes = CryptographyUtil.INSTANCE.aesDecrypt(payloadEncryptionToken, payloadBytes);
                Log.d(PpaassMessageUtil.class.getName(),
                        "Message payload bytes:\n" + ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(payloadBytes)) +
                                "\n");
                result.setPayload(payloadBytes);
                return result;
            }
        }
        throw new IllegalStateException();
    }

    public ProxyMessagePayload parseProxyMessagePayloadBytes(byte[] payloadBytes) {
        ProxyMessagePayload result = new ProxyMessagePayload();
        ByteBuf payloadByteBuf = Unpooled.wrappedBuffer(payloadBytes);
        int payloadType = payloadByteBuf.readByte();
        result.setPayloadType(ProxyMessagePayloadType.from((byte) payloadType));
        boolean sourceAddressExist = payloadByteBuf.readBoolean();
        if (sourceAddressExist) {
            NetAddress sourceAddress = readNetAddress(payloadByteBuf);
            result.setSourceAddress(sourceAddress);
        }
        boolean targetAddressExist = payloadByteBuf.readBoolean();
        if (targetAddressExist) {
            NetAddress targetAddress = readNetAddress(payloadByteBuf);
            result.setTargetAddress(targetAddress);
        }
        long dataLength = payloadByteBuf.readLong();
        byte[] data = new byte[(int) dataLength];
        payloadByteBuf.readBytes(data);
        result.setData(data);
        return result;
    }

    private NetAddress readNetAddress(ByteBuf payloadByteBuf) {
        byte addressType = payloadByteBuf.readByte();
        NetAddressType netAddressType = NetAddressType.from(addressType);
        switch (netAddressType) {
            case IpV4: {
                byte[] ipV4Address = new byte[4];
                for (int i = 0; i < ipV4Address.length; i++) {
                    ipV4Address[i] = payloadByteBuf.readByte();
                }
                int port = payloadByteBuf.readShort() & 0xFFFF;
                NetAddress result = new NetAddress();
                result.setType(NetAddressType.IpV4);
                result.setHost(ipV4Address);
                result.setPort(port);
                return result;
            }
            case IpV6: {
                byte[] ipV6Address = new byte[16];
                for (int i = 0; i < ipV6Address.length; i++) {
                    ipV6Address[i] = payloadByteBuf.readByte();
                }
                int port = payloadByteBuf.readShort() & 0xFFFF;
                NetAddress result = new NetAddress();
                result.setType(NetAddressType.IpV6);
                result.setHost(ipV6Address);
                result.setPort(port);
                return result;
            }
            case Domain: {
                int domainLength = payloadByteBuf.readInt();
                byte[] hostNameBytes = new byte[domainLength];
                payloadByteBuf.readBytes(hostNameBytes);
                int port = payloadByteBuf.readShort() & 0xFFFF;
                NetAddress result = new NetAddress();
                result.setType(NetAddressType.Domain);
                result.setHost(hostNameBytes);
                result.setPort(port);
                return result;
            }
        }
        throw new UnsupportedOperationException();
    }

    public byte[] generateNetAddressBytes(NetAddress netAddress) {
        ByteBuf resultBuf = Unpooled.buffer();
        resultBuf.writeByte(netAddress.getType().getValue());
        if (NetAddressType.IpV4 == netAddress.getType()) {
            resultBuf.writeBytes(netAddress.getHost());
            resultBuf.writeShort(netAddress.getPort());
            byte[] result = new byte[resultBuf.readableBytes()];
            resultBuf.readBytes(result);
            return result;
        }
        if (NetAddressType.IpV6 == netAddress.getType()) {
            resultBuf.writeBytes(netAddress.getHost());
            resultBuf.writeShort(netAddress.getPort());
            byte[] result = new byte[resultBuf.readableBytes()];
            resultBuf.readBytes(result);
            return result;
        }
        resultBuf.writeInt(netAddress.getHost().length);
        resultBuf.writeBytes(netAddress.getHost());
        resultBuf.writeShort(netAddress.getPort());
        byte[] result = new byte[resultBuf.readableBytes()];
        resultBuf.readBytes(result);
        return result;
    }

    public byte[] generateAgentMessagePayloadBytes(AgentMessagePayload agentMessagePayload) {
        ByteBuf resultBuf = Unpooled.buffer();
        resultBuf.writeByte(agentMessagePayload.getPayloadType().getValue());
        if (agentMessagePayload.getSourceAddress() == null) {
            resultBuf.writeBoolean(false);
        } else {
            resultBuf.writeBoolean(true);
            resultBuf.writeBytes(this.generateNetAddressBytes(agentMessagePayload.getSourceAddress()));
        }
        if (agentMessagePayload.getTargetAddress() == null) {
            resultBuf.writeBoolean(false);
        } else {
            resultBuf.writeBoolean(true);
            resultBuf.writeBytes(this.generateNetAddressBytes(agentMessagePayload.getTargetAddress()));
        }
        if (agentMessagePayload.getData() == null) {
            resultBuf.writeLong(0);
            byte[] result = new byte[resultBuf.readableBytes()];
            resultBuf.readBytes(result);
            return result;
        }
        resultBuf.writeLong(agentMessagePayload.getData().length);
        resultBuf.writeBytes(agentMessagePayload.getData());
        byte[] result = new byte[resultBuf.readableBytes()];
        resultBuf.readBytes(result);
        return result;
    }

    public byte[] generateMessageBytes(Message message) {
        ByteBuf resultBuf = Unpooled.buffer();
        resultBuf.writeShort(message.getId().length());
        resultBuf.writeBytes(message.getId().getBytes());
        if (message.getRefId() == null) {
            resultBuf.writeShort(0);
        } else {
            resultBuf.writeShort(message.getRefId().length());
            resultBuf.writeBytes(message.getRefId().getBytes());
        }
        if (message.getConnectionId() == null) {
            resultBuf.writeShort(0);
        } else {
            resultBuf.writeShort(message.getConnectionId().length());
            resultBuf.writeBytes(message.getConnectionId().getBytes());
        }
        resultBuf.writeShort(message.getUserToken().length());
        resultBuf.writeBytes(message.getUserToken().getBytes());
        resultBuf.writeByte(message.getPayloadEncryptionType().getValue());
        byte[] rsaEncryptedPayloadEncryptionToken =
                CryptographyUtil.INSTANCE.rsaEncrypt(message.getPayloadEncryptionToken());
        resultBuf.writeShort(rsaEncryptedPayloadEncryptionToken.length);
        resultBuf.writeBytes(rsaEncryptedPayloadEncryptionToken);
        if (message.getPayload() == null) {
            resultBuf.writeLong(0);
            byte[] result = new byte[resultBuf.readableBytes()];
            resultBuf.readBytes(result);
            return result;
        }
        if (PayloadEncryptionType.Aes == message.getPayloadEncryptionType()) {
            byte[] encryptedPayload = CryptographyUtil.INSTANCE.aesEncrypt(message.getPayloadEncryptionToken(),
                    message.getPayload());
            resultBuf.writeLong(encryptedPayload.length);
            resultBuf.writeBytes(encryptedPayload);
            byte[] result = new byte[resultBuf.readableBytes()];
            resultBuf.readBytes(result);
            return result;
        }
        //Plain
        resultBuf.writeLong(message.getPayload().length);
        resultBuf.writeBytes(message.getPayload());
        byte[] result = new byte[resultBuf.readableBytes()];
        resultBuf.readBytes(result);
        return result;
    }
}
