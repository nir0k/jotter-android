# Bouncy Castle's JCE provider loads each algorithm family's Mappings class by name and creates its
# algorithms from class names built from the family class's own name; a family whose classes are
# gone is skipped. Only the families sshj asks the "BC" provider for are kept:
# the digests and HMACs of key exchange, signatures, MACs and fingerprints, the ciphers sshj offers
# that Bouncy Castle has (the others sshj disables when it starts), and the key types of host keys,
# sign-in keys and key exchange. Key files are decrypted by the platform's providers.
-keep class org.bouncycastle.jcajce.provider.digest.MD5, org.bouncycastle.jcajce.provider.digest.MD5$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.RIPEMD160, org.bouncycastle.jcajce.provider.digest.RIPEMD160$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA1, org.bouncycastle.jcajce.provider.digest.SHA1$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA256, org.bouncycastle.jcajce.provider.digest.SHA256$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA384, org.bouncycastle.jcajce.provider.digest.SHA384$* { *; }
-keep class org.bouncycastle.jcajce.provider.digest.SHA512, org.bouncycastle.jcajce.provider.digest.SHA512$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.AES, org.bouncycastle.jcajce.provider.symmetric.AES$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Blowfish, org.bouncycastle.jcajce.provider.symmetric.Blowfish$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.ChaCha, org.bouncycastle.jcajce.provider.symmetric.ChaCha$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.DESede, org.bouncycastle.jcajce.provider.symmetric.DESede$* { *; }
-keep class org.bouncycastle.jcajce.provider.symmetric.Poly1305, org.bouncycastle.jcajce.provider.symmetric.Poly1305$* { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DH, org.bouncycastle.jcajce.provider.asymmetric.DH$*, org.bouncycastle.jcajce.provider.asymmetric.dh.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.DSA, org.bouncycastle.jcajce.provider.asymmetric.DSA$*, org.bouncycastle.jcajce.provider.asymmetric.dsa.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EC, org.bouncycastle.jcajce.provider.asymmetric.EC$*, org.bouncycastle.jcajce.provider.asymmetric.ec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.EdEC, org.bouncycastle.jcajce.provider.asymmetric.EdEC$*, org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.RSA, org.bouncycastle.jcajce.provider.asymmetric.RSA$*, org.bouncycastle.jcajce.provider.asymmetric.rsa.** { *; }
# Bouncy Castle's LDAP certificate store, which the application never uses, refers to JNDI, which Android lacks.
-dontwarn javax.naming.**
