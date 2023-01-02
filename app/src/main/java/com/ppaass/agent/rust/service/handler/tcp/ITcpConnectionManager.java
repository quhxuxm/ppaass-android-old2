package com.ppaass.agent.rust.service.handler.tcp;

public interface ITcpConnectionManager {
    void unregisterConnection(TcpConnectionRepositoryKey key);
}
