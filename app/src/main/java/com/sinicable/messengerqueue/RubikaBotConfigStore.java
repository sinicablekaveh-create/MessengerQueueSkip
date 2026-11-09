package com.sinicable.messengerqueue;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class RubikaBotConfigStore {
    private static final String PREFS="rubika_bot_secure";
    private static final String ALIAS="three_ib_rubika_bot_aes_v1";
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    static boolean saveToken(Context c,String token){
        try{
            String t=token==null?"":token.trim();
            if(t.length()<10||t.length()>512)return false;
            return prefs(c).edit().putString("token",encrypt(t,key())).commit();
        }catch(Exception e){return false;}
    }
    static String token(Context c){
        try{
            String v=prefs(c).getString("token","");
            return v.isEmpty()?"":decrypt(v,key());
        }catch(Exception e){return "";}
    }
    static void clear(Context c){prefs(c).edit().clear().apply();}

    private static SecretKey key()throws Exception{
        KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);
        if(s.containsAlias(ALIAS)){java.security.Key k=s.getKey(ALIAS,null);if(k instanceof SecretKey)return(SecretKey)k;}
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return g.generateKey();
    }
    private static String encrypt(String v,SecretKey k)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,k);
        byte[] enc=c.doFinal(v.getBytes(StandardCharsets.UTF_8)),iv=c.getIV(),all=new byte[iv.length+enc.length];
        System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(enc,0,all,iv.length,enc.length);
        return Base64.encodeToString(all,Base64.NO_WRAP);
    }
    private static String decrypt(String v,SecretKey k)throws Exception{
        byte[] all=Base64.decode(v,Base64.NO_WRAP);if(all.length<13)throw new IllegalArgumentException("bad encrypted token");
        byte[] iv=Arrays.copyOfRange(all,0,12),enc=Arrays.copyOfRange(all,12,all.length);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,iv));
        return new String(c.doFinal(enc),StandardCharsets.UTF_8);
    }
    private RubikaBotConfigStore(){}
}
