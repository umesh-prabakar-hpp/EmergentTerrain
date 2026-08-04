package com.emergentterrain.service;

public record SnapshotPayload(int v,
                              String profileId,
                              String snapshotId,
                              String stateHash,
                              String issuedAt) {
}
