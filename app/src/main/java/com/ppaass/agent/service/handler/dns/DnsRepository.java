package com.ppaass.agent.service.handler.dns;

import com.ppaass.agent.service.IVpnConst;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DnsRepository {
    public static final DnsRepository INSTANCE = new DnsRepository();
    private final Map<String, DnsEntry> entries;

    private DnsRepository() {
        this.entries = new ConcurrentHashMap<>();
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            synchronized (INSTANCE) {
                entries.forEach((k, v) -> {
                    if ((System.currentTimeMillis() - v.getLastAccessTime()) >= IVpnConst.DNS_IDLE_TIMEOUT_MS) {
                        entries.remove(k);
                    }
                });
            }
        }, 0, 20, TimeUnit.SECONDS);
    }

    public synchronized DnsEntry getAddress(String domainName) {
        return this.entries.get(domainName);
    }

    public synchronized void saveAddresses(String domainName, List<InetAddress> addresses) {
        var addressesList = new ArrayList<InetAddress>();
        DnsEntry entryToInsert = new DnsEntry();
        entryToInsert.setName(domainName);
        entryToInsert.setLastAccessTime(System.currentTimeMillis());
        entryToInsert.setAddresses(addressesList);
        DnsEntry result = this.entries.putIfAbsent(domainName, entryToInsert);
        if (result == null) {
            entryToInsert.getAddresses().addAll(addresses);
            return;
        }
        result.getAddresses().addAll(addresses);
    }

    public synchronized void saveAddress(String domainName, InetAddress address) {
        var addressesList = new ArrayList<InetAddress>();
        DnsEntry entryToInsert = new DnsEntry();
        entryToInsert.setName(domainName);
        entryToInsert.setLastAccessTime(System.currentTimeMillis());
        entryToInsert.setAddresses(addressesList);
        DnsEntry result = this.entries.putIfAbsent(domainName, entryToInsert);
        if (result == null) {
            entryToInsert.getAddresses().add(address);
            return;
        }
        result.getAddresses().add(address);
    }
}
