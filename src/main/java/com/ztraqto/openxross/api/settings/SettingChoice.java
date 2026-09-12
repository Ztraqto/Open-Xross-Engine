package com.ztraqto.openxross.api.settings;

public record SettingChoice(String value, String label) {

    public SettingChoice {
        if (value == null || value.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("Setting choice value and label are required.");
        }
        value = value.trim();
        label = label.trim();
    }
}
