package com.ztraqto.openxross.api.storage;

/**
 * データベース接続のコンテキスト（所有者）を表すインターフェース。
 * プラグインや内部サービスが実装する。
 */
public interface IDatabaseOwner {
    
    /**
     * このコンテキストの一意なIDを返す。
     * これがDBファイル名の一部になる（例: "security" -> "data/security.db"）
     * 
     * @return コンテキストID (ファイル名に使用可能な文字列)
     */
    String getDatabaseContextId();
}