package com.ridehailing.notification.entity;

/** Where a push token came from; a provider needs it to pick the right payload shape. */
public enum DevicePlatform {
    ANDROID,
    IOS,
    WEB
}
