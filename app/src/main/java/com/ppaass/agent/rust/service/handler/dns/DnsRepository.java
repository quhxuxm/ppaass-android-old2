package com.ppaass.agent.rust.service.handler.dns;

import android.content.SharedPreferences;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ppaass.agent.rust.service.IVpnConst;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DnsRepository {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final DnsRepository INSTANCE = new DnsRepository();
    private SharedPreferences sharedPreferences;

    private DnsRepository() {
    }

    public void init(SharedPreferences sharedPreferences) {
        this.sharedPreferences = sharedPreferences;
        var legacyEntryToRemove = new ArrayList<String>();
        sharedPreferences.getAll().forEach((k, v) -> {
            DnsEntry dnsEntry = null;
            try {
                dnsEntry = OBJECT_MAPPER.readValue((String) v, DnsEntry.class);
            } catch (JsonProcessingException e) {
                legacyEntryToRemove.add(k);
                return;
            }
            if (dnsEntry == null) {
                legacyEntryToRemove.add(k);
                return;
            }
            if (dnsEntry.getLastAccessTime() == null) {
                legacyEntryToRemove.add(k);
                return;
            }
            if ((System.currentTimeMillis() - dnsEntry.getLastAccessTime()) >= IVpnConst.DNS_IDLE_TIMEOUT_MS) {
                legacyEntryToRemove.add(k);
            }
        });
        var preferenceEditor = sharedPreferences.edit();
        legacyEntryToRemove.forEach(preferenceEditor::remove);
        preferenceEditor.apply();
    }

    public synchronized void clearAll() {
        var dnsSharedPreferenceEditor = this.sharedPreferences.edit();
        dnsSharedPreferenceEditor.clear();
        dnsSharedPreferenceEditor.apply();
    }

    public synchronized DnsEntry getAddress(String domainName) {
        var dnsEntryString = this.sharedPreferences.getString(domainName, null);
        if (dnsEntryString == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(dnsEntryString, DnsEntry.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void saveAddresses(String domainName, List<byte[]> addresses) {
        var entryToInsert = this.getAddress(domainName);
        if (entryToInsert == null) {
            entryToInsert = new DnsEntry();
            entryToInsert.setName(domainName);
            entryToInsert.setAddresses(addresses);
        } else {
            var addressesToAdd = new ArrayList<byte[]>();
            var finalEntryToInsert = entryToInsert;
            addresses.forEach(saveAddressBytes -> {
                boolean existing = false;
                for (byte[] existingAddressBytes : finalEntryToInsert.getAddresses()) {
                    if (Arrays.equals(existingAddressBytes, saveAddressBytes)) {
                        existing = true;
                        break;
                    }
                }
                if (!existing) {
                    addressesToAdd.add(saveAddressBytes);
                }
            });
            entryToInsert.getAddresses().addAll(addressesToAdd);
        }
        entryToInsert.setLastAccessTime(System.currentTimeMillis());
        try {
            var dnsEntryString = OBJECT_MAPPER.writeValueAsString(entryToInsert);
            SharedPreferences.Editor sharedPreferenceEditor = this.sharedPreferences.edit();
            sharedPreferenceEditor.putString(domainName, dnsEntryString);
            sharedPreferenceEditor.apply();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
