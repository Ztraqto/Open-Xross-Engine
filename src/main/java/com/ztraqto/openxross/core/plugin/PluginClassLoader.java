package com.ztraqto.openxross.core.plugin;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * External Pluginごとに生成される独立したクラスローダーである。
 * これを破棄(close)することで、クラス定義をメモリから消去しホットスワップを実現する。
 */
public class PluginClassLoader extends URLClassLoader {

    public PluginClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    // 特定のクラスローディング戦略が必要な場合はここで loadClass をオーバーライドするが、
    // まずは標準の親委譲モデル(Parent-First)で実装する。
}