# SPDX-License-Identifier: Apache-2.0
# The engine makes the tool source an app names in its provider XML from its class name: a Kotlin object's INSTANCE,
# or else a constructor taking the app's Context, or one taking nothing.
-keep class * implements com.nujan_agent.atp.provider.AtpToolSource {
    public static ** INSTANCE;
    <init>(android.content.Context);
    <init>();
}
