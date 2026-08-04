package com.emergentterrain.researchservice;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Research-only runtime. It deliberately does not scan controllers, web sockets, GPU adapters,
 * world generation, or RoomService; the game process is unnecessary for autonomous research.
 */
@SpringBootApplication(scanBasePackages = "com.emergentterrain.researchservice")
@ComponentScan(
        basePackages = {
                "com.emergentterrain.research",
                "com.emergentterrain.llm",
                "com.emergentterrain.secrets",
                "com.emergentterrain.service"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.emergentterrain\\.service\\.(RoomService|WorldService|BotMovementService|"
                        + "BotSimulationGpuService|BotArchetypeBehaviorService|HighwayEmergenceService|"
                        + "MapIndexService|OccupancyService|PayloadService|PolicyConsultationService|"
                        + "ProfileService|QrService|RoomProposalService|SnapshotTokenService|"
                        + "TileValidatorService|UserService|ExplanationService)"))
@EntityScan(basePackages = {"com.emergentterrain.domain", "com.emergentterrain.research"})
@EnableJpaRepositories(basePackages = "com.emergentterrain.repo")
@EnableScheduling
public class ResearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResearchServiceApplication.class, args);
    }
}
