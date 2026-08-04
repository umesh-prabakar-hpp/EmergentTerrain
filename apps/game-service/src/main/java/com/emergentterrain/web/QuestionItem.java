package com.emergentterrain.web;

public class QuestionItem {
    private final String code;
    private final String label;

    public QuestionItem(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}
