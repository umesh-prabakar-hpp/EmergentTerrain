package com.emergentterrain.controller;

import com.emergentterrain.repo.UserRepository;
import com.emergentterrain.service.UserService;
import org.springframework.context.annotation.Profile;
import com.emergentterrain.web.RegisterForm;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@Profile("!perf")
public class AuthController {
    private final UserService userService;
    private final UserRepository userRepository;

    public AuthController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @GetMapping("/register")
    public String registerForm(Model model) {
        if (userRepository.countByRoleNot("SYSTEM") > 0) {
            return "redirect:/login";
        }
        model.addAttribute("form", new RegisterForm());
        return "register";
    }

    @PostMapping("/register")
    public String register(@ModelAttribute("form") RegisterForm form, Model model) {
        if (userRepository.countByRoleNot("SYSTEM") > 0) {
            model.addAttribute("error", "This installation already has its player account.");
            return "login";
        }
        if (form.getEmail() == null || form.getEmail().isBlank() || form.getPassword() == null || form.getPassword().isBlank()) {
            model.addAttribute("error", "Email and password are required.");
            return "register";
        }
        if (userRepository.findByEmail(form.getEmail().toLowerCase()).isPresent()) {
            model.addAttribute("error", "Email already registered.");
            return "register";
        }
        userService.register(form.getEmail(), form.getPassword());
        return "redirect:/login";
    }

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }
}
