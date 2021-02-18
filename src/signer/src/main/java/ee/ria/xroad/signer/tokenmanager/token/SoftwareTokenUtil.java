/**
 * The MIT License
 * Copyright (c) 2019- Nordic Institute for Interoperability Solutions (NIIS)
 * Copyright (c) 2018 Estonian Information System Authority (RIA),
 * Nordic Institute for Interoperability Solutions (NIIS), Population Register Centre (VRK)
 * Copyright (c) 2015-2017 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ee.ria.xroad.signer.tokenmanager.token;

import ee.ria.xroad.common.SystemProperties;
import ee.ria.xroad.common.util.CryptoUtils;
import ee.ria.xroad.common.util.ResourceUtils;
import ee.ria.xroad.signer.util.SignerUtil;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.operator.ContentSigner;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static ee.ria.xroad.common.SystemProperties.getConfPath;
import static ee.ria.xroad.common.util.CryptoUtils.loadPkcs12KeyStore;

/**
 * Utility methods for software token.
 */
@Slf4j
public final class SoftwareTokenUtil {

    static final String PIN_ALIAS = "pin";

    static final String PIN_FILE = ".softtoken";

    static final String P12 = ".p12";

    // TODO make it configurable.
    private static final String SIGNATURE_ALGORITHM = CryptoUtils.SHA512WITHRSA_ID;

    private static final FilenameFilter P12_FILTER = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name != null && !name.startsWith(PIN_FILE) && name.endsWith(P12);
        }
    };

    private SoftwareTokenUtil() {
    }

    /**
     * @return true if software token is initialized
     */
    public static boolean isTokenInitialized() {
        return new File(getKeyStoreFileName(PIN_FILE)).exists();
    }

    /**
     * @param keyId the key id
     * @return the key store file name for a key id
     */
    public static String getKeyStoreFileName(String keyId) {
        return getKeyDir() + "/" + keyId + P12;
    }

    /**
     * Returns a temporary filepath for a key store. Used e.g. when changing pin codes for key stores.
     * Usually the temp dir exists already when this method is called
     * (to create the temp dir use {@link #createTempKeyDir()})
     * @param keyId the key id
     * @return the key store file name for a key id in a temporary folder
     */
    public static String getTempKeyStoreFileName(String keyId) {
        return getTempKeyDir() + keyId + P12;
    }

    private static String getTempKeyDir() {
        return getConfPath() + ".keys.tmp/";
    }

    private static String getBackupKeyDir() {
        return getConfPath() + ".keys.bak/";
    }

    /**
     * Create a temp directory for key stores. Used e.g. when changing pin codes for key stores
     * @throws IOException creating temp dir fails
     */
    public static void createTempKeyDir() throws IOException {
        FileUtils.copyDirectory(getKeyDir(), new File(getTempKeyDir()));
    }

    /**
     * Rename the temp dir to the official key dir. Used e.g. when changing pin codes for key stores
     * @throws IOException renaming fails
     */
    public static void renameTempToKeyDir() throws IOException {
        FileUtils.moveDirectory(new File(getTempKeyDir()), getKeyDir());
    }

    /**
     * Rename the key dir to a backup dir. Used e.g. when changing pin codes for key stores
     * @throws IOException renaming fails
     */
    public static void renameKeyDirToBackup() throws IOException {
        FileUtils.moveDirectory(getKeyDir(), new File(getBackupKeyDir()));
    }

    /**
     * Remove the backup key dir. Used e.g. when changing pin codes for key stores
     * @throws IOException removing fails
     */
    public static void removeBackupKeyDir() throws IOException {
        FileUtils.deleteDirectory(new File(getBackupKeyDir()));
    }

    static List<String> listKeysOnDisk() {
        List<String> keys = new ArrayList<>();

        for (String p12File : getKeyDir().list(P12_FILTER)) {
            keys.add(p12File.substring(0, p12File.indexOf(P12)));
        }

        return keys;
    }

    static File getKeyDir() {
        return new File(ResourceUtils.getFullPathFromFileName(SystemProperties.getKeyConfFile()));
    }

    static KeyStore createKeyStore(KeyPair kp, String alias, char[] password) throws Exception {
        ContentSigner signer = CryptoUtils.createContentSigner(SIGNATURE_ALGORITHM, kp.getPrivate());

        X509Certificate[] certChain = new X509Certificate[1];
        certChain[0] = SignerUtil.createCertificate("KeyHolder", kp, signer);

        KeyStore keyStore = KeyStore.getInstance("pkcs12");
        keyStore.load(null, null);

        KeyStore.PrivateKeyEntry pkEntry = new KeyStore.PrivateKeyEntry(kp.getPrivate(), certChain);

        keyStore.setEntry(alias, pkEntry, new KeyStore.PasswordProtection(password));

        return keyStore;
    }

    static PrivateKey loadPrivateKey(String keyStoreFile, String alias, char[] password) throws Exception {
        KeyStore ks = loadPkcs12KeyStore(new File(keyStoreFile), password);
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password);

        if (privateKey == null) {
            // Could not find private key for given alias, attempt to find
            // key for any alias in the key store
            Enumeration<String> aliases = ks.aliases();

            while (aliases.hasMoreElements()) {
                privateKey = (PrivateKey) ks.getKey(aliases.nextElement(), password);

                if (privateKey != null) {
                    return privateKey;
                }
            }

            throw new RuntimeException("Private key not found in keystore '" + keyStoreFile + "', wrong password?");
        }

        return privateKey;
    }

    static Certificate loadCertificate(String keyStoreFile, String alias, char[] password) throws Exception {
        KeyStore ks = loadPkcs12KeyStore(new File(keyStoreFile), password);

        Certificate cert = ks.getCertificate(alias);

        if (cert == null) {
            // Could not find certificate for given alias, attempt to find
            // certificate for any alias in the key store
            Enumeration<String> aliases = ks.aliases();

            while (aliases.hasMoreElements()) {
                cert = ks.getCertificate(aliases.nextElement());

                if (cert != null) {
                    return cert;
                }
            }
        }

        return cert;
    }

    static KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
        keyPairGen.initialize(keySize, new SecureRandom());

        return keyPairGen.generateKeyPair();
    }
}
