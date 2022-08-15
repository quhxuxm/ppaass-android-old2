package com.ppaass.agent.protocol.message;

public enum AgentMessagePayloadType {
    TcpConnect(110),
    TcpData(111),
    UdpAssociate(120),
    UdpData(121),
    Heartbeat(130);
    private final  int value;

    AgentMessagePayloadType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static AgentMessagePayloadType from(int value){
        if(TcpConnect.getValue() == value){
            return TcpConnect;
        }
        if(TcpData.getValue() == value){
            return TcpData;
        }
        if(UdpAssociate.getValue() == value){
            return UdpAssociate;
        }
        if(UdpData.getValue() == value){
            return UdpData;
        }
        if(Heartbeat.getValue() == value){
            return Heartbeat;
        }
        throw new UnsupportedOperationException();
    }
}
