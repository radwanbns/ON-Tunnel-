#include <jni.h>
#include <cstdint>

namespace {

constexpr jint kSigningCertificatesFlag = 0x08000000; // GET_SIGNING_CERTIFICATES
constexpr jint kLegacySignaturesFlag = 0x00000040;     // GET_SIGNATURES
constexpr jint kPackageMask = 0x23;
constexpr jint kDigestMask = 0x5A;

// Encoded package name: "com.ontunnel.app" XOR 0x23.
constexpr uint8_t kEncodedPackage[] = {
    64, 76, 78, 13, 76, 77, 87, 86, 77, 77, 70, 79, 13, 66, 83, 83
};

// SHA-256 of the expected APK signing certificate, XOR 0x5A.
constexpr uint8_t kEncodedDigest[] = {
    117, 137, 175, 19, 101, 50, 181, 198,
    3, 9, 92, 171, 4, 110, 202, 118,
    148, 242, 187, 158, 225, 191, 98, 158,
    88, 218, 173, 180, 45, 198, 250, 80
};

// SHA-256 of the stable debug APK signing certificate, XOR 0x5A.
constexpr uint8_t kEncodedDebugDigest[] = {
    191, 25, 112, 197, 182, 240, 35, 24,
    14, 24, 215, 139, 66, 180, 74, 241,
    237, 238, 17, 86, 53, 156, 23, 53,
    205, 177, 192, 198, 77, 52, 119, 50
};

bool has_exception(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

bool matches_package(JNIEnv* env, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    if (!contextClass) return false;
    jmethodID getPackageName = env->GetMethodID(
        contextClass, "getPackageName", "()Ljava/lang/String;");
    if (!getPackageName || has_exception(env)) return false;

    auto packageName = static_cast<jstring>(env->CallObjectMethod(context, getPackageName));
    if (!packageName || has_exception(env)) return false;

    const jsize length = env->GetStringUTFLength(packageName);
    if (length != static_cast<jsize>(sizeof(kEncodedPackage))) return false;

    const char* chars = env->GetStringUTFChars(packageName, nullptr);
    if (!chars || has_exception(env)) return false;

    bool valid = true;
    for (jsize i = 0; i < length; ++i) {
        const uint8_t actual = static_cast<uint8_t>(chars[i]);
        const uint8_t expected = static_cast<uint8_t>(kEncodedPackage[i] ^ kPackageMask);
        if (actual != expected) valid = false;
    }
    env->ReleaseStringUTFChars(packageName, chars);
    return valid;
}

jobjectArray get_signers(JNIEnv* env, jobject context) {
    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPackageManager = env->GetMethodID(
        contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (!getPackageManager || has_exception(env)) return nullptr;

    jobject pm = env->CallObjectMethod(context, getPackageManager);
    if (!pm || has_exception(env)) return nullptr;

    jmethodID getPackageInfo = env->GetMethodID(
        env->GetObjectClass(pm), "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    if (!getPackageInfo || has_exception(env)) return nullptr;

    jmethodID getPackageName = env->GetMethodID(
        contextClass, "getPackageName", "()Ljava/lang/String;");
    if (!getPackageName || has_exception(env)) return nullptr;
    jobject packageName = env->CallObjectMethod(context, getPackageName);
    if (!packageName || has_exception(env)) return nullptr;

    jclass versionClass = env->FindClass("android/os/Build$VERSION");
    if (!versionClass || has_exception(env)) return nullptr;
    jfieldID sdkField = env->GetStaticFieldID(versionClass, "SDK_INT", "I");
    if (!sdkField || has_exception(env)) return nullptr;
    const jint sdk = env->GetStaticIntField(versionClass, sdkField);

    const jint flags = sdk >= 28 ? kSigningCertificatesFlag : kLegacySignaturesFlag;
    jobject info = env->CallObjectMethod(pm, getPackageInfo, packageName, flags);
    if (!info || has_exception(env)) return nullptr;

    if (sdk >= 28) {
        jfieldID signingInfoField = env->GetFieldID(
            env->GetObjectClass(info), "signingInfo", "Landroid/content/pm/SigningInfo;");
        if (!signingInfoField || has_exception(env)) return nullptr;
        jobject signingInfo = env->GetObjectField(info, signingInfoField);
        if (!signingInfo || has_exception(env)) return nullptr;

        jmethodID getApkContentsSigners = env->GetMethodID(
            env->GetObjectClass(signingInfo), "getApkContentsSigners",
            "()[Landroid/content/pm/Signature;");
        if (!getApkContentsSigners || has_exception(env)) return nullptr;
        return static_cast<jobjectArray>(
            env->CallObjectMethod(signingInfo, getApkContentsSigners));
    }

    jfieldID signaturesField = env->GetFieldID(
        env->GetObjectClass(info), "signatures", "[Landroid/content/pm/Signature;");
    if (!signaturesField || has_exception(env)) return nullptr;
    return static_cast<jobjectArray>(env->GetObjectField(info, signaturesField));
}

bool matches_signer(JNIEnv* env, jobject signer) {
    jmethodID toByteArray = env->GetMethodID(
        env->GetObjectClass(signer), "toByteArray", "()[B");
    if (!toByteArray || has_exception(env)) return false;
    auto certificate = static_cast<jbyteArray>(env->CallObjectMethod(signer, toByteArray));
    if (!certificate || has_exception(env)) return false;

    jclass digestClass = env->FindClass("java/security/MessageDigest");
    if (!digestClass || has_exception(env)) return false;
    jmethodID getInstance = env->GetStaticMethodID(
        digestClass, "getInstance",
        "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    if (!getInstance || has_exception(env)) return false;
    jstring algorithm = env->NewStringUTF("SHA-256");
    jobject digest = env->CallStaticObjectMethod(digestClass, getInstance, algorithm);
    if (!digest || has_exception(env)) return false;

    jmethodID digestMethod = env->GetMethodID(
        digestClass, "digest", "([B)[B");
    if (!digestMethod || has_exception(env)) return false;
    auto actual = static_cast<jbyteArray>(
        env->CallObjectMethod(digest, digestMethod, certificate));
    if (!actual || has_exception(env)) return false;

    const jsize size = env->GetArrayLength(actual);
    if (size != static_cast<jsize>(sizeof(kEncodedDigest))) return false;

    jbyte bytes[32];
    env->GetByteArrayRegion(actual, 0, size, bytes);
    if (has_exception(env)) return false;

    uint8_t releaseDifference = 0;
    uint8_t debugDifference = 0;
    for (jsize i = 0; i < size; ++i) {
        const uint8_t releaseExpected =
            static_cast<uint8_t>(kEncodedDigest[i] ^ kDigestMask);
        const uint8_t debugExpected =
            static_cast<uint8_t>(kEncodedDebugDigest[i] ^ kDigestMask);
        releaseDifference |= static_cast<uint8_t>(bytes[i]) ^ releaseExpected;
        debugDifference |= static_cast<uint8_t>(bytes[i]) ^ debugExpected;
    }
    return releaseDifference == 0 || debugDifference == 0;
}

} // namespace

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_ontunnel_app_NativeIntegrity_verifyNative(
    JNIEnv* env, jobject /* receiver */, jobject context) {
    if (!context || !matches_package(env, context)) return JNI_FALSE;

    jobjectArray signers = get_signers(env, context);
    if (!signers || has_exception(env)) return JNI_FALSE;

    const jsize count = env->GetArrayLength(signers);
    if (count < 1) return JNI_FALSE;

    bool valid = false;
    for (jsize i = 0; i < count; ++i) {
        jobject signer = env->GetObjectArrayElement(signers, i);
        if (signer && matches_signer(env, signer)) valid = true;
        if (signer) env->DeleteLocalRef(signer);
    }
    return valid ? JNI_TRUE : JNI_FALSE;
}
