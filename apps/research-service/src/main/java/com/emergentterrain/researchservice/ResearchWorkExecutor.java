package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchWorkRequest;

/** Executes a generic immutable work request without source-application types. */
public interface ResearchWorkExecutor {
    boolean supports(String workType);
    ResearchResult execute(ResearchWorkRequest request);
}
