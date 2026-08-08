package com.emergentterrain.researchservice;

/** A work request ID was retried with a payload other than its originally accepted payload. */
public class ResearchWorkReplayConflictException extends IllegalStateException {

    public ResearchWorkReplayConflictException() {
        super("A requestId cannot be rebound to a different research work payload");
    }
}
