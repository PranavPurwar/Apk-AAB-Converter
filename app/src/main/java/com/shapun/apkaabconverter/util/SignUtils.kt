package com.shapun.apkaabconverter.util

import android.content.Context
import com.android.apksig.ApkSigner
import com.google.common.collect.ImmutableList
import com.shapun.apkaabconverter.jks.JavaKeyStore
import java.io.*
import java.security.KeyFactory
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Arrays
import java.util.Properties
import java.util.stream.Collectors


object SignUtils {
    private lateinit var SIGNER_NAME: String

    fun getDebugSignerConfig(context: Context): ApkSigner.SignerConfig {
        val assets = context.assets
        val privateKey: PrivateKey
        assets.open("testkey.pk8").use {
            val privateKeyBlob = it.readBytes()
            val keySpec = PKCS8EncodedKeySpec(privateKeyBlob)
            // Load the private key from its PKCS #8 encoded form.
            try {
                privateKey = loadPkcs8EncodedPrivateKey(keySpec)!!
            } catch (e: InvalidKeySpecException) {
                throw InvalidKeySpecException(
                    "Failed to load PKCS #8 encoded private key ", e
                )
            }
        }
        val certs = assets.open("testkey.x509.pem").use { inputStream ->
            CertificateFactory.getInstance("X.509").generateCertificates(inputStream)
                .stream()
                .map { (it as X509Certificate).apply { initSignerName(this) } }
                .collect(Collectors.toList())
        }
        return ApkSigner.SignerConfig.Builder(SIGNER_NAME, privateKey, certs).build()
    }

    @Throws(InvalidKeySpecException::class, NoSuchAlgorithmException::class)
    private fun loadPkcs8EncodedPrivateKey(spec: PKCS8EncodedKeySpec): PrivateKey? {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec)
        } catch (_: InvalidKeySpecException) {
        }
        try {
            return KeyFactory.getInstance("EC").generatePrivate(spec)
        } catch (_: InvalidKeySpecException) {
        }
        try {
            return KeyFactory.getInstance("DSA").generatePrivate(spec)
        } catch (_: InvalidKeySpecException) {
        }
        throw InvalidKeySpecException("Not an RSA, EC, or DSA private key")
    }

    fun getKeyStore(inputStream: InputStream, password: String): KeyStore {
        val data = ByteArrayInputStream(ByteArrayOutputStream().apply {
            inputStream.copyTo(this)
        }.toByteArray())
        val keystore: KeyStore = if (isJKS(data)) JavaKeyStore() else KeyStore.getInstance("PKCS12")
        keystore.load(data, password.toCharArray())
        return keystore
    }

    private fun isJKS(data: InputStream): Boolean = try {
        DataInputStream(BufferedInputStream(data)).readInt() == 0XFEEDFEEDL.toInt()
    } catch (_: Throwable) {
        false
    } finally {
        data.reset() //must reset
    }

    fun getSignerConfig(
        keystore: KeyStore, keyAlias: String, aliasPassword: String
    ): ApkSigner.SignerConfig {
        val privateKey = keystore.getKey(
            keyAlias,
            KeyStore.PasswordProtection(aliasPassword.toCharArray()).password
        ) as PrivateKey
        val certChain = keystore.getCertificateChain(keyAlias)
            ?: throw RuntimeException("No key found with alias '%s' in keystore.")
        /* ?: throw CommandExecutionException.builder()
             .withInternalMessage("No key found with alias '%s' in keystore.", keyAlias)
             .build()
         */
        val certificates = Arrays.stream(certChain).map {
            (it as X509Certificate).apply { initSignerName(this) }
        }.collect(ImmutableList.toImmutableList())
        return ApkSigner.SignerConfig.Builder(SIGNER_NAME, privateKey, certificates).build()
    }

    private fun initSignerName(cert: X509Certificate) {
        val defaultName = "CERT"
        SIGNER_NAME = try {
            val properties = Properties()
            properties.load(cert.subjectDN.name.replace(',', '\n').byteInputStream())
            properties.getProperty("CN") ?: defaultName
        } catch (_: Exception) {
            defaultName
        }
    }
}