package com.ztraqto.openxross.api.command;

import java.util.ArrayList;
import java.util.List;

/**
 * コマンドのヘルプ詳細情報を保持するクラス。
 */
public class HelpInfo {

    private String usage;
    private String detail;
    private final List<String> examples = new ArrayList<>();

    public HelpInfo(String usage, String detail) {
        this.usage = usage;
        this.detail = detail;
    }

    public void addExample(String example) {
        this.examples.add(example);
    }

    // Getters

    public String getUsage() {
        return usage;
    }

    public String getDetail() {
        return detail;
    }

    public List<String> getExamples() {
        return examples;
    }
}
