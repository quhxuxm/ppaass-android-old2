package com.ppaass.agent.protocol.message;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class DomainResolveResponse {
    private int id;
    private String name;
    private List<byte[]> addresses;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<byte[]> getAddresses() {
        return addresses;
    }

    public void setAddresses(List<byte[]> addresses) {
        this.addresses = addresses;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        StringBuilder addressesBuilder = new StringBuilder();
        addressesBuilder.append("[");
        addresses.forEach(addr -> {
            try {
                addressesBuilder.append(InetAddress.getByAddress(addr));
                addressesBuilder.append(";");
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        });
        addressesBuilder.append("]");
        return "DomainResolveResponse{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", addresses=" + addressesBuilder.toString() +
                '}';
    }
}
