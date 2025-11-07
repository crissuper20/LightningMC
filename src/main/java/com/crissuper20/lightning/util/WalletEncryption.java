package com.crissuper20.lightning.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
public class WalletEncryption {
    
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    
    private final SecretKey secretKey;
    private final SecureRandom secureRandom;
    
    public WalletEncryption(String masterPassword, String serverSalt) throws Exception {
        this.secureRandom = new SecureRandom();
        
        // Combine password and salt for key derivation
        String keyMaterial = masterPassword + serverSalt;
        
        // Use SHA-256 to create a 256-bit key
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = sha.digest(keyMaterial.getBytes(StandardCharsets.UTF_8));
        
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }
    
    /**
     * @param plaintext The data to encrypt
     * @return Base64-encoded ciphertext with IV prepended
     */
    public String encrypt(String plaintext) throws Exception {
        // Generate random IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        
        // Encrypt
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
        
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // Prepend IV to ciphertext
        byte[] combined = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
        
        // Encode to Base64
        return Base64.getEncoder().encodeToString(combined);
    }
    
    /**
     * Decrypt a string
     * 
     * @param encrypted Base64-encoded ciphertext with IV
     * @return Original plaintext
     */
    public String decrypt(String encrypted) throws Exception {
        // Decode from Base64
        byte[] combined = Base64.getDecoder().decode(encrypted);
        
        // Extract IV and ciphertext
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
        
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, ciphertext, 0, ciphertext.length);
        
        // Decrypt
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        
        byte[] plaintext = cipher.doFinal(ciphertext);
        
        return new String(plaintext, StandardCharsets.UTF_8);
    }
    
    /**
     * Check if a string is encrypted (base64 with correct length)
     */
    public static boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            // Encrypted values should have at least IV + some data
            return decoded.length > GCM_IV_LENGTH;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
    
    /**
     * Generate a random encryption password
     */
    public static String generatePassword() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}