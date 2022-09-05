package com.ppaass.agent.service.handler.dns;

import java.util.ArrayList;
import java.util.List;

public class DnsUtil {
    public static final DnsUtil INSTANCE = new DnsUtil();

    private DnsUtil() {
    }

    public List<String> parseAllDomainNames(String domainName) {
        var domainParts = domainName.split("\\.");
        var allDomainNames = new ArrayList<String>();
        for (int i = 0; i < domainParts.length; i++) {
            StringBuilder domainNameBuilder = new StringBuilder();
            for (int m = i; m < domainParts.length; m++) {
                domainNameBuilder.append(domainParts[m]);
                domainNameBuilder.append(".");
            }
            allDomainNames.add(domainNameBuilder.toString());
        }
        return allDomainNames;
    }
}
