package org.liftfree.mtb;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Android URI adapter and Keystore-backed settings for the tested protocol client. */
final class StravaApi extends StravaClient {
    StravaApi(Context c){super(new Secrets(c.getApplicationContext()));}
    Uri authorization()throws Exception{return Uri.parse(authorizationUrl());}
    void callback(Uri uri)throws Exception{super.callback(uri.toString());}
    /** Android Keystore-backed AES-GCM; no token or client secret is committed. */
    static final class Secrets implements StravaClient.Settings {
        final SharedPreferences prefs;
        Secrets(Context c){prefs=c.getSharedPreferences("encrypted-secrets",Context.MODE_PRIVATE);}
        javax.crypto.SecretKey key()throws Exception {
            KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);
            if(!k.containsAlias("LiftFreeSecrets")){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder("LiftFreeSecrets",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();}
            return (javax.crypto.SecretKey)k.getKey("LiftFreeSecrets",null);
        }
        public synchronized String get(String name)throws Exception {
            String value=prefs.getString(name,"");if(value.isEmpty())return "";byte[] blob=Base64.decode(value,Base64.NO_WRAP);if(blob.length<29)throw new IOException("Encrypted setting is damaged");
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(blob,12)));return new String(c.doFinal(Arrays.copyOfRange(blob,12,blob.length)),StandardCharsets.UTF_8);
        }
        public synchronized void put(String name,String value)throws Exception {
            if(value.isEmpty()){if(!prefs.edit().remove(name).commit())throw new IOException("Cannot save setting");return;}
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(c.getIV());b.write(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
            if(!prefs.edit().putString(name,Base64.encodeToString(b.toByteArray(),Base64.NO_WRAP)).commit())throw new IOException("Cannot save encrypted setting");
        }
    }
}
