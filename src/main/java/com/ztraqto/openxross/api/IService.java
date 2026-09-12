package com.ztraqto.openxross.api;

import com.ztraqto.openxross.XrossEngine;

/**
 * 内部サービスの共通インターフェース
 * ライフサイクル管理（初期化・終了）を統一します。
 */
public interface IService {
    
    /**
     * サービスの初期化処理
     * @param engine エンジンインスタンス（依存関係注入用）
     */
    void init(XrossEngine engine);

    /**
     * サービスの終了処理
     * リソース開放、DB切断、スレッド停止などを行う
     */
    void shutdown();
    
    /**
     * サービスの名前（ログ出力用）
     */
    String getName();
}