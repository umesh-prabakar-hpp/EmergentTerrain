package com.emergentterrain.controller;

import com.emergentterrain.service.QrService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QrController {
    private final QrService qrService;

    public QrController(QrService qrService) {
        this.qrService = qrService;
    }

    @GetMapping("/qr")
    public ResponseEntity<byte[]> qr(@RequestParam("token") String token) {
        byte[] png = qrService.generatePng(token, 260);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @GetMapping("/qr/url")
    public ResponseEntity<byte[]> qrUrl(@RequestParam("data") String data) {
        byte[] png = qrService.generatePng(data, 260);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
