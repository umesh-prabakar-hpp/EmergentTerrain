package com.emergentterrain.controller;

import org.springframework.stereotype.Controller;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile("!perf")
public class HomeController {
    @GetMapping("/")
    public String home() {
        return "redirect:/login";
    }
}
