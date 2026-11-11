package com.tirso.tirsolador;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound encryption for application credentials and TDLib's database key. */
final class SecretStore {
    private static final String ALIAS="tirsolador.storage.v1";
    private final SharedPreferences prefs;
    SecretStore(Context context){prefs=context.getSharedPreferences("secrets",Context.MODE_PRIVATE);}
    private synchronized SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    synchronized void put(String name,String value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        String iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
        String encrypted=Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString(name,iv+":"+encrypted).commit())throw new java.io.IOException(T.t("No se pudo guardar la configuración"));
    }
    synchronized String get(String name) throws Exception {
        String value=prefs.getString(name,"");if(value.isEmpty())return "";
        String[] pieces=value.split(":",2);if(pieces.length!=2)throw new java.security.GeneralSecurityException(T.t("Configuración inválida"));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(pieces[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(pieces[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    synchronized byte[] databaseKey() throws Exception {
        String existing=get("databaseKey");
        if(existing.isEmpty()){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);existing=Base64.encodeToString(bytes,Base64.NO_WRAP);put("databaseKey",existing);}
        return Base64.decode(existing,Base64.NO_WRAP);
    }
}
