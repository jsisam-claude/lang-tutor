plugins {
    // Version-less: AGP comes from the root buildscript classpath.
    id("com.android.asset-pack")
}

assetPack {
    packName.set("model_pack")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
