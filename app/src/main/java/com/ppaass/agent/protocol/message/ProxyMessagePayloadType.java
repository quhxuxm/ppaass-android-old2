package com.ppaass.agent.protocol.message;

public enum ProxyMessagePayloadType {
    TcpConnectSuccess(210),
    TcpConnectFail(211),
    TcpData(212),
    UdpAssociateSuccess(221),
    UdpAssociateFail(222),
    UdpData(224),
    UdpDataRelayFail(223),
    HeartbeatSuccess(230);
    private final int value;

    ProxyMessagePayloadType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static ProxyMessagePayloadType from(int value) {
        if (TcpConnectSuccess.getValue() == value) {
            return TcpConnectSuccess;
        }
        if (TcpConnectFail.getValue() == value) {
            return TcpConnectFail;
        }
        if (TcpData.getValue() == value) {
            return TcpData;
        }
        if (UdpAssociateSuccess.getValue() == value) {
            return UdpAssociateSuccess;
        }
        if (UdpAssociateFail.getValue() == value) {
            return UdpAssociateFail;
        }
        if (UdpData.getValue() == value) {
            return UdpData;
        }
        if (UdpDataRelayFail.getValue() == value) {
            return UdpDataRelayFail;
        }
        if (HeartbeatSuccess.getValue() == value) {
            return HeartbeatSuccess;
        }
        throw new UnsupportedOperationException();
    }
}
