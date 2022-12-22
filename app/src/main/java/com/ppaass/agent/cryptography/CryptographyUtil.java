package com.ppaass.agent.cryptography;

import android.util.Log;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

public class CryptographyUtil {
    private static final String ALGORITHM_RSA = "RSA";
    private static final String RSA_CHIPHER = "RSA/ECB/PKCS1Padding";
    private static final String ALGORITHM_AES = "AES";
    private static final String ALGORITHM_BLOWFISH = "Blowfish";
    private static final String AES_CIPHER = "AES/ECB/PKCS5Padding";
    private static final String BLOWFISH_CIPHER = "Blowfish/ECB/PKCS5Padding";
    public static final CryptographyUtil INSTANCE = new CryptographyUtil();
    private PublicKey publicKey;
    private PrivateKey privateKey;

    private CryptographyUtil() {
    }

    public void init(byte[] rsaPublicKey, byte[] rsaPrivateKey) {
        String rsaPublicKeyString = new String(rsaPublicKey);
        rsaPublicKeyString = rsaPublicKeyString.replace("-----BEGIN PUBLIC KEY-----", "")
                .replaceAll("\r\n", "")
                .replace("-----END PUBLIC KEY-----", "");
        byte[] rsaPublicKeyRawBytes = Base64.decodeBase64(rsaPublicKeyString);
        String rsaPrivateKeyString = new String(rsaPrivateKey);
        rsaPrivateKeyString = rsaPrivateKeyString.replace("-----BEGIN PRIVATE KEY-----", "")
                .replaceAll("\r\n", "")
                .replace("-----END PRIVATE KEY-----", "");
        byte[] rsaPrivateKeyRawBytes = Base64.decodeBase64(rsaPrivateKeyString);
        try {
            X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(rsaPublicKeyRawBytes);
            KeyFactory rsaEncryptionCipherKeyFactory = KeyFactory.getInstance(ALGORITHM_RSA);
            this.publicKey = rsaEncryptionCipherKeyFactory.generatePublic(publicKeySpec);
            PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(rsaPrivateKeyRawBytes);
            KeyFactory rsaDecryptionCipherKeyFactory = KeyFactory.getInstance(ALGORITHM_RSA);
            this.privateKey = rsaDecryptionCipherKeyFactory.generatePrivate(privateKeySpec);
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to init cryptography util because of exception.",
                    e);
            throw new IllegalStateException(e);
        }
    }

    /**
     * Do AES encryption with encryption token.
     *
     * @param encryptionToken Encryption token.
     * @param data            The data to do encryption.
     * @return The encryption result
     */
    public byte[] aesEncrypt(byte[] encryptionToken, byte[] data) {
        try {
            SecretKeySpec key = new SecretKeySpec(encryptionToken, ALGORITHM_AES);
            Cipher cipher = Cipher.getInstance(AES_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to encrypt data with encryption token in AES because of exception. Encryption token: \n" +
                            ByteBufUtil
                                    .prettyHexDump(Unpooled.wrappedBuffer(encryptionToken)) + "\n", e);
            throw new IllegalStateException(
                    "Fail to encrypt data with encryption token in AES because of exception.",
                    e);
        }
    }

    /**
     * Decrypt AES data with encryption token.
     *
     * @param encryptionToken Encryption token.
     * @param aesData         The data encrypted.
     * @return The original data
     */
    public byte[] aesDecrypt(byte[] encryptionToken, byte[] aesData) {
        try {
            SecretKeySpec key = new SecretKeySpec(encryptionToken, ALGORITHM_AES);
            Cipher cipher = Cipher.getInstance(AES_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(aesData);
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to decrypt data with encryption token in AES because of exception. Encryption token: \n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(encryptionToken)) + "\n");
            throw new IllegalStateException(
                    "Fail to decrypt data with encryption token in AES because of exception.",
                    e);
        }
    }



    /**
     * Do Blowfish encryption with encryption token.
     *
     * @param encryptionToken Encryption token.
     * @param data            The data to do encryption.
     * @return The encryption result
     */
    public byte[] blowfishEncrypt(byte[] encryptionToken, byte[] data) {
        try {
            SecretKeySpec key = new SecretKeySpec(encryptionToken, ALGORITHM_BLOWFISH);
            Cipher cipher = Cipher.getInstance(BLOWFISH_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to encrypt data with encryption token in Blowfish because of exception. Encryption token: \n" +
                            ByteBufUtil
                                    .prettyHexDump(Unpooled.wrappedBuffer(encryptionToken)) + "\n", e);
            throw new IllegalStateException(
                    "Fail to encrypt data with encryption token in Blowfish because of exception.",
                    e);
        }
    }

    /**
     * Decrypt Blowfish data with encryption token.
     *
     * @param encryptionToken Encryption token.
     * @param aesData         The data encrypted.
     * @return The original data
     */
    public byte[] blowfishDecrypt(byte[] encryptionToken, byte[] aesData) {
        try {
            SecretKeySpec key = new SecretKeySpec(encryptionToken, ALGORITHM_BLOWFISH);
            Cipher cipher = Cipher.getInstance(BLOWFISH_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(aesData);
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to decrypt data with encryption token in Blowfish because of exception. Encryption token: \n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(encryptionToken)) + "\n");
            throw new IllegalStateException(
                    "Fail to decrypt data with encryption token in Blowfish because of exception.",
                    e);
        }
    }

    /**
     * Do RSA encryption with public key.
     *
     * @param target Target data to do encrypt.
     * @return The encryption result
     */
    public byte[] rsaEncrypt(byte[] target) {
        try {
            Cipher rsaEncryptionCipher = Cipher.getInstance(RSA_CHIPHER);
            rsaEncryptionCipher.init(Cipher.ENCRYPT_MODE, publicKey);
            rsaEncryptionCipher.update(target);
            return rsaEncryptionCipher.doFinal();
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to encrypt data with rsa public key because of exception. Target data: \n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(target)) + "\n", e);
            throw new IllegalStateException("Fail to encrypt data with rsa public key because of exception.", e);
        }
    }

    /**
     * Do RSA decryption with private key.
     *
     * @param target Target data to do decrypt.
     * @return The decrypt result
     */
    public byte[] rsaDecrypt(byte[] target) {
        try {
            Cipher rsaDecryptionCipher = Cipher.getInstance(RSA_CHIPHER);
            rsaDecryptionCipher.init(Cipher.DECRYPT_MODE, privateKey);
            rsaDecryptionCipher.update(target);
            return rsaDecryptionCipher.doFinal();
        } catch (Exception e) {
            Log.e(CryptographyUtil.class.getName(),
                    "Fail to decrypt data with rsa private key because of exception. Target data:\n" +
                            ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(target)) + "\n", e);
            throw new IllegalStateException("Fail to decrypt data with rsa private key because of exception.", e);
        }
    }

    private static class RsaKeyPair {
        private final byte[] publicKey;
        private final byte[] privateKey;

        public RsaKeyPair(byte[] publicKey, byte[] privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }

        public byte[] getPublicKey() {
            return publicKey;
        }

        public byte[] getPrivateKey() {
            return privateKey;
        }
    }

    private void writeBytesToFile(Path filePath, byte[] bytes) throws IOException {
        File targetFile = filePath.toFile();
        if (targetFile.exists()) {
            if (!targetFile.delete()) {
                Log.e(CryptographyUtil.class.getName(), "Fail to delete existing file: " + filePath);
                throw new IllegalStateException("Fail to delete existing file.");
            }
        }
        FileOutputStream fileOutputStream = new FileOutputStream(targetFile);
        fileOutputStream.write(bytes);
        fileOutputStream.close();
    }

    private RsaKeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance(ALGORITHM_RSA);
        keyPairGen.initialize(1024);
        KeyPair keyPair = keyPairGen.generateKeyPair();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        Log.i(CryptographyUtil.class.getName(),
                "RSA key pair public key:\n" +
                        ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(publicKey))
        );
        byte[] privateKey = keyPair.getPrivate().getEncoded();
        Log.i(CryptographyUtil.class.getName(),
                "RSA key pair private key:\n" +
                        ByteBufUtil.prettyHexDump(Unpooled.wrappedBuffer(privateKey)));
        return new RsaKeyPair(publicKey, privateKey);
    }

    public static void main(String[] args) throws Exception {
//        System.out.println("\nGenerate agent RSA key pair:\n");
//        RsaKeyPair agentKeyPair = CryptographyUtil.INSTANCE.generateRsaKeyPair();
//        CryptographyUtil.INSTANCE.writeBytesToFile(Paths.get("D://", "agentPublicKey"), agentKeyPair.getPublicKey());
//        CryptographyUtil.INSTANCE.writeBytesToFile(Paths.get("D://", "agentPrivateKey"), agentKeyPair.getPrivateKey());
//        System.out.println("\nGenerate proxy RSA key pair:\n");
//        RsaKeyPair proxyKeyPair = CryptographyUtil.INSTANCE.generateRsaKeyPair();
//        CryptographyUtil.INSTANCE.writeBytesToFile(Paths.get("D://", "proxyPublicKey"), proxyKeyPair.getPublicKey());
//        CryptographyUtil.INSTANCE.writeBytesToFile(Paths.get("D://", "proxyPrivateKey"), proxyKeyPair.getPrivateKey());
        byte[] publicKey = FileUtils.readFileToByteArray(
                new File("D:\\Git\\ppaass-android\\app\\src\\main\\res\\raw\\agentprivatekey"));
        byte[] privateKey = FileUtils.readFileToByteArray(
                new File("D:\\Git\\ppaass-android\\app\\src\\main\\res\\raw\\proxypublickey"));
        byte[] rsaEncrypted = new byte[]{
                (byte) 176, 84, 10, (byte) 213, (byte) 184, (byte) 164, 37, 40, 11, (byte) 230, 76, (byte) 225,
                (byte) 200, (byte) 179, (byte) 175, (byte) 195, (byte)133, 87, 30, 69, 70, 26,(byte) 176,
                103, (byte)192, (byte)243, 15, 37, (byte)254, (byte)199, 112, 1
        };
        CryptographyUtil.INSTANCE.init(publicKey, privateKey);
        System.out.println(CryptographyUtil.INSTANCE.rsaDecrypt(rsaEncrypted));
//        CryptographyUtil.INSTANCE.aesDecrypt();
    }
}
