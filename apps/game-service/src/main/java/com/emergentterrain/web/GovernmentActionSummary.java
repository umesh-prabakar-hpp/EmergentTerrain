package com.emergentterrain.web;

import java.util.ArrayList;
import java.util.List;

public class GovernmentActionSummary {
    private String kind;
    private String title;
    private String meta;
    private String summary;
    private List<String> details = new ArrayList<>();

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMeta() {
        return meta;
    }

    public void setMeta(String meta) {
        this.meta = meta;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details == null ? new ArrayList<>() : details;
    }
}
