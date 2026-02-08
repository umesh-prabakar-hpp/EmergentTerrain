package com.emergentterrain.controller;

import com.emergentterrain.domain.User;
import com.emergentterrain.repo.UserRepository;
import com.emergentterrain.service.ProfileResult;
import com.emergentterrain.service.ProfileService;
import com.emergentterrain.web.ProfileForm;
import com.emergentterrain.web.QuestionItem;
import com.emergentterrain.web.ProfileSelectItem;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

import java.security.Principal;
import java.util.List;

@Controller
public class ProfileController {
    private final ProfileService profileService;
    private final UserRepository userRepository;

    public ProfileController(ProfileService profileService, UserRepository userRepository) {
        this.profileService = profileService;
        this.userRepository = userRepository;
    }

    @GetMapping("/profile/new")
    public String profileForm(Model model) {
        model.addAttribute("form", new ProfileForm());
        model.addAttribute("questions", questions());
        return "profile_form";
    }

    @GetMapping("/profile/select")
    public String selectProfile(Principal principal, Model model) {
        User user = userRepository.findByEmail(principal.getName().toLowerCase()).orElseThrow();
        List<ProfileSelectItem> items = profileService.getProfilesForUser(user).stream()
            .map(profile -> new ProfileSelectItem(
                profile.getId().toString(),
                profileService.buildTokenForProfile(profile),
                profile.getCreatedAt()
            )).toList();
        model.addAttribute("profiles", items);
        return "profile_select";
    }

    @PostMapping("/profile")
    public String createProfile(@ModelAttribute("form") ProfileForm form, Principal principal, Model model) {
        User user = userRepository.findByEmail(principal.getName().toLowerCase()).orElseThrow();
        ProfileResult result = profileService.createProfile(user, form);
        model.addAttribute("token", result.token());
        model.addAttribute("profileId", result.profile().getId().toString());
        return "profile_success";
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
