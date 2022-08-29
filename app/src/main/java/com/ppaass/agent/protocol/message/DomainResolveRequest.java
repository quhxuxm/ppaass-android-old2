package com.ppaass.agent.protocol.message;

public class DomainResolveRequest {
    private String name;
    private int id;
    private Integer port;

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "DomainResolveRequest{" +
                "name='" + name + '\'' +
                ", id=" + id +
                '}';
    }
}
