package com.emergentterrain.service;

import java.util.Map;

public record Payload(int v,
                      String profileId,
                      int age,
                      String sex,
                      String location,
                      Map<String, Boolean> answers,
                      HiddenProperties hidden,
                      String issuedAt) {
}
