import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import java.io.File;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Signs an APK with the v1, v2 and v3 schemes, then verifies the result. */
public class Sign {
    public static void main(String[] a) throws Exception {
        File keystore = new File(a[0]);
        String alias = a[1];
        char[] password = a[2].toCharArray();
        File in = new File(a[3]);
        File out = new File(a[4]);

        // Detects JKS or PKCS12 from the file itself.
        KeyStore ks = KeyStore.getInstance(keystore, password);
        if (!ks.isKeyEntry(alias)) {
            List<String> keys = new ArrayList<>();
            for (String name : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(name)) keys.add(name);
            }
            if (keys.size() != 1) {
                System.err.println("Set KEY_ALIAS to one of the keys in " + keystore + ": " + keys);
                System.exit(1);
            }
            alias = keys.get(0);
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, password);
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(
                alias, key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();

        ApkVerifier.Result r = new ApkVerifier.Builder(out).build().verify();
        // For minSdk 29 the verifier checks only the newest scheme the device would use (v3).
        System.out.println("verified=" + r.isVerified()
                + " v3=" + r.isVerifiedUsingV3Scheme());
        for (ApkVerifier.IssueWithParams e : r.getErrors()) System.out.println("ERROR " + e);
        for (ApkVerifier.IssueWithParams w : r.getWarnings()) System.out.println("WARN " + w);
        if (!r.isVerified()) System.exit(1);
    }
}
