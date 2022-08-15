package com.ppaass.agent.service.handler.tcp;

import com.ppaass.agent.protocol.message.AgentMessagePayload;
import com.ppaass.agent.protocol.message.ProxyMessagePayload;

public class PpaassMessagePayloadUtil {
    public static PpaassMessagePayloadUtil INSTANCE = new PpaassMessagePayloadUtil();

    private PpaassMessagePayloadUtil() {
    }

    public ProxyMessagePayload parseProxyMessagePayloadBytes(byte[] payloadBytes) {
        return null;
    }

    public byte[] generateAgentMessagePayloadBytes(AgentMessagePayload agentMessagePayload) {
        return null;
    }
}
