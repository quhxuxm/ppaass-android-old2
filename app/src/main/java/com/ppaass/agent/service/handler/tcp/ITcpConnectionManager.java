package com.ppaass.agent.service.handler.tcp;

public interface ITcpConnectionManager {
    void closeConnection(TcpConnectionRepositoryKey key);
}
