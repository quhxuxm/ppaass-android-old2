package com.ppaass.agent.service.handler.udp;

import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public class DnsRepository {
    public static DnsRepository INSTANCE = new DnsRepository();
    private final Map<String, Set<InetAddress>> addresses;

    private DnsRepository() {
        this.addresses = new WeakHashMap<>();
    }

    public synchronized Set<InetAddress> getAddress(String domainName) {
        return this.addresses.get(domainName);
    }

    public synchronized void saveAddresses(String domainName, Set<InetAddress> address) {
        Set<InetAddress> addressesSet = new HashSet<>();
        Set<InetAddress> result = this.addresses.putIfAbsent(domainName, addressesSet);
        if (result == null) {
            addressesSet.addAll(address);
            return;
        }
        result.addAll(address);
    }

    public synchronized void saveAddress(String domainName, InetAddress address) {
        Set<InetAddress> addressesSet = new HashSet<>();
        Set<InetAddress> result = this.addresses.putIfAbsent(domainName, addressesSet);
        if (result == null) {
            addressesSet.add(address);
            return;
        }
        result.add(address);
    }
}
