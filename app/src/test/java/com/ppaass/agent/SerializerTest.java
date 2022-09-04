package com.ppaass.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.protocol.message.*;
import com.ppaass.agent.util.UUIDUtil;
import org.junit.Test;

import java.util.Arrays;

public class SerializerTest {
    @Test
    public void testDomainResolveResponse() throws JsonProcessingException {
        var domainResolveResponse = new DomainResolveResponse();
        domainResolveResponse.setId(65534);
        domainResolveResponse.setName("www.baidu.com");
        domainResolveResponse.setPort(80);
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
        var message = new Message();
        message.setId(UUIDUtil.INSTANCE.generateUuid());
        message.setConnectionId(UUIDUtil.INSTANCE.generateUuid());
        message.setRefId(UUIDUtil.INSTANCE.generateUuid());
        message.setPayloadEncryption(
                new PayloadEncryption(PayloadEncryptionType.Aes, UUIDUtil.INSTANCE.generateUuidInBytes()));
        message.setPayload(UUIDUtil.INSTANCE.generateUuidInBytes());
        ObjectMapper objectMapper = new ObjectMapper();
        System.out.println(objectMapper.writeValueAsString(message));
    }

    @Test
    public void testAgentMessagePayload() throws JsonProcessingException {
        var agentMessagePayload = new AgentMessagePayload();
        agentMessagePayload.setPayloadType(AgentMessagePayloadType.TcpConnect);
        agentMessagePayload.setSourceAddress(
                new NetAddress(NetAddressType.IpV4,
                        new NetAddressValue(new byte[]{(byte) 192, (byte) 168, 31, (byte) 200
                        }, 9097)));
        agentMessagePayload.setTargetAddress(
                new NetAddress(NetAddressType.IpV4,
                        new NetAddressValue(new byte[]{(byte) 192, (byte) 168, 31, (byte) 200
                        }, 9097)));
        agentMessagePayload.setData("hello".getBytes());
        ObjectMapper objectMapper = new ObjectMapper();
        System.out.println(objectMapper.writeValueAsString(agentMessagePayload));
    }
}
