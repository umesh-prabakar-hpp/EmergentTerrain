package com.emergentterrain.controller;

import com.emergentterrain.domain.User;
import com.emergentterrain.repo.UserRepository;
import com.emergentterrain.service.ProfileResult;
import com.emergentterrain.service.ProfileService;
import com.emergentterrain.web.ProfileForm;
import com.emergentterrain.web.QuestionItem;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.util.UriUtils;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Controller
@Profile("!perf")
public class ProfileController {
    private final ProfileService profileService;
    private final UserRepository userRepository;

    public ProfileController(ProfileService profileService, UserRepository userRepository) {
        this.profileService = profileService;
        this.userRepository = userRepository;
    }

    @GetMapping("/profile/new")
    public String profileForm(Model model) {
        java.util.Optional<com.emergentterrain.domain.Profile> existing = profileService.getSingletonProfile();
        if (existing.isPresent()) {
            return redirectToGame(profileService.buildTokenForProfile(existing.get()));
        }
        model.addAttribute("form", new ProfileForm());
        model.addAttribute("questions", questions());
        return "profile_form";
    }

    @GetMapping("/profile/select")
    public String selectProfile(Principal principal, Model model) {
        java.util.Optional<com.emergentterrain.domain.Profile> existing = profileService.getSingletonProfile();
        if (existing.isEmpty()) {
            return "redirect:/profile/new";
        }
        return redirectToGame(profileService.buildTokenForProfile(existing.get()));
    }

    @PostMapping("/profile")
    public String createProfile(@ModelAttribute("form") ProfileForm form, Principal principal, Model model) {
        User user = userRepository.findByEmail(principal.getName().toLowerCase()).orElseThrow();
        ProfileResult result = profileService.createProfile(user, form);
        return redirectToGame(result.token());
    }

    private String redirectToGame(String token) {
        return "redirect:/game?token=" + UriUtils.encodeQueryParam(token, StandardCharsets.UTF_8);
    }

    private List<QuestionItem> questions() {
        return List.of(
            new QuestionItem("likesForests", "Do you like forests?"),
            new QuestionItem("likesWater", "Do you like water features?"),
            new QuestionItem("likesMountains", "Do you like mountains?"),
            new QuestionItem("likesOpenFields", "Do you like open fields?"),
            new QuestionItem("likesWetClimate", "Do you like wet climates?"),
            new QuestionItem("likesSunny", "Do you like sunny weather?")
        );
    }
}
