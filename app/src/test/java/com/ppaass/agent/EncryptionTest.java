package com.ppaass.agent;

import com.ppaass.agent.cryptography.CryptographyUtil;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.io.FileUtils;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class EncryptionTest {
    @Test
    public void test() throws IOException {
//        byte[] publicKey = FileUtils.readFileToByteArray(
//                new File("D:\\Git\\ppaass-android\\app\\src\\main\\res\\raw\\proxypublickey"));
//        byte[] privateKey = FileUtils.readFileToByteArray(
//                new File("D:\\Git\\ppaass-android\\app\\src\\main\\res\\raw\\agentprivatekey"));
//        byte[] rsaEncrypted = FileUtils.readFileToByteArray(new File("D:\\rsa"));
//        CryptographyUtil.INSTANCE.init(publicKey, privateKey);
//        String aestoken=new String(CryptographyUtil.INSTANCE.rsaDecrypt(rsaEncrypted));
//        System.out.println(aestoken);
//        byte[] aesEncrypted = FileUtils.readFileToByteArray(new File("D:\\aes"));
//        String aesContent = new String(CryptographyUtil.INSTANCE.aesDecrypt(aestoken.getBytes(),aesEncrypted));
//        System.out.println(aesContent);
    }
}
