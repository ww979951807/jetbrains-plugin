package com.haifasoft.haifassh.settings;

import com.intellij.credentialStore.CredentialAttributes;
import org.jetbrains.annotations.NotNull;

/**
 * Kotlin compiles {@code CredentialAttributes(name)} against the 2024.2 platform to the synthetic
 * default-arguments constructor, which newer platforms keep only as a deprecated binary shim.
 * javac binds to the plain {@code (String)} constructor instead, which exists un-deprecated on
 * every supported build (242+).
 */
final class CredentialAttributesCompat {

    private CredentialAttributesCompat() {
    }

    static @NotNull CredentialAttributes of(@NotNull String serviceName) {
        return new CredentialAttributes(serviceName);
    }
}
