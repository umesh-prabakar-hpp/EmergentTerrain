package com.emergentterrain.service;

import com.emergentterrain.domain.Profile;

public record ProfileResult(Profile profile, String token, Payload payload) {
}
