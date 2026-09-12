package com.ztraqto.openxross.api.bus;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 通信パケットであることを示すアノテーション。
 * 異なるプラグインでも、このチャンネル名(value)が一致していれば通信可能。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PluginData {
    String value(); // チャンネル名 (例: "economy.pay")
}