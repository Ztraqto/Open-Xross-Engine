package com.ztraqto.openxross.api.wrider;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * WriderSQL用の主キー（Primary Key）を示すアノテーション。
 * 保存・読み込み時にこのフィールドが検索条件になります。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface WriderId {
}