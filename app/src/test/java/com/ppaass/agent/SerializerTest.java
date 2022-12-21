package com.ppaass.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.protocol.message.address.PpaassNetAddressIpValue;
import com.ppaass.agent.protocol.message.address.PpaassNetAddressType;
import com.ppaass.agent.protocol.message.address.PpaassNetAddress;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryptionType;
import com.ppaass.agent.protocol.message.encryption.PpaassMessagePayloadEncryption;
import com.ppaass.agent.protocol.message.payload.DomainResolveResponsePayload;
import com.ppaass.agent.util.UUIDUtil;
import org.junit.Test;

import java.util.Arrays;

public class SerializerTest {
    @Test
    public void testDomainResolveResponse() throws JsonProcessingException {
        var domainResolveResponse = new DomainResolveResponsePayload();
        domainResolveResponse.setId(65534);
        domainResolveResponse.setName("www.baidu.com");
        domainResolveResponse.setAddresses(Arrays.asList(new byte[]{
                (byte) 192, (byte) 168, 31, (byte) 200
        }, new byte[]{
                (byte) 192, (byte) 168, 31, (byte) 201
        }, new byte[]{
                (byte) 192, (byte) 168, 31, (byte) 202
        }));
        ObjectMapper objectMapper = new ObjectMapper();
        System.out.println(objectMapper.writeValueAsString(domainResolveResponse));
    }

    @Test
    public void testMessage() throws JsonProcessingException {
        var message = new PpaassMessage();
        message.setId(UUIDUtil.INSTANCE.generateUuid());
        message.setConnectionId(UUIDUtil.INSTANCE.generateUuid());
        message.setRefId(UUIDUtil.INSTANCE.generateUuid());
        message.setPayloadEncryption(
                new PpaassMessagePayloadEncryption(PpaassMessagePayloadEncryptionType.Aes, UUIDUtil.INSTANCE.generateUuidInBytes()));
        message.setPayload(UUIDUtil.INSTANCE.generateUuidInBytes());
        ObjectMapper objectMapper = new ObjectMapper();
        System.out.println(objectMapper.writeValueAsString(message));
    }

    @Test
    public void testAgentMessagePayload() throws JsonProcessingException {
        var agentMessagePayload = new PpaassMessageAgentPayload();
        agentMessagePayload.setPayloadType(PpaassMessageAgentPayloadType.TcpConnect);
        agentMessagePayload.setSourceAddress(
                new PpaassNetAddress(PpaassNetAddressType.IpV4,
                        new PpaassNetAddressIpValue(new byte[]{(byte) 192, (byte) 168, 31, (byte) 200
                        }, 9097)));
        agentMessagePayload.setTargetAddress(
                new PpaassNetAddress(PpaassNetAddressType.IpV4,
                        new PpaassNetAddressIpValue(new byte[]{(byte) 192, (byte) 168, 31, (byte) 200
                        }, 9097)));
        agentMessagePayload.setData("hello".getBytes());
        ObjectMapper objectMapper = new ObjectMapper();
        System.out.println(objectMapper.writeValueAsString(agentMessagePayload));
    }
}
